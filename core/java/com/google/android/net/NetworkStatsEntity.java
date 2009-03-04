/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.net;

import android.os.NetStat;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.EventLog;

import org.apache.http.HttpEntity;
import org.apache.http.entity.HttpEntityWrapper;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;


public class NetworkStatsEntity extends HttpEntityWrapper {

    private static final int HTTP_STATS_EVENT = 52001;

    private class NetworkStatsInputStream extends FilterInputStream {

        public NetworkStatsInputStream(InputStream wrapped) {
            super(wrapped);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                long processingTime = SystemClock.elapsedRealtime() - mProcessingStartTime;
                long tx = NetStat.getUidTxBytes(mUid);
                long rx = NetStat.getUidRxBytes(mUid);

                EventLog.writeEvent(HTTP_STATS_EVENT, mUa, mResponseLatency, processingTime,
                        tx - mStartTx, rx - mStartRx);
            }
        }
    }

    private final String mUa;
    private final int mUid;
    private final long mStartTx;
    private final long mStartRx;
    private final long mResponseLatency;
    private final long mProcessingStartTime;

    public NetworkStatsEntity(HttpEntity orig, String ua,
            int uid, long startTx, long startRx, long responseLatency,
            long processingStartTime) {
        super(orig);
        this.mUa = ua;
        this.mUid = uid;
        this.mStartTx = startTx;
        this.mStartRx = startRx;
        this.mResponseLatency = responseLatency;
        this.mProcessingStartTime = processingStartTime;
    }

    public static boolean shouldLogNetworkStats() {
      return "1".equals(SystemProperties.get("googlehttpclient.logstats"));
    }

    @Override
    public InputStream getContent() throws IOException {
        InputStream orig = super.getContent();
        return new NetworkStatsInputStream(orig);
    }
}
