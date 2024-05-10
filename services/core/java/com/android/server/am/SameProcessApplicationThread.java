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

package com.android.server.am;

import android.annotation.NonNull;
import android.app.IApplicationThread;
import android.app.ReceiverInfo;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.CompatibilityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteCallback;
import android.os.RemoteException;

import java.util.List;
import java.util.Objects;

/**
 * Wrapper around an {@link IApplicationThread} that delegates selected calls
 * through a {@link Handler} so they meet the {@code oneway} contract of
 * returning immediately after dispatch.
 */
public class SameProcessApplicationThread extends IApplicationThread.Default {
    private final IApplicationThread mWrapped;
    private final Handler mHandler;

    public SameProcessApplicationThread(@NonNull IApplicationThread wrapped,
            @NonNull Handler handler) {
        mWrapped = Objects.requireNonNull(wrapped);
        mHandler = Objects.requireNonNull(handler);
    }

    @Override
    public void scheduleReceiver(Intent intent, ActivityInfo info, CompatibilityInfo compatInfo,
            int resultCode, String data, Bundle extras, boolean ordered, boolean assumeDelivered,
            int sendingUser, int processState, int sendingUid, String sendingPackage) {
        mHandler.post(() -> {
            try {
                mWrapped.scheduleReceiver(intent, info, compatInfo, resultCode, data, extras,
                        ordered, assumeDelivered, sendingUser, processState, sendingUid,
                        sendingPackage);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void scheduleRegisteredReceiver(IIntentReceiver receiver, Intent intent, int resultCode,
            String data, Bundle extras, boolean ordered, boolean sticky, boolean assumeDelivered,
            int sendingUser, int processState, int sendingUid, String sendingPackage) {
        mHandler.post(() -> {
            try {
                mWrapped.scheduleRegisteredReceiver(receiver, intent, resultCode, data, extras,
                        ordered, sticky, assumeDelivered, sendingUser, processState, sendingUid,
                        sendingPackage);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void scheduleReceiverList(List<ReceiverInfo> info) {
        mHandler.post(() -> {
            try {
                mWrapped.scheduleReceiverList(info);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void schedulePing(RemoteCallback pong) {
        mHandler.post(() -> {
            try {
                mWrapped.schedulePing(pong);
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
