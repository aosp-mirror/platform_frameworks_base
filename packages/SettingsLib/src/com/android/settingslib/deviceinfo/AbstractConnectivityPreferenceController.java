/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.deviceinfo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;

import com.android.internal.util.ArrayUtils;
import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.core.lifecycle.Lifecycle;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;

import java.lang.ref.WeakReference;

/**
 * Base class for preference controllers which listen to connectivity broadcasts
 */
public abstract class AbstractConnectivityPreferenceController
        extends AbstractPreferenceController implements LifecycleObserver, OnStart, OnStop {

    private final BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ArrayUtils.contains(getConnectivityIntents(), action)) {
                getHandler().sendEmptyMessage(EVENT_UPDATE_CONNECTIVITY);
            }
        }
    };

    private static final int EVENT_UPDATE_CONNECTIVITY = 600;

    private Handler mHandler;

    public AbstractConnectivityPreferenceController(Context context, Lifecycle lifecycle) {
        super(context);
        if (lifecycle != null) {
            lifecycle.addObserver(this);
        }
    }

    @Override
    public void onStop() {
        mContext.unregisterReceiver(mConnectivityReceiver);
    }

    @Override
    public void onStart() {
        final IntentFilter connectivityIntentFilter = new IntentFilter();
        final String[] intents = getConnectivityIntents();
        for (String intent : intents) {
            connectivityIntentFilter.addAction(intent);
        }

        mContext.registerReceiver(mConnectivityReceiver, connectivityIntentFilter,
                android.Manifest.permission.CHANGE_NETWORK_STATE, null);
    }

    protected abstract String[] getConnectivityIntents();

    protected abstract void updateConnectivity();

    private Handler getHandler() {
        if (mHandler == null) {
            mHandler = new ConnectivityEventHandler(this);
        }
        return mHandler;
    }

    private static class ConnectivityEventHandler extends Handler {
        private WeakReference<AbstractConnectivityPreferenceController> mPreferenceController;

        public ConnectivityEventHandler(AbstractConnectivityPreferenceController activity) {
            mPreferenceController = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            AbstractConnectivityPreferenceController preferenceController
                    = mPreferenceController.get();
            if (preferenceController == null) {
                return;
            }

            switch (msg.what) {
                case EVENT_UPDATE_CONNECTIVITY:
                    preferenceController.updateConnectivity();
                    break;
                default:
                    throw new IllegalStateException("Unknown message " + msg.what);
            }
        }
    }
}
