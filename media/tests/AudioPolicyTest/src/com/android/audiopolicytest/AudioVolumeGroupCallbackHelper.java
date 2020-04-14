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

package com.android.audiopolicytest;

import static org.junit.Assert.assertNotNull;

import android.media.AudioManager;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


final class AudioVolumeGroupCallbackHelper extends AudioManager.VolumeGroupCallback {
    private static final String TAG = "AudioVolumeGroupCallbackHelper";
    public static final long ASYNC_TIMEOUT_MS = 800;

    private int mExpectedVolumeGroupId;

    private CountDownLatch mVolumeGroupChanged = null;

    void setExpectedVolumeGroup(int group) {
        mVolumeGroupChanged = new CountDownLatch(1);
        mExpectedVolumeGroupId = group;
    }

    @Override
    public void onAudioVolumeGroupChanged(int group, int flags) {
        if (group != mExpectedVolumeGroupId) {
            return;
        }
        if (mVolumeGroupChanged == null) {
            Log.wtf(TAG, "Received callback but object not initialized");
            return;
        }
        if (mVolumeGroupChanged.getCount() <= 0) {
            Log.i(TAG, "callback for group: " + group + " already received");
            return;
        }
        mVolumeGroupChanged.countDown();
    }

    public boolean waitForExpectedVolumeGroupChanged(long timeOutMs) {
        assertNotNull("Call first setExpectedVolumeGroup before waiting...", mVolumeGroupChanged);
        boolean timeoutReached = false;
        if (mVolumeGroupChanged.getCount() == 0) {
            // done already...
            return true;
        }
        try {
            timeoutReached = !mVolumeGroupChanged.await(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) { }
        return !timeoutReached;
    }
}
