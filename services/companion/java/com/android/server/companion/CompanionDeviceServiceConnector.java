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

import static android.content.Context.BIND_IMPORTANT;

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
import android.os.IBinder;
import android.util.Log;

import com.android.internal.infra.ServiceConnector;

/**
 * Manages a connection (binding) to an instance of {@link CompanionDeviceService} running in the
 * application process.
 */
@SuppressLint("LongLogTag")
class CompanionDeviceServiceConnector extends ServiceConnector.Impl<ICompanionDeviceService> {
    private static final String TAG = "CompanionDevice_ServiceConnector";
    private static final boolean DEBUG = false;
    private static final int BINDING_FLAGS = BIND_IMPORTANT;

    /** Listener for changes to the state of the {@link CompanionDeviceServiceConnector}  */
    interface Listener {
        void onBindingDied(@UserIdInt int userId, @NonNull String packageName);
    }

    private final @UserIdInt int mUserId;
    private final @NonNull ComponentName mComponentName;
    private @Nullable Listener mListener;

    CompanionDeviceServiceConnector(@NonNull Context context, @UserIdInt int userId,
            @NonNull ComponentName componentName) {
        super(context, buildIntent(componentName), BINDING_FLAGS, userId, null);
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

    @Override
    public void onBindingDied(@NonNull ComponentName name) {
        // IMPORTANT: call super!
        super.onBindingDied(name);

        if (DEBUG) Log.d(TAG, "onBindingDied() " + mComponentName.toShortString());

        mListener.onBindingDied(mUserId, mComponentName.getPackageName());
    }

    @Override
    protected ICompanionDeviceService binderAsInterface(@NonNull IBinder service) {
        return ICompanionDeviceService.Stub.asInterface(service);
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
}
