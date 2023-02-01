/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.companion;

import static android.content.Context.BIND_ALMOST_PERCEPTIBLE;
import static android.content.Context.BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE;
import static android.os.Process.THREAD_PRIORITY_DEFAULT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.companion.ICompanionDeviceService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.android.internal.infra.ServiceConnector;
import com.android.server.ServiceThread;

/**
 * Manages a connection (binding) to an instance of {@link CompanionDeviceService} running in the
 * application process.
 */
@SuppressLint("LongLogTag")
class CompanionDeviceServiceConnector extends ServiceConnector.Impl<ICompanionDeviceService> {
    private static final String TAG = "CompanionDevice_ServiceConnector";
    private static final boolean DEBUG = false;

    /** Listener for changes to the state of the {@link CompanionDeviceServiceConnector}  */
    interface Listener {
        void onBindingDied(@UserIdInt int userId, @NonNull String packageName);
    }

    private final @UserIdInt int mUserId;
    private final @NonNull ComponentName mComponentName;
    // IMPORTANT: this can (and will!) be null (at the moment, CompanionApplicationController only
    // installs a listener to the primary ServiceConnector), hence we should always null-check the
    // reference before calling on it.
    private @Nullable Listener mListener;

    /**
     * Create a CompanionDeviceServiceConnector instance.
     *
     * For self-managed apps, the binding flag will be BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE
     * (oom_score_adj = VISIBLE_APP_ADJ = 100).
     *
     * For non self-managed apps, the binding flag will be BIND_ALMOST_PERCEPTIBLE
     * (oom_score_adj = PERCEPTIBLE_MEDIUM_APP = 225). The target service will be treated
     * as important as a perceptible app (IMPORTANCE_VISIBLE = 200), and will be unbound when
     * the app is removed from task manager.
     *
     * One time permission's importance level to keep session alive is
     * IMPORTANCE_FOREGROUND_SERVICE = 125. In order to kill the one time permission session, the
     * service importance level should be higher than 125.
     */
    static CompanionDeviceServiceConnector newInstance(@NonNull Context context,
            @UserIdInt int userId, @NonNull ComponentName componentName, boolean isSelfManaged) {
        final int bindingFlags = isSelfManaged ? BIND_TREAT_LIKE_VISIBLE_FOREGROUND_SERVICE
                : BIND_ALMOST_PERCEPTIBLE;
        return new CompanionDeviceServiceConnector(context, userId, componentName, bindingFlags);
    }

    private CompanionDeviceServiceConnector(@NonNull Context context, @UserIdInt int userId,
            @NonNull ComponentName componentName, int bindingFlags) {
        super(context, buildIntent(componentName), bindingFlags, userId, null);
        mUserId = userId;
        mComponentName = componentName;
    }

    void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    void postOnDeviceAppeared(@NonNull AssociationInfo associationInfo) {
        post(companionService -> companionService.onDeviceAppeared(associationInfo));
    }

    void postOnDeviceDisappeared(@NonNull AssociationInfo associationInfo) {
        post(companionService -> companionService.onDeviceDisappeared(associationInfo));
    }

    /**
     * Post "unbind" job, which will run *after* all previously posted jobs complete.
     *
     * IMPORTANT: use this method instead of invoking {@link ServiceConnector#unbind()} directly,
     * because the latter may cause previously posted callback, such as
     * {@link ICompanionDeviceService#onDeviceDisappeared(AssociationInfo)} to be dropped.
     */
    void postUnbind() {
        post(it -> unbind());
    }

    @Override
    protected void onServiceConnectionStatusChanged(
            @NonNull ICompanionDeviceService service, boolean isConnected) {
        if (DEBUG) {
            Log.d(TAG, "onServiceConnection_StatusChanged() " + mComponentName.toShortString()
                    + " connected=" + isConnected);
        }
    }

    // This method is only called when app is force-closed via settings,
    // but does not handle crashes. Binder death should be handled in #binderDied()
    @Override
    public void onBindingDied(@NonNull ComponentName name) {
        // IMPORTANT: call super! this will also invoke binderDied()
        super.onBindingDied(name);

        if (DEBUG) Log.d(TAG, "onBindingDied() " + mComponentName.toShortString());
    }

    @Override
    public void binderDied() {
        super.binderDied();

        if (DEBUG) Log.d(TAG, "binderDied() " + mComponentName.toShortString());

        // Handle primary process being killed
        if (mListener != null) {
            mListener.onBindingDied(mUserId, mComponentName.getPackageName());
        }
    }

    @Override
    protected ICompanionDeviceService binderAsInterface(@NonNull IBinder service) {
        return ICompanionDeviceService.Stub.asInterface(service);
    }

    /**
     * Overrides {@link ServiceConnector.Impl#getJobHandler()} to provide an alternative Thread
     * ("in form of" a {@link Handler}) to process jobs on.
     * <p>
     * (By default, {@link ServiceConnector.Impl} process jobs on the
     * {@link android.os.Looper#getMainLooper() MainThread} which is a shared singleton thread
     * within system_server and thus tends to get heavily congested)
     */
    @Override
    protected @NonNull Handler getJobHandler() {
        return getServiceThread().getThreadHandler();
    }

    @Override
    protected long getAutoDisconnectTimeoutMs() {
        // Do NOT auto-disconnect.
        return -1;
    }

    private static @NonNull Intent buildIntent(@NonNull ComponentName componentName) {
        return new Intent(CompanionDeviceService.SERVICE_INTERFACE)
                .setComponent(componentName);
    }

    private static @NonNull ServiceThread getServiceThread() {
        if (sServiceThread == null) {
            synchronized (CompanionDeviceManagerService.class) {
                if (sServiceThread == null) {
                    sServiceThread = new ServiceThread("companion-device-service-connector",
                            THREAD_PRIORITY_DEFAULT, /* allowIo */ false);
                    sServiceThread.start();
                }
            }
        }
        return sServiceThread;
    }

    /**
     * A worker thread for the {@link ServiceConnector} to process jobs on.
     *
     * <p>
     *  Do NOT reference directly, use {@link #getServiceThread()} method instead.
     */
    private static volatile @Nullable ServiceThread sServiceThread;
}
