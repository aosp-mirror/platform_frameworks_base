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

package com.android.server.biometrics.sensors.fingerprint.hidl;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.trust.TrustManager;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintManager.AuthenticationCallback;
import android.hardware.fingerprint.FingerprintManager.AuthenticationResult;
import android.hardware.fingerprint.FingerprintSensorProperties;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintStateListener;
import android.hardware.fingerprint.IUdfpsOverlayController;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.R;
import com.android.server.biometrics.sensors.AuthenticationConsumer;
import com.android.server.biometrics.sensors.BaseClientMonitor;
import com.android.server.biometrics.sensors.BiometricScheduler;
import com.android.server.biometrics.sensors.ClientMonitorCallbackConverter;
import com.android.server.biometrics.sensors.LockoutResetDispatcher;
import com.android.server.biometrics.sensors.fingerprint.FingerprintStateCallback;
import com.android.server.biometrics.sensors.fingerprint.GestureAvailabilityDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A mockable/testable provider of the {@link android.hardware.biometrics.fingerprint.V2_3} HIDL
 * interface. This class is intended simulate UDFPS logic for devices that do not have an actual
 * fingerprint@2.3 HAL (where UDFPS starts to become supported)
 *
 * UDFPS "accept" can only happen within a set amount of time after a sensor authentication. This is
 * specified by {@link MockHalResultController#AUTH_VALIDITY_MS}. Touches after this duration will
 * be treated as "reject".
 *
 * This class provides framework logic to emulate, for testing only, the UDFPS functionalies below:
 *
 * 1) IF either A) the caller is keyguard, and the device is not in a trusted state (authenticated
 *    via biometric sensor or unlocked with a trust agent {@see android.app.trust.TrustManager}, OR
 *    B) the caller is not keyguard, and regardless of trusted state, AND (following applies to both
 *    (A) and (B) above) {@link FingerprintManager#onFingerDown(int, int, float, float)} is
 *    received, this class will respond with {@link AuthenticationCallback#onAuthenticationFailed()}
 *    after a tunable flat_time + variance_time.
 *
 *    In the case above (1), callers must not receive a successful authentication event here because
 *    the sensor has not actually been authenticated.
 *
 * 2) IF A) the caller is keyguard and the device is not in a trusted state, OR B) the caller is not
 *    keyguard and regardless of trusted state, AND (following applies to both (A) and (B)) the
 *    sensor is touched and the fingerprint is accepted by the HAL, and then
 *    {@link FingerprintManager#onFingerDown(int, int, float, float)} is received, this class will
 *    respond with {@link AuthenticationCallback#onAuthenticationSucceeded(AuthenticationResult)}
 *    after a tunable flat_time + variance_time. Note that the authentication callback from the
 *    sensor is held until {@link FingerprintManager#onFingerDown(int, int, float, float)} is
 *    invoked.
 *
 *    In the case above (2), callers can receive a successful authentication callback because the
 *    real sensor was authenticated. Note that even though the real sensor was touched, keyguard
 *    fingerprint authentication does not put keyguard into a trusted state because the
 *    authentication callback is held until onFingerDown was invoked. This allows callers such as
 *    keyguard to simulate a realistic path.
 *
 * 3) IF the caller is keyguard AND the device in a trusted state and then
 *    {@link FingerprintManager#onFingerDown(int, int, float, float)} is received, this class will
 *    respond with {@link AuthenticationCallback#onAuthenticationSucceeded(AuthenticationResult)}
 *    after a tunable flat_time + variance time.
 *
 *    In the case above (3), since the device is already unlockable via trust agent, it's fine to
 *    simulate the successful auth path. Non-keyguard clients will fall into either (1) or (2)
 *    above.
 *
 *  This class currently does not simulate false rejection. Conversely, this class relies on the
 *  real hardware sensor so does not affect false acceptance.
 */
@SuppressWarnings("deprecation")
public class Fingerprint21UdfpsMock extends Fingerprint21 implements TrustManager.TrustListener {

    private static final String TAG = "Fingerprint21UdfpsMock";

    // Secure setting integer. If true, the system will load this class to enable udfps testing.
    public static final String CONFIG_ENABLE_TEST_UDFPS =
            "com.android.server.biometrics.sensors.fingerprint.test_udfps.enable";
    // Secure setting integer. A fixed duration intended to simulate something like the duration
    // required for image capture.
    private static final String CONFIG_AUTH_DELAY_PT1 =
            "com.android.server.biometrics.sensors.fingerprint.test_udfps.auth_delay_pt1";
    // Secure setting integer. A fixed duration intended to simulate something like the duration
    // required for template matching to complete.
    private static final String CONFIG_AUTH_DELAY_PT2 =
            "com.android.server.biometrics.sensors.fingerprint.test_udfps.auth_delay_pt2";
    // Secure setting integer. A random value between [-randomness, randomness] will be added to the
    // capture delay above for each accept/reject.
    private static final String CONFIG_AUTH_DELAY_RANDOMNESS =
            "com.android.server.biometrics.sensors.fingerprint.test_udfps.auth_delay_randomness";

    private static final int DEFAULT_AUTH_DELAY_PT1_MS = 300;
    private static final int DEFAULT_AUTH_DELAY_PT2_MS = 400;
    private static final int DEFAULT_AUTH_DELAY_RANDOMNESS_MS = 100;

    @NonNull private final TestableBiometricScheduler mScheduler;
    @NonNull private final Handler mHandler;
    @NonNull private final FingerprintSensorPropertiesInternal mSensorProperties;
    @NonNull private final MockHalResultController mMockHalResultController;
    @NonNull private final TrustManager mTrustManager;
    @NonNull private final SparseBooleanArray mUserHasTrust;
    @NonNull private final Random mRandom;
    @NonNull private final FakeRejectRunnable mFakeRejectRunnable;
    @NonNull private final FakeAcceptRunnable mFakeAcceptRunnable;
    @NonNull private final RestartAuthRunnable mRestartAuthRunnable;

    private static class TestableBiometricScheduler extends BiometricScheduler {
        @NonNull private final TestableInternalCallback mInternalCallback;
        @NonNull private Fingerprint21UdfpsMock mFingerprint21;

        TestableBiometricScheduler(@NonNull String tag,
                @Nullable GestureAvailabilityDispatcher gestureAvailabilityDispatcher) {
            super(tag, BiometricScheduler.SENSOR_TYPE_FP_OTHER,
                    gestureAvailabilityDispatcher);
            mInternalCallback = new TestableInternalCallback();
        }

        class TestableInternalCallback extends InternalCallback {
            @Override
            public void onClientStarted(BaseClientMonitor clientMonitor) {
                super.onClientStarted(clientMonitor);
                Slog.d(TAG, "Client started: " + clientMonitor);
                mFingerprint21.setDebugMessage("Started: " + clientMonitor);
            }

            @Override
            public void onClientFinished(BaseClientMonitor clientMonitor, boolean success) {
                super.onClientFinished(clientMonitor, success);
                Slog.d(TAG, "Client finished: " + clientMonitor);
                mFingerprint21.setDebugMessage("Finished: " + clientMonitor);
            }
        }

        void init(@NonNull Fingerprint21UdfpsMock fingerprint21) {
            mFingerprint21 = fingerprint21;
        }

        /**
         * Expose the internal finish callback so it can be used for testing
         */
        @Override
        @NonNull protected InternalCallback getInternalCallback() {
            return mInternalCallback;
        }
    }

    /**
     * All of the mocking/testing should happen in here. This way we don't need to modify the
     * {@link BaseClientMonitor} implementations and can run the
     * real path there.
     */
    private static class MockHalResultController extends HalResultController {

        // Duration for which a sensor authentication can be treated as UDFPS success.
        private static final int AUTH_VALIDITY_MS = 10 * 1000; // 10 seconds

        static class LastAuthArgs {
            @NonNull final AuthenticationConsumer lastAuthenticatedClient;
            final long deviceId;
            final int fingerId;
            final int groupId;
            @Nullable final ArrayList<Byte> token;

            LastAuthArgs(@NonNull AuthenticationConsumer authenticationConsumer, long deviceId,
                    int fingerId, int groupId, @Nullable ArrayList<Byte> token) {
                lastAuthenticatedClient = authenticationConsumer;
                this.deviceId = deviceId;
                this.fingerId = fingerId;
                this.groupId = groupId;
                if (token == null) {
                    this.token = null;
                } else {
                    // Store a copy so the owner can be GC'd
                    this.token = new ArrayList<>(token);
                }
            }
        }

        // Initialized after the constructor, but before it's ever used.
        @NonNull private RestartAuthRunnable mRestartAuthRunnable;
        @NonNull private Fingerprint21UdfpsMock mFingerprint21;
        @Nullable private LastAuthArgs mLastAuthArgs;

        MockHalResultController(int sensorId, @NonNull Context context, @NonNull Handler handler,
                @NonNull BiometricScheduler scheduler) {
            super(sensorId, context, handler, scheduler);
        }

        void init(@NonNull RestartAuthRunnable restartAuthRunnable,
                @NonNull Fingerprint21UdfpsMock fingerprint21) {
            mRestartAuthRunnable = restartAuthRunnable;
            mFingerprint21 = fingerprint21;
        }

        @Nullable AuthenticationConsumer getLastAuthenticatedClient() {
            return mLastAuthArgs != null ? mLastAuthArgs.lastAuthenticatedClient : null;
        }

        /**
         * Intercepts the HAL authentication and holds it until the UDFPS simulation decides
         * that authentication finished.
         */
        @Override
        public void onAuthenticated(long deviceId, int fingerId, int groupId,
                ArrayList<Byte> token) {
            mHandler.post(() -> {
                final BaseClientMonitor client = mScheduler.getCurrentClient();
                if (!(client instanceof AuthenticationConsumer)) {
                    Slog.e(TAG, "Non authentication consumer: " + client);
                    return;
                }

                final boolean accepted = fingerId != 0;
                if (accepted) {
                    mFingerprint21.setDebugMessage("Finger accepted");
                } else {
                    mFingerprint21.setDebugMessage("Finger rejected");
                }

                final AuthenticationConsumer authenticationConsumer =
                        (AuthenticationConsumer) client;
                mLastAuthArgs = new LastAuthArgs(authenticationConsumer, deviceId, fingerId,
                        groupId, token);

                // Remove any existing restart runnbables since auth just started, and put a new
                // one on the queue.
                mHandler.removeCallbacks(mRestartAuthRunnable);
                mRestartAuthRunnable.setLastAuthReference(authenticationConsumer);
                mHandler.postDelayed(mRestartAuthRunnable, AUTH_VALIDITY_MS);
            });
        }

        /**
         * Calls through to the real interface and notifies clients of accept/reject.
         */
        void sendAuthenticated(long deviceId, int fingerId, int groupId,
                ArrayList<Byte> token) {
            Slog.d(TAG, "sendAuthenticated: " + (fingerId != 0));
            mFingerprint21.setDebugMessage("Udfps match: " + (fingerId != 0));
            super.onAuthenticated(deviceId, fingerId, groupId, token);
        }
    }

    public static Fingerprint21UdfpsMock newInstance(@NonNull Context context,
            @NonNull FingerprintStateCallback fingerprintStateCallback,
            @NonNull FingerprintSensorPropertiesInternal sensorProps,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull GestureAvailabilityDispatcher gestureAvailabilityDispatcher) {
        Slog.d(TAG, "Creating Fingerprint23Mock!");

        final Handler handler = new Handler(Looper.getMainLooper());
        final TestableBiometricScheduler scheduler =
                new TestableBiometricScheduler(TAG, gestureAvailabilityDispatcher);
        final MockHalResultController controller =
                new MockHalResultController(sensorProps.sensorId, context, handler, scheduler);
        return new Fingerprint21UdfpsMock(context, fingerprintStateCallback, sensorProps, scheduler,
                handler, lockoutResetDispatcher, controller);
    }

    private static abstract class FakeFingerRunnable implements Runnable {
        private long mFingerDownTime;
        private int mCaptureDuration;

        /**
         * @param fingerDownTime System time when onFingerDown occurred
         * @param captureDuration Duration that the finger needs to be down for
         */
        void setSimulationTime(long fingerDownTime, int captureDuration) {
            mFingerDownTime = fingerDownTime;
            mCaptureDuration = captureDuration;
        }

        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean isImageCaptureComplete() {
            return System.currentTimeMillis() - mFingerDownTime > mCaptureDuration;
        }
    }

    private final class FakeRejectRunnable extends FakeFingerRunnable {
        @Override
        public void run() {
            mMockHalResultController.sendAuthenticated(0, 0, 0, null);
        }
    }

    private final class FakeAcceptRunnable extends FakeFingerRunnable {
        @Override
        public void run() {
            if (mMockHalResultController.mLastAuthArgs == null) {
                // This can happen if the user has trust agents enabled, which make lockscreen
                // dismissable. Send a fake non-zero (accept) finger.
                Slog.d(TAG, "Sending fake finger");
                mMockHalResultController.sendAuthenticated(1 /* deviceId */,
                        1 /* fingerId */, 1 /* groupId */, null /* token */);
            } else {
                mMockHalResultController.sendAuthenticated(
                        mMockHalResultController.mLastAuthArgs.deviceId,
                        mMockHalResultController.mLastAuthArgs.fingerId,
                        mMockHalResultController.mLastAuthArgs.groupId,
                        mMockHalResultController.mLastAuthArgs.token);
            }
        }
    }

    /**
     * The fingerprint HAL allows multiple (5) fingerprint attempts per HIDL invocation of the
     * authenticate method. However, valid fingerprint authentications are invalidated after
     * {@link MockHalResultController#AUTH_VALIDITY_MS}, meaning UDFPS touches will be reported as
     * rejects if touched after that duration. However, since a valid fingerprint was detected, the
     * HAL and FingerprintService will not look for subsequent fingerprints.
     *
     * In order to keep the FingerprintManager API consistent (that multiple fingerprint attempts
     * are allowed per auth lifecycle), we internally cancel and restart authentication so that the
     * sensor is responsive again.
     */
    private static final class RestartAuthRunnable implements Runnable {
        @NonNull private final Fingerprint21UdfpsMock mFingerprint21;
        @NonNull private final TestableBiometricScheduler mScheduler;

        // Store a reference to the auth consumer that should be invalidated.
        private AuthenticationConsumer mLastAuthConsumer;

        RestartAuthRunnable(@NonNull Fingerprint21UdfpsMock fingerprint21,
                @NonNull TestableBiometricScheduler scheduler) {
            mFingerprint21 = fingerprint21;
            mScheduler = scheduler;
        }

        void setLastAuthReference(AuthenticationConsumer lastAuthConsumer) {
            mLastAuthConsumer = lastAuthConsumer;
        }

        @Override
        public void run() {
            final BaseClientMonitor client = mScheduler.getCurrentClient();

            // We don't care about FingerprintDetectClient, since accept/rejects are both OK. UDFPS
            // rejects will just simulate the path where non-enrolled fingers are presented.
            if (!(client instanceof FingerprintAuthenticationClient)) {
                Slog.e(TAG, "Non-FingerprintAuthenticationClient client: " + client);
                return;
            }

            // Perhaps the runnable is stale, or the user stopped/started auth manually. Do not
            // restart auth in this case.
            if (client != mLastAuthConsumer) {
                Slog.e(TAG, "Current client: " + client
                        + " does not match mLastAuthConsumer: " + mLastAuthConsumer);
                return;
            }

            Slog.d(TAG, "Restarting auth, current: " + client);
            mFingerprint21.setDebugMessage("Auth timed out");

            final FingerprintAuthenticationClient authClient =
                    (FingerprintAuthenticationClient) client;
            // Store the authClient parameters so it can be rescheduled
            final IBinder token = client.getToken();
            final long operationId = authClient.getOperationId();
            final int user = client.getTargetUserId();
            final int cookie = client.getCookie();
            final ClientMonitorCallbackConverter listener = client.getListener();
            final String opPackageName = client.getOwnerString();
            final boolean restricted = authClient.isRestricted();
            final int statsClient = client.getStatsClient();
            final boolean isKeyguard = authClient.isKeyguard();

            // Don't actually send cancel() to the HAL, since successful auth already finishes
            // HAL authenticate() lifecycle. Just
            mScheduler.getInternalCallback().onClientFinished(client, true /* success */);

            // Schedule this only after we invoke onClientFinished for the previous client, so that
            // internal preemption logic is not run.
            mFingerprint21.scheduleAuthenticate(mFingerprint21.mSensorProperties.sensorId, token,
                    operationId, user, cookie, listener, opPackageName, restricted, statsClient,
                    isKeyguard);
        }
    }

    private Fingerprint21UdfpsMock(@NonNull Context context,
            @NonNull FingerprintStateCallback fingerprintStateCallback,
            @NonNull FingerprintSensorPropertiesInternal sensorProps,
            @NonNull TestableBiometricScheduler scheduler,
            @NonNull Handler handler,
            @NonNull LockoutResetDispatcher lockoutResetDispatcher,
            @NonNull MockHalResultController controller) {
        super(context, fingerprintStateCallback, sensorProps, scheduler, handler,
                lockoutResetDispatcher, controller);
        mScheduler = scheduler;
        mScheduler.init(this);
        mHandler = handler;
        // resetLockout is controlled by the framework, so hardwareAuthToken is not required
        final boolean resetLockoutRequiresHardwareAuthToken = false;
        final int maxTemplatesAllowed = mContext.getResources()
                .getInteger(R.integer.config_fingerprintMaxTemplatesPerUser);
        mSensorProperties = new FingerprintSensorPropertiesInternal(sensorProps.sensorId,
                sensorProps.sensorStrength, maxTemplatesAllowed, sensorProps.componentInfo,
                FingerprintSensorProperties.TYPE_UDFPS_OPTICAL,
                resetLockoutRequiresHardwareAuthToken, sensorProps.sensorLocationX,
                sensorProps.sensorLocationY, sensorProps.sensorRadius);
        mMockHalResultController = controller;
        mUserHasTrust = new SparseBooleanArray();
        mTrustManager = context.getSystemService(TrustManager.class);
        mTrustManager.registerTrustListener(this);
        mRandom = new Random();
        mFakeRejectRunnable = new FakeRejectRunnable();
        mFakeAcceptRunnable = new FakeAcceptRunnable();
        mRestartAuthRunnable = new RestartAuthRunnable(this, mScheduler);

        // We can't initialize this during MockHalresultController's constructor due to a circular
        // dependency.
        mMockHalResultController.init(mRestartAuthRunnable, this);
    }

    @Override
    public void onTrustChanged(boolean enabled, int userId, int flags) {
        mUserHasTrust.put(userId, enabled);
    }

    @Override
    public void onTrustManagedChanged(boolean enabled, int userId) {

    }

    @Override
    public void onTrustError(CharSequence message) {

    }

    @Override
    @NonNull
    public List<FingerprintSensorPropertiesInternal> getSensorProperties() {
        final List<FingerprintSensorPropertiesInternal> properties = new ArrayList<>();
        properties.add(mSensorProperties);
        return properties;
    }

    @Override
    public void onPointerDown(int sensorId, int x, int y, float minor, float major) {
        mHandler.post(() -> {
            Slog.d(TAG, "onFingerDown");
            final AuthenticationConsumer lastAuthenticatedConsumer =
                    mMockHalResultController.getLastAuthenticatedClient();
            final BaseClientMonitor currentScheduledClient = mScheduler.getCurrentClient();

            if (currentScheduledClient == null) {
                Slog.d(TAG, "Not authenticating");
                return;
            }

            mHandler.removeCallbacks(mFakeRejectRunnable);
            mHandler.removeCallbacks(mFakeAcceptRunnable);

            // The sensor was authenticated, is still the currently scheduled client, and the
            // user touched the UDFPS affordance. Pretend that auth succeeded.
            final boolean authenticatedClientIsCurrent = lastAuthenticatedConsumer != null
                    && lastAuthenticatedConsumer == currentScheduledClient;
            // User is unlocked on keyguard via Trust Agent
            final boolean keyguardAndTrusted;
            if (currentScheduledClient instanceof FingerprintAuthenticationClient) {
                keyguardAndTrusted = ((FingerprintAuthenticationClient) currentScheduledClient)
                        .isKeyguard()
                        && mUserHasTrust.get(currentScheduledClient.getTargetUserId(), false);
            } else {
                keyguardAndTrusted = false;
            }

            final int captureDuration = getNewCaptureDuration();
            final int matchingDuration = getMatchingDuration();
            final int totalDuration = captureDuration + matchingDuration;
            setDebugMessage("Duration: " + totalDuration
                    + " (" + captureDuration + " + " + matchingDuration + ")");
            if (authenticatedClientIsCurrent || keyguardAndTrusted) {
                mFakeAcceptRunnable.setSimulationTime(System.currentTimeMillis(), captureDuration);
                mHandler.postDelayed(mFakeAcceptRunnable, totalDuration);
            } else if (currentScheduledClient instanceof AuthenticationConsumer) {
                // Something is authenticating but authentication has not succeeded yet. Pretend
                // that auth rejected.
                mFakeRejectRunnable.setSimulationTime(System.currentTimeMillis(), captureDuration);
                mHandler.postDelayed(mFakeRejectRunnable, totalDuration);
            }
        });
    }

    @Override
    public void onPointerUp(int sensorId) {
        mHandler.post(() -> {
            Slog.d(TAG, "onFingerUp");

            // Only one of these can be on the handler at any given time (see onFingerDown). If
            // image capture is not complete, send ACQUIRED_TOO_FAST and remove the runnable from
            // the handler. Image capture (onFingerDown) needs to happen again.
            if (mHandler.hasCallbacks(mFakeRejectRunnable)
                    && !mFakeRejectRunnable.isImageCaptureComplete()) {
                mHandler.removeCallbacks(mFakeRejectRunnable);
                mMockHalResultController.onAcquired(0 /* deviceId */,
                        FingerprintManager.FINGERPRINT_ACQUIRED_TOO_FAST,
                        0 /* vendorCode */);
            } else if (mHandler.hasCallbacks(mFakeAcceptRunnable)
                    && !mFakeAcceptRunnable.isImageCaptureComplete()) {
                mHandler.removeCallbacks(mFakeAcceptRunnable);
                mMockHalResultController.onAcquired(0 /* deviceId */,
                        FingerprintManager.FINGERPRINT_ACQUIRED_TOO_FAST,
                        0 /* vendorCode */);
            }
        });
    }

    private int getNewCaptureDuration() {
        final ContentResolver contentResolver = mContext.getContentResolver();
        final int captureTime = Settings.Secure.getIntForUser(contentResolver,
                CONFIG_AUTH_DELAY_PT1,
                DEFAULT_AUTH_DELAY_PT1_MS,
                UserHandle.USER_CURRENT);
        final int randomDelayRange = Settings.Secure.getIntForUser(contentResolver,
                CONFIG_AUTH_DELAY_RANDOMNESS,
                DEFAULT_AUTH_DELAY_RANDOMNESS_MS,
                UserHandle.USER_CURRENT);
        final int randomDelay = mRandom.nextInt(randomDelayRange * 2) - randomDelayRange;

        // Must be at least 0
        return Math.max(captureTime + randomDelay, 0);
    }

    private int getMatchingDuration() {
        final int matchingTime = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                CONFIG_AUTH_DELAY_PT2,
                DEFAULT_AUTH_DELAY_PT2_MS,
                UserHandle.USER_CURRENT);

        // Must be at least 0
        return Math.max(matchingTime, 0);
    }

    private void setDebugMessage(String message) {
        try {
            final IUdfpsOverlayController controller = getUdfpsOverlayController();
            // Things can happen before SysUI loads and sets the controller.
            if (controller != null) {
                Slog.d(TAG, "setDebugMessage: " + message);
                controller.setDebugMessage(mSensorProperties.sensorId, message);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote exception when sending message: " + message, e);
        }
    }
}
