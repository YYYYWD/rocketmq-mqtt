/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.mqtt.cs.protocol.mqtt.handler;


import com.alibaba.fastjson.JSON;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.mqtt.MqttFixedHeader;
import io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader;
import io.netty.handler.codec.mqtt.MqttSubAckMessage;
import io.netty.handler.codec.mqtt.MqttSubAckPayload;
import io.netty.handler.codec.mqtt.MqttSubscribeMessage;
import io.netty.handler.codec.mqtt.MqttSubscribePayload;
import io.netty.handler.codec.mqtt.MqttTopicSubscription;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.mqtt.common.facade.LmqQueueStore;
import org.apache.rocketmq.mqtt.common.facade.RetainedPersistManager;
import org.apache.rocketmq.mqtt.common.hook.HookResult;
import org.apache.rocketmq.mqtt.common.model.Message;
import org.apache.rocketmq.mqtt.common.model.Subscription;
import org.apache.rocketmq.mqtt.common.util.MessageUtil;
import org.apache.rocketmq.mqtt.common.util.TopicUtils;
import org.apache.rocketmq.mqtt.cs.channel.ChannelCloseFrom;
import org.apache.rocketmq.mqtt.cs.channel.ChannelInfo;
import org.apache.rocketmq.mqtt.cs.channel.ChannelManager;
import org.apache.rocketmq.mqtt.cs.protocol.mqtt.MqttPacketHandler;
import org.apache.rocketmq.mqtt.cs.session.Session;
import org.apache.rocketmq.mqtt.cs.session.infly.MqttMsgId;
import org.apache.rocketmq.mqtt.cs.session.infly.PushAction;
import org.apache.rocketmq.mqtt.cs.session.infly.RetryDriver;
import org.apache.rocketmq.mqtt.cs.session.loop.SessionLoop;
import org.apache.rocketmq.mqtt.meta.core.MetaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.mqtt.MqttMessageIdVariableHeader.from;
import static io.netty.handler.codec.mqtt.MqttMessageType.SUBACK;
import static io.netty.handler.codec.mqtt.MqttQoS.AT_MOST_ONCE;
import static java.lang.Math.min;


@Component
public class MqttSubscribeHandler implements MqttPacketHandler<MqttSubscribeMessage> {
    private static Logger logger = LoggerFactory.getLogger(MqttSubscribeHandler.class);

    @Resource
    private MqttMsgId mqttMsgId;
    @Resource
    private SessionLoop sessionLoop;

    @Resource
    private LmqQueueStore lmqQueueStore;
    @Resource
    private ChannelManager channelManager;

    @Resource
    private RetainedPersistManager retainedPersistManager;

    @Resource
    private PushAction pushAction;
    @Resource
    private RetryDriver retryDriver;

