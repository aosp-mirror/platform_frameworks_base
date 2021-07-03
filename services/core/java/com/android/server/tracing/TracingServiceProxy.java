/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.tracing;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.UserHandle;
import android.tracing.ITracingServiceProxy;
import android.util.Log;

import com.android.server.SystemService;

/**
 * TracingServiceProxy is the system_server intermediary between the Perfetto tracing daemon and the
 * system tracing app Traceur.
 *
 * Access to this service is restricted via SELinux. Normal apps do not have access.
 *
 * @hide
 */
public class TracingServiceProxy extends SystemService {
    private static final String TAG = "TracingServiceProxy";

    public static final String TRACING_SERVICE_PROXY_BINDER_NAME = "tracing.proxy";

    private static final String TRACING_APP_PACKAGE_NAME = "com.android.traceur";
    private static final String TRACING_APP_ACTIVITY = "com.android.traceur.StopTraceService";

    // Keep this in sync with the definitions in TraceService
    private static final String INTENT_ACTION_NOTIFY_SESSION_STOPPED =
            "com.android.traceur.NOTIFY_SESSION_STOPPED";
    private static final String INTENT_ACTION_NOTIFY_SESSION_STOLEN =
            "com.android.traceur.NOTIFY_SESSION_STOLEN";

    private final Context mContext;
    private final PackageManager mPackageManager;

    private final ITracingServiceProxy.Stub mTracingServiceProxy = new ITracingServiceProxy.Stub() {
        /**
          * Notifies system tracing app that a tracing session has ended. If a session is repurposed
          * for use in a bugreport, sessionStolen can be set to indicate that tracing has ended but
          * there is no buffer available to dump.
          */
        @Override
        public void notifyTraceSessionEnded(boolean sessionStolen) {
            notifyTraceur(sessionStolen);
        }
    };

    public TracingServiceProxy(Context context) {
        super(context);
        mContext = context;
        mPackageManager = context.getPackageManager();
    }

    @Override
    public void onStart() {
        publishBinderService(TRACING_SERVICE_PROXY_BINDER_NAME, mTracingServiceProxy);
    }

    private void notifyTraceur(boolean sessionStolen) {
        final Intent intent = new Intent();

        try {
            // Validate that Traceur is a system app.
            PackageInfo info = mPackageManager.getPackageInfo(TRACING_APP_PACKAGE_NAME,
                    PackageManager.MATCH_SYSTEM_ONLY);

            intent.setClassName(info.packageName, TRACING_APP_ACTIVITY);
            if (sessionStolen) {
                intent.setAction(INTENT_ACTION_NOTIFY_SESSION_STOLEN);
            } else {
                intent.setAction(INTENT_ACTION_NOTIFY_SESSION_STOPPED);
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                mContext.startForegroundServiceAsUser(intent, UserHandle.SYSTEM);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed to notifyTraceSessionEnded", e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }

        } catch (NameNotFoundException e) {
            Log.e(TAG, "Failed to locate Traceur", e);
        }
    }
}
