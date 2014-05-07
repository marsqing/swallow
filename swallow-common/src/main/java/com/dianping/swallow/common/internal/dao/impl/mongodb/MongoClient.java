package com.dianping.swallow.common.internal.dao.impl.mongodb;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dianping.hawk.jmx.HawkJMXUtil;
import com.dianping.swallow.common.internal.config.ConfigChangeListener;
import com.dianping.swallow.common.internal.config.DynamicConfig;
import com.dianping.swallow.common.internal.config.impl.LionDynamicConfig;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;
import com.mongodb.MongoOptions;
import com.mongodb.ServerAddress;

public class MongoClient implements ConfigChangeListener {

   private static final String           MSG_PREFIX                                        = "msg#";
   private static final String           ACK_PREFIX                                        = "ack#";
   private static final String           BACKUP_MSG_PREFIX                                 = "b_m#";
   private static final String           BACKUP_ACK_PREFIX                                 = "b_a#";

   private static final Logger           LOG                                               = LoggerFactory
                                                                                                 .getLogger(MongoClient.class);

   private static final String           MONGO_CONFIG_FILENAME                             = "swallow-mongo.properties";
   private static final String           LION_CONFIG_FILENAME                              = "swallow-mongo-lion.properties";
   private static final String           DEFAULT_COLLECTION_NAME                           = "c";
//   private static final String           TOPICNAME_HEARTBEAT                               = "heartbeat";
   private static final String           TOPICNAME_DEFAULT                                 = "default";

   private static final String           LION_KEY_MSG_CAPPED_COLLECTION_SIZE               = "swallow.mongo.msgCappedCollectionSize";
   private static final String           LION_KEY_MSG_CAPPED_COLLECTION_MAX_DOC_NUM        = "swallow.mongo.msgCappedCollectionMaxDocNum";
   private static final String           LION_KEY_ACK_CAPPED_COLLECTION_SIZE               = "swallow.mongo.ackCappedCollectionSize";
   private static final String           LION_KEY_ACK_CAPPED_COLLECTION_MAX_DOC_NUM        = "swallow.mongo.ackCappedCollectionMaxDocNum";
   private static final String           LION_KEY_BACKUP_MSG_CAPPED_COLLECTION_SIZE        = "swallow.mongo.backupMsgCappedCollectionSize";
   private static final String           LION_KEY_BACKUP_MSG_CAPPED_COLLECTION_MAX_DOC_NUM = "swallow.mongo.backupMsgCappedCollectionMaxDocNum";
   private static final String           LION_KEY_BACKUP_ACK_CAPPED_COLLECTION_SIZE        = "swallow.mongo.backupAckCappedCollectionSize";
   private static final String           LION_KEY_BACKUP_ACK_CAPPED_COLLECTION_MAX_DOC_NUM = "swallow.mongo.backupAckCappedCollectionMaxDocNum";

   private static final String           LION_KEY_HEARTBEAT_SERVER_URI                     = "swallow.mongo.heartbeatServerURI";
   private static final String           LION_KEY_HEARTBEAT_CAPPED_COLLECTION_SIZE         = "swallow.mongo.heartbeatCappedCollectionSize";
   private static final String           LION_KEY_HEARTBEAT_CAPPED_COLLECTION_MAX_DOC_NUM  = "swallow.mongo.heartbeatCappedCollectionMaxDocNum";

   private static final long             MILLION                                           = 1000000;

   //serverURI的名字可配置(consumer和producer在Lion上的名字是不同的)
   private final String                  severURILionKey;

   /** 缓存default collection 存在的标识，避免db.collectionExists的调用 */
   private final Map<DB, Byte>           collectionExistsSign                              = new ConcurrentHashMap<DB, Byte>();

   //lion config
   private volatile Map<String, Integer> msgTopicNameToSizes;
   private volatile Map<String, Integer> msgTopicNameToMaxDocNums;
   private volatile Map<String, Integer> ackTopicNameToSizes;
   private volatile Map<String, Integer> ackTopicNameToMaxDocNums;

   private volatile Map<String, Integer> backupMsgTopicNameToSizes;
   private volatile Map<String, Integer> backupMsgTopicNameToMaxDocNums;
   private volatile Map<String, Integer> backupAckTopicNameToSizes;
   private volatile Map<String, Integer> backupAckTopicNameToMaxDocNums;

