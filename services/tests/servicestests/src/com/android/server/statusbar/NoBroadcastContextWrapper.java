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

package com.android.server.statusbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.UserHandle;
import android.testing.TestableContext;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

/**
 * {@link ContextWrapper} that doesn't register {@link BroadcastReceiver}.
 *
 * Instead, it keeps a list of the registrations for querying.
 */
class NoBroadcastContextWrapper extends TestableContext {

    ArrayList<BroadcastReceiverRegistration> mRegistrationList =
            new ArrayList<>();

    NoBroadcastContextWrapper(Context context) {
        super(context);
    }

    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter) {
        return registerReceiver(receiver, filter, 0);
    }

    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter,
            int flags) {
        return registerReceiver(receiver, filter, null, null, flags);
    }

    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter,
            @Nullable String broadcastPermission, @Nullable Handler scheduler) {
        return registerReceiver(receiver, filter, broadcastPermission, scheduler, 0);
    }

    @Override
    public Intent registerReceiver(@Nullable BroadcastReceiver receiver, IntentFilter filter,
            @Nullable String broadcastPermission, @Nullable Handler scheduler, int flags) {
        return registerReceiverAsUser(receiver, getUser(), filter, broadcastPermission, scheduler,
                flags);
    }

    @Nullable
    @Override
    public Intent registerReceiverForAllUsers(@Nullable BroadcastReceiver receiver,
            @NonNull IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler) {
        return registerReceiverForAllUsers(receiver, filter, broadcastPermission, scheduler, 0);
    }

    @Nullable
    @Override
    public Intent registerReceiverForAllUsers(@Nullable BroadcastReceiver receiver,
            @NonNull IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler, int flags) {
        return registerReceiverAsUser(receiver, UserHandle.ALL, filter, broadcastPermission,
                scheduler, flags);
    }

    @Override
    public Intent registerReceiverAsUser(@Nullable BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler) {
        return registerReceiverAsUser(receiver, user, filter, broadcastPermission,
                scheduler, 0);
    }

    @Override
    public Intent registerReceiverAsUser(@Nullable BroadcastReceiver receiver, UserHandle user,
            IntentFilter filter, @Nullable String broadcastPermission,
            @Nullable Handler scheduler, int flags) {
        BroadcastReceiverRegistration reg = new BroadcastReceiverRegistration(
                receiver, user, filter, broadcastPermission, scheduler, flags
        );
        mRegistrationList.add(reg);
        return null;
    }

    @Override
    public void unregisterReceiver(BroadcastReceiver receiver) {
        mRegistrationList.removeIf((reg) -> reg.mReceiver == receiver);
    }

    static class BroadcastReceiverRegistration {
        final BroadcastReceiver mReceiver;
        final UserHandle mUser;
        final IntentFilter mIntentFilter;
        final String mBroadcastPermission;
        final Handler mHandler;
        final int mFlags;

        BroadcastReceiverRegistration(BroadcastReceiver receiver, UserHandle user,
                IntentFilter intentFilter, String broadcastPermission, Handler handler, int flags) {
            mReceiver = receiver;
            mUser = user;
            mIntentFilter = intentFilter;
            mBroadcastPermission = broadcastPermission;
            mHandler = handler;
            mFlags = flags;
        }
    }
}
