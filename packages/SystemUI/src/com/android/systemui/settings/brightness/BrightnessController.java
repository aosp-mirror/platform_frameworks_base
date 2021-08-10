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

package com.android.systemui.settings.brightness;

import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.settingslib.display.BrightnessUtils.convertGammaToLinearFloat;
import static com.android.settingslib.display.BrightnessUtils.convertLinearToGammaFloat;

import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.Log;
import android.util.MathUtils;

import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;

import java.util.ArrayList;

import javax.inject.Inject;

public class BrightnessController implements ToggleSlider.Listener, MirroredBrightnessController {
    private static final String TAG = "StatusBar.BrightnessController";
    private static final int SLIDER_ANIMATION_DURATION = 3000;

    private static final int MSG_UPDATE_SLIDER = 1;
    private static final int MSG_ATTACH_LISTENER = 2;
    private static final int MSG_DETACH_LISTENER = 3;
    private static final int MSG_VR_MODE_CHANGED = 4;

    private static final Uri BRIGHTNESS_MODE_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE);
    private static final Uri BRIGHTNESS_FOR_VR_FLOAT_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FOR_VR_FLOAT);

    private final float mMinimumBacklightForVr;
    private final float mMaximumBacklightForVr;

    private final int mDisplayId;
    private final Context mContext;
    private final ToggleSlider mControl;
    private final DisplayManager mDisplayManager;
    private final CurrentUserTracker mUserTracker;
    private final IVrManager mVrManager;

    private final Handler mBackgroundHandler;
    private final BrightnessObserver mBrightnessObserver;

    private final DisplayListener mDisplayListener = new DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            mBackgroundHandler.post(mUpdateSliderRunnable);
            notifyCallbacks();
        }
    };

    private ArrayList<BrightnessStateChangeCallback> mChangeCallbacks =
            new ArrayList<BrightnessStateChangeCallback>();

    private volatile boolean mAutomatic;  // Brightness adjusted automatically using ambient light.
    private volatile boolean mIsVrModeEnabled;
    private boolean mListening;
    private boolean mExternalChange;
    private boolean mControlValueInitialized;
    private float mBrightnessMin = PowerManager.BRIGHTNESS_MIN;
    private float mBrightnessMax = PowerManager.BRIGHTNESS_MAX;

    private ValueAnimator mSliderAnimator;

    @Override
    public void setMirror(BrightnessMirrorController controller) {
        mControl.setMirrorControllerAndMirror(controller);
    }

    public interface BrightnessStateChangeCallback {
        /** Indicates that some of the brightness settings have changed */
        void onBrightnessLevelChanged();
    }

    /** ContentObserver to watch brightness */
    private class BrightnessObserver extends ContentObserver {

        BrightnessObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (selfChange) return;

            if (BRIGHTNESS_MODE_URI.equals(uri)) {
                mBackgroundHandler.post(mUpdateModeRunnable);
                mBackgroundHandler.post(mUpdateSliderRunnable);
            } else if (BRIGHTNESS_FOR_VR_FLOAT_URI.equals(uri)) {
                mBackgroundHandler.post(mUpdateSliderRunnable);
            } else {
                mBackgroundHandler.post(mUpdateModeRunnable);
                mBackgroundHandler.post(mUpdateSliderRunnable);
            }
            notifyCallbacks();
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    BRIGHTNESS_MODE_URI,
                    false, this, UserHandle.USER_ALL);
            cr.registerContentObserver(
                    BRIGHTNESS_FOR_VR_FLOAT_URI,
                    false, this, UserHandle.USER_ALL);
            mDisplayManager.registerDisplayListener(mDisplayListener, mHandler,
                    DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS);
        }

        public void stopObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }

    }

    private final Runnable mStartListeningRunnable = new Runnable() {
        @Override
        public void run() {
            if (mListening) {
                return;
            }
            mListening = true;

            if (mVrManager != null) {
                try {
                    mVrManager.registerListener(mVrStateCallbacks);
                    mIsVrModeEnabled = mVrManager.getVrModeState();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to register VR mode state listener: ", e);
                }
            }

            mBrightnessObserver.startObserving();
            mUserTracker.startTracking();

            // Update the slider and mode before attaching the listener so we don't
            // receive the onChanged notifications for the initial values.
            mUpdateModeRunnable.run();
            mUpdateSliderRunnable.run();

            mHandler.sendEmptyMessage(MSG_ATTACH_LISTENER);
        }
    };

    private final Runnable mStopListeningRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mListening) {
                return;
            }
            mListening = false;

            if (mVrManager != null) {
                try {
                    mVrManager.unregisterListener(mVrStateCallbacks);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to unregister VR mode state listener: ", e);
                }
            }

            mBrightnessObserver.stopObserving();
            mUserTracker.stopTracking();

            mHandler.sendEmptyMessage(MSG_DETACH_LISTENER);
        }
    };

    /**
     * Fetch the brightness mode from the system settings and update the icon. Should be called from
     * background thread.
     */
    private final Runnable mUpdateModeRunnable = new Runnable() {
        @Override
        public void run() {
            int automatic;
            automatic = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                    UserHandle.USER_CURRENT);
            mAutomatic = automatic != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        }
    };

    /**
     * Fetch the brightness from the system settings and update the slider. Should be called from
     * background thread.
     */
    private final Runnable mUpdateSliderRunnable = new Runnable() {
        @Override
        public void run() {
            final boolean inVrMode = mIsVrModeEnabled;
            final BrightnessInfo info = mContext.getDisplay().getBrightnessInfo();
            if (info == null) {
                return;
            }
            mBrightnessMax = info.brightnessMaximum;
            mBrightnessMin = info.brightnessMinimum;
            // Value is passed as intbits, since this is what the message takes.
            final int valueAsIntBits = Float.floatToIntBits(info.brightness);
            mHandler.obtainMessage(MSG_UPDATE_SLIDER, valueAsIntBits,
                    inVrMode ? 1 : 0).sendToTarget();
        }
    };

    private final IVrStateCallbacks mVrStateCallbacks = new IVrStateCallbacks.Stub() {
        @Override
        public void onVrStateChanged(boolean enabled) {
            mHandler.obtainMessage(MSG_VR_MODE_CHANGED, enabled ? 1 : 0, 0)
                    .sendToTarget();
        }
    };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            mExternalChange = true;
            try {
                switch (msg.what) {
                    case MSG_UPDATE_SLIDER:
                        updateSlider(Float.intBitsToFloat(msg.arg1), msg.arg2 != 0);
                        break;
                    case MSG_ATTACH_LISTENER:
                        mControl.setOnChangedListener(BrightnessController.this);
                        break;
                    case MSG_DETACH_LISTENER:
                        mControl.setOnChangedListener(null);
                        break;
                    case MSG_VR_MODE_CHANGED:
                        updateVrMode(msg.arg1 != 0);
                        break;
                    default:
                        super.handleMessage(msg);
                }
            } finally {
                mExternalChange = false;
            }
        }
    };

    public BrightnessController(
            Context context,
            ToggleSlider control,
            BroadcastDispatcher broadcastDispatcher,
            @Background Handler bgHandler) {
        mContext = context;
        mControl = control;
        mControl.setMax(GAMMA_SPACE_MAX);
        mBackgroundHandler = bgHandler;
        mUserTracker = new CurrentUserTracker(broadcastDispatcher) {
            @Override
            public void onUserSwitched(int newUserId) {
                mBackgroundHandler.post(mUpdateModeRunnable);
                mBackgroundHandler.post(mUpdateSliderRunnable);
            }
        };
        mBrightnessObserver = new BrightnessObserver(mHandler);

        mDisplayId = mContext.getDisplayId();
        PowerManager pm = context.getSystemService(PowerManager.class);
        mMinimumBacklightForVr = pm.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MINIMUM_VR);
        mMaximumBacklightForVr = pm.getBrightnessConstraint(
                PowerManager.BRIGHTNESS_CONSTRAINT_TYPE_MAXIMUM_VR);

        mDisplayManager = context.getSystemService(DisplayManager.class);
        mVrManager = IVrManager.Stub.asInterface(ServiceManager.getService(
                Context.VR_SERVICE));
    }

    public void addStateChangedCallback(BrightnessStateChangeCallback cb) {
        mChangeCallbacks.add(cb);
    }

    public boolean removeStateChangedCallback(BrightnessStateChangeCallback cb) {
        return mChangeCallbacks.remove(cb);
    }

    public void registerCallbacks() {
        mBackgroundHandler.post(mStartListeningRunnable);
    }

    /** Unregister all call backs, both to and from the controller */
    public void unregisterCallbacks() {
        mBackgroundHandler.post(mStopListeningRunnable);
        mControlValueInitialized = false;
    }

    @Override
    public void onChanged(boolean tracking, int value, boolean stopTracking) {
        if (mExternalChange) return;

        if (mSliderAnimator != null) {
            mSliderAnimator.cancel();
        }

        final float minBacklight;
        final float maxBacklight;
        final int metric;

        if (mIsVrModeEnabled) {
            metric = MetricsEvent.ACTION_BRIGHTNESS_FOR_VR;
            minBacklight = mMinimumBacklightForVr;
            maxBacklight = mMaximumBacklightForVr;
        } else {
            metric = mAutomatic
                    ? MetricsEvent.ACTION_BRIGHTNESS_AUTO
                    : MetricsEvent.ACTION_BRIGHTNESS;
            minBacklight = mBrightnessMin;
            maxBacklight = mBrightnessMax;
        }
        final float valFloat = MathUtils.min(
                convertGammaToLinearFloat(value, minBacklight, maxBacklight),
                maxBacklight);
        if (stopTracking) {
            // TODO(brightnessfloat): change to use float value instead.
            MetricsLogger.action(mContext, metric,
                    BrightnessSynchronizer.brightnessFloatToInt(valFloat));

        }
        setBrightness(valFloat);
        if (!tracking) {
            AsyncTask.execute(new Runnable() {
                    public void run() {
                        mDisplayManager.setBrightness(mDisplayId, valFloat);
                    }
                });
        }

        for (BrightnessStateChangeCallback cb : mChangeCallbacks) {
            cb.onBrightnessLevelChanged();
        }
    }

    public void checkRestrictionAndSetEnabled() {
        mBackgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                mControl.setEnforcedAdmin(
                        RestrictedLockUtilsInternal.checkIfRestrictionEnforced(mContext,
                                UserManager.DISALLOW_CONFIG_BRIGHTNESS,
                                mUserTracker.getCurrentUserId()));
            }
        });
    }

    public void hideSlider() {
        mControl.hideView();
    }

    public void showSlider() {
        mControl.showView();
    }

    private void setBrightness(float brightness) {
        mDisplayManager.setTemporaryBrightness(mDisplayId, brightness);
    }

    private void updateVrMode(boolean isEnabled) {
        if (mIsVrModeEnabled != isEnabled) {
            mIsVrModeEnabled = isEnabled;
            mBackgroundHandler.post(mUpdateSliderRunnable);
        }
    }

    private void updateSlider(float brightnessValue, boolean inVrMode) {
        final float min;
        final float max;
        if (inVrMode) {
            min = mMinimumBacklightForVr;
            max = mMaximumBacklightForVr;
        } else {
            min = mBrightnessMin;
            max = mBrightnessMax;
        }
        // convertGammaToLinearFloat returns 0-1
        if (BrightnessSynchronizer.floatEquals(brightnessValue,
                convertGammaToLinearFloat(mControl.getValue(), min, max))) {
            // If the value in the slider is equal to the value on the current brightness
            // then the slider does not need to animate, since the brightness will not change.
            return;
        }
        // Returns GAMMA_SPACE_MIN - GAMMA_SPACE_MAX
        final int sliderVal = convertLinearToGammaFloat(brightnessValue, min, max);
        animateSliderTo(sliderVal);
    }

    private void animateSliderTo(int target) {
        if (!mControlValueInitialized) {
            // Don't animate the first value since its default state isn't meaningful to users.
            mControl.setValue(target);
            mControlValueInitialized = true;
        }
        if (mSliderAnimator != null && mSliderAnimator.isStarted()) {
            mSliderAnimator.cancel();
        }
        mSliderAnimator = ValueAnimator.ofInt(mControl.getValue(), target);
        mSliderAnimator.addUpdateListener((ValueAnimator animation) -> {
            mExternalChange = true;
            mControl.setValue((int) animation.getAnimatedValue());
            mExternalChange = false;
        });
        final long animationDuration = SLIDER_ANIMATION_DURATION * Math.abs(
                mControl.getValue() - target) / GAMMA_SPACE_MAX;
        mSliderAnimator.setDuration(animationDuration);
        mSliderAnimator.start();
    }

    private void notifyCallbacks() {
        final int size = mChangeCallbacks.size();
        for (int i = 0; i < size; i++) {
            mChangeCallbacks.get(i).onBrightnessLevelChanged();
        }
    }

    /** Factory for creating a {@link BrightnessController}. */
    public static class Factory {
        private final Context mContext;
        private final BroadcastDispatcher mBroadcastDispatcher;
        private final Handler mBackgroundHandler;

        @Inject
        public Factory(
                Context context,
                BroadcastDispatcher broadcastDispatcher,
                @Background Handler bgHandler) {
            mContext = context;
            mBroadcastDispatcher = broadcastDispatcher;
            mBackgroundHandler = bgHandler;
        }

        /** Create a {@link BrightnessController} */
        public BrightnessController create(ToggleSlider toggleSlider) {
            return new BrightnessController(
                    mContext,
                    toggleSlider,
                    mBroadcastDispatcher,
                    mBackgroundHandler);
        }
    }

}
