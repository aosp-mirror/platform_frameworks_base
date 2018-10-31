/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.telephony.rcs;

import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.Rlog;

import com.android.internal.telephony.rcs.IRcs;

/**
 * RcsManager is the application interface to RcsProvider and provides access methods to
 * RCS related database tables.
 * @hide - TODO make this public
 */
@SystemService(Context.TELEPHONY_RCS_SERVICE)
public class RcsManager {
    private static final String TAG = "RcsManager";
    private static final boolean VDBG = false;

    /**
     * Delete the RcsThread identified by the given threadId.
     * @param threadId threadId of the thread to be deleted.
     */
    public void deleteThread(int threadId) {
        if (VDBG) logd("deleteThread: threadId: " + threadId);
        try {
            IRcs iRcs = IRcs.Stub.asInterface(ServiceManager.getService("ircs"));
            if (iRcs != null) {
                iRcs.deleteThread(threadId);
            }
        } catch (RemoteException re) {

        }
    }

    private static void logd(String msg) {
        Rlog.d(TAG, msg);
    }
}
