/**
 * Project: swallow-producerserver
 * 
 * File Created at 2012-6-27
 * $Id$
 * 
 * Copyright 2010 dianping.com.
 * All rights reserved.
 *
 * This software is the confidential and proprietary information of
 * Dianping Company. ("Confidential Information").  You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with dianping.com.
 */
package com.dianping.swallow.producerserver.bootstrap;

import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.dianping.swallow.common.producer.exceptions.RemoteServiceInitFailedException;
import com.dianping.swallow.common.producer.exceptions.ServerDaoException;

/**
 * ProducerServer的Bootstrap类，用以启动ProducerServer
 * 
 * @author tong.song
 */
public class ProducerServerBootstrap {
   public static void main(String[] args) {
//      try {
         new ClassPathXmlApplicationContext("producerServer.xml");
//      }catch (RemoteServiceInitFailedException e){
//         System.out.println(e.toString());
//      }catch (ServerDaoException e) {
//         System.out.println(e.toString());
//      }
   }
}