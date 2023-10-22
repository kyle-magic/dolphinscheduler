/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.escheduler.server.master.runner;

import cn.escheduler.common.Constants;
import cn.escheduler.common.thread.Stopper;
import cn.escheduler.common.thread.ThreadUtils;
import cn.escheduler.common.utils.OSUtils;
import cn.escheduler.dao.ProcessDao;
import cn.escheduler.dao.model.ProcessInstance;
import cn.escheduler.server.zk.ZKMasterClient;
import org.apache.commons.configuration.Configuration;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 *  master scheduler thread
 *  在MasterServer调用
 *
 *  DESC：遍历 command 表 -> 将 command 转成 ProcessInstance，
 *  并 发起一次工作流调用 masterExecService.execute(new MasterExecThread(processInstance))
 */
public class MasterSchedulerThread implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(MasterSchedulerThread.class);

    private final ExecutorService masterExecService;

    /**
     * escheduler database interface
     */
    private final ProcessDao processDao;

    private final ZKMasterClient zkMasterClient ;

    private int masterExecThreadNum;

    private final Configuration conf;


    public MasterSchedulerThread(ZKMasterClient zkClient, ProcessDao processDao, Configuration conf, int masterExecThreadNum){
        this.processDao = processDao;
        this.zkMasterClient = zkClient;
        this.conf = conf;
        this.masterExecThreadNum = masterExecThreadNum;
        this.masterExecService = ThreadUtils.newDaemonFixedThreadExecutor("Master-Exec-Thread",masterExecThreadNum);
    }


    @Override
    public void run() {
        while (Stopper.isRunning()){        //一直占据线程执行

            // process instance
            ProcessInstance processInstance = null;

            InterProcessMutex mutex = null;
            try {

                if(OSUtils.checkResource(conf, true)){
                    if (zkMasterClient.getZkClient().getState() == CuratorFrameworkState.STARTED) {
                        //抢 分布式锁，成为active master
                        // create distributed lock with the root node path of the lock space as /escheduler/lock/failover/master
                        String znodeLock = zkMasterClient.getMasterLockPath();

                        mutex = new InterProcessMutex(zkMasterClient.getZkClient(), znodeLock);
                        mutex.acquire();

                        ThreadPoolExecutor poolExecutor = (ThreadPoolExecutor) masterExecService;
                        int activeCount = poolExecutor.getActiveCount();
                        // make sure to scan and delete command  table in one transaction
                        //一个 Command 代表了要执行一次工作流，拿到后创建 ProcessInstance，删除该command
                        processInstance = processDao.scanCommand(logger, OSUtils.getHost(), this.masterExecThreadNum - activeCount);
                        if (processInstance != null) {
                            logger.info("start master exex thread , split DAG ...");
                            masterExecService.execute(new MasterExecThread(processInstance));
                        }
                    }
                }

                // accessing the command table every SLEEP_TIME_MILLIS milliseconds
                Thread.sleep(Constants.SLEEP_TIME_MILLIS);

            }catch (Exception e){
                logger.error("master scheduler thread exception : " + e.getMessage(),e);
            }finally{
                if (mutex != null){
                    try {
                        mutex.release();
                    } catch (Exception e) {
                        if(e.getMessage().equals("instance must be started before calling this method")){
                            logger.warn("lock release");
                        }else{
                            logger.error("lock release failed : " + e.getMessage(),e);
                        }

                    }
                }
            }
        }
    }


}
