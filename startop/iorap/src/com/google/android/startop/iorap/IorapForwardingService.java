/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.startop.iorap;
// TODO: rename to com.android.server.startop.iorap

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityMetricsLaunchObserver;
import com.android.server.wm.ActivityMetricsLaunchObserver.ActivityRecordProto;
import com.android.server.wm.ActivityMetricsLaunchObserver.Temperature;
import com.android.server.wm.ActivityMetricsLaunchObserverRegistry;
import com.android.server.wm.ActivityTaskManagerInternal;

/**
 * System-server-local proxy into the {@code IIorap} native service.
 */
public class IorapForwardingService extends SystemService {

    public static final boolean DEBUG = true; // TODO: read from a getprop?
    public static final String TAG = "IorapForwardingService";

    private IIorap mIorapRemote;

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public IorapForwardingService(Context context) {
       super(context);
    }

    //<editor-fold desc="Providers">
    /*
     * Providers for external dependencies:
     * - These are marked as protected to allow tests to inject different values via mocks.
     */

    @VisibleForTesting
    protected ActivityMetricsLaunchObserverRegistry provideLaunchObserverRegistry() {
        ActivityTaskManagerInternal amtInternal =
                LocalServices.getService(ActivityTaskManagerInternal.class);
        ActivityMetricsLaunchObserverRegistry launchObserverRegistry =
                amtInternal.getLaunchObserverRegistry();
        return launchObserverRegistry;
    }

    @VisibleForTesting
    protected IIorap provideIorapRemote() {
        try {
            return IIorap.Stub.asInterface(ServiceManager.getServiceOrThrow("iorapd"));
        } catch (ServiceManager.ServiceNotFoundException e) {
            // TODO: how do we handle service being missing?
            throw new AssertionError(e);
        }
    }

    //</editor-fold>

    @Override
    public void onStart() {
        if (DEBUG) {
            Log.v(TAG, "onStart");
        }

        // Connect to the native binder service.
        mIorapRemote = provideIorapRemote();
        invokeRemote( () -> mIorapRemote.setTaskListener(new RemoteTaskListener()) );

        // Listen to App Launch Sequence events from ActivityTaskManager,
        // and forward them to the native binder service.
        ActivityMetricsLaunchObserverRegistry launchObserverRegistry =
                provideLaunchObserverRegistry();
        launchObserverRegistry.registerLaunchObserver(new AppLaunchObserver());
    }

    private class AppLaunchObserver implements ActivityMetricsLaunchObserver {
        // We add a synthetic sequence ID here to make it easier to differentiate new
        // launch sequences on the native side.
        private @AppLaunchEvent.SequenceId long mSequenceId = -1;

        @Override
        public void onIntentStarted(@NonNull Intent intent) {
            // #onIntentStarted [is the only transition that] initiates a new launch sequence.
            ++mSequenceId;

            if (DEBUG) {
                Log.v(TAG, String.format("AppLaunchObserver#onIntentStarted(%d, %s)",
                        mSequenceId, intent));
            }

            invokeRemote(() ->
                    mIorapRemote.onAppLaunchEvent(RequestId.nextValueForSequence(),
                        new AppLaunchEvent.IntentStarted(mSequenceId, intent))
            );
        }

        @Override
        public void onIntentFailed() {
            if (DEBUG) {
                Log.v(TAG, String.format("AppLaunchObserver#onIntentFailed(%d)", mSequenceId));
            }

            invokeRemote(() ->
                    mIorapRemote.onAppLaunchEvent(RequestId.nextValueForSequence(),
                        new AppLaunchEvent.IntentFailed(mSequenceId))
            );
        }

        @Override
        public void onActivityLaunched(@NonNull @ActivityRecordProto byte[] activity,
                @Temperature int temperature) {
            if (DEBUG) {
                Log.v(TAG, String.format("AppLaunchObserver#onActivityLaunched(%d, %s, %d)",
                        mSequenceId, activity, temperature));
            }

            invokeRemote(() ->
                    mIorapRemote.onAppLaunchEvent(RequestId.nextValueForSequence(),
                            new AppLaunchEvent.ActivityLaunched(mSequenceId, activity, temperature))
            );
        }

        @Override
        public void onActivityLaunchCancelled(@Nullable @ActivityRecordProto byte[] activity) {
            if (DEBUG) {
                Log.v(TAG, String.format("AppLaunchObserver#onActivityLaunchCancelled(%d, %s)",
                        mSequenceId, activity));
            }

            invokeRemote(() ->
                    mIorapRemote.onAppLaunchEvent(RequestId.nextValueForSequence(),
                            new AppLaunchEvent.ActivityLaunchCancelled(mSequenceId,
                                    activity)));
        }

        @Override
        public void onActivityLaunchFinished(@NonNull @ActivityRecordProto byte[] activity) {
            if (DEBUG) {
                Log.v(TAG, String.format("AppLaunchObserver#onActivityLaunchFinished(%d, %s)",
                        mSequenceId, activity));
            }

            invokeRemote(() ->
                mIorapRemote.onAppLaunchEvent(RequestId.nextValueForSequence(),
                        new AppLaunchEvent.ActivityLaunchCancelled(mSequenceId, activity))
            );
        }
    }

    private class RemoteTaskListener extends ITaskListener.Stub {
        @Override
        public void onProgress(RequestId requestId, TaskResult result) throws RemoteException {
            if (DEBUG) {
                Log.v(TAG,
                        String.format("RemoteTaskListener#onProgress(%s, %s)", requestId, result));
            }

            // TODO: implement rest.
        }

        @Override
        public void onComplete(RequestId requestId, TaskResult result) throws RemoteException {
            if (DEBUG) {
                Log.v(TAG,
                        String.format("RemoteTaskListener#onComplete(%s, %s)", requestId, result));
            }

            // TODO: implement rest.
        }
    }

    private interface RemoteRunnable {
        void run() throws RemoteException;
    }

    private static void invokeRemote(RemoteRunnable r) {
       try {
           r.run();
       } catch (RemoteException e) {
           // TODO: what do we do with exceptions?
           throw new AssertionError("not implemented", e);
       }
    }
}
