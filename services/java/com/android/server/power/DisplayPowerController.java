/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.power;

import com.android.server.LightsService;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.SystemSensorManager;
import android.hardware.display.DisplayManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Slog;
import android.util.Spline;
import android.util.TimeUtils;
import android.view.Display;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;

/**
 * Controls the power state of the display.
 *
 * Handles the proximity sensor, light sensor, and animations between states
 * including the screen off animation.
 *
 * This component acts independently of the rest of the power manager service.
 * In particular, it does not share any state and it only communicates
 * via asynchronous callbacks to inform the power manager that something has
 * changed.
 *
 * Everything this class does internally is serialized on its handler although
 * it may be accessed by other threads from the outside.
 *
 * Note that the power manager service guarantees that it will hold a suspend
 * blocker as long as the display is not ready.  So most of the work done here
 * does not need to worry about holding a suspend blocker unless it happens
 * independently of the display ready signal.
 *
 * For debugging, you can make the electron beam and brightness animations run
 * slower by changing the "animator duration scale" option in Development Settings.
 */
final class DisplayPowerController {
    private static final String TAG = "DisplayPowerController";

    private static boolean DEBUG = false;
    private static final boolean DEBUG_PRETEND_PROXIMITY_SENSOR_ABSENT = false;
    private static final boolean DEBUG_PRETEND_LIGHT_SENSOR_ABSENT = false;

    // If true, uses the electron beam on animation.
    // We might want to turn this off if we cannot get a guarantee that the screen
    // actually turns on and starts showing new content after the call to set the
    // screen state returns.  Playing the animation can also be somewhat slow.
    private static final boolean USE_ELECTRON_BEAM_ON_ANIMATION = false;

    private static final int ELECTRON_BEAM_ON_ANIMATION_DURATION_MILLIS = 300;
    private static final int ELECTRON_BEAM_OFF_ANIMATION_DURATION_MILLIS = 600;

    private static final int MSG_UPDATE_POWER_STATE = 1;
    private static final int MSG_PROXIMITY_SENSOR_DEBOUNCED = 2;
    private static final int MSG_LIGHT_SENSOR_DEBOUNCED = 3;

    private static final int PROXIMITY_UNKNOWN = -1;
    private static final int PROXIMITY_NEGATIVE = 0;
    private static final int PROXIMITY_POSITIVE = 1;

    // Proximity sensor debounce delay in milliseconds.
    private static final int PROXIMITY_SENSOR_DEBOUNCE_DELAY = 250;

    // Trigger proximity if distance is less than 5 cm.
    private static final float TYPICAL_PROXIMITY_THRESHOLD = 5.0f;

    // Light sensor event rate in microseconds.
    private static final int LIGHT_SENSOR_RATE = 1000000;

    // Brightness animation ramp rate in brightness units per second.
    private static final int BRIGHTNESS_RAMP_RATE_FAST = 200;
    private static final int BRIGHTNESS_RAMP_RATE_SLOW = 40;

    // Filter time constant in milliseconds for computing a moving
    // average of light samples.  Different constants are used
    // to calculate the average light level when adapting to brighter or
    // dimmer environments.
    // This parameter only controls the filtering of light samples.
    private static final long BRIGHTENING_LIGHT_TIME_CONSTANT = 600;
    private static final long DIMMING_LIGHT_TIME_CONSTANT = 4000;

    // Stability requirements in milliseconds for accepting a new brightness
    // level.  This is used for debouncing the light sensor.  Different constants
    // are used to debounce the light sensor when adapting to brighter or dimmer
    // environments.
    // This parameter controls how quickly brightness changes occur in response to
    // an observed change in light level.
    private static final long BRIGHTENING_LIGHT_DEBOUNCE = 2500;
    private static final long DIMMING_LIGHT_DEBOUNCE = 10000;

    private final Object mLock = new Object();

    // Notifier for sending asynchronous notifications.
    private final Notifier mNotifier;

    // A suspend blocker.
    private final SuspendBlocker mSuspendBlocker;

    // Our handler.
    private final DisplayControllerHandler mHandler;

    // Asynchronous callbacks into the power manager service.
    // Only invoked from the handler thread while no locks are held.
    private final Callbacks mCallbacks;
    private Handler mCallbackHandler;

    // The lights service.
    private final LightsService mLights;

    // The sensor manager.
    private final SensorManager mSensorManager;

    // The proximity sensor, or null if not available or needed.
    private Sensor mProximitySensor;

    // The light sensor, or null if not available or needed.
    private Sensor mLightSensor;

    // The dim screen brightness.
    private final int mScreenBrightnessDimConfig;

    // Auto-brightness.
    private boolean mUseSoftwareAutoBrightnessConfig;
    private Spline mScreenAutoBrightnessSpline;

