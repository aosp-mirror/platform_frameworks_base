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

package com.android.server.inputmethod;

import static android.inputmethodservice.InputMethodService.FINISH_INPUT_NO_FALLBACK_CONNECTION;
import static android.view.inputmethod.InputMethodManager.CLEAR_SHOW_FORCED_FLAG_WHEN_LEAVING;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.compat.IPlatformCompat;

/**
 * A utility class used by {@link InputMethodManagerService} to manage the platform
 * compatibility changes on IMF (Input Method Framework) side.
 */
final class ImePlatformCompatUtils {
    private final IPlatformCompat mPlatformCompat = IPlatformCompat.Stub.asInterface(
            ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));

    /**
     * Whether to finish the {@link android.view.inputmethod.InputConnection} when the device
     * becomes {@link android.os.PowerManager#isInteractive non-interactive}.
     *
     * @param imeUid The uid of the IME application
     */
    public boolean shouldFinishInputWithReportToIme(int imeUid) {
        return isChangeEnabledByUid(FINISH_INPUT_NO_FALLBACK_CONNECTION, imeUid);
    }

    /**
     *  Whether to clear {@link android.view.inputmethod.InputMethodManager#SHOW_FORCED} flag
     *  when the next IME focused application changed.
     *
     * @param clientUid The uid of the app
     */
    public boolean shouldClearShowForcedFlag(int clientUid) {
        return isChangeEnabledByUid(CLEAR_SHOW_FORCED_FLAG_WHEN_LEAVING, clientUid);
    }

    private boolean isChangeEnabledByUid(long changeFlag, int uid) {
        boolean result = false;
        try {
            result = mPlatformCompat.isChangeEnabledByUid(changeFlag, uid);
        } catch (RemoteException e) {
        }
        return result;
    }
}
