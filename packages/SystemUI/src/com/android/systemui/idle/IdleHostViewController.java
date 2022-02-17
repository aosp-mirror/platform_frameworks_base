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
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

/**
 * {@link IdleHostViewController} processes signals to control the lifecycle of the idle screen.
 */
public class IdleHostViewController extends ViewController<IdleHostView> {
    private static final String TAG = "IdleHostViewController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @Retention(RetentionPolicy.RUNTIME)
    @IntDef({STATE_IDLE_MODE_ENABLED, STATE_KEYGUARD_SHOWING, STATE_DOZING, STATE_DREAMING,
            STATE_LOW_LIGHT})
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

    // The aggregate current state.
    private int mState;
    private boolean mIsMonitoringLowLight;
    private boolean mIsMonitoringDream;

    private final BroadcastDispatcher mBroadcastDispatcher;

    private final PowerManager mPowerManager;

    // Keyguard state controller for monitoring keyguard show state.
    private final KeyguardStateController mKeyguardStateController;

    // Status bar state controller for monitoring when the device is dozing.
    private final StatusBarStateController mStatusBarStateController;

    // Intent filter for receiving dream broadcasts.
    private IntentFilter mDreamIntentFilter;

    // Monitor for the current ambient light mode. Used to trigger / exit low-light mode.
    private final AmbientLightModeMonitor mAmbientLightModeMonitor;

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

    private final AmbientLightModeMonitor.Callback mAmbientLightModeCallback =
            mode -> {
                boolean shouldBeLowLight;
                switch (mode) {
                    case AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_UNDECIDED:
                        return;
                    case AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_LIGHT:
                        shouldBeLowLight = false;
                        break;
                    case AmbientLightModeMonitor.AMBIENT_LIGHT_MODE_DARK:
                        shouldBeLowLight = true;
                        break;
                    default:
                        Log.w(TAG, "invalid ambient light mode");
                        return;
                }

                if (DEBUG) Log.d(TAG, "ambient light mode changed to " + mode);

                final boolean isLowLight = getState(STATE_LOW_LIGHT);
                if (shouldBeLowLight != isLowLight) {
                    setState(STATE_LOW_LIGHT, shouldBeLowLight);
                }
            };

    final Provider<View> mIdleViewProvider;

    @Inject
    protected IdleHostViewController(
            BroadcastDispatcher broadcastDispatcher,
            PowerManager powerManager,
            IdleHostView view,
            @Main Resources resources,
            @Named(IDLE_VIEW) Provider<View> idleViewProvider,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController,
            AmbientLightModeMonitor ambientLightModeMonitor) {
        super(view);
        mBroadcastDispatcher = broadcastDispatcher;
        mPowerManager = powerManager;
        mIdleViewProvider = idleViewProvider;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mAmbientLightModeMonitor = ambientLightModeMonitor;

        mState = STATE_KEYGUARD_SHOWING;

        final boolean enabled = resources.getBoolean(R.bool.config_enableIdleMode);
        if (enabled) {
            mState |= STATE_IDLE_MODE_ENABLED;
        }

        setState(mState, true);

        if (DEBUG) {
            Log.d(TAG, "initial state:" + mState + " enabled:" + enabled);
        }
    }

    @Override
    public void init() {
        super.init();

        setState(STATE_KEYGUARD_SHOWING, mKeyguardStateController.isShowing());
        setState(STATE_DOZING, mStatusBarStateController.isDozing());
    }

    private void setState(@State int state, boolean active) {
        final int oldState = mState;

        if (active) {
            mState |= state;
        } else {
            mState &= ~state;
        }

        if (oldState == mState) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "set " + getStateName(state) + " to " + active);
            logCurrentState();
        }

        final boolean inCommunalMode = getState(STATE_IDLE_MODE_ENABLED)
                && getState(STATE_KEYGUARD_SHOWING);

        enableDreamMonitoring(inCommunalMode);
        enableLowLightMonitoring(inCommunalMode);

        if (state == STATE_LOW_LIGHT) {
            enableLowLightMode(inCommunalMode && active);
        }
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

    private void enableLowLightMonitoring(boolean enable) {
        if (enable == mIsMonitoringLowLight) {
            return;
        }

        mIsMonitoringLowLight = enable;

        if (mIsMonitoringLowLight) {
            if (DEBUG) Log.d(TAG, "enable low light monitoring");
            mAmbientLightModeMonitor.start(mAmbientLightModeCallback);
        } else {
            if (DEBUG) Log.d(TAG, "disable low light monitoring");
            mAmbientLightModeMonitor.stop();
        }
    }

    private void enableLowLightMode(boolean enable) {
        if (enable == getState(STATE_DOZING)) {
            return;
        }

        if (enable) {
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
                + "}");
    }
}
