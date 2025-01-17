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

package org.apache.rocketmq.mqtt.common.model;

import org.apache.rocketmq.mqtt.common.util.TopicUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


public class Trie<K, V> {

    private TrieNode<K, V> rootNode = new TrieNode(null);

    private Set<String> nodePath = new HashSet<>();

    private int nodeCount = 0;


    public synchronized V addNode(String key, V nodeValue, K nodeKey) {
        try {
            String[] keyArray = key.split(Constants.MQTT_TOPIC_DELIMITER);
            TrieNode<K, V> currentNode = rootNode;
            int level = 0;
            while (level < keyArray.length) {
                TrieNode<K, V> trieNode = currentNode.children.get(keyArray[level]);
                if (trieNode == null) {
                    trieNode = new TrieNode(currentNode);
                    TrieNode oldNode = currentNode.children.putIfAbsent(keyArray[level], trieNode);
                    if (oldNode != null) {
                        trieNode = oldNode;
                    }
                }
                level++;
                currentNode = trieNode;
            }
            currentNode.end = true;
            V old = currentNode.valueSet.put(nodeKey, nodeValue);
            nodePath.add(key);
            nodeCount++;
            return old;
        } catch (Throwable e) {
            throw new TrieException(e);
        }
    }

    /**
     * @param key
     * @param valueKey
     * @return null if can not find the key and valueKey or return the value
     */
    public synchronized V deleteNode(String key, K valueKey) {
        try {
            String[] keyArray = key.split(Constants.MQTT_TOPIC_DELIMITER);
            TrieNode<K, V> currentNode = rootNode;
            int level = 0;
            while (level < keyArray.length) {
                TrieNode trieNode = currentNode.children.get(keyArray[level]);
                if (trieNode == null) {
                    break;
                }
                level++;
                currentNode = trieNode;
            }
            V oldValue = currentNode.valueSet.remove(valueKey);
            //clean the empty node
            while (currentNode.children.isEmpty() && currentNode.valueSet.isEmpty() && currentNode.parentNode != null) {
                currentNode.parentNode.children.remove(keyArray[--level]);
                currentNode = currentNode.parentNode;
            }
            return oldValue;
        } catch (Throwable e) {
            throw new TrieException(e);
        }
    }

    public synchronized boolean deleteTrieNode(String key, K valueKey) {
        try {
            if (!nodePath.contains(key)) {
                return false;
            }
            String[] keyArray = key.split(Constants.MQTT_TOPIC_DELIMITER);
            TrieNode<K, V> currentNode = rootNode;
            int level = 0;
            while (level < keyArray.length) {
                TrieNode trieNode = currentNode.children.get(keyArray[level]);
                if (trieNode == null) {
                    break;
                }
                level++;
                currentNode = trieNode;
            }
            V oldValue = currentNode.valueSet.remove(valueKey);
            currentNode.end = false;
            //clean the empty node
            while (currentNode.children.isEmpty() && currentNode.valueSet.isEmpty() && currentNode.parentNode != null) {
                currentNode.parentNode.children.remove(keyArray[--level]);
                currentNode = currentNode.parentNode;
            }
            this.nodePath.remove(key);
            nodeCount--;
            return true;
        } catch (Throwable e) {
            throw new TrieException(e);
        }
    }

    public long countSubRecords() {
        return countLevelRecords(rootNode);
    }

    private long countLevelRecords(TrieNode<K, V> currentNode) {  //Calculate how many chantels there are for the whole tree
        if (currentNode == null) {
            return 0;
        }
        if (currentNode.children.isEmpty()) {
            return currentNode.valueSet.size();
        }
        long childrenCount = 0;
        for (Map.Entry<String, TrieNode<K, V>> entry : currentNode.children.entrySet()) {
            childrenCount += countLevelRecords(entry.getValue());
        }
        return childrenCount + currentNode.valueSet.size();
    }

    public Map<K, V> getNode(String key) {   //Get all the channels under the given key prefix, the feeling is the key method, according to the key to check the corresponding channels
        try {
            String[] keyArray = key.split(Constants.MQTT_TOPIC_DELIMITER);
            Map<K, V> result = findValueSet(rootNode, keyArray, 0, keyArray.length, false);
            return result;
        } catch (Throwable e) {
            throw new TrieException(e);
        }
    }

    public void traverseAll(TrieMethod<K, V> method) {
        StringBuilder builder = new StringBuilder(128);
        traverse(rootNode, method, builder);
    }

    public Set<String> getNodePath(String key) {
        try {
            String[] keyArray = key.split(Constants.MQTT_TOPIC_DELIMITER);
            StringBuilder builder = new StringBuilder(key.length());
            Set<String> result = findValuePath(rootNode, keyArray, 0, keyArray.length, builder, false);
            return result;
        } catch (Throwable e) {
            throw new TrieException(e);
        }
    }