   private volatile Mongo                heartbeatMongo;
   private volatile int                  heartbeatCappedCollectionSize;
   private volatile int                  heartbeatCappedCollectionMaxDocNum;
   private volatile Map<String, Mongo>   topicNameToMongoMap;

   //local config
   private MongoOptions                  mongoOptions;

   private DynamicConfig                 dynamicConfig;

   /**
    * 从 Lion(配置topicName,serverUrl的列表) 和 MongoConfigManager(配置Mongo参数) 获取配置，创建
    * “topicName -&gt; Mongo实例” 的Map映射。<br>
    * <br>
    * 当 Lion 配置发现变化时，“topicName -&gt; Mongo实例” 的Map映射;<br>
    * 将 MongoClient 实例注入到DAO：dao通过调用MongoClient.getXXCollectiond得到Collection。
    * 
    * @param uri
    * @param config
    */
   public MongoClient(String severURILionKey, DynamicConfig dynamicConfig) {
      this.severURILionKey = severURILionKey;
      if (LOG.isDebugEnabled()) {
         LOG.debug("Init MongoClient - start.");
      }
      //读取properties配置(如果存在configFile，则使用configFile)
      InputStream in = MongoClient.class.getClassLoader().getResourceAsStream(MONGO_CONFIG_FILENAME);
      MongoConfig config;
      if (in != null) {
         config = new MongoConfig(in);
      } else {
         config = new MongoConfig();
      }
      mongoOptions = this.getMongoOptions(config);
      LOG.info("MongoOptions=" + mongoOptions.toString());
      if (dynamicConfig != null) {
         this.dynamicConfig = dynamicConfig;
      } else {
         this.dynamicConfig = new LionDynamicConfig(LION_CONFIG_FILENAME);
      }
      loadLionConfig();
      if (LOG.isDebugEnabled()) {
         LOG.debug("Init MongoClient - done.");
      }
      //hawk监控
      HawkJMXUtil.unregisterMBean("MongoClient");
      HawkJMXUtil.registerMBean("MongoClient", new HawkMBean(this));
   }

   public MongoClient(String severURILionKey) {
      this(severURILionKey, null);
   }

