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
package com.android.server.devicepolicy;

import static android.app.admin.DevicePolicyManager.OPERATION_SAFETY_REASON_NONE;
import static android.app.admin.DevicePolicyManager.operationSafetyReasonToString;
import static android.app.admin.DevicePolicyManager.operationToString;

import android.app.admin.DevicePolicyManager.DevicePolicyOperation;
import android.app.admin.DevicePolicyManager.OperationSafetyReason;
import android.app.admin.DevicePolicyManagerLiteInternal;
import android.app.admin.DevicePolicySafetyChecker;
import android.os.Handler;
import android.os.Looper;
import android.util.Slog;

import com.android.internal.os.IResultReceiver;
import com.android.server.LocalServices;

import java.util.Objects;

//TODO(b/172376923): add unit tests

/**
 * {@code DevicePolicySafetyChecker} implementation that overrides the real checker for just
 * one command.
 *
 * <p>Used only for debugging and CTS tests.
 */
final class OneTimeSafetyChecker implements DevicePolicySafetyChecker {

    private static final String TAG = OneTimeSafetyChecker.class.getSimpleName();

    private static final long SELF_DESTRUCT_TIMEOUT_MS = 10_000;

    private final DevicePolicyManagerService mService;
    private final DevicePolicySafetyChecker mRealSafetyChecker;
    private final @DevicePolicyOperation int mOperation;
    private final @OperationSafetyReason int mReason;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mDone;

    OneTimeSafetyChecker(DevicePolicyManagerService service,
            @DevicePolicyOperation int operation, @OperationSafetyReason int reason) {
        mService = Objects.requireNonNull(service);
        mOperation = operation;
        mReason = reason;
        mRealSafetyChecker = service.getDevicePolicySafetyChecker();
        Slog.i(TAG, "OneTimeSafetyChecker constructor: operation=" + operationToString(operation)
                + ", reason=" + operationSafetyReasonToString(reason)
                + ", realChecker=" + mRealSafetyChecker
                + ", maxDuration=" + SELF_DESTRUCT_TIMEOUT_MS + "ms");
        mHandler.postDelayed(() -> selfDestruct(), SELF_DESTRUCT_TIMEOUT_MS);
    }

    @Override
    @OperationSafetyReason
    public int getUnsafeOperationReason(@DevicePolicyOperation int operation) {
        String name = operationToString(operation);
        Slog.i(TAG, "getUnsafeOperationReason(" + name + ")");
        int reason = OPERATION_SAFETY_REASON_NONE;
        if (operation == mOperation) {
            reason = mReason;
        } else {
            Slog.wtf(TAG, "invalid call to isDevicePolicyOperationSafe(): asked for " + name
                    + ", should be " + operationToString(mOperation));
        }
        String reasonName = operationSafetyReasonToString(reason);
        DevicePolicyManagerLiteInternal dpmi = LocalServices
                .getService(DevicePolicyManagerLiteInternal.class);

        Slog.i(TAG, "notifying " + reasonName + " is UNSAFE");
        dpmi.notifyUnsafeOperationStateChanged(this, reason, /* isSafe= */ false);

        Slog.i(TAG, "notifying " + reasonName + " is SAFE");
        dpmi.notifyUnsafeOperationStateChanged(this, reason, /* isSafe= */ true);

        Slog.i(TAG, "returning " + reasonName);

        disableSelf();
        return reason;
    }

    @Override
    public boolean isSafeOperation(@OperationSafetyReason int reason) {
        boolean safe = mReason != reason;
        Slog.i(TAG, "isSafeOperation(" + operationSafetyReasonToString(reason) + "): " + safe);

        disableSelf();
        return safe;
    }

    @Override
    public void onFactoryReset(IResultReceiver callback) {
        throw new UnsupportedOperationException();
    }

    private void disableSelf() {
        if (mDone) {
            Slog.w(TAG, "disableSelf(): already disabled");
            return;
        }
        Slog.i(TAG, "restoring DevicePolicySafetyChecker to " + mRealSafetyChecker);
        mService.setDevicePolicySafetyCheckerUnchecked(mRealSafetyChecker);
        mDone = true;
    }

    private void selfDestruct() {
        if (mDone) return;

        // Usually happens when a CTS failed before calling the DPM method that would clear it
        Slog.e(TAG, "Self destructing " + this + ", as it was not automatically disabled");
        disableSelf();
    }

    @Override
    public String toString() {
        return "OneTimeSafetyChecker[id=" + System.identityHashCode(this)
                + ", reason=" + operationSafetyReasonToString(mReason)
                + ", operation=" + operationToString(mOperation) + ']';
    }
}
