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
package org.apache.accumulo.server;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.classloader.ClassLoaderUtil;
import org.apache.accumulo.core.cli.ConfigOpts;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.metrics.MetricsProducer;
import org.apache.accumulo.core.trace.TraceUtil;
import org.apache.accumulo.core.util.Timer;
import org.apache.accumulo.core.util.threads.ThreadPools;
import org.apache.accumulo.server.mem.LowMemoryDetector;
import org.apache.accumulo.server.metrics.ProcessMetrics;
import org.apache.accumulo.server.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;

public abstract class AbstractServer implements AutoCloseable, MetricsProducer, Runnable {

  private final ServerContext context;
  protected final String applicationName;
  private final String hostname;
  private final ProcessMetrics processMetrics;
  protected final long idleReportingPeriodMillis;
  private volatile Timer idlePeriodTimer = null;

  protected AbstractServer(String appName, ConfigOpts opts, String[] args) {
    this.applicationName = appName;
    opts.parseArgs(appName, args);
    var siteConfig = opts.getSiteConfiguration();
    this.hostname = siteConfig.get(Property.GENERAL_PROCESS_BIND_ADDRESS);
    SecurityUtil.serverLogin(siteConfig);
    context = new ServerContext(siteConfig);
    Logger log = LoggerFactory.getLogger(getClass());
    log.info("Version " + Constants.VERSION);
    log.info("Instance " + context.getInstanceID());
    context.init(appName);
    ClassLoaderUtil.initContextFactory(context.getConfiguration());
    TraceUtil.initializeTracer(context.getConfiguration());
    if (context.getSaslParams() != null) {
      // Server-side "client" check to make sure we're logged in as a user we expect to be
      context.enforceKerberosLogin();
    }
    final LowMemoryDetector lmd = context.getLowMemoryDetector();
    ScheduledFuture<?> future = context.getScheduledExecutor().scheduleWithFixedDelay(
        () -> lmd.logGCInfo(context.getConfiguration()), 0,
        lmd.getIntervalMillis(context.getConfiguration()), MILLISECONDS);
    ThreadPools.watchNonCriticalScheduledTask(future);
    processMetrics = new ProcessMetrics(context);
    idleReportingPeriodMillis =
        context.getConfiguration().getTimeInMillis(Property.GENERAL_IDLE_PROCESS_INTERVAL);
  }

  /**
   * Updates the idle status of the server to set the idle process metric. The server must be idle
   * for multiple calls over a specified period for the metric to reflect the idle state. If the
   * server is busy or the idle period hasn't started, it resets the idle tracking.
   *
   * @param isIdle whether the server is idle
   */
  protected void updateIdleStatus(boolean isIdle) {
    boolean shouldResetIdlePeriod = !isIdle || idleReportingPeriodMillis == 0;
    boolean hasIdlePeriodStarted = idlePeriodTimer != null;
    boolean hasExceededIdlePeriod =
        hasIdlePeriodStarted && idlePeriodTimer.hasElapsed(idleReportingPeriodMillis, MILLISECONDS);

    if (shouldResetIdlePeriod) {
      // Reset idle period and set idle metric to false
      idlePeriodTimer = null;
      processMetrics.setIdleValue(false);
    } else if (!hasIdlePeriodStarted) {
      // Start tracking idle period
      idlePeriodTimer = Timer.startNew();
    } else if (hasExceededIdlePeriod) {
      // Set idle metric to true and reset the start of the idle period
      processMetrics.setIdleValue(true);
      idlePeriodTimer = null;
    }
  }

  /**
   * Run this server in a main thread
   */
  public void runServer() throws Exception {
    final AtomicReference<Throwable> err = new AtomicReference<>();
    Thread service = new Thread(TraceUtil.wrap(this), applicationName);
    service.setUncaughtExceptionHandler((thread, exception) -> err.set(exception));
    service.start();
    service.join();
    Throwable thrown = err.get();
    if (thrown != null) {
      if (thrown instanceof Error) {
        throw (Error) thrown;
      }
      if (thrown instanceof Exception) {
        throw (Exception) thrown;
      }
      throw new IllegalStateException("Weird throwable type thrown", thrown);
    }
  }

  /**
   * Called
   */
  @Override
  public void registerMetrics(MeterRegistry registry) {
    // makes mocking subclasses easier
    if (processMetrics != null) {
      processMetrics.registerMetrics(registry);
    }
  }

  public String getHostname() {
    return hostname;
  }

  public ServerContext getContext() {
    return context;
  }

  public AccumuloConfiguration getConfiguration() {
    return getContext().getConfiguration();
  }

  public String getApplicationName() {
    return applicationName;
  }

  @Override
  public void close() {}

}
