/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.ICheckinService;
import android.os.IParentalControlCallback;
import android.os.RecoverySystem;
import android.util.Log;

import java.io.IOException;

import com.google.android.net.ParentalControlState;

/**
 * @hide
 */
public final class FallbackCheckinService extends ICheckinService.Stub {
    static final String TAG = "FallbackCheckinService";
    final Context mContext;

    public FallbackCheckinService(Context context) {
        mContext = context;
    }

    public void masterClear() {
        if (mContext.checkCallingOrSelfPermission("android.permission.MASTER_CLEAR") !=
                PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission Denial: can't invoke masterClear from "
                    + "pid=" + Binder.getCallingPid() + ", "
                    + "uid=" + Binder.getCallingUid());
            return;
        }

        // Save the android ID so the new system can get it erased.
        try {
            RecoverySystem.rebootWipeUserData(mContext);
        } catch (IOException e) {
            Log.e(TAG, "Reboot for masterClear() failed", e);
        }
    }

    public void masterClearAndToggleEFS(boolean efsEnabled) {
        if (mContext.checkCallingOrSelfPermission("android.permission.MASTER_CLEAR") !=
			PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission Denial: can't invoke masterClearAndToggleEFS from "
				  + "pid=" + Binder.getCallingPid() + ", "
				  + "uid=" + Binder.getCallingUid());
            return;
        }

        // Save the android ID so the new system can get it erased.
        try {
            RecoverySystem.rebootToggleEFS(mContext, efsEnabled);
        } catch (IOException e) {
            Log.e(TAG, "Reboot for toggle EFS failed", e);
        }
    }

    public void getParentalControlState(IParentalControlCallback p, String requestingApp)
            throws android.os.RemoteException {
        ParentalControlState state = new ParentalControlState();
        state.isEnabled = false;
        p.onResult(state);
    }
}
