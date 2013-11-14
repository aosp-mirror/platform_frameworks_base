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

import com.android.server.lights.LightsManager;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.FloatMath;
import android.util.MathUtils;
import android.util.Slog;
import android.util.Spline;
import android.util.TimeUtils;

import java.io.PrintWriter;
import java.util.Arrays;

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

    private static boolean DEBUG = true;
    private static final boolean DEBUG_PRETEND_PROXIMITY_SENSOR_ABSENT = false;
    private static final boolean DEBUG_PRETEND_LIGHT_SENSOR_ABSENT = false;

    // If true, uses the electron beam on animation.
    // We might want to turn this off if we cannot get a guarantee that the screen
    // actually turns on and starts showing new content after the call to set the
    // screen state returns.  Playing the animation can also be somewhat slow.
    private static final boolean USE_ELECTRON_BEAM_ON_ANIMATION = false;

    // If true, enables the use of the screen auto-brightness adjustment setting.
    private static final boolean USE_SCREEN_AUTO_BRIGHTNESS_ADJUSTMENT =
            PowerManager.useScreenAutoBrightnessAdjustmentFeature();

    // The maximum range of gamma adjustment possible using the screen
    // auto-brightness adjustment setting.
    private static final float SCREEN_AUTO_BRIGHTNESS_ADJUSTMENT_MAX_GAMMA = 3.0f;

    // The minimum reduction in brightness when dimmed.
    private static final int SCREEN_DIM_MINIMUM_REDUCTION = 10;

    // If true, enables the use of the current time as an auto-brightness adjustment.
    // The basic idea here is to expand the dynamic range of auto-brightness
    // when it is especially dark outside.  The light sensor tends to perform
    // poorly at low light levels so we compensate for it by making an
    // assumption about the environment.
    private static final boolean USE_TWILIGHT_ADJUSTMENT =
            PowerManager.useTwilightAdjustmentFeature();

    // Specifies the maximum magnitude of the time of day adjustment.
    private static final float TWILIGHT_ADJUSTMENT_MAX_GAMMA = 1.5f;

    // The amount of time after or before sunrise over which to start adjusting
    // the gamma.  We want the change to happen gradually so that it is below the
    // threshold of perceptibility and so that the adjustment has maximum effect
    // well after dusk.
    private static final long TWILIGHT_ADJUSTMENT_TIME = DateUtils.HOUR_IN_MILLIS * 2;

    private static final int ELECTRON_BEAM_ON_ANIMATION_DURATION_MILLIS = 250;
    private static final int ELECTRON_BEAM_OFF_ANIMATION_DURATION_MILLIS = 400;

    private static final int MSG_UPDATE_POWER_STATE = 1;
    private static final int MSG_PROXIMITY_SENSOR_DEBOUNCED = 2;
    private static final int MSG_UPDATE_AMBIENT_LUX = 3;

    private static final int PROXIMITY_UNKNOWN = -1;
    private static final int PROXIMITY_NEGATIVE = 0;
    private static final int PROXIMITY_POSITIVE = 1;

    // Proximity sensor debounce delay in milliseconds for positive or negative transitions.
    private static final int PROXIMITY_SENSOR_POSITIVE_DEBOUNCE_DELAY = 0;
    private static final int PROXIMITY_SENSOR_NEGATIVE_DEBOUNCE_DELAY = 250;

    // Trigger proximity if distance is less than 5 cm.
    private static final float TYPICAL_PROXIMITY_THRESHOLD = 5.0f;

    // Light sensor event rate in milliseconds.
    private static final int LIGHT_SENSOR_RATE_MILLIS = 1000;

    // Period of time in which to consider light samples in milliseconds.
    private static final int AMBIENT_LIGHT_HORIZON = 10000;

    private static final int WEIGHTING_INTERCEPT = AMBIENT_LIGHT_HORIZON;

    // Brightness animation ramp rate in brightness units per second.
    private static final int BRIGHTNESS_RAMP_RATE_FAST = 200;
    private static final int BRIGHTNESS_RAMP_RATE_SLOW = 40;

   // Stability requirements in milliseconds for accepting a new brightness level.  This is used
   // for debouncing the light sensor.  Different constants are used to debounce the light sensor
   // when adapting to brighter or darker environments.  This parameter controls how quickly
   // brightness changes occur in response to an observed change in light level that exceeds the
   // hysteresis threshold.
   private static final long BRIGHTENING_LIGHT_DEBOUNCE = 4000;
   private static final long DARKENING_LIGHT_DEBOUNCE = 8000;

    // Hysteresis constraints for brightening or darkening.
    // The recent lux must have changed by at least this fraction relative to the
    // current ambient lux before a change will be considered.
    private static final float BRIGHTENING_LIGHT_HYSTERESIS = 0.10f;
    private static final float DARKENING_LIGHT_HYSTERESIS = 0.20f;

    private final Object mLock = new Object();

    // Notifier for sending asynchronous notifications.
    private final Notifier mNotifier;

    // The display suspend blocker.
    // Held while there are pending state change notifications.
    private final SuspendBlocker mDisplaySuspendBlocker;

    // The display blanker.
    private final DisplayBlanker mDisplayBlanker;

    // Our handler.
    private final DisplayControllerHandler mHandler;

    // Asynchronous callbacks into the power manager service.
    // Only invoked from the handler thread while no locks are held.
    private final Callbacks mCallbacks;
    private Handler mCallbackHandler;

    // The lights service.
    private final LightsManager mLights;

    // The twilight service.
    private final TwilightManager mTwilight;

    // The sensor manager.
    private final SensorManager mSensorManager;

    // The proximity sensor, or null if not available or needed.
    private Sensor mProximitySensor;

    // The light sensor, or null if not available or needed.
    private Sensor mLightSensor;

    // The dim screen brightness.
    private final int mScreenBrightnessDimConfig;

    // The minimum allowed brightness.
    private final int mScreenBrightnessRangeMinimum;

    // The maximum allowed brightness.
    private final int mScreenBrightnessRangeMaximum;

    // True if auto-brightness should be used.
    private boolean mUseSoftwareAutoBrightnessConfig;

    // The auto-brightness spline adjustment.
    // The brightness values have been scaled to a range of 0..1.
    private Spline mScreenAutoBrightnessSpline;

    // Amount of time to delay auto-brightness after screen on while waiting for
    // the light sensor to warm-up in milliseconds.
    // May be 0 if no warm-up is required.
    private int mLightSensorWarmUpTimeConfig;

    // True if we should fade the screen while turning it off, false if we should play
    // a stylish electron beam animation instead.
    private boolean mElectronBeamFadesConfig;

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
    private long mPendingProximityDebounceTime = -1; // -1 if fully debounced

    // True if the screen was turned off because of the proximity sensor.
    // When the screen turns on again, we report user activity to the power manager.
    private boolean mScreenOffBecauseOfProximity;

    // True if the screen on is being blocked.
    private boolean mScreenOnWasBlocked;

    // The elapsed real time when the screen on was blocked.
    private long mScreenOnBlockStartRealTime;

    // Set to true if the light sensor is enabled.
    private boolean mLightSensorEnabled;

    // The time when the light sensor was enabled.
    private long mLightSensorEnableTime;

    // The currently accepted nominal ambient light level.
    private float mAmbientLux;

    // True if mAmbientLux holds a valid value.
    private boolean mAmbientLuxValid;

    // The ambient light level threshold at which to brighten or darken the screen.
    private float mBrighteningLuxThreshold;
    private float mDarkeningLuxThreshold;

    // The most recent light sample.
    private float mLastObservedLux;

    // The time of the most light recent sample.
    private long mLastObservedLuxTime;

    // The number of light samples collected since the light sensor was enabled.
    private int mRecentLightSamples;

    private AmbientLightRingBuffer mAmbientLightRingBuffer;

    // The screen brightness level that has been chosen by the auto-brightness
    // algorithm.  The actual brightness should ramp towards this value.
    // We preserve this value even when we stop using the light sensor so
    // that we can quickly revert to the previous auto-brightness level
    // while the light sensor warms up.
    // Use -1 if there is no current auto-brightness value available.
    private int mScreenAutoBrightness = -1;

    // The last screen auto-brightness gamma.  (For printing in dump() only.)
    private float mLastScreenAutoBrightnessGamma = 1.0f;

    // True if the screen auto-brightness value is actually being used to
    // set the display brightness.
    private boolean mUsingScreenAutoBrightness;

    // Animators.
    private ObjectAnimator mElectronBeamOnAnimator;
    private ObjectAnimator mElectronBeamOffAnimator;
    private RampAnimator<DisplayPowerState> mScreenBrightnessRampAnimator;

    // Twilight changed.  We might recalculate auto-brightness values.
    private boolean mTwilightChanged;

    /**
     * Creates the display power controller.
     */
    public DisplayPowerController(Looper looper, Context context, Notifier notifier,
            LightsManager lights, TwilightManager twilight, SensorManager sensorManager,
            SuspendBlocker displaySuspendBlocker, DisplayBlanker displayBlanker,
            Callbacks callbacks, Handler callbackHandler) {
        mHandler = new DisplayControllerHandler(looper);
        mNotifier = notifier;
        mDisplaySuspendBlocker = displaySuspendBlocker;
        mDisplayBlanker = displayBlanker;
        mCallbacks = callbacks;
        mCallbackHandler = callbackHandler;

        mLights = lights;
        mTwilight = twilight;
        mSensorManager = sensorManager;

        final Resources resources = context.getResources();

        mScreenBrightnessDimConfig = clampAbsoluteBrightness(resources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessDim));

        int screenBrightnessMinimum = Math.min(resources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMinimum),
                mScreenBrightnessDimConfig);

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
            } else {
                if (screenBrightness[0] < screenBrightnessMinimum) {
                    screenBrightnessMinimum = screenBrightness[0];
                }
            }

            mLightSensorWarmUpTimeConfig = resources.getInteger(
                    com.android.internal.R.integer.config_lightSensorWarmupTime);
        }

        mAmbientLightRingBuffer = new AmbientLightRingBuffer();

        mScreenBrightnessRangeMinimum = clampAbsoluteBrightness(screenBrightnessMinimum);
        mScreenBrightnessRangeMaximum = PowerManager.BRIGHTNESS_ON;

        mElectronBeamFadesConfig = resources.getBoolean(
                com.android.internal.R.bool.config_animateScreenLights);

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

        if (mUseSoftwareAutoBrightnessConfig && USE_TWILIGHT_ADJUSTMENT) {
            mTwilight.registerListener(mTwilightListener, mHandler);
        }
    }

    private static Spline createAutoBrightnessSpline(int[] lux, int[] brightness) {
        try {
            final int n = brightness.length;
            float[] x = new float[n];
            float[] y = new float[n];
            y[0] = normalizeAbsoluteBrightness(brightness[0]);
            for (int i = 1; i < n; i++) {
                x[i] = lux[i - 1];
                y[i] = normalizeAbsoluteBrightness(brightness[i]);
            }

            Spline spline = Spline.createMonotoneCubicSpline(x, y);
            if (DEBUG) {
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
        mPowerState = new DisplayPowerState(new ElectronBeam(), mDisplayBlanker,
                mLights.getLight(LightsManager.LIGHT_ID_BACKLIGHT));

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

        // Initialize screen on state.
        if (mPowerState.isScreenOn()) {
            mNotifier.onScreenOn();
        } else {
            mNotifier.onScreenOff();
        }
        mNotifier.onScreenBrightness(mPowerState.getScreenBrightness());
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
        boolean updateAutoBrightness = mTwilightChanged;
        boolean wasDim = false;
        mTwilightChanged = false;

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
                if (mPowerRequest.screenAutoBrightnessAdjustment
                        != mPendingRequestLocked.screenAutoBrightnessAdjustment) {
                    updateAutoBrightness = true;
                }
                wasDim = (mPowerRequest.screenState == DisplayPowerRequest.SCREEN_STATE_DIM);
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
                    sendOnProximityPositiveWithWakelock();
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
                sendOnProximityNegativeWithWakelock();
            }
        } else {
            mWaitingForNegativeProximity = false;
        }

        // Turn on the light sensor if needed.
        if (mLightSensor != null) {
            setLightSensorEnabled(mPowerRequest.useAutoBrightness
                    && wantScreenOn(mPowerRequest.screenState), updateAutoBrightness);
        }

        // Set the screen brightness.
        if (wantScreenOn(mPowerRequest.screenState)) {
            int target;
            boolean slow;
            if (mScreenAutoBrightness >= 0 && mLightSensorEnabled) {
                // Use current auto-brightness value.
                target = mScreenAutoBrightness;
                slow = mUsingScreenAutoBrightness;
                mUsingScreenAutoBrightness = true;
            } else {
                // Light sensor is disabled or not ready yet.
                // Use the current brightness setting from the request, which is expected
                // provide a nominal default value for the case where auto-brightness
                // is not ready yet.
                target = mPowerRequest.screenBrightness;
                slow = false;
                mUsingScreenAutoBrightness = false;
            }
            if (mPowerRequest.screenState == DisplayPowerRequest.SCREEN_STATE_DIM) {
                // Dim quickly by at least some minimum amount.
                target = Math.min(target - SCREEN_DIM_MINIMUM_REDUCTION,
                        mScreenBrightnessDimConfig);
                slow = false;
            } else if (wasDim) {
                // Brighten quickly.
                slow = false;
            }
            animateScreenBrightness(clampScreenBrightness(target),
                    slow ? BRIGHTNESS_RAMP_RATE_SLOW : BRIGHTNESS_RAMP_RATE_FAST);
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
                    // Turn the screen on.  The contents of the screen may not yet
                    // be visible if the electron beam has not been dismissed because
                    // its last frame of animation is solid black.
                    setScreenOn(true);

                    if (mPowerRequest.blockScreenOn
                            && mPowerState.getElectronBeamLevel() == 0.0f) {
                        blockScreenOn();
                    } else {
                        unblockScreenOn();
                        if (USE_ELECTRON_BEAM_ON_ANIMATION) {
                            if (!mElectronBeamOnAnimator.isStarted()) {
                                if (mPowerState.getElectronBeamLevel() == 1.0f) {
                                    mPowerState.dismissElectronBeam();
                                } else if (mPowerState.prepareElectronBeam(
                                        mElectronBeamFadesConfig ?
                                                ElectronBeam.MODE_FADE :
                                                        ElectronBeam.MODE_WARM_UP)) {
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
                }
            } else {
                // Want screen off.
                // Wait for previous on animation to complete beforehand.
                if (!mElectronBeamOnAnimator.isStarted()) {
                    if (!mElectronBeamOffAnimator.isStarted()) {
                        if (mPowerState.getElectronBeamLevel() == 0.0f) {
                            setScreenOn(false);
                        } else if (mPowerState.prepareElectronBeam(
                                mElectronBeamFadesConfig ?
                                        ElectronBeam.MODE_FADE :
                                                ElectronBeam.MODE_COOL_DOWN)
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
                && !mScreenOnWasBlocked
                && !mElectronBeamOnAnimator.isStarted()
                && !mElectronBeamOffAnimator.isStarted()
                && mPowerState.waitUntilClean(mCleanListener)) {
            synchronized (mLock) {
                if (!mPendingRequestChangedLocked) {
                    mDisplayReadyLocked = true;

                    if (DEBUG) {
                        Slog.d(TAG, "Display ready!");
                    }
                }
            }
            sendOnStateChangedWithWakelock();
        }
    }

    private void blockScreenOn() {
        if (!mScreenOnWasBlocked) {
            mScreenOnWasBlocked = true;
            if (DEBUG) {
                Slog.d(TAG, "Blocked screen on.");
                mScreenOnBlockStartRealTime = SystemClock.elapsedRealtime();
            }
        }
    }

    private void unblockScreenOn() {
        if (mScreenOnWasBlocked) {
            mScreenOnWasBlocked = false;
            if (DEBUG) {
                Slog.d(TAG, "Unblocked screen on after " +
                        (SystemClock.elapsedRealtime() - mScreenOnBlockStartRealTime) + " ms");
            }
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

    private int clampScreenBrightness(int value) {
        return clamp(value, mScreenBrightnessRangeMinimum, mScreenBrightnessRangeMaximum);
    }

    private static int clampAbsoluteBrightness(int value) {
        return clamp(value, PowerManager.BRIGHTNESS_OFF, PowerManager.BRIGHTNESS_ON);
    }

    private static int clamp(int value, int min, int max) {
        if (value <= min) {
            return min;
        }
        if (value >= max) {
            return max;
        }
        return value;
    }

    private static float normalizeAbsoluteBrightness(int value) {
        return (float)clampAbsoluteBrightness(value) / PowerManager.BRIGHTNESS_ON;
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
                // Register the listener.
                // Proximity sensor state already cleared initially.
                mProximitySensorEnabled = true;
                mSensorManager.registerListener(mProximitySensorListener, mProximitySensor,
                        SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            }
        } else {
            if (mProximitySensorEnabled) {
                // Unregister the listener.
                // Clear the proximity sensor state for next time.
                mProximitySensorEnabled = false;
                mProximity = PROXIMITY_UNKNOWN;
                mPendingProximity = PROXIMITY_UNKNOWN;
                mHandler.removeMessages(MSG_PROXIMITY_SENSOR_DEBOUNCED);
                mSensorManager.unregisterListener(mProximitySensorListener);
                clearPendingProximityDebounceTime(); // release wake lock (must be last)
            }
        }
    }

    private void handleProximitySensorEvent(long time, boolean positive) {
        if (mProximitySensorEnabled) {
            if (mPendingProximity == PROXIMITY_NEGATIVE && !positive) {
                return; // no change
            }
            if (mPendingProximity == PROXIMITY_POSITIVE && positive) {
                return; // no change
            }

            // Only accept a proximity sensor reading if it remains
            // stable for the entire debounce delay.  We hold a wake lock while
            // debouncing the sensor.
            mHandler.removeMessages(MSG_PROXIMITY_SENSOR_DEBOUNCED);
            if (positive) {
                mPendingProximity = PROXIMITY_POSITIVE;
                setPendingProximityDebounceTime(
                        time + PROXIMITY_SENSOR_POSITIVE_DEBOUNCE_DELAY); // acquire wake lock
            } else {
                mPendingProximity = PROXIMITY_NEGATIVE;
                setPendingProximityDebounceTime(
                        time + PROXIMITY_SENSOR_NEGATIVE_DEBOUNCE_DELAY); // acquire wake lock
            }

            // Debounce the new sensor reading.
            debounceProximitySensor();
        }
    }

    private void debounceProximitySensor() {
        if (mProximitySensorEnabled
                && mPendingProximity != PROXIMITY_UNKNOWN
                && mPendingProximityDebounceTime >= 0) {
            final long now = SystemClock.uptimeMillis();
            if (mPendingProximityDebounceTime <= now) {
                // Sensor reading accepted.  Apply the change then release the wake lock.
                mProximity = mPendingProximity;
                updatePowerState();
                clearPendingProximityDebounceTime(); // release wake lock (must be last)
            } else {
                // Need to wait a little longer.
                // Debounce again later.  We continue holding a wake lock while waiting.
                Message msg = mHandler.obtainMessage(MSG_PROXIMITY_SENSOR_DEBOUNCED);
                msg.setAsynchronous(true);
                mHandler.sendMessageAtTime(msg, mPendingProximityDebounceTime);
            }
        }
    }

    private void clearPendingProximityDebounceTime() {
        if (mPendingProximityDebounceTime >= 0) {
            mPendingProximityDebounceTime = -1;
            mDisplaySuspendBlocker.release(); // release wake lock
        }
    }

    private void setPendingProximityDebounceTime(long debounceTime) {
        if (mPendingProximityDebounceTime < 0) {
            mDisplaySuspendBlocker.acquire(); // acquire wake lock
        }
        mPendingProximityDebounceTime = debounceTime;
    }

    private void setLightSensorEnabled(boolean enable, boolean updateAutoBrightness) {
        if (enable) {
            if (!mLightSensorEnabled) {
                updateAutoBrightness = true;
                mLightSensorEnabled = true;
                mLightSensorEnableTime = SystemClock.uptimeMillis();
                mSensorManager.registerListener(mLightSensorListener, mLightSensor,
                        LIGHT_SENSOR_RATE_MILLIS * 1000, mHandler);
            }
        } else {
            if (mLightSensorEnabled) {
                mLightSensorEnabled = false;
                mAmbientLuxValid = false;
                mRecentLightSamples = 0;
                mAmbientLightRingBuffer.clear();
                mHandler.removeMessages(MSG_UPDATE_AMBIENT_LUX);
                mSensorManager.unregisterListener(mLightSensorListener);
            }
        }
        if (updateAutoBrightness) {
            updateAutoBrightness(false);
        }
    }

    private void handleLightSensorEvent(long time, float lux) {
        mHandler.removeMessages(MSG_UPDATE_AMBIENT_LUX);

        applyLightSensorMeasurement(time, lux);
        updateAmbientLux(time);
    }

    private void applyLightSensorMeasurement(long time, float lux) {
        mRecentLightSamples++;
        mAmbientLightRingBuffer.prune(time - AMBIENT_LIGHT_HORIZON);
        mAmbientLightRingBuffer.push(time, lux);

        // Remember this sample value.
        mLastObservedLux = lux;
        mLastObservedLuxTime = time;
    }

    private void setAmbientLux(float lux) {
        mAmbientLux = lux;
        mBrighteningLuxThreshold = mAmbientLux * (1.0f + BRIGHTENING_LIGHT_HYSTERESIS);
        mDarkeningLuxThreshold = mAmbientLux * (1.0f - DARKENING_LIGHT_HYSTERESIS);
    }

    private float calculateAmbientLux(long now) {
        final int N = mAmbientLightRingBuffer.size();
        if (N == 0) {
            Slog.e(TAG, "calculateAmbientLux: No ambient light readings available");
            return 0;
        }
        float sum = 0;
        float totalWeight = 0;
        long endTime = 1;
        for (int i = N - 1; i >= 0; i--) {
            long startTime = (mAmbientLightRingBuffer.getTime(i) - now);
            float weight = calculateWeight(startTime, endTime);
            if (DEBUG) {
                Slog.d(TAG, "calculateAmbientLux: [" +
                        (startTime) + ", " +
                        (endTime) + "]: " + weight);
            }
            totalWeight += weight;
            sum += mAmbientLightRingBuffer.getLux(i) * weight;
            endTime = startTime;
        }
        if (DEBUG) {
            Slog.d(TAG, "calculateAmbientLux: totalWeight=" + totalWeight +
                    ", newAmbientLux=" + (sum / totalWeight));
        }
        return sum / totalWeight;
    }

    private static float calculateWeight(long startDelta, long endDelta) {
        return weightIntegral(endDelta) - weightIntegral(startDelta);
    }

    // Calculates the integral of y = x + WEIGHTING_INTERCEPT from 0 to x
    private static float weightIntegral(long x) {
        return x * (x * 0.5f + WEIGHTING_INTERCEPT);
    }

    private long nextAmbientLightBrighteningTransition(long time) {
        final int N = mAmbientLightRingBuffer.size();
        for (int i = 0; i < N; i++) {
            if (mAmbientLightRingBuffer.getLux(i) <= mBrighteningLuxThreshold) {
                long nextCheck;
                if (i + 1 < N) {
                    nextCheck = mAmbientLightRingBuffer.getTime(i+1) + AMBIENT_LIGHT_HORIZON;
                } else {
                    nextCheck = time + BRIGHTENING_LIGHT_DEBOUNCE;
                }
                if (DEBUG) {
                    Slog.e(TAG, "{lux= " + mAmbientLightRingBuffer.getLux(i) +
                            ", time= " + mAmbientLightRingBuffer.getTime(i) +
                            "} is below brightening threshold, checking again in " +
                            TimeUtils.formatUptime(nextCheck));
                }
                return nextCheck;
            }
        }
        if (N == 0) {
            long nextCheck = time + BRIGHTENING_LIGHT_DEBOUNCE;
            if (DEBUG) {
                Slog.e(TAG, "No data, checking again in " + TimeUtils.formatUptime(nextCheck));
            }
            return nextCheck;
        }

        if (time - mAmbientLightRingBuffer.getTime(0) < BRIGHTENING_LIGHT_DEBOUNCE) {
            long nextCheck = mAmbientLightRingBuffer.getTime(0) + BRIGHTENING_LIGHT_DEBOUNCE;
            if (DEBUG) {
                Slog.e(TAG, "{lux= " + mAmbientLightRingBuffer.getLux(0) +
                        ", time= " + mAmbientLightRingBuffer.getTime(0) +
                        "} hasn't passed debounce threshold, checking again in " +
                        TimeUtils.formatUptime(nextCheck));
            }
            return nextCheck;
        }

        return 0;
    }

    private long nextAmbientLightDarkeningTransition(long time) {
        final int N = mAmbientLightRingBuffer.size();
        for (int i = 0; i < N; i ++) {
            if (mAmbientLightRingBuffer.getLux(i) >= mDarkeningLuxThreshold) {
                long nextCheck;
                if (i + 1 < N) {
                    nextCheck = mAmbientLightRingBuffer.getTime(i+1) + AMBIENT_LIGHT_HORIZON;
                } else {
                    nextCheck = time + DARKENING_LIGHT_DEBOUNCE;
                }
                if (DEBUG) {
                    Slog.e(TAG, "{lux= " + mAmbientLightRingBuffer.getLux(i) +
                            ", time= " + mAmbientLightRingBuffer.getTime(i) +
                            "} is above darkening threshold, checking again in " +
                            TimeUtils.formatUptime(nextCheck));
                }
                return nextCheck;
            }
        }
        if (N == 0) {
            long nextCheck = time + DARKENING_LIGHT_DEBOUNCE;
            if (DEBUG) {
                Slog.e(TAG, "No data, checking again in " + TimeUtils.formatUptime(nextCheck));
            }
            return nextCheck;
        }

        if (time - mAmbientLightRingBuffer.getTime(0) < DARKENING_LIGHT_DEBOUNCE) {
            long nextCheck = mAmbientLightRingBuffer.getTime(0) + DARKENING_LIGHT_DEBOUNCE;
            if (DEBUG) {
                Slog.e(TAG, "{lux= " + mAmbientLightRingBuffer.getLux(0) +
                        ", time= " + mAmbientLightRingBuffer.getTime(0) +
                        "} hasn't passed debounce threshold, checking again in " +
                        TimeUtils.formatUptime(nextCheck));
            }
            return nextCheck;
        }
        return 0;
    }

    private void updateAmbientLux() {
        long time = SystemClock.uptimeMillis();
        mAmbientLightRingBuffer.prune(time - AMBIENT_LIGHT_HORIZON);
        updateAmbientLux(time);
    }

    private void updateAmbientLux(long time) {
        // If the light sensor was just turned on then immediately update our initial
        // estimate of the current ambient light level.
        if (!mAmbientLuxValid) {
            final long timeWhenSensorWarmedUp =
                mLightSensorWarmUpTimeConfig + mLightSensorEnableTime;
            if (time < timeWhenSensorWarmedUp) {
                if (DEBUG) {
                    Slog.d(TAG, "updateAmbientLux: Sensor not  ready yet: "
                            + "time=" + time
                            + ", timeWhenSensorWarmedUp=" + timeWhenSensorWarmedUp);
                }
                mHandler.sendEmptyMessageAtTime(MSG_UPDATE_AMBIENT_LUX,
                        timeWhenSensorWarmedUp);
                return;
            }
            setAmbientLux(calculateAmbientLux(time));
            mAmbientLuxValid = true;
            if (DEBUG) {
                Slog.d(TAG, "updateAmbientLux: Initializing: "
                        + "mAmbientLightRingBuffer=" + mAmbientLightRingBuffer
                        + ", mAmbientLux=" + mAmbientLux);
            }
            updateAutoBrightness(true);
        }

        long nextBrightenTransition = nextAmbientLightBrighteningTransition(time);
        long nextDarkenTransition = nextAmbientLightDarkeningTransition(time);

        if (nextBrightenTransition == 0) {
            setAmbientLux(calculateAmbientLux(time));
            if (DEBUG) {
                Slog.d(TAG, "updateAmbientLux: Brightened: "
                        + "mBrighteningLuxThreshold=" + mBrighteningLuxThreshold
                        + ", mAmbientLightRingBuffer=" + mAmbientLightRingBuffer
                        + ", mAmbientLux=" + mAmbientLux);
            }
            updateAutoBrightness(true);
            nextBrightenTransition = nextAmbientLightBrighteningTransition(time);
        } else if (nextDarkenTransition == 0) {
            setAmbientLux(calculateAmbientLux(time));
            if (DEBUG) {
                Slog.d(TAG, "updateAmbientLux: Darkened: "
                        + "mDarkeningLuxThreshold=" + mDarkeningLuxThreshold
                        + ", mAmbientLightRingBuffer=" + mAmbientLightRingBuffer
                        + ", mAmbientLux=" + mAmbientLux);
            }
            updateAutoBrightness(true);
            nextDarkenTransition = nextAmbientLightDarkeningTransition(time);
        }
        long nextTransitionTime = Math.min(nextDarkenTransition, nextBrightenTransition);
        if (nextTransitionTime > time) {
            if (DEBUG) {
                Slog.d(TAG, "updateAmbientLux: Scheduling ambient lux update for "
                        + nextTransitionTime + TimeUtils.formatUptime(nextTransitionTime));
            }
            mHandler.sendEmptyMessageAtTime(MSG_UPDATE_AMBIENT_LUX, nextTransitionTime);
        }
    }

    private void updateAutoBrightness(boolean sendUpdate) {
        if (!mAmbientLuxValid) {
            return;
        }

        float value = mScreenAutoBrightnessSpline.interpolate(mAmbientLux);
        float gamma = 1.0f;

        if (USE_SCREEN_AUTO_BRIGHTNESS_ADJUSTMENT
                && mPowerRequest.screenAutoBrightnessAdjustment != 0.0f) {
            final float adjGamma = MathUtils.pow(SCREEN_AUTO_BRIGHTNESS_ADJUSTMENT_MAX_GAMMA,
                    Math.min(1.0f, Math.max(-1.0f,
                            -mPowerRequest.screenAutoBrightnessAdjustment)));
            gamma *= adjGamma;
            if (DEBUG) {
                Slog.d(TAG, "updateAutoBrightness: adjGamma=" + adjGamma);
            }
        }

        if (USE_TWILIGHT_ADJUSTMENT) {
            TwilightState state = mTwilight.getCurrentState();
            if (state != null && state.isNight()) {
                final long now = System.currentTimeMillis();
                final float earlyGamma =
                        getTwilightGamma(now, state.getYesterdaySunset(), state.getTodaySunrise());
                final float lateGamma =
                        getTwilightGamma(now, state.getTodaySunset(), state.getTomorrowSunrise());
                gamma *= earlyGamma * lateGamma;
                if (DEBUG) {
                    Slog.d(TAG, "updateAutoBrightness: earlyGamma=" + earlyGamma
                            + ", lateGamma=" + lateGamma);
                }
            }
        }

        if (gamma != 1.0f) {
            final float in = value;
            value = FloatMath.pow(value, gamma);
            if (DEBUG) {
                Slog.d(TAG, "updateAutoBrightness: gamma=" + gamma
                        + ", in=" + in + ", out=" + value);
            }
        }

        int newScreenAutoBrightness = clampScreenBrightness(
                Math.round(value * PowerManager.BRIGHTNESS_ON));
        if (mScreenAutoBrightness != newScreenAutoBrightness) {
            if (DEBUG) {
                Slog.d(TAG, "updateAutoBrightness: mScreenAutoBrightness="
                        + mScreenAutoBrightness + ", newScreenAutoBrightness="
                        + newScreenAutoBrightness);
            }

            mScreenAutoBrightness = newScreenAutoBrightness;
            mLastScreenAutoBrightnessGamma = gamma;
            if (sendUpdate) {
                sendUpdatePowerState();
            }
        }
    }

    private static float getTwilightGamma(long now, long lastSunset, long nextSunrise) {
        if (lastSunset < 0 || nextSunrise < 0
                || now < lastSunset || now > nextSunrise) {
            return 1.0f;
        }

        if (now < lastSunset + TWILIGHT_ADJUSTMENT_TIME) {
            return lerp(1.0f, TWILIGHT_ADJUSTMENT_MAX_GAMMA,
                    (float)(now - lastSunset) / TWILIGHT_ADJUSTMENT_TIME);
        }

        if (now > nextSunrise - TWILIGHT_ADJUSTMENT_TIME) {
            return lerp(1.0f, TWILIGHT_ADJUSTMENT_MAX_GAMMA,
                    (float)(nextSunrise - now) / TWILIGHT_ADJUSTMENT_TIME);
        }

        return TWILIGHT_ADJUSTMENT_MAX_GAMMA;
    }

    private static float lerp(float x, float y, float alpha) {
        return x + (y - x) * alpha;
    }

    private void sendOnStateChangedWithWakelock() {
        mDisplaySuspendBlocker.acquire();
        mCallbackHandler.post(mOnStateChangedRunnable);
    }

    private final Runnable mOnStateChangedRunnable = new Runnable() {
        @Override
        public void run() {
            mCallbacks.onStateChanged();
            mDisplaySuspendBlocker.release();
        }
    };

    private void sendOnProximityPositiveWithWakelock() {
        mDisplaySuspendBlocker.acquire();
        mCallbackHandler.post(mOnProximityPositiveRunnable);
    }

    private final Runnable mOnProximityPositiveRunnable = new Runnable() {
        @Override
        public void run() {
            mCallbacks.onProximityPositive();
            mDisplaySuspendBlocker.release();
        }
    };

    private void sendOnProximityNegativeWithWakelock() {
        mDisplaySuspendBlocker.acquire();
        mCallbackHandler.post(mOnProximityNegativeRunnable);
    }

    private final Runnable mOnProximityNegativeRunnable = new Runnable() {
        @Override
        public void run() {
            mCallbacks.onProximityNegative();
            mDisplaySuspendBlocker.release();
        }
    };

    public void dump(final PrintWriter pw) {
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
        pw.println("  mScreenBrightnessRangeMinimum=" + mScreenBrightnessRangeMinimum);
        pw.println("  mScreenBrightnessRangeMaximum=" + mScreenBrightnessRangeMaximum);
        pw.println("  mUseSoftwareAutoBrightnessConfig="
                + mUseSoftwareAutoBrightnessConfig);
        pw.println("  mScreenAutoBrightnessSpline=" + mScreenAutoBrightnessSpline);
        pw.println("  mLightSensorWarmUpTimeConfig=" + mLightSensorWarmUpTimeConfig);

        mHandler.runWithScissors(new Runnable() {
            @Override
            public void run() {
                dumpLocal(pw);
            }
        }, 1000);
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
        pw.println("  mAmbientLux=" + mAmbientLux);
        pw.println("  mAmbientLuxValid=" + mAmbientLuxValid);
        pw.println("  mLastObservedLux=" + mLastObservedLux);
        pw.println("  mLastObservedLuxTime="
                + TimeUtils.formatUptime(mLastObservedLuxTime));
        pw.println("  mRecentLightSamples=" + mRecentLightSamples);
        pw.println("  mAmbientLightRingBuffer=" + mAmbientLightRingBuffer);
        pw.println("  mScreenAutoBrightness=" + mScreenAutoBrightness);
        pw.println("  mUsingScreenAutoBrightness=" + mUsingScreenAutoBrightness);
        pw.println("  mLastScreenAutoBrightnessGamma=" + mLastScreenAutoBrightnessGamma);
        pw.println("  mTwilight.getCurrentState()=" + mTwilight.getCurrentState());

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
        void onProximityPositive();
        void onProximityNegative();
    }

    private final class DisplayControllerHandler extends Handler {
        public DisplayControllerHandler(Looper looper) {
            super(looper, null, true /*async*/);
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

                case MSG_UPDATE_AMBIENT_LUX:
                    updateAmbientLux();
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

    private final TwilightListener mTwilightListener = new TwilightListener() {
        @Override
        public void onTwilightStateChanged() {
            mTwilightChanged = true;
            updatePowerState();
        }
    };

    private static class AmbientLightRingBuffer{
        // Proportional extra capacity of the buffer beyond the expected number of light samples
        // in the horizon
        private static final float BUFFER_SLACK = 1.5f;
        private float[] mRingLux;
        private long[] mRingTime;
        private int mCapacity;

        // The first valid element and the next open slot.
        // Note that if mCount is zero then there are no valid elements.
        private int mStart;
        private int mEnd;
        private int mCount;

        public AmbientLightRingBuffer() {
            this((int) (AMBIENT_LIGHT_HORIZON / LIGHT_SENSOR_RATE_MILLIS * BUFFER_SLACK));
        }

        public AmbientLightRingBuffer(int initialCapacity) {
            mCapacity = initialCapacity;
            mRingLux = new float[mCapacity];
            mRingTime = new long[mCapacity];
            mStart = 0;
            mEnd = 0;
            mCount = 0;
        }

        public float getLux(int index) {
            if (index >= size() || index < 0) {
                throw new ArrayIndexOutOfBoundsException(index);
            }
            index += mStart;
            if (index >= mCapacity) {
                index -= mCapacity;
            }
            return mRingLux[index];
        }

        public long getTime(int index) {
            if (index >= size() || index < 0) {
                throw new ArrayIndexOutOfBoundsException(index);
            }
            index += mStart;
            if (index >= mCapacity) {
                index -= mCapacity;
            }
            return mRingTime[index];
        }

        public void push(long time, float lux) {
            int next = mEnd;
            if (mCount == mCapacity) {
                int newSize = mCapacity * 2;

                float[] newRingLux = new float[newSize];
                long[] newRingTime = new long[newSize];
                int length = mCapacity - mStart;
                System.arraycopy(mRingLux, mStart, newRingLux, 0, length);
                System.arraycopy(mRingTime, mStart, newRingTime, 0, length);
                if (mStart != 0) {
                    System.arraycopy(mRingLux, 0, newRingLux, length, mCapacity - length);
                    System.arraycopy(mRingTime, 0, newRingTime, length, mCapacity - length);
                }
                mRingLux = newRingLux;
                mRingTime = newRingTime;

                next = mCapacity;
                mCapacity = newSize;
                mStart = 0;
            }
            mRingTime[next] = time;
            mRingLux[next] = lux;
            mEnd = next + 1;
            if (mEnd == mCapacity) {
                mEnd = 0;
            }
            mCount++;
        }

        public void prune(long horizon) {
            int removalCount = 0;
            while(removalCount < size() && getTime(removalCount) <= horizon) {
                removalCount++;
            }

            if (removalCount > 0) {
                // Some light sensors only produce data upon a change in the ambient light levels,
                // so we need to consider the previous measurement as the ambient light level for
                // all points in time up until we receive a new measurement. Thus, we always want
                // to keep the youngest element that would be removed from the buffer and just set
                // its measurement time to the horizon time since at that point it is the ambient
                // light level, and to remove it would be to drop a valid data point within our
                // horizon.
                removalCount--;

                mCount -= removalCount;
                mStart += removalCount;
                if (mStart >= mCapacity) {
                    mStart -= mCapacity;
                }

                if (getTime(0) < horizon) {
                    mRingTime[mStart] = horizon;
                }
            }
        }

        public int size() {
            return mCount;
        }

        public boolean isEmpty() {
            return mCount == 0;
        }

        public void clear() {
            mStart = 0;
            mEnd = 0;
            mCount = 0;
        }

        @Override
        public String toString() {
            final int length = mCapacity - mStart;
            float[] lux = new float[mCount];
            long[] time = new long[mCount];

            if (mCount <= length) {
                System.arraycopy(mRingLux, mStart, lux, 0, mCount);
                System.arraycopy(mRingTime, mStart, time, 0, mCount);
            } else {
                System.arraycopy(mRingLux, mStart, lux, 0, length);
                System.arraycopy(mRingLux, 0, lux, length, mCount - length);

                System.arraycopy(mRingTime, mStart, time, 0, length);
                System.arraycopy(mRingTime, 0, time, length, mCount - length);
            }
            return "AmbientLightRingBuffer{mCapacity=" + mCapacity
                + ", mStart=" + mStart
                + ", mEnd=" + mEnd
                + ", mCount=" + mCount
                + ", mRingLux=" + Arrays.toString(lux)
                + ", mRingTime=" + Arrays.toString(time)
                + "}";
        }
    }
}
