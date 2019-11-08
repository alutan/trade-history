/*
       Copyright 2018, 2019 IBM Corp All Rights Reserved
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
       http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package com.ibm.hybrid.cloud.sample.stocktrader.tradehistory.demo;

import java.io.IOException;
import java.io.StringReader;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.websocket.CloseReason;
import javax.websocket.EncodeException;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.log4j.Logger;

import com.ibm.hybrid.cloud.sample.stocktrader.tradehistory.mongo.MongoConnector;
import com.ibm.hybrid.cloud.sample.stocktrader.tradehistory.mongo.StockPurchase;
import com.sun.corba.se.spi.activation._ActivatorImplBase;
import com.ibm.hybrid.cloud.sample.stocktrader.tradehistory.kafka.Consumer;

import javax.inject.Inject;

@ServerEndpoint(value = "/democonsume", encoders = { DemoMessageEncoder.class })
public class DemoConsumeSocket {

    private Session currentSession = null;

    @Inject
    private Consumer consumer;

    private MessageController messageController = null;

    private Logger logger = Logger.getLogger(DemoConsumeSocket.class);

    private static MongoConnector MONGO_CONNECTOR;
    @Resource
    ManagedExecutorService managedExecutorService;

    @OnOpen
    public void onOpen(Session session, EndpointConfig endpointConfig) {
        logger.debug(String.format("Socket opened with id %s", session.getId()));
        currentSession = session;
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        try (JsonReader reader = Json.createReader(new StringReader(message))) {
            JsonObject jsonMessage = reader.readObject();
            String action = jsonMessage.getString("action");
            logger.debug(String.format("Message received from session %s with action %s", session.getId(), action));
            switch (action) {
            case "start":
                if (messageController == null) {
                    logger.info("Starting message consumption");
                    messageController = new MessageController();
                    messageController.start();
                } else {
                    logger.info("Resuming message consumption");
                    messageController.resume();
                }
                break;
            case "stop":
                logger.info("Pausing message consumption");
                messageController.pause();
                break;
            default:
                logger.warn("Received message with unknown action, expected 'start' or 'stop'.");
                break;
            }
        } catch (JsonException | IllegalStateException e) {
            onError(e);
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        logger.debug("Closed websocket");
        if (messageController != null) {
            logger.debug("Stopping message controller");
            messageController.stop();
        }
        logger.info(String.format("Consumer and client connection for session %s closed.", session.getId()));
    }

    @OnError
    public void onError(Throwable throwable) {
        logger.error(throwable);
        try {
            CloseReason closeReason = new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION,
                    throwable.getMessage());
            currentSession.close(closeReason);
        } catch (IOException e) {
            logger.error(e);
        }
    }
    
    private class MessageController {
        MessageSender sender = new MessageSender();
        KafkaConsumer consumer = new KafkaConsumer();
        BlockingQueue<DemoConsumedMessage> queue = new LinkedBlockingQueue<>();
        
        void start() {
            if(MONGO_CONNECTOR != null) {
                sender.messageQueue = queue;
                consumer.messageQueue = queue;
                Thread thread = new Thread(sender);
                thread.start();
                thread = new Thread(consumer);
                thread.start();
            }
            else {
                logger.debug("Mongo not initialized properly");
                stop();
            }
        }
        
        void pause() {
            sender.sendMessages = false;
        }
        
        void resume() {
            sender.sendMessages = true;
        }
        
        void stop() {
            consumer.exit = true;
            sender.exit = true;
        }
    }

    private class MessageSender implements Runnable {
        volatile boolean exit = false;
        volatile boolean sendMessages = true;
        BlockingQueue<DemoConsumedMessage> messageQueue;

        @Override
        public void run() {
            try {
                while (!exit) {
                    int messagesSent = 0;
                    while (!exit && sendMessages) {
                        logger.debug("Sending / waiting for messages, queue depth : " + messageQueue.size());
                        try {
                            DemoConsumedMessage message = messageQueue.poll(1, TimeUnit.SECONDS);
                            if (message != null) {
                                logger.debug(String.format("Updating session %s with new message %s",
                                        currentSession.getId(), message.encode()));
                                currentSession.getBasicRemote().sendObject(message);
                                messagesSent++;
                            }
                        } catch (IOException | EncodeException e) {
                            onError(e);
                        } 
                    }
                    sendMessages = false;
                    Thread.sleep(1000);
                    logger.debug(String.format("Paused consumer for session %s.", currentSession.getId()));
                }
            }catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        }

    }

    private class KafkaConsumer implements Runnable {
        volatile boolean exit = false;
        BlockingQueue<DemoConsumedMessage> messageQueue;

        public KafkaConsumer(){
            super();
            try {
                MONGO_CONNECTOR = new MongoConnector();
            }
            catch(NullPointerException e) {
                logger.debug(e.getMessage());
                MONGO_CONNECTOR = null;
            }  
            catch(IllegalArgumentException e) {
                logger.debug(e.getMessage());

                MONGO_CONNECTOR = null;
            }
            catch(Exception e) {
                logger.debug(e.getMessage());
                MONGO_CONNECTOR = null;
            }
        }
        @Override
        public void run() {
            while (!exit) {
                logger.info("Consuming messages from Kafka");
                ConsumerRecords<String, String> records = consumer.consume();
                logger.info("Processing records: " + records);
                for (ConsumerRecord<String, String> record : records) {
                    DemoConsumedMessage message = new DemoConsumedMessage(record.topic(), record.partition(),
                            record.offset(), record.value(), record.timestamp());
                    
                    StockPurchase sp = new StockPurchase(message.getValue());
                    logger.info("Inserting stock purchase history to Mongo DB");
                    MONGO_CONNECTOR.insertStockPurchase(sp, message.getTopic());
                    try {
                        logger.debug(String.format("Consumed message %s",message.encode()));
                        while (!exit && !messageQueue.offer(message, 1, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            logger.debug("Closing consumer");
            consumer.shutdown();
            logger.debug("Consumer closed");
        }

    }
}
