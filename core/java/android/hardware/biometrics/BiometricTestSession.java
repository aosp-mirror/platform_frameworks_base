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

package android.hardware.biometrics;

import static android.Manifest.permission.TEST_BIOMETRIC;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.content.Context;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Common set of interfaces to test biometric-related APIs, including {@link BiometricPrompt} and
 * {@link android.hardware.fingerprint.FingerprintManager}.
 *
 * @hide
 */
@TestApi
public class BiometricTestSession implements AutoCloseable {
    private static final String BASE_TAG = "BiometricTestSession";

    /**
     * @hide
     */
    public interface TestSessionProvider {
        @NonNull
        ITestSession createTestSession(@NonNull Context context, int sensorId,
                @NonNull ITestSessionCallback callback) throws RemoteException;
    }

    private final int mSensorId;
    private final List<ITestSession> mTestSessionsForAllSensors = new ArrayList<>();
    private ITestSession mTestSession;

    // Keep track of users that were tested, which need to be cleaned up when finishing.
    @NonNull
    private final ArraySet<Integer> mTestedUsers;

    // Track the users currently cleaning up, and provide a latch that gets notified when all
    // users have finished cleaning up. This is an imperfect system, as there can technically be
    // multiple cleanups per user. Theoretically we should track the cleanup's BaseClientMonitor's
    // unique ID, but it's complicated to plumb it through. This should be fine for now.
    @Nullable
    private CountDownLatch mCloseLatch;
    @NonNull
    private final ArraySet<Integer> mUsersCleaningUp;

    private class TestSessionCallbackIml extends ITestSessionCallback.Stub {
        private final int mSensorId;
        private TestSessionCallbackIml(int sensorId) {
            mSensorId = sensorId;
        }

        @Override
        public void onCleanupStarted(int userId) {
            Log.d(getTag(), "onCleanupStarted, sensor: " + mSensorId + ", userId: " + userId);
        }

        @Override
        public void onCleanupFinished(int userId) {
            Log.d(getTag(), "onCleanupFinished, sensor: " + mSensorId
                    + ", userId: " + userId
                    + ", remaining users: " + mUsersCleaningUp.size());
            mUsersCleaningUp.remove(userId);

            if (mUsersCleaningUp.isEmpty() && mCloseLatch != null) {
                Log.d(getTag(), "counting down");
                mCloseLatch.countDown();
            }
        }
    }

    /**
     * @hide
     */
    public BiometricTestSession(@NonNull Context context, List<SensorProperties> sensors,
            int sensorId, @NonNull TestSessionProvider testSessionProvider) throws RemoteException {
        mSensorId = sensorId;
        // When any of the sensors should create the test session, all the other sensors should
        // set test hal enabled too.
        for (SensorProperties sensor : sensors) {
            final int id = sensor.getSensorId();
            final ITestSession session = testSessionProvider.createTestSession(context, id,
                    new TestSessionCallbackIml(id));
            mTestSessionsForAllSensors.add(session);
            if (id == sensorId) {
                mTestSession = session;
            }
        }

        mTestedUsers = new ArraySet<>();
        mUsersCleaningUp = new ArraySet<>();
        setTestHalEnabled(true);
        Log.d(getTag(), "Opening BiometricTestSession");
    }

    /**
     * Switches the specified sensor to use a test HAL. In this mode, the framework will not invoke
     * any methods on the real HAL implementation. This allows the framework to test a substantial
     * portion of the framework code that would otherwise require human interaction. Note that
     * secure pathways such as HAT/Keystore are not testable, since they depend on the TEE or its
     * equivalent for the secret key.
     *
     * @param enabled If true, enable testing with a fake HAL instead of the real HAL.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    private void setTestHalEnabled(boolean enabled) {
        try {
            for (ITestSession session : mTestSessionsForAllSensors) {
                Log.w(getTag(), "setTestHalEnabled, sensor: " + session.getSensorId() + " enabled: "
                        + enabled);
                session.setTestHalEnabled(enabled);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Starts the enrollment process. This should generally be used when the test HAL is enabled.
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void startEnroll(int userId) {
        try {
            mTestedUsers.add(userId);
            mTestSession.startEnroll(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Finishes the enrollment process. Simulates the HAL's callback.
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void finishEnroll(int userId) {
        try {
            mTestedUsers.add(userId);
            mTestSession.finishEnroll(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Simulates a successful authentication, but does not provide a valid HAT.
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void acceptAuthentication(int userId) {
        try {
            mTestSession.acceptAuthentication(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Simulates a rejected attempt.
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void rejectAuthentication(int userId) {
        try {
            mTestSession.rejectAuthentication(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Simulates an acquired message from the HAL.
     *
     * @param userId      User that this command applies to.
     * @param acquireInfo See
     *                    {@link
     *                    BiometricPrompt.AuthenticationCallback#onAuthenticationAcquired(int)} and
     *                    {@link
     *                    FingerprintManager.AuthenticationCallback#onAuthenticationAcquired(int)}
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void notifyAcquired(int userId, int acquireInfo) {
        try {
            mTestSession.notifyAcquired(userId, acquireInfo);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Simulates an error message from the HAL.
     *
     * @param userId    User that this command applies to.
     * @param errorCode See
     *                  {@link BiometricPrompt.AuthenticationCallback#onAuthenticationError(int,
     *                  CharSequence)} and
     *                  {@link FingerprintManager.AuthenticationCallback#onAuthenticationError(int,
     *                  CharSequence)}
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void notifyError(int userId, int errorCode) {
        try {
            mTestSession.notifyError(userId, errorCode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Matches the framework's cached enrollments against the HAL's enrollments. Any enrollment
     * that isn't known by both sides are deleted. This should generally be used when the test
     * HAL is disabled (e.g. to clean up after a test).
     *
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void cleanupInternalState(int userId) {
        try {
            if (mUsersCleaningUp.contains(userId)) {
                Log.w(getTag(), "Cleanup already in progress for user: " + userId);
            }

            for (ITestSession session : mTestSessionsForAllSensors) {
                mUsersCleaningUp.add(userId);
                Log.d(getTag(), "cleanupInternalState for sensor: " + session.getSensorId());
                mCloseLatch = new CountDownLatch(1);
                session.cleanupInternalState(userId);

                try {
                    Log.d(getTag(), "Awaiting latch...");
                    mCloseLatch.await(3, TimeUnit.SECONDS);
                    Log.d(getTag(), "Finished awaiting");
                } catch (InterruptedException e) {
                    Log.e(getTag(), "Latch interrupted", e);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    @RequiresPermission(TEST_BIOMETRIC)
    public void close() {
        Log.d(getTag(), "Close, mTestedUsers size; " + mTestedUsers.size());
        // Cleanup can be performed using the test HAL, since it always responds to enumerate with
        // zero enrollments.
        if (!mTestedUsers.isEmpty()) {
            for (int user : mTestedUsers) {
                cleanupInternalState(user);
            }
        }

        if (!mUsersCleaningUp.isEmpty()) {
            // TODO(b/186600837): this seems common on multi sensor devices
            Log.e(getTag(), "Cleanup not finished before shutdown - pending: "
                    + mUsersCleaningUp.size());
        }

        // Disable the test HAL after the sensor becomes idle.
        setTestHalEnabled(false);
    }

    private String getTag() {
        return BASE_TAG + "_" + mSensorId;
    }
}