   /**
    * URI格式,如：
    * 
    * <pre>
    * swallow.mongo.consumerServerURI：default,feed=mongodb://localhost:27017;topicForUnitTest=mongodb://192.168.31.178:27016 
    * swallow.mongo.producerServerURI：default,feed=mongodb://localhost:27017;topicForUnitTest=mongodb://192.168.31.178:27016 
    * swallow.mongo.msgCappedCollectionSize：default=1024;feed,topicForUnitTest=1025
    * swallow.mongo.msgCappedCollectionMaxDocNum：default=1024;feed,topicForUnitTest=1025
    * swallow.mongo.ackCappedCollectionSize：default=1024;feed,topicForUnitTest=1025
    * swallow.mongo.ackCappedCollectionMaxDocNum：default=1024;feed,topicForUnitTest=1025
    * 
    * swallow.mongo.heartbeatServerURI：mongodb://localhost:27017
    * swallow.mongo.heartbeatCappedCollectionSize=1024
    * swallow.mongo.heartbeatCappedCollectionMaxDocNum=1024
    * </pre>
    */
   private void loadLionConfig() {
      try {
         //serverURI
         this.topicNameToMongoMap = parseURIAndCreateTopicMongo(dynamicConfig.get(this.severURILionKey).trim());
         //msgTopicNameToSizes
         String msgTopicNameToSizes = dynamicConfig.get(LION_KEY_MSG_CAPPED_COLLECTION_SIZE);
         if (msgTopicNameToSizes != null) {
            this.msgTopicNameToSizes = parseSizeOrDocNum(msgTopicNameToSizes.trim());
         }
         //msgTopicNameToMaxDocNums(可选)
         String msgTopicNameToMaxDocNums = dynamicConfig.get(LION_KEY_MSG_CAPPED_COLLECTION_MAX_DOC_NUM);
         if (msgTopicNameToMaxDocNums != null) {
            this.msgTopicNameToMaxDocNums = parseSizeOrDocNum(msgTopicNameToMaxDocNums.trim());
         }
         //ackTopicNameToSizes
         String ackTopicNameToSizes = dynamicConfig.get(LION_KEY_ACK_CAPPED_COLLECTION_SIZE);
         this.ackTopicNameToSizes = parseSizeOrDocNum(ackTopicNameToSizes.trim());
         //ackTopicNameToMaxDocNums(可选)
         String ackTopicNameToMaxDocNums = dynamicConfig.get(LION_KEY_ACK_CAPPED_COLLECTION_MAX_DOC_NUM);
         if (ackTopicNameToMaxDocNums != null) {
            this.ackTopicNameToMaxDocNums = parseSizeOrDocNum(ackTopicNameToMaxDocNums.trim());
         }
         //backupMsgTopicNameToSizes
         String backupMsgTopicNameToSizes = dynamicConfig.get(LION_KEY_BACKUP_MSG_CAPPED_COLLECTION_SIZE);
         if (backupMsgTopicNameToSizes != null) {
            this.backupMsgTopicNameToSizes = parseSizeOrDocNum(backupMsgTopicNameToSizes.trim());
         }
         //backupMsgTopicNameToMaxDocNums(可选)
         String backupMsgTopicNameToMaxDocNums = dynamicConfig.get(LION_KEY_BACKUP_MSG_CAPPED_COLLECTION_MAX_DOC_NUM);
         if (backupMsgTopicNameToMaxDocNums != null) {
            this.backupMsgTopicNameToMaxDocNums = parseSizeOrDocNum(backupMsgTopicNameToMaxDocNums.trim());
         }
         //backupAckTopicNameToSizes
         String backupAckTopicNameToSizes = dynamicConfig.get(LION_KEY_BACKUP_ACK_CAPPED_COLLECTION_SIZE);
         this.backupAckTopicNameToSizes = parseSizeOrDocNum(backupAckTopicNameToSizes.trim());
         //backupAckTopicNameToMaxDocNums(可选)
         String backupAckTopicNameToMaxDocNums = dynamicConfig.get(LION_KEY_BACKUP_ACK_CAPPED_COLLECTION_MAX_DOC_NUM);
         if (backupAckTopicNameToMaxDocNums != null) {
            this.backupAckTopicNameToMaxDocNums = parseSizeOrDocNum(backupAckTopicNameToMaxDocNums.trim());
         }
         //heartbeat
         this.heartbeatMongo = parseURIAndCreateHeartbeatMongo(dynamicConfig.get(LION_KEY_HEARTBEAT_SERVER_URI).trim());
         String heartbeatCappedCollectionSize = dynamicConfig.get(LION_KEY_HEARTBEAT_CAPPED_COLLECTION_SIZE);
         this.heartbeatCappedCollectionSize = Integer.parseInt(heartbeatCappedCollectionSize.trim());
         String heartbeatCappedCollectionMaxDocNum = dynamicConfig
               .get(LION_KEY_HEARTBEAT_CAPPED_COLLECTION_MAX_DOC_NUM);//(可选)
         if (heartbeatCappedCollectionMaxDocNum != null) {
            this.heartbeatCappedCollectionMaxDocNum = Integer.parseInt(heartbeatCappedCollectionMaxDocNum.trim());
         }
         //添加Lion监听
         dynamicConfig.addConfigChangeListener(this);
      } catch (Exception e) {
         throw new IllegalArgumentException("Error Loading Config from Lion : " + e.getMessage(), e);
      }
   }

   /**
    * 解析URI，且创建heartbeat使用的Mongo实例
    */
   private Mongo parseURIAndCreateHeartbeatMongo(String serverURI) {
      Mongo mongo = null;
      List<ServerAddress> replicaSetSeeds = this.parseUriToAddressList(serverURI);
      mongo = getExistsMongo(replicaSetSeeds);
      if (mongo == null) {
         mongo = new Mongo(replicaSetSeeds, mongoOptions);
      }
      if (LOG.isInfoEnabled()) {
         LOG.info("parseURIAndCreateHeartbeatMongo() - parse " + serverURI + " to: " + mongo);
      }
      return mongo;
   }

