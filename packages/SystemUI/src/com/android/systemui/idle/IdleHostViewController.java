/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.idle;

import static com.android.systemui.communal.dagger.CommunalModule.IDLE_VIEW;

import android.annotation.IntDef;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.InputMonitorCompat;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.sensors.AsyncSensorManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/**
 * {@link IdleHostViewController} processes signals to control the lifecycle of the idle screen.
 */
public class IdleHostViewController extends ViewController<IdleHostView> implements
        SensorEventListener {
    private static final String INPUT_MONITOR_IDENTIFIER = "IdleHostViewController";
    private static final String TAG = "IdleHostViewController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @Retention(RetentionPolicy.RUNTIME)
    @IntDef({STATE_IDLE_MODE_ENABLED, STATE_KEYGUARD_SHOWING, STATE_DOZING, STATE_DREAMING,
            STATE_LOW_LIGHT, STATE_IDLING, STATE_SHOULD_START_IDLING})
    public @interface State {}

    // Set at construction to indicate idle mode is available.
    private static final int STATE_IDLE_MODE_ENABLED = 1 << 0;

    // Set when keyguard is present, even below a dream or AOD. AKA the device is locked.
    private static final int STATE_KEYGUARD_SHOWING = 1 << 1;

    // Set when the device has entered a dozing / low power state.
    private static final int STATE_DOZING = 1 << 2;

    // Set when the device has entered a dreaming state, which includes dozing.
    private static final int STATE_DREAMING = 1 << 3;

    // Set when the device is in a low light environment.
    private static final int STATE_LOW_LIGHT = 1 << 4;

    // Set when the device is idling, which is either dozing or dreaming.
    private static final int STATE_IDLING = 1 << 5;

    // Set when the controller decides that the device should start idling (either dozing or
    // dreaming).
    private static final int STATE_SHOULD_START_IDLING = 1 << 6;

    // The aggregate current state.
    private int mState;
    private boolean mIdleModeActive;
    private boolean mLowLightModeActive;
    private boolean mIsMonitoringLowLight;
    private boolean mIsMonitoringDream;

    // Whether in a state waiting for dozing to complete before starting dreaming.
    private boolean mDozeToDreamLock = false;

    private final Context mContext;

    // Timeout to idle in milliseconds.
    private final int mIdleTimeout;

    // Factory for generating input listeners.
    private final InputMonitorFactory mInputMonitorFactory;

    // Delayable executor.
    private final DelayableExecutor mDelayableExecutor;

    private final BroadcastDispatcher mBroadcastDispatcher;

    private final PowerManager mPowerManager;

    private final AsyncSensorManager mSensorManager;

    // Light sensor used to detect low light condition.
    private final Sensor mSensor;

    // Runnable for canceling enabling idle.
    private Runnable mCancelEnableIdling;

    // Keyguard state controller for monitoring keyguard show state.
    private final KeyguardStateController mKeyguardStateController;

    // Status bar state controller for monitoring when the device is dozing.
    private final StatusBarStateController mStatusBarStateController;

    // Looper to use for monitoring input.
    private final Looper mLooper;

    // Choreographer to use for monitoring input.
    private final Choreographer mChoreographer;

    // Helper class for DreamService related requests.
    private final DreamHelper mDreamHelper;

    // Monitor for tracking touches for activity.
    private InputMonitorCompat mInputMonitor;

    // Intent filter for receiving dream broadcasts.
    private IntentFilter mDreamIntentFilter;

    // Delayed callback for starting idling.
    private final Runnable mEnableIdlingCallback = () -> {
        if (DEBUG) {
            Log.d(TAG, "time out, should start idling");
        }
        setState(STATE_SHOULD_START_IDLING, true);
    };

    private final KeyguardStateController.Callback mKeyguardCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    setState(STATE_KEYGUARD_SHOWING, mKeyguardStateController.isShowing());
                }
            };

    private final StatusBarStateController.StateListener mStatusBarCallback =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozingChanged(boolean isDozing) {
                    setState(STATE_DOZING, isDozing);
                }
            };

    private final BroadcastReceiver mDreamStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_DREAMING_STARTED.equals(intent.getAction())) {
                setState(STATE_DREAMING, true);
            } else if (Intent.ACTION_DREAMING_STOPPED.equals(intent.getAction())) {
                setState(STATE_DREAMING, false);
            }
        }
    };

    final Provider<View> mIdleViewProvider;

    @Inject
    protected IdleHostViewController(
            Context context,
            BroadcastDispatcher broadcastDispatcher,
            PowerManager powerManager,
            AsyncSensorManager sensorManager,
            IdleHostView view, InputMonitorFactory factory,
            @Main DelayableExecutor delayableExecutor,
            @Main Resources resources,
            @Main Looper looper,
            @Named(IDLE_VIEW) Provider<View> idleViewProvider,
            Choreographer choreographer,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            DreamHelper dreamHelper) {
        super(view);
        mContext = context;
        mBroadcastDispatcher = broadcastDispatcher;
        mPowerManager = powerManager;
        mSensorManager = sensorManager;
        mIdleViewProvider = idleViewProvider;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mLooper = looper;
        mChoreographer = choreographer;
        mDreamHelper = dreamHelper;

        mState = STATE_KEYGUARD_SHOWING;

        final boolean enabled = resources.getBoolean(R.bool.config_enableIdleMode);
        if (enabled) {
            mState |= STATE_IDLE_MODE_ENABLED;
        }

        setState(mState, true);

        mIdleTimeout = resources.getInteger(R.integer.config_idleModeTimeout);
        mInputMonitorFactory = factory;
        mDelayableExecutor = delayableExecutor;
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

        if (DEBUG) {
            Log.d(TAG, "initial state:" + mState + " enabled:" + enabled
                    + " timeout:" + mIdleTimeout);
        }
    }

    @Override
    public void init() {
        super.init();

        setState(STATE_KEYGUARD_SHOWING, mKeyguardStateController.isShowing());
        setState(STATE_DOZING, mStatusBarStateController.isDozing());
    }

    private void setState(@State int state, boolean active) {
        // If waiting for dozing to stop, ignore any state update until dozing is stopped.
        if (mDozeToDreamLock) {
            if (state == STATE_DOZING && !active) {
                if (DEBUG) {
                    Log.d(TAG, "dozing stopped, now start dreaming");
                }

                mDozeToDreamLock = false;
                enableIdleMode(true);
            }

            return;
        }

        final int oldState = mState;

        if (active) {
            mState |= state;
        } else {
            mState &= ~state;
        }

        if (oldState == mState) {
            return;
        }

        // Updates STATE_IDLING.
        final boolean isIdling = getState(STATE_DOZING) || getState(STATE_DREAMING);
        if (isIdling) {
            mState |= STATE_IDLING;
        } else {
            mState &= ~STATE_IDLING;
        }

        // Updates STATE_SHOULD_START_IDLING.
        final boolean stoppedIdling = stoppedIdling(oldState);
        if (stoppedIdling) {
            mState &= ~STATE_SHOULD_START_IDLING;
        } else if (shouldStartIdling(oldState)) {
            mState |= STATE_SHOULD_START_IDLING;
        }

        if (DEBUG) {
            Log.d(TAG, "set " + getStateName(state) + " to " + active);
            logCurrentState();
        }

        final boolean wasLowLight = getState(STATE_LOW_LIGHT, oldState);
        final boolean isLowLight = getState(STATE_LOW_LIGHT);
        final boolean wasIdling = getState(STATE_IDLING, oldState);

        // When the device is idling and no longer in low light, wake up from dozing, wait till
        // done, and start dreaming.
        if (wasLowLight && !isLowLight && wasIdling && isIdling) {
            if (DEBUG) {
                Log.d(TAG, "idling and no longer in low light, stop dozing");
            }

            mDozeToDreamLock = true;

            enableLowLightMode(false);
            return;
        }

        final boolean inCommunalMode = getState(STATE_IDLE_MODE_ENABLED)
                && getState(STATE_KEYGUARD_SHOWING);

        enableDreamMonitoring(inCommunalMode);
        enableLowLightMonitoring(inCommunalMode);
        enableIdleMonitoring(inCommunalMode && !getState(STATE_IDLING));
        enableIdleMode(inCommunalMode && !getState(STATE_LOW_LIGHT)
                && getState(STATE_SHOULD_START_IDLING));
        enableLowLightMode(inCommunalMode && !stoppedIdling && getState(STATE_LOW_LIGHT));
    }

    private void enableDreamMonitoring(boolean enable) {
        if (mIsMonitoringDream == enable) {
            return;
        }

        mIsMonitoringDream = enable;

        if (DEBUG) {
            Log.d(TAG, (enable ? "enable" : "disable") + " dream monitoring");
        }

        if (mDreamIntentFilter == null) {
            mDreamIntentFilter = new IntentFilter();
            mDreamIntentFilter.addAction(Intent.ACTION_DREAMING_STARTED);
            mDreamIntentFilter.addAction(Intent.ACTION_DREAMING_STOPPED);
        }

        if (enable) {
            mBroadcastDispatcher.registerReceiver(mDreamStateReceiver, mDreamIntentFilter);
        } else {
            mBroadcastDispatcher.unregisterReceiver(mDreamStateReceiver);
        }
    }

    private void enableIdleMonitoring(boolean enable) {
        if (enable && mInputMonitor == null) {
            if (DEBUG) {
                Log.d(TAG, "enable idle monitoring");
            }
            // Set initial timeout to idle.
            mCancelEnableIdling = mDelayableExecutor.executeDelayed(mEnableIdlingCallback,
                    mIdleTimeout);

            // Monitor - any input should reset timer
            mInputMonitor = mInputMonitorFactory.getInputMonitor(INPUT_MONITOR_IDENTIFIER);
            mInputMonitor.getInputReceiver(mLooper, mChoreographer,
                    v -> {
                        if (DEBUG) {
                            Log.d(TAG, "touch detected, resetting timeout");
                        }
                        // When input is received, reset timeout.
                        if (mCancelEnableIdling != null) {
                            mCancelEnableIdling.run();
                            mCancelEnableIdling = null;
                        }
                        mCancelEnableIdling = mDelayableExecutor.executeDelayed(
                                mEnableIdlingCallback, mIdleTimeout);
                    });
        } else if (!enable && mInputMonitor != null) {
            if (DEBUG) {
                Log.d(TAG, "disable idle monitoring");
            }
            // Clean up idle callback and touch monitoring.
            if (mCancelEnableIdling != null) {
                mCancelEnableIdling.run();
                mCancelEnableIdling = null;
            }

            mInputMonitor.dispose();
            mInputMonitor = null;
        }
    }

    private void enableIdleMode(boolean enable) {
        if (mIdleModeActive == enable) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, (enable ? "enable" : "disable") + " idle mode");
        }

        mIdleModeActive = enable;

        if (mIdleModeActive) {
            // Start dream.
            mDreamHelper.startDreaming(mContext);
        }
    }

    private void enableLowLightMonitoring(boolean enable) {
        if (enable == mIsMonitoringLowLight) {
            return;
        }

        mIsMonitoringLowLight = enable;

        if (mIsMonitoringLowLight) {
            if (DEBUG) Log.d(TAG, "enable low light monitoring");
            mSensorManager.registerListener(this /*listener*/, mSensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            if (DEBUG) Log.d(TAG, "disable low light monitoring");
            mSensorManager.unregisterListener(this);
        }
    }

    private void enableLowLightMode(boolean enable) {
        if (mLowLightModeActive == enable) {
            return;
        }

        mLowLightModeActive = enable;

        if (mLowLightModeActive) {
            if (DEBUG) Log.d(TAG, "enter low light, start dozing");

            mPowerManager.goToSleep(
                    SystemClock.uptimeMillis(),
                    PowerManager.GO_TO_SLEEP_REASON_APPLICATION, 0);
        } else {
            if (DEBUG) Log.d(TAG, "exit low light, stop dozing");
            mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                    PowerManager.WAKE_REASON_APPLICATION, "Exit low light condition");
        }
    }

    @Override
    protected void onViewAttached() {
        if (DEBUG) {
            Log.d(TAG, "onViewAttached");
        }

        mKeyguardStateController.addCallback(mKeyguardCallback);
        mStatusBarStateController.addCallback(mStatusBarCallback);
    }

    @Override
    protected void onViewDetached() {
        mKeyguardStateController.removeCallback(mKeyguardCallback);
        mStatusBarStateController.removeCallback(mStatusBarCallback);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0) {
            if (DEBUG) Log.w(TAG, "SensorEvent doesn't have value");
            return;
        }

        final boolean shouldBeLowLight = event.values[0] < 10;
        final boolean isLowLight = getState(STATE_LOW_LIGHT);

        if (shouldBeLowLight != isLowLight) {
            setState(STATE_LOW_LIGHT, shouldBeLowLight);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (DEBUG) {
            Log.d(TAG, "onAccuracyChanged accuracy=" + accuracy);
        }
    }

    // Returns whether the device just stopped idling by comparing the previous state with the
    // current one.
    private boolean stoppedIdling(int oldState) {
        // The device stopped idling if it's no longer dreaming or dozing.
        return !getState(STATE_DOZING) && !getState(STATE_DREAMING)
                && (getState(STATE_DOZING, oldState) || getState(STATE_DREAMING, oldState));
    }

    private boolean shouldStartIdling(int oldState) {
        // Should start idling immediately if the device went in low light environment.
        return !getState(STATE_LOW_LIGHT, oldState) && getState(STATE_LOW_LIGHT);
    }

    private String getStateName(@State int state) {
        switch (state) {
            case STATE_IDLE_MODE_ENABLED:
                return "STATE_IDLE_MODE_ENABLED";
            case STATE_KEYGUARD_SHOWING:
                return "STATE_KEYGUARD_SHOWING";
            case STATE_DOZING:
                return "STATE_DOZING";
            case STATE_DREAMING:
                return "STATE_DREAMING";
            case STATE_LOW_LIGHT:
                return "STATE_LOW_LIGHT";
            case STATE_IDLING:
                return "STATE_IDLING";
            case STATE_SHOULD_START_IDLING:
                return "STATE_SHOULD_START_IDLING";
            default:
                return "STATE_UNKNOWN";
        }
    }

    private boolean getState(@State int state) {
        return getState(state, mState);
    }

    private boolean getState(@State int state, int oldState) {
        return (oldState & state) == state;
    }

    private String getStateLog(@State int state) {
        return getStateName(state) + " = " + getState(state);
    }

    private void logCurrentState() {
        Log.d(TAG, "current state: {\n"
                + "\t" + getStateLog(STATE_IDLE_MODE_ENABLED) + "\n"
                + "\t" + getStateLog(STATE_KEYGUARD_SHOWING) + "\n"
                + "\t" + getStateLog(STATE_DOZING) + "\n"
                + "\t" + getStateLog(STATE_DREAMING) + "\n"
                + "\t" + getStateLog(STATE_LOW_LIGHT) + "\n"
                + "\t" + getStateLog(STATE_IDLING) + "\n"
                + "\t" + getStateLog(STATE_SHOULD_START_IDLING) + "\n"
                + "}");
    }
}