    // Amount of time to delay auto-brightness after screen on while waiting for
    // the light sensor to warm-up in milliseconds.
    // May be 0 if no warm-up is required.
    private int mLightSensorWarmUpTimeConfig;

    // The pending power request.
    // Initially null until the first call to requestPowerState.
    // Guarded by mLock.
    private DisplayPowerRequest mPendingRequestLocked;

    // True if a request has been made to wait for the proximity sensor to go negative.
    // Guarded by mLock.
    private boolean mPendingWaitForNegativeProximityLocked;

    // True if the pending power request or wait for negative proximity flag
    // has been changed since the last update occurred.
    // Guarded by mLock.
    private boolean mPendingRequestChangedLocked;

    // Set to true when the important parts of the pending power request have been applied.
    // The important parts are mainly the screen state.  Brightness changes may occur
    // concurrently.
    // Guarded by mLock.
    private boolean mDisplayReadyLocked;

    // Set to true if a power state update is required.
    // Guarded by mLock.
    private boolean mPendingUpdatePowerStateLocked;

    /* The following state must only be accessed by the handler thread. */

    // The currently requested power state.
    // The power controller will progressively update its internal state to match
    // the requested power state.  Initially null until the first update.
    private DisplayPowerRequest mPowerRequest;

    // The current power state.
    // Must only be accessed on the handler thread.
    private DisplayPowerState mPowerState;

    // True if the device should wait for negative proximity sensor before
    // waking up the screen.  This is set to false as soon as a negative
    // proximity sensor measurement is observed or when the device is forced to
    // go to sleep by the user.  While true, the screen remains off.
    private boolean mWaitingForNegativeProximity;

    // The actual proximity sensor threshold value.
    private float mProximityThreshold;

    // Set to true if the proximity sensor listener has been registered
    // with the sensor manager.
    private boolean mProximitySensorEnabled;

    // The debounced proximity sensor state.
    private int mProximity = PROXIMITY_UNKNOWN;

    // The raw non-debounced proximity sensor state.
    private int mPendingProximity = PROXIMITY_UNKNOWN;
    private long mPendingProximityDebounceTime;

    // True if the screen was turned off because of the proximity sensor.
    // When the screen turns on again, we report user activity to the power manager.
    private boolean mScreenOffBecauseOfProximity;

    // Set to true if the light sensor is enabled.
    private boolean mLightSensorEnabled;

    // The time when the light sensor was enabled.
    private long mLightSensorEnableTime;

    // The currently accepted average light sensor value.
    private float mLightMeasurement;

    // True if the light sensor measurement is valid.
    private boolean mLightMeasurementValid;

    // The number of light sensor samples that have been collected since the
    // last time a light sensor reading was accepted.
    private int mRecentLightSamples;

    // The moving average of recent light sensor values.
    private float mRecentLightAverage;

    // True if recent light samples are getting brighter than the previous
    // stable light measurement.
    private boolean mRecentLightBrightening;

    // The time constant to use for filtering based on whether the
    // light appears to be brightening or dimming.
    private long mRecentLightTimeConstant;

    // The most recent light sample.
    private float mLastLightSample;

    // The time of the most light recent sample.
    private long mLastLightSampleTime;

    // The time when we accumulated the first recent light sample into mRecentLightSamples.
    private long mFirstRecentLightSampleTime;

    // The upcoming debounce light sensor time.
    // This is only valid when mLightMeasurementValue && mRecentLightSamples >= 1.
    private long mPendingLightSensorDebounceTime;

    // The screen brightness level that has been chosen by the auto-brightness
    // algorithm.  The actual brightness should ramp towards this value.
    // We preserve this value even when we stop using the light sensor so
    // that we can quickly revert to the previous auto-brightness level
    // while the light sensor warms up.
    // Use -1 if there is no current auto-brightness value available.
    private int mScreenAutoBrightness = -1;

    // True if the screen auto-brightness value is actually being used to
    // set the display brightness.
    private boolean mUsingScreenAutoBrightness;

    // Animators.
    private ObjectAnimator mElectronBeamOnAnimator;
    private ObjectAnimator mElectronBeamOffAnimator;
    private RampAnimator<DisplayPowerState> mScreenBrightnessRampAnimator;

