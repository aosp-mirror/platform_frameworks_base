/*
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

package com.android.server.vibrator;

import android.annotation.NonNull;
import android.frameworks.vibrator.IVibratorController;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.VibrationAttributes;

/**
 * Provides a fake implementation of {@link android.frameworks.vibrator.IVibratorController} for
 * testing.
 */
public final class FakeVibratorController extends IVibratorController.Stub {

    public boolean isLinkedToDeath = false;
    public boolean didRequestVibrationParams = false;
    public int requestVibrationType = VibrationAttributes.USAGE_UNKNOWN;
    public long requestTimeoutInMillis = 0;

    @Override
    public void requestVibrationParams(int vibrationType, long timeoutInMillis, IBinder iBinder)
            throws RemoteException {
        didRequestVibrationParams = true;
        requestVibrationType = vibrationType;
        requestTimeoutInMillis = timeoutInMillis;
    }

    @Override
    public int getInterfaceVersion() throws RemoteException {
        return 0;
    }

    @Override
    public String getInterfaceHash() throws RemoteException {
        return null;
    }

    @Override
    public void linkToDeath(@NonNull DeathRecipient recipient, int flags) {
        super.linkToDeath(recipient, flags);
        isLinkedToDeath = true;
    }

    @Override
    public boolean unlinkToDeath(@NonNull DeathRecipient recipient, int flags) {
        isLinkedToDeath = false;
        return super.unlinkToDeath(recipient, flags);
    }
}
