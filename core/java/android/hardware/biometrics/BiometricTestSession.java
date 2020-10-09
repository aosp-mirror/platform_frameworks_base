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
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Common set of interfaces to test biometric-related APIs, including {@link BiometricPrompt} and
 * {@link android.hardware.fingerprint.FingerprintManager}.
 * @hide
 */
@TestApi
public class BiometricTestSession implements AutoCloseable {

    private static final String TAG = "TestManager";

    private final Context mContext;
    private final ITestService mTestService;

    /**
     * @hide
     */
    public BiometricTestSession(@NonNull Context context, @NonNull ITestService testService) {
        mContext = context;
        mTestService = testService;
    }

    /**
     * @return A list of {@link SensorProperties}
     */
    @NonNull
    @RequiresPermission(TEST_BIOMETRIC)
    public List<SensorProperties> getSensorProperties() {
        try {
            final List<SensorPropertiesInternal> internalProps =
                    mTestService.getSensorPropertiesInternal(mContext.getOpPackageName());
            final List<SensorProperties> props = new ArrayList<>();
            for (SensorPropertiesInternal internalProp : internalProps) {
                props.add(new SensorProperties(internalProp.sensorId, internalProp.sensorStrength));
            }
            return props;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Switches the specified sensor to use a test HAL. In this mode, the framework will not invoke
     * any methods on the real HAL implementation. This allows the framework to test a substantial
     * portion of the framework code that would otherwise require human interaction. Note that
     * secure pathways such as HAT/Keystore are not testable, since they depend on the TEE or its
     * equivalent for the secret key.
     *
     * @param sensorId Sensor that this command applies to.
     * @param enableTestHal If true, enable testing with a fake HAL instead of the real HAL.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void enableTestHal(int sensorId, boolean enableTestHal) {
        try {
            mTestService.enableTestHal(sensorId, enableTestHal);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception", e);
        }
    }

    /**
     * Starts the enrollment process. This should generally be used when the test HAL is enabled.
     *
     * @param sensorId Sensor that this command applies to.
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void enrollStart(int sensorId, int userId) {
        try {
            mTestService.enrollStart(sensorId, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception", e);
        }
    }

    /**
     * Finishes the enrollment process. Simulates the HAL's callback.
     *
     * @param sensorId Sensor that this command applies to.
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void enrollFinish(int sensorId, int userId) {
        try {
            mTestService.enrollFinish(sensorId, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception", e);
        }
    }

    /**
     * Simulates a successful authentication, but does not provide a valid HAT.
     *
     * @param sensorId Sensor that this command applies to.
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void authenticateSuccess(int sensorId, int userId) {
        try {
            mTestService.authenticateSuccess(sensorId, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception", e);
        }
    }

    /**
     * Simulates a rejected attempt.
     *
     * @param sensorId Sensor that this command applies to.
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void authenticateReject(int sensorId, int userId) {
        try {
            mTestService.authenticateReject(sensorId, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception", e);
        }
    }

    /**
     * Simulates an acquired message from the HAL.
     *
     * @param sensorId Sensor that this command applies to.
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void notifyAcquired(int sensorId, int userId) {
        try {
            mTestService.notifyAcquired(sensorId, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception", e);
        }
    }

    /**
     * Simulates an error message from the HAL.
     *
     * @param sensorId Sensor that this command applies to.
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void notifyError(int sensorId, int userId) {
        try {
            mTestService.notifyError(sensorId, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception", e);
        }
    }

    /**
     * Matches the framework's cached enrollments against the HAL's enrollments. Any enrollment
     * that isn't known by both sides are deleted. This should generally be used when the test
     * HAL is disabled (e.g. to clean up after a test).
     *
     * @param sensorId Sensor that this command applies to.
     * @param userId User that this command applies to.
     */
    @RequiresPermission(TEST_BIOMETRIC)
    public void internalCleanup(int sensorId, int userId) {
        try {
            mTestService.internalCleanup(sensorId, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Remote exception", e);
        }
    }

    @Override
    @RequiresPermission(TEST_BIOMETRIC)
    public void close() {

    }
}
