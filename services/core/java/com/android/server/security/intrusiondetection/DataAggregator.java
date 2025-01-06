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

package com.android.server.security.intrusiondetection;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.security.intrusiondetection.IntrusionDetectionEvent;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class DataAggregator {
    private static final String TAG = "IntrusionDetection DataAggregator";
    private static final int MSG_SINGLE_DATA = 0;
    private static final int MSG_BATCH_DATA = 1;
    private static final int MSG_DISABLE = 2;

    private static final int STORED_EVENTS_SIZE_LIMIT = 1024;

    private final IntrusionDetectionService mIntrusionDetectionService;
    private final ArrayList<DataSource> mDataSources;
    private final AtomicBoolean mIsLoggingInitialized = new AtomicBoolean(false);

    private Context mContext;
    private List<IntrusionDetectionEvent> mStoredEvents = new ArrayList<>();
    private ServiceThread mHandlerThread;
    private Handler mHandler;

    public DataAggregator(Context context, IntrusionDetectionService intrusionDetectionService) {
        mIntrusionDetectionService = intrusionDetectionService;
        mContext = context;
        mDataSources = new ArrayList<DataSource>();
    }

    @VisibleForTesting
    void setHandler(Looper looper, ServiceThread serviceThread) {
        mHandlerThread = serviceThread;
        mHandler = new EventHandler(looper, this);
    }

    /** Initialize DataSources */
    private void initialize() {
        mDataSources.add(new SecurityLogSource(mContext, this));
        mDataSources.add(new NetworkLogSource(mContext, this));
    }

    /**
     * Enable the data collection of all DataSources.
     */
    public void enable() {
        if (!mIsLoggingInitialized.get()) {
            initialize();
            mIsLoggingInitialized.set(true);
        }
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
    public void addSingleData(IntrusionDetectionEvent event) {
        mHandler.obtainMessage(MSG_SINGLE_DATA, event).sendToTarget();
    }

    /**
     * DataSource calls it to transmit list of events.
     */
    public void addBatchData(List<IntrusionDetectionEvent> events) {
        mHandler.obtainMessage(MSG_BATCH_DATA, events).sendToTarget();
    }

    /**
     * Disable the data collection of all DataSources.
     */
    public void disable() {
        mHandler.obtainMessage(MSG_DISABLE).sendToTarget();
    }

    private void onNewSingleData(IntrusionDetectionEvent event) {
        if (mStoredEvents.size() < STORED_EVENTS_SIZE_LIMIT) {
            mStoredEvents.add(event);
        } else {
            mIntrusionDetectionService.addNewData(mStoredEvents);
            mStoredEvents = new ArrayList<>();
        }
    }

    private void onNewBatchData(List<IntrusionDetectionEvent> events) {
        mIntrusionDetectionService.addNewData(events);
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
                    mDataAggregator.onNewSingleData((IntrusionDetectionEvent) msg.obj);
                    break;
                case MSG_BATCH_DATA:
                    mDataAggregator.onNewBatchData((List<IntrusionDetectionEvent>) msg.obj);
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
