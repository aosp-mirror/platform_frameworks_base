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

package com.android.server.devicepolicy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * BroadcastReceiver that listens to {@link Intent#ACTION_USER_UNLOCKED} in order to provide
 * a blocking wait until the managed profile has been started and unlocked.
 */
class UserUnlockedBlockingReceiver extends BroadcastReceiver {
    private static final int WAIT_FOR_USER_UNLOCKED_TIMEOUT_SECONDS = 120;

    private final Semaphore mSemaphore = new Semaphore(0);
    private final int mUserId;

    UserUnlockedBlockingReceiver(int userId) {
        mUserId = userId;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
            return;
        }
        if (intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL) == mUserId) {
            mSemaphore.release();
        }
    }

    public boolean waitForUserUnlocked() {
        try {
            return mSemaphore.tryAcquire(
                    WAIT_FOR_USER_UNLOCKED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            return false;
        }
    }
}
