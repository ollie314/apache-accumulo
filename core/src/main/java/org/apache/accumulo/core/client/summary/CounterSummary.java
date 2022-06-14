/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.core.client.summary;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

/**
 * This a convenience class for interpreting summary data generated by implementations of
 * {@link CountingSummarizer}
 *
 * @since 2.0.0
 */
public class CounterSummary {
  private Map<String,Long> stats;

  private static final Logger log = LoggerFactory.getLogger(CounterSummary.class);

  /**
   * This method will call {@link #CounterSummary(Summary, boolean)} with true.
   */
  public CounterSummary(Summary summary) {
    this(summary, true);
  }

  /**
   * @param summary
   *          a summary
   * @param checkType
   *          If true will try to ensure the classname from
   *          {@link Summary#getSummarizerConfiguration()} is an instance of
   *          {@link CountingSummarizer}. However this check can only succeed if the class is on the
   *          classpath. For cases where the summary data needs to be used and the class is not on
   *          the classpath, set this to false.
   */
  public CounterSummary(Summary summary, boolean checkType) {
    if (checkType) {
      String className = summary.getSummarizerConfiguration().getClassName();
      try {
        final var aClass =
            getClass().getClassLoader().loadClass(className).asSubclass(CountingSummarizer.class);
        log.trace("{} loaded in constructor", aClass.getName());
      } catch (ClassCastException e) {
        throw new IllegalArgumentException(
            className + " is not an instance of " + CountingSummarizer.class.getSimpleName(), e);
      } catch (ClassNotFoundException e) {
        throw new IllegalArgumentException(
            "Unable to check summary was produced by a " + CountingSummarizer.class.getSimpleName(),
            e);
      }
    }
    this.stats = summary.getStatistics();
  }

  @VisibleForTesting
  CounterSummary(Map<String,Long> stats) {
    this.stats = stats;
  }

  /**
   * @return statistic for {@link CountingSummarizer#SEEN_STAT}
   */
  public long getSeen() {
    return stats.getOrDefault(CountingSummarizer.SEEN_STAT, 0L);
  }

  /**
   * @return statistic for {@link CountingSummarizer#EMITTED_STAT}
   */
  public long getEmitted() {
    return stats.getOrDefault(CountingSummarizer.EMITTED_STAT, 0L);
  }

  /**
   * @return the sum of {@link #getTooLong()} and {@link #getTooLong()}
   */
  public long getIgnored() {
    return getTooLong() + getTooMany();
  }

  /**
   * @return statistic for {@link CountingSummarizer#TOO_LONG_STAT}
   */
  public long getTooLong() {
    return stats.getOrDefault(CountingSummarizer.TOO_LONG_STAT, 0L);
  }

  /**
   * @return statistic for {@link CountingSummarizer#TOO_MANY_STAT}
   */
  public long getTooMany() {
    return stats.getOrDefault(CountingSummarizer.TOO_MANY_STAT, 0L);
  }

  /**
   * @return statistic for {@link CountingSummarizer#DELETES_IGNORED_STAT}
   */
  public long getDeletesIgnored() {
    return stats.getOrDefault(CountingSummarizer.DELETES_IGNORED_STAT, 0L);
  }

  /**
   * @return All statistics with a prefix of {@link CountingSummarizer#COUNTER_STAT_PREFIX} with the
   *         prefix stripped off.
   */
  public Map<String,Long> getCounters() {
    HashMap<String,Long> ret = new HashMap<>();
    for (Entry<String,Long> entry : stats.entrySet()) {
      if (entry.getKey().startsWith(CountingSummarizer.COUNTER_STAT_PREFIX)) {
        ret.put(entry.getKey().substring(CountingSummarizer.COUNTER_STAT_PREFIX.length()),
            entry.getValue());
      }
    }
    return ret;
  }
}
