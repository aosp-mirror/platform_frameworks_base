/**
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.profcollect;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder.DeathRecipient;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;

import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityMetricsLaunchObserver;
import com.android.server.wm.ActivityMetricsLaunchObserverRegistry;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.concurrent.ThreadLocalRandom;

/**
 * System-server-local proxy into the {@code IProfcollectd} native service.
 */
public final class ProfcollectForwardingService extends SystemService {
    public static final String LOG_TAG = "ProfcollectForwardingService";

    private IProfCollectd mIProfcollect;
    private ProfcollectForwardingService mSelfService;
    private final Handler mHandler = new ProfcollectdHandler(IoThread.getHandler().getLooper());

    public ProfcollectForwardingService(Context context) {
        super(context);

        if (mSelfService != null) {
            throw new AssertionError("only one service instance allowed");
        }
        mSelfService = this;
    }

    @Override
    public void onStart() {
        Log.i(LOG_TAG, "Profcollect forwarding service start");
        connectNativeService();
        if (mIProfcollect == null) {
            return;
        }
        if (serviceHasSupportedTraceProvider()) {
            registerObservers();
        }
    }

    private boolean serviceHasSupportedTraceProvider() {
        if (mIProfcollect == null) {
            return false;
        }
        try {
            return !mIProfcollect.GetSupportedProvider().isEmpty();
        } catch (RemoteException e) {
            Log.e(LOG_TAG, e.getMessage());
            return false;
        }
    }

    private boolean tryConnectNativeService() {
        if (connectNativeService()) {
            return true;
        }
        // Cannot connect to the native service at this time, retry after a short delay.
        mHandler.sendEmptyMessageDelayed(ProfcollectdHandler.MESSAGE_BINDER_CONNECT, 5000);
        return false;
    }

    private boolean connectNativeService() {
        try {
            IProfCollectd profcollectd =
                    IProfCollectd.Stub.asInterface(
                            ServiceManager.getServiceOrThrow("profcollectd"));
            profcollectd.asBinder().linkToDeath(new ProfcollectdDeathRecipient(), /*flags*/0);
            mIProfcollect = profcollectd;
            return true;
        } catch (ServiceManager.ServiceNotFoundException | RemoteException e) {
            Log.w(LOG_TAG, "Failed to connect profcollectd binder service.");
            return false;
        }
    }

    private class ProfcollectdHandler extends Handler {
        public ProfcollectdHandler(Looper looper) {
            super(looper);
        }

        public static final int MESSAGE_BINDER_CONNECT = 0;

        @Override
        public void handleMessage(android.os.Message message) {
            switch (message.what) {
                case MESSAGE_BINDER_CONNECT:
                    connectNativeService();
                    break;
                default:
                    throw new AssertionError("Unknown message: " + message.toString());
            }
        }
    }

    private class ProfcollectdDeathRecipient implements DeathRecipient {
        @Override
        public void binderDied() {
            Log.w(LOG_TAG, "profcollectd has died");

            mIProfcollect = null;
            tryConnectNativeService();
        }
    }

    // Event observers
    private void registerObservers() {
        registerAppLaunchObserver();
    }

    private final AppLaunchObserver mAppLaunchObserver = new AppLaunchObserver();
    private void registerAppLaunchObserver() {
        ActivityTaskManagerInternal atmInternal =
                LocalServices.getService(ActivityTaskManagerInternal.class);
        ActivityMetricsLaunchObserverRegistry launchObserverRegistry =
                atmInternal.getLaunchObserverRegistry();
        launchObserverRegistry.registerLaunchObserver(mAppLaunchObserver);
    }

    private void traceOnAppStart(String packageName) {
        if (mIProfcollect == null) {
            return;
        }

        // Sample for a fraction of app launches.
        int traceFrequency = SystemProperties.getInt("profcollectd.applaunch_trace_freq", 2);
        int randomNum = ThreadLocalRandom.current().nextInt(100);
        if (randomNum < traceFrequency) {
            try {
                Log.i(LOG_TAG, "Tracing on app launch event: " + packageName);
                mIProfcollect.TraceOnce("applaunch");
            } catch (RemoteException e) {
                Log.e(LOG_TAG, e.getMessage());
            }
        }
    }

    private class AppLaunchObserver implements ActivityMetricsLaunchObserver {
        @Override
        public void onIntentStarted(Intent intent, long timestampNanos) {
            traceOnAppStart(intent.getPackage());
        }

        @Override
        public void onIntentFailed() {
            // Ignored
        }

        @Override
        public void onActivityLaunched(byte[] activity, int temperature) {
            // Ignored
        }

        @Override
        public void onActivityLaunchCancelled(byte[] abortingActivity) {
            // Ignored
        }

        @Override
        public void onActivityLaunchFinished(byte[] finalActivity, long timestampNanos) {
            // Ignored
        }

        @Override
        public void onReportFullyDrawn(byte[] activity, long timestampNanos) {
            // Ignored
        }
    }
}