   /**
    * 解析URI，且创建topic(msg和ack)使用的Mongo实例
    */
   private Map<String, Mongo> parseURIAndCreateTopicMongo(String serverURI) {
      try {
         //解析uri
         Map<String, List<String>> serverURIToTopicNames = new HashMap<String, List<String>>();
         boolean defaultExists = false;
         for (String topicNamesToURI : serverURI.split(";")) {
            String[] splits = topicNamesToURI.split("=");
            String mongoURI = splits[1];
            String topicNameStr = splits[0];
            List<String> topicNames = new ArrayList<String>();
            for (String topicName : topicNameStr.split(",")) {
               if (TOPICNAME_DEFAULT.equals(topicName)) {
                  defaultExists = true;
               }
               topicNames.add(topicName);
            }
            List<String> topicNames0 = serverURIToTopicNames.get(mongoURI);
            if (topicNames0 != null) {
               topicNames.addAll(topicNames0);
            }
            serverURIToTopicNames.put(mongoURI, topicNames);
         }
         //验证uri(default是必须存在的topicName)
         if (!defaultExists) {
            throw new IllegalArgumentException("The '" + this.severURILionKey
                  + "' property must contain 'default' topicName!");
         }
         //根据uri创建Mongo，放到Map
         HashMap<String, Mongo> topicNameToMongoMap = new HashMap<String, Mongo>();
         for (Map.Entry<String, List<String>> entry : serverURIToTopicNames.entrySet()) {
            String uri = entry.getKey();
            List<ServerAddress> replicaSetSeeds = parseUriToAddressList(uri);
            Mongo mongo = null;
            List<String> topicNames = entry.getValue();
            mongo = getExistsMongo(replicaSetSeeds);
            if (mongo == null) {//创建mongo实例
               mongo = new Mongo(replicaSetSeeds, mongoOptions);
            }
            for (String topicName : topicNames) {
               topicNameToMongoMap.put(topicName, mongo);
            }
         }
         if (LOG.isInfoEnabled()) {
            LOG.info("parseURIAndCreateTopicMongo() - parse " + serverURI + " to: " + topicNameToMongoMap);
         }
         return topicNameToMongoMap;
      } catch (RuntimeException e) {
         throw new IllegalArgumentException(
               "Error parsing the '*ServerURI' property, the format is '<topicName>,default=<mongoURI>;<topicName>=<mongoURI>': "
                     + e.getMessage(), e);
      }
   }

   /**
    * 如果已有的map或heartbeatMongo中已经存在相同的地址的Mongo实例，则重复使用
    */
   private Mongo getExistsMongo(List<ServerAddress> replicaSetSeeds) {
      Mongo mongo = null;
      if (this.topicNameToMongoMap != null) {//如果已有的map中已经存在该Mongo实例，则重复使用
         for (Mongo m : this.topicNameToMongoMap.values()) {
            if (equalsOutOfOrder(m.getAllAddress(), replicaSetSeeds)) {
               mongo = m;
               break;
            }
         }
      }
      if (this.heartbeatMongo != null) {//如果已经存在该Mongo实例，则重复使用
         if (this.equalsOutOfOrder(this.heartbeatMongo.getAllAddress(), replicaSetSeeds)) {
            mongo = this.heartbeatMongo;
         }
      }
      if (mongo != null) {
         if (LOG.isInfoEnabled()) {
            LOG.info("getExistsMongo() return a exists Mongo instance : " + mongo);
         }
      }
      return mongo;
   }

   private Map<String, Integer> parseSizeOrDocNum(String sizeStr) {
      try {
         Map<String, Integer> topicNameToSizes = new HashMap<String, Integer>();
         boolean defaultExists = false;
         for (String topicNameToSize : sizeStr.split(";")) {
            String[] splits = topicNameToSize.split("=");
            String size = splits[1];
            String topicNameStr = splits[0];
            for (String topicName : topicNameStr.split(",")) {
               if (TOPICNAME_DEFAULT.equals(topicName)) {
                  defaultExists = true;
               }
               int intSize = Integer.parseInt(size);
               if (intSize <= 0) {
                  throw new IllegalArgumentException("Size or DocNum value must larger than 0 :" + sizeStr);
               }
               topicNameToSizes.put(topicName, intSize);
            }
         }
         //验证uri(default是必须存在的topicName)
         if (!defaultExists) {
            throw new IllegalArgumentException("The '" + this.severURILionKey
                  + "' property must contain 'default' topicName!");
         }
         if (LOG.isInfoEnabled()) {
            LOG.info("parseSizeOrDocNum() - parse " + sizeStr + " to: " + topicNameToSizes);
         }
         return topicNameToSizes;
      } catch (Exception e) {
         throw new IllegalArgumentException(
               "Error parsing the '*Size' or '*MaxDocNum' property, the format is like 'default=<int>;<topicName>,<topicName>=<int>': "
                     + e.getMessage(), e);
      }
   }

