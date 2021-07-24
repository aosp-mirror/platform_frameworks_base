/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.communal.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.communal.CommunalSourceMonitor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.communal.ICommunalSource;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * The {@link CommunalSourcePrimer} is responsible for priming SystemUI with a pre-configured
 * Communal source. The SystemUI service binds to the component to retrieve the
 * {@link com.android.systemui.communal.CommunalSource}. {@link CommunalSourcePrimer} has no effect
 * if there is no pre-defined value.
 */
@SysUISingleton
public class CommunalSourcePrimer extends SystemUI {
    private static final String TAG = "CommunalSourcePrimer";
    private static final boolean DEBUG = false;
    private final Context mContext;
    private final Executor mMainExecutor;
    private final CommunalSourceMonitor mMonitor;
    private static final String ACTION_COMMUNAL_SOURCE = "android.intent.action.COMMUNAL_SOURCE";

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            final ICommunalSource source = ICommunalSource.Stub.asInterface(service);
            if (DEBUG) {
                Log.d(TAG, "onServiceConnected. source;" + source);
            }

            mMonitor.setSource(
                    source != null ? new CommunalSourceImpl(mMainExecutor, source) : null);

        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
        }
    };

    @Inject
    public CommunalSourcePrimer(Context context, @Main Executor mainExecutor,
            CommunalSourceMonitor monitor) {
        super(context);
        mContext = context;
        mMainExecutor = mainExecutor;
        mMonitor = monitor;
    }

    @Override
    public void start() {
        if (DEBUG) {
            Log.d(TAG, "start");
        }
    }

    @Override
    protected void onBootCompleted() {
        super.onBootCompleted();
        final String serviceComponent = mContext.getString(R.string.config_communalSourceComponent);

        if (DEBUG) {
            Log.d(TAG, "onBootCompleted. communal source component:" + serviceComponent);
        }

        if (serviceComponent == null || serviceComponent.isEmpty()) {
            return;
        }

        final Intent intent = new Intent();
        intent.setAction(ACTION_COMMUNAL_SOURCE);
        intent.setComponent(ComponentName.unflattenFromString(serviceComponent));

        mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }
}