    private Set<String> findValuePath(TrieNode<K, V> currentNode, String[] topicArray, int level, int maxLevel,
                                      StringBuilder builder, boolean isNumberSign) {
        Set<String> result = new HashSet<>();
        // match end of path
        boolean isPathEnd = (level == maxLevel || isNumberSign) && !currentNode.valueSet.isEmpty() && builder.length() > 0;
        if (isPathEnd) {
            result.add(TopicUtils.normalizeTopic(builder.toString().substring(0, builder.length() - 1)));
        }
        // match the '#'
        TrieNode numberMatch = currentNode.children.get(Constants.NUMBER_SIGN);
        if (numberMatch != null) {
            int start = builder.length();
            builder.append(Constants.NUMBER_SIGN).append(Constants.MQTT_TOPIC_DELIMITER);
            result.addAll(findValuePath(numberMatch, topicArray, level + 1, maxLevel, builder, true));
            builder.delete(start, builder.length());
        }
        // match the mqtt-topic path
        if (level < maxLevel && !currentNode.children.isEmpty()) {
            // match the precise
            TrieNode trieNode = currentNode.children.get(topicArray[level]);
            if (trieNode != null) {
                int start = builder.length();
                builder.append(topicArray[level]).append(Constants.MQTT_TOPIC_DELIMITER);
                result.addAll(findValuePath(trieNode, topicArray, level + 1, maxLevel, builder, false));
                builder.delete(start, builder.length());
            }
            // match the '+'
            TrieNode plusMatch = currentNode.children.get(Constants.PLUS_SIGN);
            if (plusMatch != null) {
                int start = builder.length();
                builder.append(Constants.PLUS_SIGN).append(Constants.MQTT_TOPIC_DELIMITER);
                result.addAll(findValuePath(plusMatch, topicArray, level + 1, maxLevel, builder, false));
                builder.delete(start, builder.length());
            }
        }
        return result;
    }

    private void traverse(TrieNode<K, V> currentNode, TrieMethod<K, V> method, StringBuilder builder) {
        for (Map.Entry<String, TrieNode<K, V>> entry : currentNode.children.entrySet()) {
            int start = builder.length();
            builder.append(entry.getKey()).append(Constants.MQTT_TOPIC_DELIMITER);
            traverse(entry.getValue(), method, builder);
            builder.delete(start, builder.length());
        }
        Iterator<Entry<K, V>> iterator = currentNode.valueSet.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, V> entry = iterator.next();
            try {
                method.doMethod(builder.toString(), entry.getKey());
            } catch (Throwable e) {
            }
        }
    }

    private Map<K, V> findValueSet(TrieNode<K, V> currentNode, String[] topicArray, int level, int maxLevel,
                                   boolean isNumberSign) {
        Map<K, V> result = new HashMap<>(16);
        // match the mqtt-topic leaf or match the leaf node of trie
        if (level == maxLevel || isNumberSign) {
            result.putAll(currentNode.valueSet);
        }
        // match the '#'
        TrieNode numberMatch = currentNode.children.get(Constants.NUMBER_SIGN);
        if (numberMatch != null) {
            result.putAll(findValueSet(numberMatch, topicArray, level + 1, maxLevel, true));
        }
        // match the mqtt-topic path
        if (level < maxLevel && !currentNode.children.isEmpty()) {
            // match the precise
            TrieNode trieNode = currentNode.children.get(topicArray[level]);
            if (trieNode != null) {
                result.putAll(findValueSet(trieNode, topicArray, level + 1, maxLevel, false));
            }
            // match the '+'
            TrieNode plusMatch = currentNode.children.get(Constants.PLUS_SIGN);
            if (plusMatch != null) {
                result.putAll(findValueSet(plusMatch, topicArray, level + 1, maxLevel, false));
            }
        }
        return result;
    }

    /**
     * @param key Topic wildcard
     * @return null if can not find the path correspond wildcard
     */
    public Set<String> getAllPath(String key) {  //find all node according to wildcard
        try {
            String[] keyArray = key.split(Constants.MQTT_TOPIC_DELIMITER);
            Set<String> result = new HashSet<>();
            _getAllPath(rootNode, keyArray, 0, keyArray.length, false, result, "");
            return result;
        } catch (Throwable e) {
            throw new TrieException(e);
        }
    }

    private void _getAllPath(TrieNode<K, V> currentNode, String[] topicArray, int level, int maxLevel, boolean findAll, Set<String> result, String path) {
        if (level >= maxLevel && !findAll) {
            if (currentNode.end) {
                result.add(path);
            }
            return;
        }
        if (findAll && currentNode.end) {
            result.add(path);
        }
        if (currentNode.end && level + 1 < maxLevel && topicArray[level + 1].equals("#")) {
            result.add(path);
        }
        if (findAll) {   // match the '#'
            for (String key : currentNode.children.keySet()) {
                _getAllPath(currentNode.children.get(key), topicArray, level + 1, maxLevel, true, result, path + key + Constants.MQTT_TOPIC_DELIMITER);
            }
            return;
        }
        if (topicArray[level].equals("+")) { // match the '+'
            for (String key : currentNode.children.keySet()) {
                _getAllPath(currentNode.children.get(key), topicArray, level + 1, maxLevel, false, result, path + key + Constants.MQTT_TOPIC_DELIMITER);
            }
        } else if (topicArray[level].equals("#")) { // match the '#'
            for (String key : currentNode.children.keySet()) {
                _getAllPath(currentNode.children.get(key), topicArray, level + 1, maxLevel, true, result, path + key + Constants.MQTT_TOPIC_DELIMITER);
            }
        } else {
            if (currentNode.children.get(topicArray[level]) != null) {
                String key = topicArray[level];
                _getAllPath(currentNode.children.get(topicArray[level]), topicArray, level + 1, maxLevel, false, result, path + key + Constants.MQTT_TOPIC_DELIMITER);
            }
        }

    }

    public boolean isExistNodePath(String topic) {
        return nodePath.contains(topic);
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(int nodeCount) {
        this.nodeCount = nodeCount;
    }

    public Set<String> getNodePath() {
        return this.nodePath;
    }


    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        for (String topic : nodePath) {
            result.append(topic).append(" ");
        }
        return result.toString();
    }


    class TrieNode<K, V> {
        public TrieNode<K, V> parentNode;

        public boolean end;  //end flag
        public Map<String, TrieNode<K, V>> children = new ConcurrentHashMap<>();
        public Map<K, V> valueSet = new ConcurrentHashMap<>();    //valueset k:channelId,value:qos

        public TrieNode(TrieNode<K, V> parentNode) {
            this.parentNode = parentNode;
        }
    }
}