   /**
    * 响应Lion更新事件时:<br>
    * (1)若是URI变化，重新构造Mongo实例，替换现有的Map值；<br>
    * (2)若是size和docnum配置项变化，则仅更新变量本身， 即只后续的创建Collection操作有影响。<br>
    * <p>
    * 该方法保证：<br>
    * (1)当新的Lion配置值有异常时，不会改变现有的值；<br>
    * (2)当新的Lion配置值正确，在正常更新值后，能有效替换现有的Map和int值
    * </p>
    */
   @Override
   public synchronized void onConfigChange(String key, String value) {
      if (LOG.isInfoEnabled()) {
         LOG.info("onChange() called.");
      }
      value = value.trim();
      try {
         if (this.severURILionKey.equals(key)) {
            Map<String, Mongo> oldTopicNameToMongoMap = this.topicNameToMongoMap;
            this.topicNameToMongoMap = parseURIAndCreateTopicMongo(value);
            //Mongo可能有更新，所以需要关闭旧的不再使用的Mongo
            Thread.sleep(5000);//DAO可能正在使用旧的Mongo，故等候5秒，才执行关闭操作
            closeUnuseMongo(oldTopicNameToMongoMap.values(), this.topicNameToMongoMap.values(), this.heartbeatMongo);
         } else if (LION_KEY_MSG_CAPPED_COLLECTION_SIZE.equals(key)) {
            this.msgTopicNameToSizes = parseSizeOrDocNum(value);
         } else if (LION_KEY_MSG_CAPPED_COLLECTION_MAX_DOC_NUM.equals(key)) {
            this.msgTopicNameToMaxDocNums = parseSizeOrDocNum(value);
         } else if (LION_KEY_ACK_CAPPED_COLLECTION_SIZE.equals(key)) {
            this.ackTopicNameToSizes = parseSizeOrDocNum(value);
         } else if (LION_KEY_ACK_CAPPED_COLLECTION_MAX_DOC_NUM.equals(key)) {
            this.ackTopicNameToMaxDocNums = parseSizeOrDocNum(value);
         } else if (LION_KEY_BACKUP_MSG_CAPPED_COLLECTION_SIZE.equals(key)) {
            this.backupMsgTopicNameToSizes = parseSizeOrDocNum(value);
         } else if (LION_KEY_BACKUP_MSG_CAPPED_COLLECTION_MAX_DOC_NUM.equals(key)) {
            this.backupMsgTopicNameToMaxDocNums = parseSizeOrDocNum(value);
         } else if (LION_KEY_BACKUP_ACK_CAPPED_COLLECTION_SIZE.equals(key)) {
            this.backupAckTopicNameToSizes = parseSizeOrDocNum(value);
         } else if (LION_KEY_BACKUP_ACK_CAPPED_COLLECTION_MAX_DOC_NUM.equals(key)) {
            this.backupAckTopicNameToMaxDocNums = parseSizeOrDocNum(value);
         } else if (LION_KEY_HEARTBEAT_SERVER_URI.equals(key)) {
            Mongo oldMongo = this.heartbeatMongo;
            this.heartbeatMongo = parseURIAndCreateHeartbeatMongo(value);
            //Mongo可能有更新，所以需要关闭旧的不再使用的Mongo
            Thread.sleep(5000);//DAO可能正在使用旧的Mongo，故等候5秒，才执行关闭操作
            closeUnuseMongo(oldMongo, this.topicNameToMongoMap.values(), this.heartbeatMongo);
         } else if (LION_KEY_HEARTBEAT_CAPPED_COLLECTION_SIZE.equals(key)) {
            this.heartbeatCappedCollectionSize = Integer.parseInt(value);
            if (LOG.isInfoEnabled()) {
               LOG.info("parse " + value);
            }
         } else if (LION_KEY_HEARTBEAT_CAPPED_COLLECTION_MAX_DOC_NUM.equals(key)) {
            this.heartbeatCappedCollectionMaxDocNum = Integer.parseInt(value);
            if (LOG.isInfoEnabled()) {
               LOG.info("parse " + value);
            }
         }
      } catch (Exception e) {
         LOG.error("Error occour when reset config from Lion, no config property would changed :" + e.getMessage(), e);
      }
   }

