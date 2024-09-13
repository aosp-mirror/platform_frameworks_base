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
import android.os.Build;
import android.os.IBinder;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.biometrics.BiometricsProto;
import com.android.server.biometrics.Flags;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Wraps {@link InternalEnumerateClient} and {@link RemovalClient}. Keeps track of all the
 * internal states when cleaning up mismatch between framework and HAL templates. This client
 * ends either when
 * 1) The HAL and Framework are in sync, and
 * {@link #onEnumerationResult(BiometricAuthenticator.Identifier, int)} returns true, or
 * 2) The HAL and Framework are not in sync, and
 * {@link #onRemoved(BiometricAuthenticator.Identifier, int)} returns true/
 */
public abstract class InternalCleanupClient<S extends BiometricAuthenticator.Identifier, T>
        extends HalClientMonitor<T> implements EnumerateConsumer, RemovalConsumer,
        EnrollmentModifier {

    private static final String TAG = "Biometrics/InternalCleanupClient";

    /**
     * Container for enumerated templates. Used to keep track when cleaning up unknown
     * templates.
     */
    private static final class UserTemplate {
        final BiometricAuthenticator.Identifier mIdentifier;
        final int mUserId;
        UserTemplate(BiometricAuthenticator.Identifier identifier, int userId) {
            this.mIdentifier = identifier;
            this.mUserId = userId;
        }
    }

    private final ArrayList<UserTemplate> mUnknownHALTemplates = new ArrayList<>();
    protected final BiometricUtils<S> mBiometricUtils;
    private final Map<Integer, Long> mAuthenticatorIds;
    private final boolean mHasEnrollmentsBeforeStarting;
    private BaseClientMonitor mCurrentTask;
    private boolean mFavorHalEnrollments = false;

    private final ClientMonitorCallback mEnumerateCallback = new ClientMonitorCallback() {
        @Override
        public void onClientFinished(@NonNull BaseClientMonitor clientMonitor, boolean success) {
            final List<BiometricAuthenticator.Identifier> unknownHALTemplates =
                    ((InternalEnumerateClient<T>) mCurrentTask).getUnknownHALTemplates();

            Slog.d(TAG, "Enumerate onClientFinished: " + clientMonitor + ", success: " + success);

            if (!unknownHALTemplates.isEmpty()) {
                Slog.w(TAG, "Adding " + unknownHALTemplates.size() + " templates for deletion");
            }
            for (BiometricAuthenticator.Identifier unknownHALTemplate : unknownHALTemplates) {
                mUnknownHALTemplates.add(new UserTemplate(unknownHALTemplate,
                        mCurrentTask.getTargetUserId()));
            }

            if (mUnknownHALTemplates.isEmpty()) {
                // No unknown HAL templates. Unknown framework templates are already cleaned up in
                // InternalEnumerateClient. Finish this client.
                mCallback.onClientFinished(InternalCleanupClient.this, success);
            } else {
                if (mFavorHalEnrollments && Build.isDebuggable()) {
                    // on debug builds, optionally allow the HAL be the source of
                    // truth for enrollments
                    try {
                        for (UserTemplate template : mUnknownHALTemplates) {
                            Slog.i(TAG, "Adding unknown HAL template: "
                                    + template.mIdentifier.getBiometricId());
                            onAddUnknownTemplate(template.mUserId, template.mIdentifier);
                        }
                    } finally {
                        mCallback.onClientFinished(InternalCleanupClient.this, success);
                    }
                } else {
                    startCleanupUnknownHalTemplates();
                }
            }

            if (mBiometricUtils.hasValidBiometricUserState(getContext(), getTargetUserId())
                    && Flags.notifyFingerprintsLoe()) {
                handleInvalidBiometricState();
            }
        }
    };

    private final ClientMonitorCallback mRemoveCallback = new ClientMonitorCallback() {
        @Override
        public void onClientFinished(@NonNull BaseClientMonitor clientMonitor, boolean success) {
            Slog.d(TAG, "Remove onClientFinished: " + clientMonitor + ", success: " + success);
            if (mUnknownHALTemplates.isEmpty()) {
                mCallback.onClientFinished(InternalCleanupClient.this, success);
            } else {
                startCleanupUnknownHalTemplates();
            }
        }
    };

    protected abstract InternalEnumerateClient<T> getEnumerateClient(Context context,
            Supplier<T> lazyDaemon, IBinder token, int userId, String owner,
            List<S> enrolledList, BiometricUtils<S> utils, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext);

    protected abstract RemovalClient<S, T> getRemovalClient(Context context,
            Supplier<T> lazyDaemon, IBinder token, int biometricId, int userId, String owner,
            BiometricUtils<S> utils, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            Map<Integer, Long> authenticatorIds);

    protected InternalCleanupClient(@NonNull Context context, @NonNull Supplier<T> lazyDaemon,
            int userId, @NonNull String owner, int sensorId,
            @NonNull BiometricLogger logger, @NonNull BiometricContext biometricContext,
            @NonNull BiometricUtils<S> utils,
            @NonNull Map<Integer, Long> authenticatorIds) {
        super(context, lazyDaemon, null /* token */, null /* ClientMonitorCallbackConverter */,
                userId, owner, 0 /* cookie */, sensorId, logger, biometricContext,
                false /* isMandatoryBiometrics */);
        mBiometricUtils = utils;
        mAuthenticatorIds = authenticatorIds;
        mHasEnrollmentsBeforeStarting = !utils.getBiometricsForUser(context, userId).isEmpty();
    }

    private void startCleanupUnknownHalTemplates() {
        Slog.d(TAG, "startCleanupUnknownHalTemplates, size: " + mUnknownHALTemplates.size());

        UserTemplate template = mUnknownHALTemplates.get(0);
        mUnknownHALTemplates.remove(template);
        mCurrentTask = getRemovalClient(getContext(), mLazyDaemon, getToken(),
                template.mIdentifier.getBiometricId(), template.mUserId,
                getContext().getPackageName(), mBiometricUtils, getSensorId(),
                getLogger(), getBiometricContext(), mAuthenticatorIds);

        getLogger().logUnknownEnrollmentInHal();

        if (mBiometricUtils.hasValidBiometricUserState(getContext(), getTargetUserId())
                && Flags.notifyFingerprintsLoe()) {
            getLogger().logFingerprintsLoe();
        }

        mCurrentTask.start(mRemoveCallback);
    }

    @Override
    public void unableToStart() {
        // nothing to do here
    }

    @Override
    public void start(@NonNull ClientMonitorCallback callback) {
        super.start(callback);

        final List<S> enrolledList =
                mBiometricUtils.getBiometricsForUser(getContext(), getTargetUserId());

        // Start enumeration. Removal will start if necessary, when enumeration is completed.
        mCurrentTask = getEnumerateClient(getContext(), mLazyDaemon, getToken(), getTargetUserId(),
                getOwnerString(), enrolledList, mBiometricUtils, getSensorId(), getLogger(),
                getBiometricContext());

        Slog.d(TAG, "Starting enumerate: " + mCurrentTask + " enrolledList size:"
                + enrolledList.size());
        mCurrentTask.start(mEnumerateCallback);
    }

    @Override
    protected void startHalOperation() {
        // Internal cleanup's start method does not require a HAL operation, but rather
        // relies on its subtask's ClientMonitor to start the proper HAL operation.
    }

    @Override
    public void onRemoved(BiometricAuthenticator.Identifier identifier, int remaining) {
        if (!(mCurrentTask instanceof RemovalClient)) {
            Slog.e(TAG, "onRemoved received during client: "
                    + mCurrentTask.getClass().getSimpleName());
            return;
        }
        ((RemovalClient<S, T>) mCurrentTask).onRemoved(identifier, remaining);
    }

    @Override
    public boolean hasEnrollmentStateChanged() {
        final boolean hasEnrollmentsNow = !mBiometricUtils
                .getBiometricsForUser(getContext(), getTargetUserId()).isEmpty();
        return hasEnrollmentsNow != mHasEnrollmentsBeforeStarting;
    }

    @Override
    public boolean hasEnrollments() {
        return !mBiometricUtils.getBiometricsForUser(getContext(), getTargetUserId()).isEmpty();
    }

    @Override
    public void onEnumerationResult(BiometricAuthenticator.Identifier identifier,
            int remaining) {
        if (!(mCurrentTask instanceof InternalEnumerateClient)) {
            Slog.e(TAG, "onEnumerationResult received during client: "
                    + mCurrentTask.getClass().getSimpleName());
            return;
        }
        Slog.d(TAG, "onEnumerated, remaining: " + remaining);
        ((EnumerateConsumer) mCurrentTask).onEnumerationResult(identifier, remaining);
    }

    /** When set unknown templates in the HAL will be added instead of deleted. */
    public void setFavorHalEnrollments() {
        mFavorHalEnrollments = true;
    }

    /** Called when an unknown template is found and setFavorHalEnrollments was requested. */
    protected void onAddUnknownTemplate(int userId,
            @NonNull BiometricAuthenticator.Identifier identifier) {}

    @Override
    public int getProtoEnum() {
        return BiometricsProto.CM_INTERNAL_CLEANUP;
    }

    @VisibleForTesting
    public InternalEnumerateClient<T> getCurrentEnumerateClient() {
        return (InternalEnumerateClient<T>) mCurrentTask;
    }

    @VisibleForTesting
    public RemovalClient<S, T> getCurrentRemoveClient() {
        return (RemovalClient<S, T>) mCurrentTask;
    }

    @VisibleForTesting
    public ArrayList<UserTemplate> getUnknownHALTemplates() {
        return mUnknownHALTemplates;
    }

    protected void handleInvalidBiometricState() {}

    protected abstract int getModality();
}
