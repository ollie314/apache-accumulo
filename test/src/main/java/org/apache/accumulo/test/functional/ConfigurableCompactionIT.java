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
package org.apache.accumulo.test.functional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.metadata.MetadataTable;
import org.apache.accumulo.core.metadata.schema.MetadataSchema;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.fate.util.UtilWaitThread;
import org.apache.accumulo.minicluster.impl.MiniAccumuloConfigImpl;
import org.apache.accumulo.tserver.compaction.CompactionPlan;
import org.apache.accumulo.tserver.compaction.CompactionStrategy;
import org.apache.accumulo.tserver.compaction.MajorCompactionRequest;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.junit.Test;

import com.google.common.collect.Iterators;

public class ConfigurableCompactionIT extends ConfigurableMacBase {

  @Override
  public int defaultTimeoutSeconds() {
    return 2 * 60;
  }

  @Override
  public void configure(MiniAccumuloConfigImpl cfg, Configuration hadoopCoreSite) {
    cfg.setSiteConfig(Collections.singletonMap(Property.TSERV_MAJC_DELAY.getKey(), "1s"));
  }

  public static class SimpleCompactionStrategy extends CompactionStrategy {

    @Override
    public void init(Map<String,String> options) {
      String countString = options.get("count");
      if (countString != null)
        count = Integer.parseInt(countString);
    }

    int count = 3;

    @Override
    public boolean shouldCompact(MajorCompactionRequest request) throws IOException {
      return request.getFiles().size() == count;

    }

    @Override
    public CompactionPlan getCompactionPlan(MajorCompactionRequest request) throws IOException {
      CompactionPlan result = new CompactionPlan();
      result.inputFiles.addAll(request.getFiles().keySet());
      return result;
    }

  }

  @Test
  public void test() throws Exception {
    final AccumuloClient c = getClient();
    final String tableName = getUniqueNames(1)[0];
    c.tableOperations().create(tableName);
    c.tableOperations().setProperty(tableName, Property.TABLE_COMPACTION_STRATEGY.getKey(),
        SimpleCompactionStrategy.class.getName());
    runTest(c, tableName, 3);
    c.tableOperations().setProperty(tableName,
        Property.TABLE_COMPACTION_STRATEGY_PREFIX.getKey() + "count", "" + 5);
    runTest(c, tableName, 5);
  }

  @Test
  public void testPerTableClasspath() throws Exception {
    final AccumuloClient c = getClient();
    final String tableName = getUniqueNames(1)[0];
    File destFile = installJar(getCluster().getConfig().getAccumuloDir(),
        "/TestCompactionStrat.jar");
    c.tableOperations().create(tableName);
    c.instanceOperations().setProperty(
        Property.VFS_CONTEXT_CLASSPATH_PROPERTY.getKey() + "context1", destFile.toString());
    c.tableOperations().setProperty(tableName, Property.TABLE_MAJC_RATIO.getKey(), "10");
    c.tableOperations().setProperty(tableName, Property.TABLE_CLASSPATH.getKey(), "context1");
    // EfgCompactionStrat will only compact a tablet w/ end row of 'efg'. No other tablets are
    // compacted.
    c.tableOperations().setProperty(tableName, Property.TABLE_COMPACTION_STRATEGY.getKey(),
        "org.apache.accumulo.test.EfgCompactionStrat");

    c.tableOperations().addSplits(tableName, new TreeSet<>(Arrays.asList(new Text("efg"))));

    for (char ch = 'a'; ch < 'l'; ch++)
      writeFlush(c, tableName, ch + "");

    while (countFiles(c, tableName) != 7) {
      UtilWaitThread.sleep(200);
    }
  }

  private static File installJar(File destDir, String jarFile) throws IOException {
    File destName = new File(destDir, new File(jarFile).getName());
    FileUtils.copyInputStreamToFile(ConfigurableCompactionIT.class.getResourceAsStream(jarFile),
        destName);
    return destName;
  }

  private void writeFlush(AccumuloClient client, String tablename, String row) throws Exception {
    BatchWriter bw = client.createBatchWriter(tablename, new BatchWriterConfig());
    Mutation m = new Mutation(row);
    m.put("", "", "");
    bw.addMutation(m);
    bw.close();
    client.tableOperations().flush(tablename, null, null, true);
  }

  static final Random r = new SecureRandom();

  private void makeFile(AccumuloClient client, String tablename) throws Exception {
    BatchWriter bw = client.createBatchWriter(tablename, new BatchWriterConfig());
    byte[] empty = {};
    byte[] row = new byte[10];
    r.nextBytes(row);
    Mutation m = new Mutation(row, 0, 10);
    m.put(empty, empty, empty);
    bw.addMutation(m);
    bw.flush();
    bw.close();
    client.tableOperations().flush(tablename, null, null, true);
  }

  private void runTest(final AccumuloClient c, final String tableName, final int n)
      throws Exception {
    for (int i = countFiles(c, tableName); i < n - 1; i++)
      makeFile(c, tableName);
    assertEquals(n - 1, countFiles(c, tableName));
    makeFile(c, tableName);
    for (int i = 0; i < 10; i++) {
      int count = countFiles(c, tableName);
      assertTrue(count == 1 || count == n);
      if (count == 1)
        break;
      UtilWaitThread.sleep(1000);
    }
  }

  private int countFiles(AccumuloClient c, String tableName) throws Exception {
    try (Scanner s = c.createScanner(MetadataTable.NAME, Authorizations.EMPTY)) {
      s.fetchColumnFamily(MetadataSchema.TabletsSection.DataFileColumnFamily.NAME);
      return Iterators.size(s.iterator());
    }
  }

}
