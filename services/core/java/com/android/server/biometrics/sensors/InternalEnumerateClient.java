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

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.os.IBinder;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Internal class to help clean up unknown templates in the HAL and Framework
 */
public abstract class InternalEnumerateClient<T> extends HalClientMonitor<T>
        implements EnumerateConsumer {

    private static final String TAG = "Biometrics/InternalEnumerateClient";

    private BiometricUtils mUtils;
    // List of templates that are known to the Framework. Remove from this list when enumerate
    // returns a template that contains a match.
    private List<? extends BiometricAuthenticator.Identifier> mEnrolledList;
    // List of templates to remove from the HAL
    private List<BiometricAuthenticator.Identifier> mUnknownHALTemplates = new ArrayList<>();
    private final int mInitialEnrolledSize;

    protected InternalEnumerateClient(@NonNull Context context, @NonNull Supplier<T> lazyDaemon,
            @NonNull IBinder token, int userId, @NonNull String owner,
            @NonNull List<? extends BiometricAuthenticator.Identifier> enrolledList,
            @NonNull BiometricUtils utils, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext) {
        // Internal enumerate does not need to send results to anyone. Cleanup (enumerate + remove)
        // is all done internally.
        super(context, lazyDaemon, token, null /* ClientMonitorCallbackConverter */, userId, owner,
                0 /* cookie */, sensorId, logger, biometricContext,
                false /* isMandatoryBiometrics */);
        mEnrolledList = enrolledList;
        mInitialEnrolledSize = mEnrolledList.size();
        mUtils = utils;
    }

    @Override
    public void onEnumerationResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        handleEnumeratedTemplate(identifier);
        if (remaining == 0) {
            doTemplateCleanup();
            mCallback.onClientFinished(this, true /* success */);
        }
    }

    @Override
    public void unableToStart() {
        // Nothing to do here
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);

        // The biometric template ids will be removed when we get confirmation from the HAL
        startHalOperation();
    }

    private void handleEnumeratedTemplate(BiometricAuthenticator.Identifier identifier) {
        if (identifier == null) {
            Slog.d(TAG, "Null identifier");
            return;
        }
        Slog.v(TAG, "handleEnumeratedTemplate: " + identifier.getBiometricId());
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
        Slog.v(TAG, "Matched: " + matched);
    }

    private void doTemplateCleanup() {
        if (mEnrolledList == null) {
            Slog.d(TAG, "Null enrolledList");
            return;
        }

        // At this point, mEnrolledList only contains templates known to the framework and
        // not the HAL.
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < mEnrolledList.size(); i++) {
            BiometricAuthenticator.Identifier identifier = mEnrolledList.get(i);
            names.add(identifier.getName().toString());
            Slog.e(TAG, "doTemplateCleanup(): Removing dangling template from framework: "
                    + identifier.getBiometricId() + " " + identifier.getName());
            mUtils.removeBiometricForUser(getContext(),
                    getTargetUserId(), identifier.getBiometricId());

            getLogger().logUnknownEnrollmentInFramework();
        }

        // Send dangling notification.
        if (!names.isEmpty()) {
            sendDanglingNotification(names);
        }
        mEnrolledList.clear();
    }

    public List<BiometricAuthenticator.Identifier> getUnknownHALTemplates() {
        return mUnknownHALTemplates;
    }

    /**
     * Send the dangling notification.
     */
    @VisibleForTesting
    public void sendDanglingNotification(@NonNull List<String> identifierNames) {
        if (!identifierNames.isEmpty()) {
            Slog.e(TAG, "sendDanglingNotification(): initial enrolledSize="
                    + mInitialEnrolledSize + ", after clean up size=" + mEnrolledList.size());
            final boolean allIdentifiersDeleted = mEnrolledList.size() == mInitialEnrolledSize;
            BiometricNotificationUtils.showBiometricReEnrollNotification(
                    getContext(), identifierNames, allIdentifiersDeleted, getModality());
        }
    }

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_ENUMERATE;
    }

    protected abstract int getModality();
}