   /**
    * 关闭无用的Mongo实例
    */
   private void closeUnuseMongo(Collection<Mongo> oldMongos, Collection<Mongo> curMongos, Mongo curMongo) {
      // 找到无用的Mongo：oldTopicNameToMongoMap.values - topicNameToMongoMap.values
      oldMongos.removeAll(curMongos);
      oldMongos.remove(curMongo);
      //close所有unuse的mongo
      for (Mongo unuseMongo : oldMongos) {
         if (unuseMongo != null) {
            unuseMongo.close();
            LOG.info("Close unuse Mongo: " + unuseMongo);
         }
      }
   }

   /**
    * 关闭无用的Mongo实例
    */
   private void closeUnuseMongo(Mongo oldMongo, Collection<Mongo> curMongos, Mongo curMongo) {
      if (!curMongos.contains(oldMongo) && oldMongo != curMongo) {
         oldMongo.close();
         LOG.info("Close unuse Mongo: " + oldMongo);
      }
   }

   @SuppressWarnings({ "rawtypes", "unchecked" })
   private boolean equalsOutOfOrder(List list1, List list2) {
      if (list1 == null || list2 == null) {
         return false;
      }
      return list1.containsAll(list2) && list2.containsAll(list1);
   }

   private MongoOptions getMongoOptions(MongoConfig config) {
      MongoOptions options = new MongoOptions();
      options.slaveOk = config.isSlaveOk();
      options.socketKeepAlive = config.isSocketKeepAlive();
      options.socketTimeout = config.getSocketTimeout();
      options.connectionsPerHost = config.getConnectionsPerHost();
      options.threadsAllowedToBlockForConnectionMultiplier = config.getThreadsAllowedToBlockForConnectionMultiplier();
      options.w = config.getW();
      options.wtimeout = config.getWtimeout();
      options.fsync = config.isFsync();
      options.connectTimeout = config.getConnectTimeout();
      options.maxWaitTime = config.getMaxWaitTime();
      options.autoConnectRetry = config.isAutoConnectRetry();
      options.safe = config.isSafe();
      return options;
   }

   public DBCollection getMessageCollection(String topicName) {
      return getMessageCollection(topicName, null);
   }

   /**
    * @param topicName
    * @param consumerId consumerId为null表示使用非backup消息队列
    * @return
    */
   public DBCollection getMessageCollection(String topicName, String consumerId) {
      //根据topicName获取Mongo实例
      Mongo mongo = getMongo(topicName);
      DBCollection collection;
      if (consumerId == null) {
         collection = this.getCollection(mongo, getIntSafely(msgTopicNameToSizes, topicName),
               getIntSafely(msgTopicNameToMaxDocNums, topicName), MSG_PREFIX + topicName, new BasicDBObject(
                     MessageDAOImpl.ID, -1));
      } else {
         BasicDBObject index1 = new BasicDBObject(MessageDAOImpl.ID, -1);
         BasicDBObject index2 = new BasicDBObject(MessageDAOImpl.ORIGINAL_ID, -1);
         collection = this.getCollection(mongo, getIntSafely(backupMsgTopicNameToSizes, topicName),
               getIntSafely(backupMsgTopicNameToMaxDocNums, topicName), BACKUP_MSG_PREFIX + topicName + "#"
                     + consumerId, index1, index2);
      }
      return collection;
   }

   private Mongo getMongo(String topicName) {
      Mongo mongo = this.topicNameToMongoMap.get(topicName);
      if (mongo == null) {
         if (LOG.isDebugEnabled()) {
            LOG.debug("topicname '" + topicName + "' do not match any Mongo Server, use default.");
         }
         mongo = this.topicNameToMongoMap.get(TOPICNAME_DEFAULT);
      }
      return mongo;
   }

