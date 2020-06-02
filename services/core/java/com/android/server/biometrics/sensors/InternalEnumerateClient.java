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

package com.android.server.biometrics.sensors;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal class to help clean up unknown templates in the HAL and Framework
 */
public class InternalEnumerateClient extends EnumerateClient {
    private BiometricUtils mUtils;
    // List of templates that are known to the Framework. Remove from this list when enumerate
    // returns a template that contains a match.
    private List<? extends BiometricAuthenticator.Identifier> mEnrolledList;
    // List of templates to remove from the HAL
    private List<BiometricAuthenticator.Identifier> mUnknownHALTemplates = new ArrayList<>();

    InternalEnumerateClient(Context context, Constants constants,
            BiometricServiceBase.DaemonWrapper daemon, IBinder token,
            ClientMonitorCallbackConverter listener, int groupId, int userId,
            boolean restricted, String owner,
            List<? extends BiometricAuthenticator.Identifier> enrolledList,
            BiometricUtils utils, int sensorId, int statsModality) {
        super(context, constants, daemon, token, listener, groupId, userId,
                restricted, owner, sensorId, statsModality);
        mEnrolledList = enrolledList;
        mUtils = utils;
    }

    private void handleEnumeratedTemplate(BiometricAuthenticator.Identifier identifier) {
        if (identifier == null) {
            return;
        }
        Slog.v(getLogTag(), "handleEnumeratedTemplate: " + identifier.getBiometricId());
        boolean matched = false;
        for (int i = 0; i < mEnrolledList.size(); i++) {
            if (mEnrolledList.get(i).getBiometricId() == identifier.getBiometricId()) {
                mEnrolledList.remove(i);
                matched = true;
                break;
            }
        }

        // TemplateId 0 means no templates in HAL
        if (!matched && identifier.getBiometricId() != 0) {
            mUnknownHALTemplates.add(identifier);
        }
        Slog.v(getLogTag(), "Matched: " + matched);
    }

    private void doTemplateCleanup() {
        if (mEnrolledList == null) {
            return;
        }

        // At this point, mEnrolledList only contains templates known to the framework and
        // not the HAL.
        for (int i = 0; i < mEnrolledList.size(); i++) {
            BiometricAuthenticator.Identifier identifier = mEnrolledList.get(i);
            Slog.e(getLogTag(), "doTemplateCleanup(): Removing dangling template from framework: "
                    + identifier.getBiometricId() + " "
                    + identifier.getName());
            mUtils.removeBiometricForUser(getContext(),
                    getTargetUserId(), identifier.getBiometricId());
            FrameworkStatsLog.write(FrameworkStatsLog.BIOMETRIC_SYSTEM_HEALTH_ISSUE_DETECTED,
                    mStatsModality,
                    BiometricsProtoEnums.ISSUE_UNKNOWN_TEMPLATE_ENROLLED_FRAMEWORK);
        }
        mEnrolledList.clear();
    }

    public List<BiometricAuthenticator.Identifier> getUnknownHALTemplates() {
        return mUnknownHALTemplates;
    }

    @Override
    public boolean onEnumerationResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        handleEnumeratedTemplate(identifier);
        if (remaining == 0) {
            doTemplateCleanup();
        }
        return remaining == 0;
    }
}
