/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.hbase.bolt;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.tuple.Tuple;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RetriesExhaustedWithDetailsException;
import org.apache.storm.hbase.bolt.mapper.HBaseMapper;
import org.apache.storm.hbase.common.ColumnList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * Basic bolt for writing to HBase.
 *
 * Note: Each HBaseBolt defined in a topology is tied to a specific table.
 *
 */
// TODO support more configuration options, for now we're defaulting to the hbase-*.xml files found on the classpath
public class HBaseBolt  extends BaseRichBolt {
    private static final Logger LOG = LoggerFactory.getLogger(HBaseBolt.class);

    private OutputCollector collector;

    private transient HTable table;
    private String tableName;
    private HBaseMapper mapper;
    boolean writeToWAL = true;
    private Properties properties = null;
    
    public HBaseBolt(String tableName, HBaseMapper mapper){
        this.tableName = tableName;
        this.mapper = mapper;
    }
    
    public HBaseBolt(String tableName, HBaseMapper mapper, Properties properties){
        this.tableName = tableName;
        this.mapper = mapper;
        this.properties = properties;
    }
    
    public HBaseBolt writeToWAL(boolean writeToWAL){
        this.writeToWAL = writeToWAL;
        return this;
    }

    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector collector) {
        
        String hbaseClusterDistributed;
        String hbaseRootdir;
        String hbaseZookeeperQuorum;
    	
    	this.collector = collector;
        Configuration hbConfig;
       
        hbConfig = HBaseConfiguration.create();
        
        if (properties != null)
        {
        	hbaseClusterDistributed = properties.getProperty("hbase.cluster.distribute", "true");
            hbaseRootdir = properties.getProperty("hbase.rootdir", "hdfs://localhost:8020/hbase");
            hbaseZookeeperQuorum = properties.getProperty("hbase.zookeeper.quorum", "localhost");
        	hbConfig.set("hbase.cluster.distribute", hbaseClusterDistributed);
        	hbConfig.set("hbase.rootdir", hbaseRootdir);
        	hbConfig.set("hbase.zookeeper.quorum", hbaseZookeeperQuorum);
        }
        
        Iterator<Entry<String,String>> it = hbConfig.iterator();
        Entry<String,String> en;
        
        while (it.hasNext())
        {
        	en = it.next();
        	System.out.println(en.getKey()+" "+en.getValue());        	
        }
        
        String hbRoot = (String)map.get("hbase.rootdir");
        if(hbRoot != null){
            LOG.info("Using hbase.rootdir={}", hbRoot);
            hbConfig.set("hbase.rootdir", hbRoot);
        }

        try{
            this.table = new HTable(hbConfig, this.tableName);
        } catch(IOException e){
            throw new RuntimeException("HBase bolt preparation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void execute(Tuple tuple) {
        byte[] rowKey = this.mapper.rowKey(tuple);
        ColumnList cols = this.mapper.columns(tuple);
        if(cols.hasColumns()){
            Put put = new Put(rowKey);
            // TODO fix call to deprecated method
            put.setWriteToWAL(this.writeToWAL);
            for(ColumnList.Column col : cols.getColumns()){
                if(col.getTs() > 0){
                    put.add(
                            col.getFamily(),
                            col.getQualifier(),
                            col.getTs(),
                            col.getValue()
                    );
                } else{
                    put.add(
                            col.getFamily(),
                            col.getQualifier(),
                            col.getValue()
                    );
                }
            }
            try{
                this.table.put(put);
            } catch(RetriesExhaustedWithDetailsException e){
                LOG.warn("Failing tuple. Error writing column.", e);
                this.collector.fail(tuple);
                return;
            } catch (InterruptedIOException e) {
                LOG.warn("Failing tuple. Error writing column.", e);
                this.collector.fail(tuple);
                return;
            }
        }
        if(cols.hasCounters()){
            Increment inc = new Increment(rowKey);
            // TODO fix call to deprecated method
            inc.setWriteToWAL(this.writeToWAL);
            for(ColumnList.Counter cnt : cols.getCounters()){
                inc.addColumn(
                        cnt.getFamily(),
                        cnt.getQualifier(),
                        cnt.getIncrement()
                );
            }
            try{
                this.table.increment(inc);
            } catch (IOException e) {
                LOG.warn("Failing tuple. Error incrementing counter.", e);
                this.collector.fail(tuple);
                return;
            }
        }
        this.collector.ack(tuple);

    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {

    }
}
