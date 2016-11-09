/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.autofill;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.assist.AssistStructure;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.autofill.AutoFillService;
import android.service.autofill.IAutoFillService;
import android.service.voice.VoiceInteractionSession;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;

import java.io.PrintWriter;

/**
 * An auto-fill session between the system's {@link AutoFillManagerServiceImpl} and the provider's
 * {@link AutoFillService} implementation.
 */
final class AutoFillSession {

    private static final String TAG = "AutoFillSession";

    private static final boolean FOCUSED = true;
    private static final boolean NEW_SESSION_ID = true;

    private final IAutoFillService mService;
    private final String mSessionToken;
    private final IBinder mActivityToken;
    private final Object mLock;
    private final IActivityManager mAm;

    private final IResultReceiver mAssistReceiver = new IResultReceiver.Stub() {
        @Override
        public void send(int resultCode, Bundle resultData) throws RemoteException {
            synchronized (mLock) {
                mPendingResponse = false;
                mAssistResponse = resultData;
                deliverSessionDataLocked();
            }
        }
    };

    // Assist data is filled asynchronously.
    @GuardedBy("mLock")
    private Bundle mAssistResponse;
    @GuardedBy("mLock")
    private boolean mPendingResponse;

    AutoFillSession(IAutoFillService service, Object lock, String sessionToken,
            IBinder activityToken) {
        mService = service;
        mSessionToken = sessionToken;
        mActivityToken = activityToken;
        mLock = lock;
        mAm = ActivityManagerNative.getDefault();
    }

    void startLocked() {
        /*
         * TODO: apply security checks below:
         * - checks if disabled by secure settings / device policy
         * - log operation using noteOp()
         * - check flags
         * - display disclosure if needed
         */
        mAssistResponse = null;
        mPendingResponse = true;
        try {
            // TODO: add MetricsLogger call
            if (!mAm.requestAssistContextExtras(ActivityManager.ASSIST_CONTEXT_FULL,
                    mAssistReceiver, (Bundle) null, mActivityToken, FOCUSED, NEW_SESSION_ID)) {
                mPendingResponse = false;
                Slog.w(TAG, "requestAssistContextExtras() rejected");
            }
        } catch (RemoteException e) {
            // Should happen, it's a local call.
        }
    }

    void finishLocked() {
        try {
            mService.finishSession(mSessionToken);
        } catch (RemoteException e) {
            Slog.e(TAG, "auto-fill service failed to finish session " + mSessionToken, e);
        }
    }

    private void deliverSessionDataLocked() {
        if (mAssistResponse == null) {
            Slog.w(TAG, "No assist data for session " + mSessionToken);
            return;
        }

        final Bundle assistData = mAssistResponse.getBundle(VoiceInteractionSession.KEY_DATA);
        final AssistStructure structure =
                mAssistResponse.getParcelable(VoiceInteractionSession.KEY_STRUCTURE);
        try {
            mService.newSession(mSessionToken, assistData, 0, structure);
        } catch (RemoteException e) {
            Slog.e(TAG, "auto-fill service failed to start session " + mSessionToken, e);
        } finally {
            mPendingResponse = false;
            // We could set mAssistResponse to null here, but we don't so it's shown on dump()
        }
    }

    void dumpLocked(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mSessionToken="); pw.println(mSessionToken);
        pw.print(prefix); pw.print("mActivityToken="); pw.println(mActivityToken);
        pw.print(prefix); pw.print("mPendingResponse="); pw.println(mPendingResponse);
        pw.print(prefix); pw.print("mAssistResponse="); pw.println(mAssistResponse);
    }

}