   private int getIntSafely(Map<String, Integer> map, String key) {
      Integer i = null;
      if (map != null) {
         i = map.get(key);
         if (i == null) {
            i = map.get(TOPICNAME_DEFAULT);
         }
      }
      return i == null ? -1 : i.intValue();
   }

   public DBCollection getAckCollection(String topicName, String consumerId) {
      return getAckCollection(topicName, consumerId, false);
   }

   public DBCollection getAckCollection(String topicName, String consumerId, boolean isBackup) {
      Mongo mongo = getMongo(topicName);
      DBCollection collection;
      if (!isBackup) {
         collection = this.getCollection(mongo, getIntSafely(ackTopicNameToSizes, topicName),
               getIntSafely(ackTopicNameToMaxDocNums, topicName), ACK_PREFIX + topicName + "#" + consumerId,
               new BasicDBObject(AckDAOImpl.MSG_ID, -1));
      } else {
         collection = this.getCollection(mongo, getIntSafely(backupAckTopicNameToSizes, topicName),
               getIntSafely(backupAckTopicNameToMaxDocNums, topicName), BACKUP_ACK_PREFIX + topicName + "#"
                     + consumerId, new BasicDBObject(AckDAOImpl.MSG_ID, -1));
      }
      return collection;
   }

   public DBCollection getHeartbeatCollection(String ip) {
      //根据topicName获取Mongo实例
//      Mongo mongo = getMongo(TOPICNAME_HEARTBEAT);
      Mongo mongo = this.heartbeatMongo;
      return this.getCollection(mongo, this.heartbeatCappedCollectionSize, this.heartbeatCappedCollectionMaxDocNum,
            "heartbeat#" + ip, new BasicDBObject(HeartbeatDAOImpl.TICK, -1));
   }

   private DBCollection getCollection(Mongo mongo, int size, int cappedCollectionMaxDocNum, String dbName,
                                      DBObject... indexDBObjects) {
      //根据topicname从Mongo实例从获取DB
      DB db = mongo.getDB(dbName);
      //从DB实例获取Collection(因为只有一个Collection，所以名字均叫做c),如果不存在，则创建)
      DBCollection collection = null;
      if (!collectionExists(db)) {//从缓存检查default collection 存在的标识，避免db.collectionExists的调用
         synchronized (db) {
            if (!collectionExists(db) && !db.collectionExists(DEFAULT_COLLECTION_NAME)) {
               collection = createColletcion(db, DEFAULT_COLLECTION_NAME, size, cappedCollectionMaxDocNum,
                     indexDBObjects);
            }
            markCollectionExists(db);//缓存default collection 存在的标识，避免db.collectionExists的调用
         }
         if (collection == null) {
            collection = db.getCollection(DEFAULT_COLLECTION_NAME);
         }
      } else {
         collection = db.getCollection(DEFAULT_COLLECTION_NAME);
      }
      return collection;
   }

   /**
    * 由于collection创建后不会删除，故可以在内存缓存collection是否存在<br>
    * 返回true，表示集合确实存在；<br>
    * 返回false，表示集合可能不存在。<br>
    */
   private boolean collectionExists(DB db) {
      return collectionExistsSign.containsKey(db);
   }

   /**
    * 在内存缓存db的default collection是否存在<br>
    */
   private void markCollectionExists(DB db) {
      collectionExistsSign.put(db, Byte.MAX_VALUE);
   }

   private DBCollection createColletcion(DB db, String collectionName, int size, int cappedCollectionMaxDocNum,
                                         DBObject... indexDBObjects) {
      DBObject options = new BasicDBObject();
      options.put("capped", true);
      if (size > 0) {
         options.put("size", size * MILLION);//max db file size in bytes
      }
      if (cappedCollectionMaxDocNum > 0) {
         options.put("max", cappedCollectionMaxDocNum * MILLION);//max row count
      }
      try {
         DBCollection collection = db.createCollection(collectionName, options);
         LOG.info("Create collection '" + collection + "' on db " + db + ", index is " + indexDBObjects);
         if (indexDBObjects != null) {
            for (DBObject indexDBObject : indexDBObjects) {
               collection.ensureIndex(indexDBObject);
               LOG.info("Ensure index " + indexDBObject + " on colleciton " + collection);
            }
         }
         return collection;
      } catch (MongoException e) {
         if (e.getMessage() != null && e.getMessage().indexOf("collection already exists") >= 0) {
            //collection already exists
            LOG.warn(e.getMessage() + ":the collectionName is " + collectionName);
            return db.getCollection(collectionName);
         } else {
            //other exception, can not connect to mongo etc, should abort
            throw e;
         }
      }
   }

