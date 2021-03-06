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
package org.apache.accumulo.server.zookeeper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.accumulo.fate.zookeeper.IZooReader;
import org.apache.accumulo.fate.zookeeper.IZooReaderWriter;
import org.apache.accumulo.fate.zookeeper.ZooReader;
import org.apache.accumulo.fate.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.fate.zookeeper.ZooUtil.NodeMissingPolicy;
import org.apache.accumulo.server.ServerContext;
import org.apache.zookeeper.KeeperException;

public class TransactionWatcher extends org.apache.accumulo.fate.zookeeper.TransactionWatcher {
  public static class ZooArbitrator implements Arbitrator {

    private ServerContext context;
    private ZooReader rdr;

    public ZooArbitrator(ServerContext context) {
      this.context = context;
      rdr = new ZooReader(context.getZooKeepers(), context.getZooKeepersSessionTimeOut());
    }

    @Override
    public boolean transactionAlive(String type, long tid) throws Exception {
      String path = context.getZooKeeperRoot() + "/" + type + "/" + tid;
      rdr.sync(path);
      return rdr.exists(path);
    }

    public static void start(ServerContext context, String type, long tid)
        throws KeeperException, InterruptedException {
      IZooReaderWriter writer = context.getZooReaderWriter();
      writer.putPersistentData(context.getZooKeeperRoot() + "/" + type, new byte[] {},
          NodeExistsPolicy.OVERWRITE);
      writer.putPersistentData(context.getZooKeeperRoot() + "/" + type + "/" + tid, new byte[] {},
          NodeExistsPolicy.OVERWRITE);
      writer.putPersistentData(context.getZooKeeperRoot() + "/" + type + "/" + tid + "-running",
          new byte[] {}, NodeExistsPolicy.OVERWRITE);
    }

    public static void stop(ServerContext context, String type, long tid)
        throws KeeperException, InterruptedException {
      IZooReaderWriter writer = context.getZooReaderWriter();
      writer.recursiveDelete(context.getZooKeeperRoot() + "/" + type + "/" + tid,
          NodeMissingPolicy.SKIP);
    }

    public static void cleanup(ServerContext context, String type, long tid)
        throws KeeperException, InterruptedException {
      IZooReaderWriter writer = context.getZooReaderWriter();
      writer.recursiveDelete(context.getZooKeeperRoot() + "/" + type + "/" + tid,
          NodeMissingPolicy.SKIP);
      writer.recursiveDelete(context.getZooKeeperRoot() + "/" + type + "/" + tid + "-running",
          NodeMissingPolicy.SKIP);
    }

    public static Set<Long> allTransactionsAlive(ServerContext context, String type)
        throws KeeperException, InterruptedException {
      final IZooReader reader = context.getZooReaderWriter();
      final Set<Long> result = new HashSet<>();
      final String parent = context.getZooKeeperRoot() + "/" + type;
      reader.sync(parent);
      List<String> children = reader.getChildren(parent);
      for (String child : children) {
        if (child.endsWith("-running")) {
          continue;
        }
        result.add(Long.parseLong(child));
      }
      return result;
    }

    @Override
    public boolean transactionComplete(String type, long tid) throws Exception {
      String path = context.getZooKeeperRoot() + "/" + type + "/" + tid + "-running";
      rdr.sync(path);
      return !rdr.exists(path);
    }
  }

  public TransactionWatcher(ServerContext context) {
    super(new ZooArbitrator(context));
  }
}
