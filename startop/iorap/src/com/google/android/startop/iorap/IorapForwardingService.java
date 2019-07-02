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
import android.os.IBinder.DeathRecipient;
import android.os.Handler;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.IoThread;
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

    public static final String TAG = "IorapForwardingService";
    /** $> adb shell 'setprop log.tag.IorapdForwardingService VERBOSE' */
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    /** $> adb shell 'setprop ro.iorapd.enable true' */
    private static boolean IS_ENABLED = SystemProperties.getBoolean("ro.iorapd.enable", true);
    /** $> adb shell 'setprop iorapd.forwarding_service.wtf_crash true' */
    private static boolean WTF_CRASH = SystemProperties.getBoolean(
            "iorapd.forwarding_service.wtf_crash", false);

    private IIorap mIorapRemote;
    private final Object mLock = new Object();
    /** Handle onBinderDeath by periodically trying to reconnect. */
    private final Handler mHandler =
            new BinderConnectionHandler(IoThread.getHandler().getLooper());

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
        IIorap iorap;
        try {
            iorap = IIorap.Stub.asInterface(ServiceManager.getServiceOrThrow("iorapd"));
        } catch (ServiceManager.ServiceNotFoundException e) {
            handleRemoteError(e);
            return null;
        }

        try {
            iorap.asBinder().linkToDeath(provideDeathRecipient(), /*flags*/0);
        } catch (RemoteException e) {
            handleRemoteError(e);
            return null;
        }

        return iorap;
    }

    @VisibleForTesting
    protected DeathRecipient provideDeathRecipient() {
        return new DeathRecipient() {
            @Override
            public void binderDied() {
                Log.w(TAG, "iorapd has died");
                retryConnectToRemoteAndConfigure(/*attempts*/0);
            }
        };
    }

    @VisibleForTesting
    protected boolean isIorapEnabled() {
        // Same as the property in iorapd.rc -- disabling this will mean the 'iorapd' binder process
        // never comes up, so all binder connections will fail indefinitely.
        return IS_ENABLED;
    }

    //</editor-fold>

    @Override
    public void onStart() {
        if (DEBUG) {
            Log.v(TAG, "onStart");
        }

        retryConnectToRemoteAndConfigure(/*attempts*/0);
    }

    private class BinderConnectionHandler extends Handler {
        public BinderConnectionHandler(android.os.Looper looper) {
            super(looper);
        }

        public static final int MESSAGE_BINDER_CONNECT = 0;

        private int mAttempts = 0;

        @Override
        public void handleMessage(android.os.Message message) {
           switch (message.what) {
               case MESSAGE_BINDER_CONNECT:
                   if (!retryConnectToRemoteAndConfigure(mAttempts)) {
                       mAttempts++;
                   } else {
                       mAttempts = 0;
                   }
                   break;
               default:
                   throw new AssertionError("Unknown message: " + message.toString());
           }
        }
    }

    /**
     * Handle iorapd shutdowns and crashes, by attempting to reconnect
     * until the service is reached again.
     *
     * <p>The first connection attempt is synchronous,
     * subsequent attempts are done by posting delayed tasks to the IoThread.</p>
     *
     * @return true if connection succeeded now, or false if it failed now [and needs to requeue].
     */
    private boolean retryConnectToRemoteAndConfigure(int attempts) {
        final int sleepTime = 1000;  // ms

        if (DEBUG) {
            Log.v(TAG, "retryConnectToRemoteAndConfigure - attempt #" + attempts);
        }

        if (connectToRemoteAndConfigure()) {
            return true;
        }

        // Either 'iorapd' is stuck in a crash loop (ouch!!) or we manually
        // called 'adb shell stop iorapd' , which means this would loop until it comes back
        // up.
        //
        // TODO: it would be good to get nodified of 'adb shell stop iorapd' to avoid
        // printing this warning.
        Log.w(TAG, "Failed to connect to iorapd, is it down? Delay for " + sleepTime);

        // Use a handler instead of Thread#sleep to avoid backing up the binder thread
        // when this is called from the death recipient callback.
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(BinderConnectionHandler.MESSAGE_BINDER_CONNECT),
                sleepTime);

        return false;

        // Log.e(TAG, "Can't connect to iorapd - giving up after " + attempts + " attempts");
    }

    private boolean connectToRemoteAndConfigure() {
        synchronized (mLock) {
            // Synchronize against any concurrent calls to this via the DeathRecipient.
            return connectToRemoteAndConfigureLocked();
        }
    }

    private boolean connectToRemoteAndConfigureLocked() {
        if (!isIorapEnabled()) {
            if (DEBUG) {
                Log.v(TAG, "connectToRemoteAndConfigure - iorapd is disabled, skip rest of work");
            }
            // When we see that iorapd is disabled (when system server comes up),
            // it stays disabled permanently until the next system server reset.

            // TODO: consider listening to property changes as a callback, then we can
            // be more dynamic about handling enable/disable.
            return true;
        }

        // Connect to the native binder service.
        mIorapRemote = provideIorapRemote();
        if (mIorapRemote == null) {
            Log.e(TAG, "connectToRemoteAndConfigure - null iorap remote. check for Log.wtf?");
            return false;
        }
        invokeRemote( () -> mIorapRemote.setTaskListener(new RemoteTaskListener()) );
        registerInProcessListenersLocked();

        return true;
    }

    private final AppLaunchObserver mAppLaunchObserver = new AppLaunchObserver();
    private boolean mRegisteredListeners = false;

    private void registerInProcessListenersLocked() {
        if (mRegisteredListeners) {
            // Listeners are registered only once (idempotent operation).
            //
            // Today listeners are tolerant of the remote side going away
            // by handling remote errors.
            //
            // We could try to 'unregister' the listener when we get a binder disconnect,
            // but we'd still have to handle the case of encountering synchronous errors so
            // it really wouldn't be a win (other than having less log spew).
            return;
        }

        // Listen to App Launch Sequence events from ActivityTaskManager,
        // and forward them to the native binder service.
        ActivityMetricsLaunchObserverRegistry launchObserverRegistry =
                provideLaunchObserverRegistry();
        launchObserverRegistry.registerLaunchObserver(mAppLaunchObserver);

        mRegisteredListeners = true;
    }

    private class AppLaunchObserver implements ActivityMetricsLaunchObserver {
        // We add a synthetic sequence ID here to make it easier to differentiate new
        // launch sequences on the native side.
        private @AppLaunchEvent.SequenceId long mSequenceId = -1;

        // All callbacks occur on the same background thread. Don't synchronize explicitly.

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
                        new AppLaunchEvent.ActivityLaunchFinished(mSequenceId, activity))
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

    /** Allow passing lambdas to #invokeRemote */
    private interface RemoteRunnable {
        void run() throws RemoteException;
    }

    private static void invokeRemote(RemoteRunnable r) {
       try {
           r.run();
       } catch (RemoteException e) {
           // This could be a logic error (remote side returning error), which we need to fix.
           //
           // This could also be a DeadObjectException in which case its probably just iorapd
           // being manually restarted.
           //
           // Don't make any assumption, since DeadObjectException could also mean iorapd crashed
           // unexpectedly.
           //
           // DeadObjectExceptions are recovered from using DeathRecipient and #linkToDeath.
           handleRemoteError(e);
       }
    }

    private static void handleRemoteError(Throwable t) {
        if (WTF_CRASH) {
            // In development modes, we just want to crash.
            throw new AssertionError("unexpected remote error", t);
        } else {
            // Log to wtf which gets sent to dropbox, and in system_server this does not crash.
            Log.wtf(TAG, t);
        }
    }
}