    @Resource
    private MetaClient metaClient;
    private ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, new ThreadFactoryImpl("check_subscribe_future"));


    @Override
    public boolean preHandler(ChannelHandlerContext ctx, MqttSubscribeMessage mqttMessage) {
        return true;
    }


    @Override
    public void doHandler(ChannelHandlerContext ctx, MqttSubscribeMessage mqttMessage, HookResult upstreamHookResult) {
        String clientId = ChannelInfo.getClientId(ctx.channel());
        Channel channel = ctx.channel();
        if (!upstreamHookResult.isSuccess()) {
            channelManager.closeConnect(channel, ChannelCloseFrom.SERVER, upstreamHookResult.getRemark());
            return;
        }

        CompletableFuture<Void> future = new CompletableFuture<>();
        ChannelInfo.setFuture(channel, ChannelInfo.FUTURE_SUBSCRIBE, future);
        scheduler.schedule(() -> {
            if (!future.isDone()) {
                future.complete(null);
            }
        }, 1, TimeUnit.SECONDS);
        try {
            MqttSubscribePayload payload = mqttMessage.payload();
            List<MqttTopicSubscription> mqttTopicSubscriptions = payload.topicSubscriptions();
            Set<Subscription> subscriptions = new HashSet<>();
            if (mqttTopicSubscriptions != null && !mqttTopicSubscriptions.isEmpty()) {
                for (MqttTopicSubscription mqttTopicSubscription : mqttTopicSubscriptions) {
                    Subscription subscription = new Subscription();
                    subscription.setQos(mqttTopicSubscription.qualityOfService().value());
                    subscription.setTopicFilter(TopicUtils.normalizeTopic(mqttTopicSubscription.topicName()));
                    subscriptions.add(subscription);
                }
                sessionLoop.addSubscription(ChannelInfo.getId(ctx.channel()), subscriptions);
            }
            future.thenAccept(aVoid -> {
                if (!channel.isActive()) {
                    return;
                }
                ChannelInfo.removeFuture(channel, ChannelInfo.FUTURE_SUBSCRIBE);
                channel.writeAndFlush(getResponse(mqttMessage));
                if (!subscriptions.isEmpty()) {            //Write retained message
                    try {
                        sendRetainMessage(ctx, subscriptions);
                    } catch (ExecutionException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        } catch (Exception e) {
            logger.error("Subscribe:{}", clientId, e);
            channelManager.closeConnect(channel, ChannelCloseFrom.SERVER, "SubscribeException");
        }
    }

    private MqttSubAckMessage getResponse(MqttSubscribeMessage mqttSubscribeMessage) {
        MqttSubscribePayload payload = mqttSubscribeMessage.payload();
        List<MqttTopicSubscription> mqttTopicSubscriptions = payload.topicSubscriptions();
        // AT_MOST_ONCE
        int[] qoss = new int[mqttTopicSubscriptions.size()];
        int i = 0;
        for (MqttTopicSubscription sub : mqttTopicSubscriptions) {
            qoss[i++] = sub.qualityOfService().value();
        }
        MqttFixedHeader fixedHeader = new MqttFixedHeader(SUBACK, false, AT_MOST_ONCE, false, 0);
        MqttMessageIdVariableHeader variableHeader = from(mqttSubscribeMessage.variableHeader().messageId());
        MqttSubAckMessage mqttSubAckMessage = new MqttSubAckMessage(fixedHeader, variableHeader,
            new MqttSubAckPayload(qoss));
        return mqttSubAckMessage;
    }

    private void sendRetainMessage(ChannelHandlerContext ctx, Set<Subscription> subscriptions) throws ExecutionException, InterruptedException {

        String clientId = ChannelInfo.getClientId(ctx.channel());
        Session session = sessionLoop.getSession(ChannelInfo.getId(ctx.channel()));
        Set<Subscription> preciseTopics = new HashSet<>();
        Set<Subscription> wildcardTopics = new HashSet<>();

        for (Subscription subscription : subscriptions) {
            if (!TopicUtils.isWildCard(subscription.getTopicFilter())) {
                preciseTopics.add(subscription);
            } else {
                wildcardTopics.add(subscription);
            }
        }

        for (Subscription subscription : preciseTopics) {
            CompletableFuture<byte[]> retainedMessage = retainedPersistManager.getRetainedMessage(subscription.getTopicFilter());
            retainedMessage.whenComplete((bytes, throwable) -> {
                if (bytes == null) {
                    return;
                }
                Message message = JSON.parseObject(bytes, Message.class);
                _sendMessage(session, clientId, subscription, message);
            });
        }

        for (Subscription subscription : wildcardTopics) {
            Set<String> topics = retainedPersistManager.getTopicsFromTrie(subscription);
            for (String topic : topics) {
                CompletableFuture<byte[]> retainedMessage = retainedPersistManager.getRetainedMessage(topic);
                retainedMessage.whenComplete((bytes, throwable) -> {
                    if (bytes == null) {
                        return;
                    }
                    Message message = JSON.parseObject(bytes, Message.class);
                    _sendMessage(session, clientId, subscription, message);
                });
            }
        }
    }

    private void _sendMessage(Session session, String clientId, Subscription subscription, Message message) {

        String payLoad = new String(message.getPayload());
        if (payLoad.equals(MessageUtil.EMPTYSTRING) && message.isEmpty()) {
            return;
        }

        int mqttId = mqttMsgId.nextId(clientId);
        int qos = min(subscription.getQos(), message.qos());
        if (qos == 0) {
            pushAction.write(session, message, mqttId, 0, subscription);
            pushAction.rollNextByAck(session, mqttId);
        } else {
            retryDriver.mountPublish(mqttId, message, subscription.getQos(), ChannelInfo.getId(session.getChannel()), subscription);
            pushAction.write(session, message, mqttId, qos, subscription);
        }
    }

}

