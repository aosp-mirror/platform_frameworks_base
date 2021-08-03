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

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.IBinder;
import android.os.PatternMatcher;
import android.util.Log;

import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.communal.CommunalSourceMonitor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.communal.ICommunalSource;
import com.android.systemui.util.concurrency.DelayableExecutor;

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
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String ACTION_COMMUNAL_SOURCE = "android.intent.action.COMMUNAL_SOURCE";

    private final Context mContext;
    private final DelayableExecutor mMainExecutor;
    private final CommunalSourceMonitor mMonitor;
    private final CommunalSourceImpl.Factory mSourceFactory;
    private final ComponentName mComponentName;
    private final int mBaseReconnectDelayMs;
    private final int mMaxReconnectAttempts;

    private int mReconnectAttempts = 0;
    private Runnable mCurrentReconnectCancelable;

    private final Runnable mConnectRunnable = new Runnable() {
        @Override
        public void run() {
            mCurrentReconnectCancelable = null;
            bindToService();
        }
    };


    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.d(TAG, "package added receiver - onReceive");
            }

            initiateConnectionAttempt();
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            final ICommunalSource source = ICommunalSource.Stub.asInterface(service);
            if (DEBUG) {
                Log.d(TAG, "onServiceConnected. source:" + source);
            }

            if (source == null) {
                if (DEBUG) {
                    Log.d(TAG, "onServiceConnected. invalid source");
                    // Since the service could just repeatedly return null, the primer chooses
                    // to schedule rather than initiate a new connection attempt sequence.
                    scheduleConnectionAttempt();
                }
                return;
            }

            mMonitor.setSource(mSourceFactory.create(source));
        }

        @Override
        public void onBindingDied(ComponentName name) {
            if (DEBUG) {
                Log.d(TAG, "onBindingDied. lost communal source. initiating reconnect");
            }

            initiateConnectionAttempt();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            if (DEBUG) {
                Log.d(TAG,
                        "onServiceDisconnected. lost communal source. initiating reconnect");
            }

            initiateConnectionAttempt();
        }
    };

    @Inject
    public CommunalSourcePrimer(Context context, @Main Resources resources,
            DelayableExecutor mainExecutor,
            CommunalSourceMonitor monitor,
            CommunalSourceImpl.Factory sourceFactory) {
        super(context);
        mContext = context;
        mMainExecutor = mainExecutor;
        mMonitor = monitor;
        mSourceFactory = sourceFactory;
        mMaxReconnectAttempts = resources.getInteger(
                R.integer.config_communalSourceMaxReconnectAttempts);
        mBaseReconnectDelayMs = resources.getInteger(
                R.integer.config_communalSourceReconnectBaseDelay);

        final String component = resources.getString(R.string.config_communalSourceComponent);
        mComponentName = component != null && !component.isEmpty()
                ? ComponentName.unflattenFromString(component) : null;
    }

    @Override
    public void start() {
    }

    private void initiateConnectionAttempt() {
        // Reset attempts
        mReconnectAttempts = 0;
        mMonitor.setSource(null);

        // The first attempt is always a direct invocation rather than delayed.
        bindToService();
    }

    private void registerPackageListening() {
        if (mComponentName == null) {
            return;
        }

        final IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(mComponentName.getPackageName(),
                PatternMatcher.PATTERN_LITERAL);
        // Note that we directly register the receiver here as data schemes are not supported by
        // BroadcastDispatcher.
        mContext.registerReceiver(mReceiver, filter);
    }

    private void scheduleConnectionAttempt() {
        // always clear cancelable if present.
        if (mCurrentReconnectCancelable != null) {
            mCurrentReconnectCancelable.run();
            mCurrentReconnectCancelable = null;
        }

        if (mReconnectAttempts >= mMaxReconnectAttempts) {
            if (DEBUG) {
                Log.d(TAG, "exceeded max connection attempts.");
            }
            return;
        }

        final long reconnectDelayMs =
                (long) Math.scalb(mBaseReconnectDelayMs, mReconnectAttempts);

        if (DEBUG) {
            Log.d(TAG,
                    "scheduling connection attempt in " + reconnectDelayMs + "milliseconds");
        }

        mCurrentReconnectCancelable = mMainExecutor.executeDelayed(mConnectRunnable,
                reconnectDelayMs);

        mReconnectAttempts++;
    }

    @Override
    protected void onBootCompleted() {
        super.onBootCompleted();

        if (DEBUG) {
            Log.d(TAG, "onBootCompleted. communal source component:" + mComponentName);
        }

        registerPackageListening();
        initiateConnectionAttempt();
    }

    private void bindToService() {
        if (mComponentName == null) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "attempting to bind to communal source");
        }

        final Intent intent = new Intent();
        intent.setAction(ACTION_COMMUNAL_SOURCE);
        intent.setComponent(mComponentName);

        final boolean binding = mContext.bindService(intent, Context.BIND_AUTO_CREATE,
                mMainExecutor, mConnection);

        if (!binding) {
            if (DEBUG) {
                Log.d(TAG, "bindService failed, rescheduling");
            }

            scheduleConnectionAttempt();
        }
    }
}
