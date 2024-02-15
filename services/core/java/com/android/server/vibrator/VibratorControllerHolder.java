/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.util.Slog;

/**
 * Holder class for {@link IVibratorController}.
 *
 * @hide
 */
public final class VibratorControllerHolder implements IBinder.DeathRecipient {
    private static final String TAG = "VibratorControllerHolder";

    private IVibratorController mVibratorController;

    public IVibratorController getVibratorController() {
        return mVibratorController;
    }

    /**
     * Sets the {@link IVibratorController} in {@link VibratorControllerHolder} to the new
     * controller. This will also take care of registering and unregistering death notifications
     * for the cached {@link IVibratorController}.
     */
    public void setVibratorController(IVibratorController controller) {
        try {
            if (mVibratorController != null) {
                mVibratorController.asBinder().unlinkToDeath(this, 0);
            }
            mVibratorController = controller;
            if (mVibratorController != null) {
                mVibratorController.asBinder().linkToDeath(this, 0);
            }
        } catch (RemoteException e) {
            Slog.wtf(TAG, "Failed to set IVibratorController: " + this, e);
        }
    }

    @Override
    public void binderDied(@NonNull IBinder deadBinder) {
        if (mVibratorController != null && deadBinder == mVibratorController.asBinder()) {
            setVibratorController(null);
        }
    }

    @Override
    public void binderDied() {
        // Should not be used as binderDied(IBinder who) is overridden.
        Slog.wtf(TAG, "binderDied() called unexpectedly.");
    }
}
