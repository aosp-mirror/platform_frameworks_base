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

import static android.app.admin.DevicePolicyManager.UNSAFE_OPERATION_REASON_NONE;
import static android.app.admin.DevicePolicyManager.operationToString;
import static android.app.admin.DevicePolicyManager.unsafeOperationReasonToString;

import android.app.admin.DevicePolicyManager.DevicePolicyOperation;
import android.app.admin.DevicePolicyManager.UnsafeOperationReason;
import android.app.admin.DevicePolicySafetyChecker;
import android.util.Slog;

import com.android.internal.os.IResultReceiver;

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

    private final DevicePolicyManagerService mService;
    private final DevicePolicySafetyChecker mRealSafetyChecker;
    private final @DevicePolicyOperation int mOperation;
    private final @UnsafeOperationReason int mReason;

    OneTimeSafetyChecker(DevicePolicyManagerService service,
            @DevicePolicyOperation int operation, @UnsafeOperationReason int reason) {
        mService = Objects.requireNonNull(service);
        mOperation = operation;
        mReason = reason;
        mRealSafetyChecker = service.getDevicePolicySafetyChecker();
        Slog.i(TAG, "Saving real DevicePolicySafetyChecker as " + mRealSafetyChecker);
    }

    @Override
    @UnsafeOperationReason
    public int getUnsafeOperationReason(@DevicePolicyOperation int operation) {
        String name = operationToString(operation);
        int reason = UNSAFE_OPERATION_REASON_NONE;
        if (operation == mOperation) {
            reason = mReason;
        } else {
            Slog.wtf(TAG, "invalid call to isDevicePolicyOperationSafe(): asked for " + name
                    + ", should be " + operationToString(mOperation));
        }
        Slog.i(TAG, "getDevicePolicyOperationSafety(" + name + "): returning "
                + unsafeOperationReasonToString(reason)
                + " and restoring DevicePolicySafetyChecker to " + mRealSafetyChecker);
        mService.setDevicePolicySafetyCheckerUnchecked(mRealSafetyChecker);
        return reason;
    }

    @Override
    public void onFactoryReset(IResultReceiver callback) {
        throw new UnsupportedOperationException();
    }
}
