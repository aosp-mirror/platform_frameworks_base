/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.IAssistDataReceiver;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.server.am.AssistDataRequester.AssistDataRequesterCallbacks;

/**
 * Proxies assist data to the given receiver, skipping all callbacks if the receiver dies.
 */
class AssistDataReceiverProxy implements AssistDataRequesterCallbacks,
        Binder.DeathRecipient {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "AssistDataReceiverProxy" : TAG_ATM;

    private String mCallerPackage;
    private IAssistDataReceiver mReceiver;

    public AssistDataReceiverProxy(IAssistDataReceiver receiver, String callerPackage) {
        mReceiver = receiver;
        mCallerPackage = callerPackage;
        linkToDeath();
    }

    @Override
    public boolean canHandleReceivedAssistDataLocked() {
        // We are forwarding, so we can always receive this data
        return true;
    }

    @Override
    public void onAssistDataReceivedLocked(Bundle data, int activityIndex, int activityCount) {
        if (mReceiver != null) {
            try {
                mReceiver.onHandleAssistData(data);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to proxy assist data to receiver in package="
                        + mCallerPackage, e);
            }
        }
    }

    @Override
    public void onAssistScreenshotReceivedLocked(Bitmap screenshot) {
        if (mReceiver != null) {
            try {
                mReceiver.onHandleAssistScreenshot(screenshot);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to proxy assist screenshot to receiver in package="
                        + mCallerPackage, e);
            }
        }
    }

    @Override
    public void onAssistRequestCompleted() {
        unlinkToDeath();
    }

    @Override
    public void binderDied() {
        unlinkToDeath();
    }

    private void linkToDeath() {
        try {
            mReceiver.asBinder().linkToDeath(this, 0);
        } catch (RemoteException e) {
            Log.w(TAG, "Could not link to client death", e);
        }
    }

    private void unlinkToDeath() {
        if (mReceiver != null) {
            mReceiver.asBinder().unlinkToDeath(this, 0);
        }
        mReceiver = null;
    }
}