    /**
     * Creates the display power controller.
     */
    public DisplayPowerController(Looper looper, Context context, Notifier notifier,
            LightsService lights, SuspendBlocker suspendBlocker,
            Callbacks callbacks, Handler callbackHandler) {
        mHandler = new DisplayControllerHandler(looper);
        mNotifier = notifier;
        mSuspendBlocker = suspendBlocker;
        mCallbacks = callbacks;
        mCallbackHandler = callbackHandler;

        mLights = lights;
        mSensorManager = new SystemSensorManager(mHandler.getLooper());

        final Resources resources = context.getResources();
        mScreenBrightnessDimConfig = resources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessDim);
        mUseSoftwareAutoBrightnessConfig = resources.getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);
        if (mUseSoftwareAutoBrightnessConfig) {
            int[] lux = resources.getIntArray(
                    com.android.internal.R.array.config_autoBrightnessLevels);
            int[] screenBrightness = resources.getIntArray(
                    com.android.internal.R.array.config_autoBrightnessLcdBacklightValues);

            mScreenAutoBrightnessSpline = createAutoBrightnessSpline(lux, screenBrightness);
            if (mScreenAutoBrightnessSpline == null) {
                Slog.e(TAG, "Error in config.xml.  config_autoBrightnessLcdBacklightValues "
                        + "(size " + screenBrightness.length + ") "
                        + "must be monotic and have exactly one more entry than "
                        + "config_autoBrightnessLevels (size " + lux.length + ") "
                        + "which must be strictly increasing.  "
                        + "Auto-brightness will be disabled.");
                mUseSoftwareAutoBrightnessConfig = false;
            }

            mLightSensorWarmUpTimeConfig = resources.getInteger(
                    com.android.internal.R.integer.config_lightSensorWarmupTime);
        }

        if (!DEBUG_PRETEND_PROXIMITY_SENSOR_ABSENT) {
            mProximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
            if (mProximitySensor != null) {
                mProximityThreshold = Math.min(mProximitySensor.getMaximumRange(),
                        TYPICAL_PROXIMITY_THRESHOLD);
            }
        }

        if (mUseSoftwareAutoBrightnessConfig
                && !DEBUG_PRETEND_LIGHT_SENSOR_ABSENT) {
            mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        }
    }

    private static Spline createAutoBrightnessSpline(int[] lux, int[] brightness) {
        try {
            final int n = brightness.length;
            float[] x = new float[n];
            float[] y = new float[n];
            y[0] = brightness[0];
            for (int i = 1; i < n; i++) {
                x[i] = lux[i - 1];
                y[i] = brightness[i];
            }

            Spline spline = Spline.createMonotoneCubicSpline(x, y);
            if (false) {
                Slog.d(TAG, "Auto-brightness spline: " + spline);
                for (float v = 1f; v < lux[lux.length - 1] * 1.25f; v *= 1.25f) {
                    Slog.d(TAG, String.format("  %7.1f: %7.1f", v, spline.interpolate(v)));
                }
            }
            return spline;
        } catch (IllegalArgumentException ex) {
            Slog.e(TAG, "Could not create auto-brightness spline.", ex);
            return null;
        }
    }

    /**
     * Returns true if the proximity sensor screen-off function is available.
     */
    public boolean isProximitySensorAvailable() {
        return mProximitySensor != null;
    }

    /**
     * Requests a new power state.
     * The controller makes a copy of the provided object and then
     * begins adjusting the power state to match what was requested.
     *
     * @param request The requested power state.
     * @param waitForNegativeProximity If true, issues a request to wait for
     * negative proximity before turning the screen back on, assuming the screen
     * was turned off by the proximity sensor.
     * @return True if display is ready, false if there are important changes that must
     * be made asynchronously (such as turning the screen on), in which case the caller
     * should grab a wake lock, watch for {@link Callbacks#onStateChanged()} then try
     * the request again later until the state converges.
     */
    public boolean requestPowerState(DisplayPowerRequest request,
            boolean waitForNegativeProximity) {
        if (DEBUG) {
            Slog.d(TAG, "requestPowerState: "
                    + request + ", waitForNegativeProximity=" + waitForNegativeProximity);
        }

        synchronized (mLock) {
            boolean changed = false;

            if (waitForNegativeProximity
                    && !mPendingWaitForNegativeProximityLocked) {
                mPendingWaitForNegativeProximityLocked = true;
                changed = true;
            }

            if (mPendingRequestLocked == null) {
                mPendingRequestLocked = new DisplayPowerRequest(request);
                changed = true;
            } else if (!mPendingRequestLocked.equals(request)) {
                mPendingRequestLocked.copyFrom(request);
                changed = true;
            }

            if (changed) {
                mDisplayReadyLocked = false;
            }

            if (changed && !mPendingRequestChangedLocked) {
                mPendingRequestChangedLocked = true;
                sendUpdatePowerStateLocked();
            }

            return mDisplayReadyLocked;
        }
    }

    private void sendUpdatePowerState() {
        synchronized (mLock) {
            sendUpdatePowerStateLocked();
        }
    }

    private void sendUpdatePowerStateLocked() {
        if (!mPendingUpdatePowerStateLocked) {
            mPendingUpdatePowerStateLocked = true;
            Message msg = mHandler.obtainMessage(MSG_UPDATE_POWER_STATE);
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    private void initialize() {
        final Executor executor = AsyncTask.THREAD_POOL_EXECUTOR;
        Display display = DisplayManager.getInstance().getRealDisplay(Display.DEFAULT_DISPLAY);
        mPowerState = new DisplayPowerState(new ElectronBeam(display),
                new PhotonicModulator(executor,
                        mLights.getLight(LightsService.LIGHT_ID_BACKLIGHT),
                        mSuspendBlocker));

        mElectronBeamOnAnimator = ObjectAnimator.ofFloat(
                mPowerState, DisplayPowerState.ELECTRON_BEAM_LEVEL, 0.0f, 1.0f);
        mElectronBeamOnAnimator.setDuration(ELECTRON_BEAM_ON_ANIMATION_DURATION_MILLIS);
        mElectronBeamOnAnimator.addListener(mAnimatorListener);

        mElectronBeamOffAnimator = ObjectAnimator.ofFloat(
                mPowerState, DisplayPowerState.ELECTRON_BEAM_LEVEL, 1.0f, 0.0f);
        mElectronBeamOffAnimator.setDuration(ELECTRON_BEAM_OFF_ANIMATION_DURATION_MILLIS);
        mElectronBeamOffAnimator.addListener(mAnimatorListener);

        mScreenBrightnessRampAnimator = new RampAnimator<DisplayPowerState>(
                mPowerState, DisplayPowerState.SCREEN_BRIGHTNESS);
    }

    private final Animator.AnimatorListener mAnimatorListener = new Animator.AnimatorListener() {
        @Override
        public void onAnimationStart(Animator animation) {
        }
        @Override
        public void onAnimationEnd(Animator animation) {
            sendUpdatePowerState();
        }
        @Override
        public void onAnimationRepeat(Animator animation) {
        }
        @Override
        public void onAnimationCancel(Animator animation) {
        }
    };

    private void updatePowerState() {
        // Update the power state request.
        final boolean mustNotify;
        boolean mustInitialize = false;
        synchronized (mLock) {
            mPendingUpdatePowerStateLocked = false;
            if (mPendingRequestLocked == null) {
                return; // wait until first actual power request
            }

            if (mPowerRequest == null) {
                mPowerRequest = new DisplayPowerRequest(mPendingRequestLocked);
                mWaitingForNegativeProximity = mPendingWaitForNegativeProximityLocked;
                mPendingWaitForNegativeProximityLocked = false;
                mPendingRequestChangedLocked = false;
                mustInitialize = true;
            } else if (mPendingRequestChangedLocked) {
                mPowerRequest.copyFrom(mPendingRequestLocked);
                mWaitingForNegativeProximity |= mPendingWaitForNegativeProximityLocked;
                mPendingWaitForNegativeProximityLocked = false;
                mPendingRequestChangedLocked = false;
                mDisplayReadyLocked = false;
            }

            mustNotify = !mDisplayReadyLocked;
        }

        // Initialize things the first time the power state is changed.
        if (mustInitialize) {
            initialize();
        }

        // Apply the proximity sensor.
        if (mProximitySensor != null) {
            if (mPowerRequest.useProximitySensor
                    && mPowerRequest.screenState != DisplayPowerRequest.SCREEN_STATE_OFF) {
                setProximitySensorEnabled(true);
                if (!mScreenOffBecauseOfProximity
                        && mProximity == PROXIMITY_POSITIVE) {
                    mScreenOffBecauseOfProximity = true;
                    setScreenOn(false);
                }
            } else if (mWaitingForNegativeProximity
                    && mScreenOffBecauseOfProximity
                    && mProximity == PROXIMITY_POSITIVE
                    && mPowerRequest.screenState != DisplayPowerRequest.SCREEN_STATE_OFF) {
                setProximitySensorEnabled(true);
            } else {
                setProximitySensorEnabled(false);
                mWaitingForNegativeProximity = false;
            }
            if (mScreenOffBecauseOfProximity
                    && mProximity != PROXIMITY_POSITIVE) {
                mScreenOffBecauseOfProximity = false;
                setScreenOn(true);
                sendOnProximityNegative();
            }
        } else {
            mWaitingForNegativeProximity = false;
        }

        // Turn on the light sensor if needed.
        if (mLightSensor != null) {
            setLightSensorEnabled(mPowerRequest.useAutoBrightness
                    && wantScreenOn(mPowerRequest.screenState));
        }

        // Set the screen brightness.
        if (mPowerRequest.screenState == DisplayPowerRequest.SCREEN_STATE_DIM) {
            // Screen is dimmed.  Overrides everything else.
            animateScreenBrightness(mScreenBrightnessDimConfig, BRIGHTNESS_RAMP_RATE_FAST);
            mUsingScreenAutoBrightness = false;
        } else if (mPowerRequest.screenState == DisplayPowerRequest.SCREEN_STATE_BRIGHT) {
            if (mScreenAutoBrightness >= 0 && mLightSensorEnabled) {
                // Use current auto-brightness value.
                animateScreenBrightness(
                        Math.max(mScreenAutoBrightness, mScreenBrightnessDimConfig),
                        mUsingScreenAutoBrightness ? BRIGHTNESS_RAMP_RATE_SLOW :
                                BRIGHTNESS_RAMP_RATE_FAST);
                mUsingScreenAutoBrightness = true;
            } else {
                // Light sensor is disabled or not ready yet.
                // Use the current brightness setting from the request, which is expected
                // provide a nominal default value for the case where auto-brightness
                // is not ready yet.
                animateScreenBrightness(
                        Math.max(mPowerRequest.screenBrightness, mScreenBrightnessDimConfig),
                        BRIGHTNESS_RAMP_RATE_FAST);
                mUsingScreenAutoBrightness = false;
            }
        } else {
            // Screen is off.  Don't bother changing the brightness.
            mUsingScreenAutoBrightness = false;
        }

        // Animate the screen on or off.
        if (!mScreenOffBecauseOfProximity) {
            if (wantScreenOn(mPowerRequest.screenState)) {
                // Want screen on.
                // Wait for previous off animation to complete beforehand.
                // It is relatively short but if we cancel it and switch to the
                // on animation immediately then the results are pretty ugly.
                if (!mElectronBeamOffAnimator.isStarted()) {
                    setScreenOn(true);
                    if (USE_ELECTRON_BEAM_ON_ANIMATION) {
                        if (!mElectronBeamOnAnimator.isStarted()) {
                            if (mPowerState.getElectronBeamLevel() == 1.0f) {
                                mPowerState.dismissElectronBeam();
                            } else if (mPowerState.prepareElectronBeam(true)) {
                                mElectronBeamOnAnimator.start();
                            } else {
                                mElectronBeamOnAnimator.end();
                            }
                        }
                    } else {
                        mPowerState.setElectronBeamLevel(1.0f);
                        mPowerState.dismissElectronBeam();
                    }
                }
            } else {
                // Want screen off.
                // Wait for previous on animation to complete beforehand.
                if (!mElectronBeamOnAnimator.isStarted()) {
                    if (!mElectronBeamOffAnimator.isStarted()) {
                        if (mPowerState.getElectronBeamLevel() == 0.0f) {
                            setScreenOn(false);
                        } else if (mPowerState.prepareElectronBeam(false)
                                && mPowerState.isScreenOn()) {
                            mElectronBeamOffAnimator.start();
                        } else {
                            mElectronBeamOffAnimator.end();
                        }
                    }
                }
            }
        }

        // Report whether the display is ready for use.
        // We mostly care about the screen state here, ignoring brightness changes
        // which will be handled asynchronously.
        if (mustNotify
                && !mElectronBeamOnAnimator.isStarted()
                && !mElectronBeamOffAnimator.isStarted()
                && mPowerState.waitUntilClean(mCleanListener)) {
            synchronized (mLock) {
                if (!mPendingRequestChangedLocked) {
                    mDisplayReadyLocked = true;
                }
            }
            sendOnStateChanged();
        }
    }

    private void setScreenOn(boolean on) {
        if (!mPowerState.isScreenOn() == on) {
            mPowerState.setScreenOn(on);
            if (on) {
                mNotifier.onScreenOn();
            } else {
                mNotifier.onScreenOff();
            }
        }
    }

    private void animateScreenBrightness(int target, int rate) {
        if (mScreenBrightnessRampAnimator.animateTo(target, rate)) {
            mNotifier.onScreenBrightness(target);
        }
    }

    private final Runnable mCleanListener = new Runnable() {
        @Override
        public void run() {
            sendUpdatePowerState();
        }
    };

    private void setProximitySensorEnabled(boolean enable) {
        if (enable) {
            if (!mProximitySensorEnabled) {
                mProximitySensorEnabled = true;
                mPendingProximity = PROXIMITY_UNKNOWN;
                mSensorManager.registerListener(mProximitySensorListener, mProximitySensor,
                        SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            }
        } else {
            if (mProximitySensorEnabled) {
                mProximitySensorEnabled = false;
                mProximity = PROXIMITY_UNKNOWN;
                mHandler.removeMessages(MSG_PROXIMITY_SENSOR_DEBOUNCED);
                mSensorManager.unregisterListener(mProximitySensorListener);
            }
        }
    }

    private void handleProximitySensorEvent(long time, boolean positive) {
        if (mPendingProximity == PROXIMITY_NEGATIVE && !positive) {
            return; // no change
        }
        if (mPendingProximity == PROXIMITY_POSITIVE && positive) {
            return; // no change
        }

        // Only accept a proximity sensor reading if it remains
        // stable for the entire debounce delay.
        mHandler.removeMessages(MSG_PROXIMITY_SENSOR_DEBOUNCED);
        mPendingProximity = positive ? PROXIMITY_POSITIVE : PROXIMITY_NEGATIVE;
        mPendingProximityDebounceTime = time + PROXIMITY_SENSOR_DEBOUNCE_DELAY;
        debounceProximitySensor();
    }

    private void debounceProximitySensor() {
        if (mPendingProximity != PROXIMITY_UNKNOWN) {
            final long now = SystemClock.uptimeMillis();
            if (mPendingProximityDebounceTime <= now) {
                mProximity = mPendingProximity;
                sendUpdatePowerState();
            } else {
                Message msg = mHandler.obtainMessage(MSG_PROXIMITY_SENSOR_DEBOUNCED);
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, mPendingProximityDebounceTime);
            }
        }
    }

    private void setLightSensorEnabled(boolean enable) {
        if (enable) {
            if (!mLightSensorEnabled) {
                mLightSensorEnabled = true;
                mLightSensorEnableTime = SystemClock.uptimeMillis();
                mSensorManager.registerListener(mLightSensorListener, mLightSensor,
                        LIGHT_SENSOR_RATE, mHandler);
            }
        } else {
            if (mLightSensorEnabled) {
                mLightSensorEnabled = false;
                mLightMeasurementValid = false;
                updateAutoBrightness(false);
                mHandler.removeMessages(MSG_LIGHT_SENSOR_DEBOUNCED);
                mSensorManager.unregisterListener(mLightSensorListener);
            }
        }
    }

    private void handleLightSensorEvent(long time, float lux) {
        // Take the first few readings during the warm-up period and apply them
        // immediately without debouncing.
        if (!mLightMeasurementValid
                || (time - mLightSensorEnableTime) < mLightSensorWarmUpTimeConfig) {
            mLightMeasurement = lux;
            mLightMeasurementValid = true;
            mRecentLightSamples = 0;
            updateAutoBrightness(true);
        }

        // Update our moving average.
        if (lux != mLightMeasurement && (mRecentLightSamples == 0
                || (lux < mLightMeasurement && mRecentLightBrightening)
                || (lux > mLightMeasurement && !mRecentLightBrightening))) {
            // If the newest light sample doesn't seem to be going in the
            // same general direction as recent samples, then start over.
            setRecentLight(time, lux, lux > mLightMeasurement);
        } else if (mRecentLightSamples >= 1) {
            // Add the newest light sample to the moving average.
            accumulateRecentLight(time, lux);
        }
        if (DEBUG) {
            Slog.d(TAG, "handleLightSensorEvent: lux=" + lux
                    + ", mLightMeasurementValid=" + mLightMeasurementValid
                    + ", mLightMeasurement=" + mLightMeasurement
                    + ", mRecentLightSamples=" + mRecentLightSamples
                    + ", mRecentLightAverage=" + mRecentLightAverage
                    + ", mRecentLightBrightening=" + mRecentLightBrightening
                    + ", mRecentLightTimeConstant=" + mRecentLightTimeConstant
                    + ", mFirstRecentLightSampleTime="
                            + TimeUtils.formatUptime(mFirstRecentLightSampleTime)
                    + ", mPendingLightSensorDebounceTime="
                            + TimeUtils.formatUptime(mPendingLightSensorDebounceTime));
        }

        // Debounce.
        mHandler.removeMessages(MSG_LIGHT_SENSOR_DEBOUNCED);
        debounceLightSensor();
    }

    private void setRecentLight(long time, float lux, boolean brightening) {
        mRecentLightBrightening = brightening;
        mRecentLightTimeConstant = brightening ?
                BRIGHTENING_LIGHT_TIME_CONSTANT : DIMMING_LIGHT_TIME_CONSTANT;
        mRecentLightSamples = 1;
        mRecentLightAverage = lux;
        mLastLightSample = lux;
        mLastLightSampleTime = time;
        mFirstRecentLightSampleTime = time;
        mPendingLightSensorDebounceTime = time + (brightening ?
                BRIGHTENING_LIGHT_DEBOUNCE : DIMMING_LIGHT_DEBOUNCE);
    }

    private void accumulateRecentLight(long time, float lux) {
        final long timeDelta = time - mLastLightSampleTime;
        mRecentLightSamples += 1;
        mRecentLightAverage += (lux - mRecentLightAverage) *
                timeDelta / (mRecentLightTimeConstant + timeDelta);
        mLastLightSample = lux;
        mLastLightSampleTime = time;
    }

    private void debounceLightSensor() {
        if (mLightMeasurementValid && mRecentLightSamples >= 1) {
            final long now = SystemClock.uptimeMillis();
            if (mPendingLightSensorDebounceTime <= now) {
                accumulateRecentLight(now, mLastLightSample);
                mLightMeasurement = mRecentLightAverage;

                if (DEBUG) {
                    Slog.d(TAG, "debounceLightSensor: Accepted new measurement "
                            + mLightMeasurement + " after "
                            + (now - mFirstRecentLightSampleTime) + " ms based on "
                            + mRecentLightSamples + " recent samples.");
                }

                updateAutoBrightness(true);

                // Now that we have debounced the light sensor data, we have the
                // option of either leaving the sensor in a debounced state or
                // restarting the debounce cycle by setting mRecentLightSamples to 0.
                //
                // If we leave the sensor debounced, then new average light measurements
                // may be accepted immediately as long as they are trending in the same
                // direction as they were before.  If the measurements start
                // jittering or trending in the opposite direction then the debounce
                // cycle will automatically be restarted.  The benefit is that the
                // auto-brightness control can be more responsive to changes over a
                // broad range.
                //
                // For now, we choose to be more responsive and leave the following line
                // commented out.
                //
                // mRecentLightSamples = 0;
            } else {
                Message msg = mHandler.obtainMessage(MSG_LIGHT_SENSOR_DEBOUNCED);
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, mPendingLightSensorDebounceTime);
            }
        }
    }

    private void updateAutoBrightness(boolean sendUpdate) {
        if (!mLightMeasurementValid) {
            return;
        }

        final int newScreenAutoBrightness = interpolateBrightness(
                mScreenAutoBrightnessSpline, mLightMeasurement);
        if (mScreenAutoBrightness != newScreenAutoBrightness) {
            if (DEBUG) {
                Slog.d(TAG, "updateAutoBrightness: mScreenAutoBrightness="
                        + mScreenAutoBrightness + "newScreenAutoBrightness="
                        + newScreenAutoBrightness);
            }

            mScreenAutoBrightness = newScreenAutoBrightness;
            if (sendUpdate) {
                sendUpdatePowerState();
            }
        }
    }

    private static int interpolateBrightness(Spline spline, float lux) {
        return Math.min(255, Math.max(0, (int)Math.round(spline.interpolate(lux))));
    }

    private void sendOnStateChanged() {
        mCallbackHandler.post(mOnStateChangedRunnable);
    }

    private final Runnable mOnStateChangedRunnable = new Runnable() {
        @Override
        public void run() {
            mCallbacks.onStateChanged();
        }
    };

    private void sendOnProximityNegative() {
        mCallbackHandler.post(mOnProximityNegativeRunnable);
    }

    private final Runnable mOnProximityNegativeRunnable = new Runnable() {
        @Override
        public void run() {
            mCallbacks.onProximityNegative();
        }
    };

    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println();
            pw.println("Display Controller Locked State:");
            pw.println("  mDisplayReadyLocked=" + mDisplayReadyLocked);
            pw.println("  mPendingRequestLocked=" + mPendingRequestLocked);
            pw.println("  mPendingRequestChangedLocked=" + mPendingRequestChangedLocked);
            pw.println("  mPendingWaitForNegativeProximityLocked="
                    + mPendingWaitForNegativeProximityLocked);
            pw.println("  mPendingUpdatePowerStateLocked=" + mPendingUpdatePowerStateLocked);
        }

        pw.println();
        pw.println("Display Controller Configuration:");
        pw.println("  mScreenBrightnessDimConfig=" + mScreenBrightnessDimConfig);
        pw.println("  mUseSoftwareAutoBrightnessConfig="
                + mUseSoftwareAutoBrightnessConfig);
        pw.println("  mScreenAutoBrightnessSpline=" + mScreenAutoBrightnessSpline);
        pw.println("  mLightSensorWarmUpTimeConfig=" + mLightSensorWarmUpTimeConfig);

        if (Looper.myLooper() == mHandler.getLooper()) {
            dumpLocal(pw);
        } else {
            final StringWriter out = new StringWriter();
            final CountDownLatch latch = new CountDownLatch(1);
            Message msg = Message.obtain(mHandler,  new Runnable() {
                @Override
                public void run() {
                    PrintWriter localpw = new PrintWriter(out);
                    try {
                        dumpLocal(localpw);
                    } finally {
                        localpw.flush();
                        latch.countDown();
                    }
                }
            });
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
            try {
                latch.await();
                pw.print(out.toString());
            } catch (InterruptedException ex) {
                pw.println();
                pw.println("Failed to dump thread state due to interrupted exception!");
            }
        }
    }

    private void dumpLocal(PrintWriter pw) {
        pw.println();
        pw.println("Display Controller Thread State:");
        pw.println("  mPowerRequest=" + mPowerRequest);
        pw.println("  mWaitingForNegativeProximity=" + mWaitingForNegativeProximity);

        pw.println("  mProximitySensor=" + mProximitySensor);
        pw.println("  mProximitySensorEnabled=" + mProximitySensorEnabled);
        pw.println("  mProximityThreshold=" + mProximityThreshold);
        pw.println("  mProximity=" + proximityToString(mProximity));
        pw.println("  mPendingProximity=" + proximityToString(mPendingProximity));
        pw.println("  mPendingProximityDebounceTime="
                + TimeUtils.formatUptime(mPendingProximityDebounceTime));
        pw.println("  mScreenOffBecauseOfProximity=" + mScreenOffBecauseOfProximity);

        pw.println("  mLightSensor=" + mLightSensor);
        pw.println("  mLightSensorEnabled=" + mLightSensorEnabled);
        pw.println("  mLightSensorEnableTime="
                + TimeUtils.formatUptime(mLightSensorEnableTime));
        pw.println("  mLightMeasurement=" + mLightMeasurement);
        pw.println("  mLightMeasurementValid=" + mLightMeasurementValid);
        pw.println("  mLastLightSample=" + mLastLightSample);
        pw.println("  mLastLightSampleTime="
                + TimeUtils.formatUptime(mLastLightSampleTime));
        pw.println("  mRecentLightSamples=" + mRecentLightSamples);
        pw.println("  mRecentLightAverage=" + mRecentLightAverage);
        pw.println("  mRecentLightBrightening=" + mRecentLightBrightening);
        pw.println("  mRecentLightTimeConstant=" + mRecentLightTimeConstant);
        pw.println("  mFirstRecentLightSampleTime="
                + TimeUtils.formatUptime(mFirstRecentLightSampleTime));
        pw.println("  mPendingLightSensorDebounceTime="
                + TimeUtils.formatUptime(mPendingLightSensorDebounceTime));
        pw.println("  mScreenAutoBrightness=" + mScreenAutoBrightness);
        pw.println("  mUsingScreenAutoBrightness=" + mUsingScreenAutoBrightness);

        if (mElectronBeamOnAnimator != null) {
            pw.println("  mElectronBeamOnAnimator.isStarted()=" +
                    mElectronBeamOnAnimator.isStarted());
        }
        if (mElectronBeamOffAnimator != null) {
            pw.println("  mElectronBeamOffAnimator.isStarted()=" +
                    mElectronBeamOffAnimator.isStarted());
        }

        if (mPowerState != null) {
            mPowerState.dump(pw);
        }
    }

    private static String proximityToString(int state) {
        switch (state) {
            case PROXIMITY_UNKNOWN:
                return "Unknown";
            case PROXIMITY_NEGATIVE:
                return "Negative";
            case PROXIMITY_POSITIVE:
                return "Positive";
            default:
                return Integer.toString(state);
        }
    }

    private static boolean wantScreenOn(int state) {
        switch (state) {
            case DisplayPowerRequest.SCREEN_STATE_BRIGHT:
            case DisplayPowerRequest.SCREEN_STATE_DIM:
                return true;
        }
        return false;
    }

    /**
     * Asynchronous callbacks from the power controller to the power manager service.
     */
    public interface Callbacks {
        void onStateChanged();
        void onProximityNegative();
    }

    private final class DisplayControllerHandler extends Handler {
        public DisplayControllerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_POWER_STATE:
                    updatePowerState();
                    break;

                case MSG_PROXIMITY_SENSOR_DEBOUNCED:
                    debounceProximitySensor();
                    break;

                case MSG_LIGHT_SENSOR_DEBOUNCED:
                    debounceLightSensor();
                    break;
            }
        }
    }

    private final SensorEventListener mProximitySensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mProximitySensorEnabled) {
                final long time = SystemClock.uptimeMillis();
                final float distance = event.values[0];
                boolean positive = distance >= 0.0f && distance < mProximityThreshold;
                handleProximitySensorEvent(time, positive);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }
    };

    private final SensorEventListener mLightSensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mLightSensorEnabled) {
                final long time = SystemClock.uptimeMillis();
                final float lux = event.values[0];
                handleLightSensorEvent(time, lux);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not used.
        }
    };
}
