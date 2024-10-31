/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.security.forensic;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.security.forensic.ForensicEvent;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;

import java.util.ArrayList;
import java.util.List;

public class DataAggregator {
    private static final String TAG = "Forensic DataAggregator";
    private static final int MSG_SINGLE_DATA = 0;
    private static final int MSG_BATCH_DATA = 1;
    private static final int MSG_DISABLE = 2;

    private static final int STORED_EVENTS_SIZE_LIMIT = 1024;
    private final ForensicService mForensicService;
    private final ArrayList<DataSource> mDataSources;

    private Context mContext;
    private List<ForensicEvent> mStoredEvents = new ArrayList<>();
    private ServiceThread mHandlerThread;
    private Handler mHandler;

    public DataAggregator(Context context, ForensicService forensicService) {
        mForensicService = forensicService;
        mContext = context;
        mDataSources = new ArrayList<DataSource>();
    }

    @VisibleForTesting
    void setHandler(Looper looper, ServiceThread serviceThread) {
        mHandlerThread = serviceThread;
        mHandler = new EventHandler(looper, this);
    }

    /**
     * Initialize DataSources
     * @return Whether the initialization succeeds.
     */
    // TODO: Add the corresponding data sources
    public boolean initialize() {
        SecurityLogSource securityLogSource = new SecurityLogSource(mContext, this);
        mDataSources.add(securityLogSource);
        return true;
    }

    /**
     * Enable the data collection of all DataSources.
     */
    public void enable() {
        mHandlerThread = new ServiceThread(TAG, android.os.Process.THREAD_PRIORITY_BACKGROUND,
                /* allowIo */ false);
        mHandlerThread.start();
        mHandler = new EventHandler(mHandlerThread.getLooper(), this);
        for (DataSource ds : mDataSources) {
            ds.enable();
        }
    }

    /**
     * DataSource calls it to transmit a single event.
     */
    public void addSingleData(ForensicEvent event) {
        mHandler.obtainMessage(MSG_SINGLE_DATA, event).sendToTarget();
    }

    /**
     * DataSource calls it to transmit list of events.
     */
    public void addBatchData(List<ForensicEvent> events) {
        mHandler.obtainMessage(MSG_BATCH_DATA, events).sendToTarget();
    }

    /**
     * Disable the data collection of all DataSources.
     */
    public void disable() {
        mHandler.obtainMessage(MSG_DISABLE).sendToTarget();
        for (DataSource ds : mDataSources) {
            ds.disable();
        }
    }

    private void onNewSingleData(ForensicEvent event) {
        if (mStoredEvents.size() < STORED_EVENTS_SIZE_LIMIT) {
            mStoredEvents.add(event);
        } else {
            mForensicService.addNewData(mStoredEvents);
            mStoredEvents = new ArrayList<>();
        }
    }

    private void onNewBatchData(List<ForensicEvent> events) {
        mForensicService.addNewData(events);
    }

    private void onDisable() {
        for (DataSource ds : mDataSources) {
            ds.disable();
        }
        mHandlerThread.quitSafely();
        mHandlerThread = null;
    }

    private static class EventHandler extends Handler {
        private final DataAggregator mDataAggregator;
        EventHandler(Looper looper, DataAggregator dataAggregator) {
            super(looper);
            mDataAggregator = dataAggregator;
        }
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SINGLE_DATA:
                    mDataAggregator.onNewSingleData((ForensicEvent) msg.obj);
                    break;
                case MSG_BATCH_DATA:
                    mDataAggregator.onNewBatchData((List<ForensicEvent>) msg.obj);
                    break;
                case MSG_DISABLE:
                    mDataAggregator.onDisable();
                    break;
                default:
                    Slog.w(TAG, "Unknown message: " + msg.what);
            }
        }
    }
}