   private List<ServerAddress> parseUriToAddressList(String uri) {
      uri = uri.trim();
      String schema = "mongodb://";
      if (uri.startsWith(schema)) { // 兼容老各式uri
         uri = uri.substring(schema.length());
      }
      String[] hostPortArr = uri.split(",");
      List<ServerAddress> result = new ArrayList<ServerAddress>();
      for (int i = 0; i < hostPortArr.length; i++) {
         String[] pair = hostPortArr[i].split(":");
         try {
            result.add(new ServerAddress(pair[0].trim(), Integer.parseInt(pair[1].trim())));
         } catch (Exception e) {
            throw new IllegalArgumentException(e.getMessage() + ". Bad format of mongo uri：" + uri
                  + ". The correct format is mongodb://<host>:<port>,<host>:<port>", e);
         }
      }
      return result;
   }

   public void setDynamicConfig(DynamicConfig dynamicConfig) {
      this.dynamicConfig = dynamicConfig;
   }

   /**
    * 用于Hawk监控
    */
   public static class HawkMBean {

      private final WeakReference<MongoClient> mongoClient;

      private HawkMBean(MongoClient mongoClient) {
         this.mongoClient = new WeakReference<MongoClient>(mongoClient);
      }

      public String getSeverURILionKey() {
         return (mongoClient.get() != null) ? mongoClient.get().severURILionKey : null;
      }

      public Map<String, Integer> getMsgTopicNameToSizes() {
         return (mongoClient.get() != null) ? mongoClient.get().msgTopicNameToSizes : null;
      }

      public Map<String, Integer> getMsgTopicNameToMaxDocNums() {
         return (mongoClient.get() != null) ? mongoClient.get().msgTopicNameToMaxDocNums : null;
      }

      public Map<String, Integer> getAckTopicNameToSizes() {
         return (mongoClient.get() != null) ? mongoClient.get().ackTopicNameToSizes : null;
      }

      public Map<String, Integer> getAckTopicNameToMaxDocNums() {
         return (mongoClient.get() != null) ? mongoClient.get().ackTopicNameToMaxDocNums : null;
      }

      public Map<String, Integer> getBackupMsgTopicNameToSizes() {
         return (mongoClient.get() != null) ? mongoClient.get().backupMsgTopicNameToSizes : null;
      }

      public Map<String, Integer> getBackupMsgTopicNameToMaxDocNums() {
         return (mongoClient.get() != null) ? mongoClient.get().backupMsgTopicNameToMaxDocNums : null;
      }

      public Map<String, Integer> getBackupAckTopicNameToSizes() {
         return (mongoClient.get() != null) ? mongoClient.get().backupAckTopicNameToSizes : null;
      }

      public Map<String, Integer> getBackupAckTopicNameToMaxDocNums() {
         return (mongoClient.get() != null) ? mongoClient.get().backupAckTopicNameToMaxDocNums : null;
      }

      public String getHeartbeatMongo() {
         return (mongoClient.get() != null) ? mongoClient.get().heartbeatMongo.toString() : null;
      }

      public int getHeartbeatCappedCollectionSize() {
         return (mongoClient.get() != null) ? mongoClient.get().heartbeatCappedCollectionSize : null;
      }

      public int getHeartbeatCappedCollectionMaxDocNum() {
         return (mongoClient.get() != null) ? mongoClient.get().heartbeatCappedCollectionMaxDocNum : null;
      }

      public String getTopicNameToMongoMap() {
         return (mongoClient.get() != null) ? mongoClient.get().topicNameToMongoMap.toString() : null;
      }

      public String getMongoOptions() {
         return (mongoClient.get() != null) ? mongoClient.get().mongoOptions.toString() : null;
      }

      public String getCollectionExistsSign() {
         return (mongoClient.get() != null) ? mongoClient.get().collectionExistsSign.toString() : null;
      }

   }

   public static void main(String[] args) {
      int size = 10000;
      System.out.println((long) MILLION * size);
   }

}
