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

package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Binder;
import android.os.Process;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

/**
 * This class should be used by system services which are part of mainline modules to register
 * {@link ActivityInterceptorCallback}. For other system services, this function should be used
 * instead {@link ActivityTaskManagerInternal#registerActivityStartInterceptor(
 * int, ActivityInterceptorCallback)}.
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public class ActivityInterceptorCallbackRegistry {

    private static final ActivityInterceptorCallbackRegistry sInstance =
            new ActivityInterceptorCallbackRegistry();

    private ActivityInterceptorCallbackRegistry() {}

    /** Returns an already initialised singleton instance of this class. */
    @NonNull
    public static ActivityInterceptorCallbackRegistry getInstance() {
        return sInstance;
    }

    /**
     * Registers a callback which can intercept activity launching flow.
     *
     * <p>Only system services which are part of mainline modules should call this function.
     *
     * <p>To avoid Activity launch delays, the callbacks must execute quickly and avoid acquiring
     * other system process locks.
     *
     * @param mainlineOrderId has to be one of the following [{@link
     *                        ActivityInterceptorCallback#MAINLINE_FIRST_ORDERED_ID}].
     * @param callback the {@link ActivityInterceptorCallback} to register.
     * @throws IllegalArgumentException if duplicate ids are provided, the provided id is not the
     * mainline module range or the provided {@code callback} is null.
     */
    // ExecutorRegistration is suppressed as the callback is called synchronously in the system
    // server.
    @SuppressWarnings("ExecutorRegistration")
    public void registerActivityInterceptorCallback(
            @ActivityInterceptorCallback.OrderedId int mainlineOrderId,
            @NonNull ActivityInterceptorCallback callback) {
        if (getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only system server can register "
                    + "ActivityInterceptorCallback");
        }
        if (!ActivityInterceptorCallback.isValidMainlineOrderId(mainlineOrderId)) {
            throw new IllegalArgumentException("id is not in the mainline modules range, please use"
                    + "ActivityTaskManagerInternal.registerActivityStartInterceptor(OrderedId, "
                    + "ActivityInterceptorCallback) instead.");
        }
        if (callback == null) {
            throw new IllegalArgumentException("The passed ActivityInterceptorCallback can not be "
                    + "null");
        }
        ActivityTaskManagerInternal activityTaskManagerInternal =
                LocalServices.getService(ActivityTaskManagerInternal.class);
        activityTaskManagerInternal.registerActivityStartInterceptor(mainlineOrderId, callback);
    }

    /**
     * Unregisters an already registered {@link ActivityInterceptorCallback}.
     *
     * @param mainlineOrderId the order id of the {@link ActivityInterceptorCallback} should be
     *                        unregistered, this callback should be registered before by calling
     *                        {@link #registerActivityInterceptorCallback(int,
     *                        ActivityInterceptorCallback)} using the same order id.
     * @throws IllegalArgumentException if the provided id is not the mainline module range or is
     * not registered
     */
    public void unregisterActivityInterceptorCallback(
            @ActivityInterceptorCallback.OrderedId int mainlineOrderId) {
        if (getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Only system server can register "
                    + "ActivityInterceptorCallback");
        }
        if (!ActivityInterceptorCallback.isValidMainlineOrderId(mainlineOrderId)) {
            throw new IllegalArgumentException("id is not in the mainline modules range, please use"
                    + "ActivityTaskManagerInternal.unregisterActivityStartInterceptor(OrderedId) "
                    + "instead.");
        }
        ActivityTaskManagerInternal activityTaskManagerInternal =
                LocalServices.getService(ActivityTaskManagerInternal.class);
        activityTaskManagerInternal.unregisterActivityStartInterceptor(mainlineOrderId);
    }

    /**
     * This hidden function is for unit tests as a way to behave like as if they are called from
     * system server process uid by mocking it and returning {@link Process#SYSTEM_UID}.
     * Do not make this {@code public} to apps as apps should not have a way to change the uid.
     * @hide
     */
    @VisibleForTesting
    int getCallingUid() {
        return Binder.getCallingUid();
    }
}
