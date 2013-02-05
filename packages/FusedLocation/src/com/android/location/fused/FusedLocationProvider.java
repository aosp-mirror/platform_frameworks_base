/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.location.fused;


import java.io.FileDescriptor;
import java.io.PrintWriter;

import com.android.location.provider.LocationProviderBase;
import com.android.location.provider.ProviderPropertiesUnbundled;
import com.android.location.provider.ProviderRequestUnbundled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.os.WorkSource;

public class FusedLocationProvider extends LocationProviderBase implements FusionEngine.Callback {
    private static final String TAG = "FusedLocationProvider";

    private static ProviderPropertiesUnbundled PROPERTIES = ProviderPropertiesUnbundled.create(
            false, false, false, false, true, true, true, Criteria.POWER_LOW,
            Criteria.ACCURACY_FINE);

    private static final int MSG_ENABLE = 1;
    private static final int MSG_DISABLE = 2;
    private static final int MSG_SET_REQUEST = 3;

    private final Context mContext;
    private final FusionEngine mEngine;

    private static class RequestWrapper {
        public ProviderRequestUnbundled request;
        public WorkSource source;
        public RequestWrapper(ProviderRequestUnbundled request, WorkSource source) {
            this.request = request;
            this.source = source;
        }
    }

    public FusedLocationProvider(Context context) {
        super(TAG, PROPERTIES);
        mContext = context;
        mEngine = new FusionEngine(context, Looper.myLooper());

        // listen for user change
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    mEngine.switchUser();
                }
            }
        }, UserHandle.ALL, intentFilter, null, mHandler);
    }

    /**
     * For serializing requests to mEngine.
     */
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_ENABLE:
                    mEngine.init(FusedLocationProvider.this);
                    break;
                case MSG_DISABLE:
                    mEngine.deinit();
                    break;
                case MSG_SET_REQUEST:
                    {
                        RequestWrapper wrapper = (RequestWrapper) msg.obj;
                        mEngine.setRequest(wrapper.request, wrapper.source);
                        break;
                    }
            }
        }
    };

    @Override
    public void onEnable() {
        mHandler.sendEmptyMessage(MSG_ENABLE);
    }

    @Override
    public void onDisable() {
        mHandler.sendEmptyMessage(MSG_DISABLE);
    }

    @Override
    public void onSetRequest(ProviderRequestUnbundled request, WorkSource source) {
        mHandler.obtainMessage(MSG_SET_REQUEST, new RequestWrapper(request, source)).sendToTarget();
    }

    @Override
    public void onDump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // perform synchronously
        mEngine.dump(fd, pw, args);
    }

    @Override
    public int onGetStatus(Bundle extras) {
        return LocationProvider.AVAILABLE;
    }

    @Override
    public long onGetStatusUpdateTime() {
        return 0;
    }
}
