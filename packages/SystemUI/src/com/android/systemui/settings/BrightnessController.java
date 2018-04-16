/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.settings;

import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.Looper;
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
import android.widget.ImageView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.RestrictedLockUtils;
import com.android.systemui.Dependency;

import java.util.ArrayList;

public class BrightnessController implements ToggleSlider.Listener {
    private static final String TAG = "StatusBar.BrightnessController";
    private static final boolean SHOW_AUTOMATIC_ICON = false;

    private static final int SLIDER_MAX = 1023;
    private static final int SLIDER_ANIMATION_DURATION = 3000;

    // Hybrid Log Gamma constant values
    private static final float R = 0.5f;
    private static final float A = 0.17883277f;
    private static final float B = 0.28466892f;
    private static final float C = 0.55991073f;

    private static final int MSG_UPDATE_ICON = 0;
    private static final int MSG_UPDATE_SLIDER = 1;
    private static final int MSG_SET_CHECKED = 2;
    private static final int MSG_ATTACH_LISTENER = 3;
    private static final int MSG_DETACH_LISTENER = 4;
    private static final int MSG_VR_MODE_CHANGED = 5;

    private final int mMinimumBacklight;
    private final int mMaximumBacklight;
    private final int mDefaultBacklight;
    private final int mMinimumBacklightForVr;
    private final int mMaximumBacklightForVr;
    private final int mDefaultBacklightForVr;

    private final Context mContext;
    private final ImageView mIcon;
    private final ToggleSlider mControl;
    private final boolean mAutomaticAvailable;
    private final DisplayManager mDisplayManager;
    private final CurrentUserTracker mUserTracker;
    private final IVrManager mVrManager;

    private final Handler mBackgroundHandler;
    private final BrightnessObserver mBrightnessObserver;

    private ArrayList<BrightnessStateChangeCallback> mChangeCallbacks =
            new ArrayList<BrightnessStateChangeCallback>();

    private volatile boolean mAutomatic;  // Brightness adjusted automatically using ambient light.
    private volatile boolean mIsVrModeEnabled;
    private boolean mListening;
    private boolean mExternalChange;
    private boolean mControlValueInitialized;

    private ValueAnimator mSliderAnimator;

    public interface BrightnessStateChangeCallback {
        public void onBrightnessLevelChanged();
    }

    /** ContentObserver to watch brightness **/
    private class BrightnessObserver extends ContentObserver {

        private final Uri BRIGHTNESS_MODE_URI =
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE);
        private final Uri BRIGHTNESS_URI =
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);
        private final Uri BRIGHTNESS_FOR_VR_URI =
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_FOR_VR);

        public BrightnessObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (selfChange) return;

            if (BRIGHTNESS_MODE_URI.equals(uri)) {
                mBackgroundHandler.post(mUpdateModeRunnable);
                mBackgroundHandler.post(mUpdateSliderRunnable);
            } else if (BRIGHTNESS_URI.equals(uri)) {
                mBackgroundHandler.post(mUpdateSliderRunnable);
            } else if (BRIGHTNESS_FOR_VR_URI.equals(uri)) {
                mBackgroundHandler.post(mUpdateSliderRunnable);
            } else {
                mBackgroundHandler.post(mUpdateModeRunnable);
                mBackgroundHandler.post(mUpdateSliderRunnable);
            }
            for (BrightnessStateChangeCallback cb : mChangeCallbacks) {
                cb.onBrightnessLevelChanged();
            }
        }

        public void startObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
            cr.registerContentObserver(
                    BRIGHTNESS_MODE_URI,
                    false, this, UserHandle.USER_ALL);
            cr.registerContentObserver(
                    BRIGHTNESS_URI,
                    false, this, UserHandle.USER_ALL);
            cr.registerContentObserver(
                    BRIGHTNESS_FOR_VR_URI,
                    false, this, UserHandle.USER_ALL);
        }

        public void stopObserving() {
            final ContentResolver cr = mContext.getContentResolver();
            cr.unregisterContentObserver(this);
        }

    }

    private final Runnable mStartListeningRunnable = new Runnable() {
        @Override
        public void run() {
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
            if (mAutomaticAvailable) {
                int automatic;
                automatic = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                        UserHandle.USER_CURRENT);
                mAutomatic = automatic != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
                mHandler.obtainMessage(MSG_UPDATE_ICON, mAutomatic ? 1 : 0).sendToTarget();
            } else {
                mHandler.obtainMessage(MSG_SET_CHECKED, 0).sendToTarget();
                mHandler.obtainMessage(MSG_UPDATE_ICON, 0 /* automatic */).sendToTarget();
            }
        }
    };

    /**
     * Fetch the brightness from the system settings and update the slider. Should be called from
     * background thread.
     */
    private final Runnable mUpdateSliderRunnable = new Runnable() {
        @Override
        public void run() {
            final int val;
            final boolean inVrMode = mIsVrModeEnabled;
            if (inVrMode) {
                val = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_FOR_VR, mDefaultBacklightForVr,
                        UserHandle.USER_CURRENT);
            } else {
                val = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, mDefaultBacklight,
                        UserHandle.USER_CURRENT);
            }
            mHandler.obtainMessage(MSG_UPDATE_SLIDER, val, inVrMode ? 1 : 0).sendToTarget();
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
                    case MSG_UPDATE_ICON:
                        updateIcon(msg.arg1 != 0);
                        break;
                    case MSG_UPDATE_SLIDER:
                        updateSlider(msg.arg1, msg.arg2 != 0);
                        break;
                    case MSG_SET_CHECKED:
                        mControl.setChecked(msg.arg1 != 0);
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

    public BrightnessController(Context context, ImageView icon, ToggleSlider control) {
        mContext = context;
        mIcon = icon;
        mControl = control;
        mControl.setMax(SLIDER_MAX);
        mBackgroundHandler = new Handler((Looper) Dependency.get(Dependency.BG_LOOPER));
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mBackgroundHandler.post(mUpdateModeRunnable);
                mBackgroundHandler.post(mUpdateSliderRunnable);
            }
        };
        mBrightnessObserver = new BrightnessObserver(mHandler);

        PowerManager pm = context.getSystemService(PowerManager.class);
        mMinimumBacklight = pm.getMinimumScreenBrightnessSetting();
        mMaximumBacklight = pm.getMaximumScreenBrightnessSetting();
        mDefaultBacklight = pm.getDefaultScreenBrightnessSetting();
        mMinimumBacklightForVr = pm.getMinimumScreenBrightnessForVrSetting();
        mMaximumBacklightForVr = pm.getMaximumScreenBrightnessForVrSetting();
        mDefaultBacklightForVr = pm.getDefaultScreenBrightnessForVrSetting();

        mAutomaticAvailable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);
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

    @Override
    public void onInit(ToggleSlider control) {
        // Do nothing
    }

    public void registerCallbacks() {
        if (mListening) {
            return;
        }

        if (mVrManager != null) {
            try {
                mVrManager.registerListener(mVrStateCallbacks);
                mIsVrModeEnabled = mVrManager.getVrModeState();
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to register VR mode state listener: ", e);
            }
        }

        mBackgroundHandler.post(mStartListeningRunnable);
        mListening = true;
    }

    /** Unregister all call backs, both to and from the controller */
    public void unregisterCallbacks() {
        if (!mListening) {
            return;
        }

        if (mVrManager != null) {
            try {
                mVrManager.unregisterListener(mVrStateCallbacks);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to unregister VR mode state listener: ", e);
            }
        }

        mBackgroundHandler.post(mStopListeningRunnable);
        mListening = false;
        mControlValueInitialized = false;
    }

    @Override
    public void onChanged(ToggleSlider toggleSlider, boolean tracking, boolean automatic,
            int value, boolean stopTracking) {
        updateIcon(mAutomatic);
        if (mExternalChange) return;

        if (mSliderAnimator != null) {
            mSliderAnimator.cancel();
        }

        final int min;
        final int max;
        final int metric;
        final String setting;

        if (mIsVrModeEnabled) {
            metric = MetricsEvent.ACTION_BRIGHTNESS_FOR_VR;
            min = mMinimumBacklightForVr;
            max = mMaximumBacklightForVr;
            setting = Settings.System.SCREEN_BRIGHTNESS_FOR_VR;
        } else {
            metric = mAutomatic
                    ? MetricsEvent.ACTION_BRIGHTNESS_AUTO
                    : MetricsEvent.ACTION_BRIGHTNESS;
            min = mMinimumBacklight;
            max = mMaximumBacklight;
            setting = Settings.System.SCREEN_BRIGHTNESS;
        }

        final int val = convertGammaToLinear(value, min, max);

        if (stopTracking) {
            MetricsLogger.action(mContext, metric, val);
        }

        setBrightness(val);
        if (!tracking) {
            AsyncTask.execute(new Runnable() {
                    public void run() {
                        Settings.System.putIntForUser(mContext.getContentResolver(),
                                setting, val, UserHandle.USER_CURRENT);
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
                ((ToggleSliderView)mControl).setEnforcedAdmin(
                        RestrictedLockUtils.checkIfRestrictionEnforced(mContext,
                                UserManager.DISALLOW_CONFIG_BRIGHTNESS,
                                mUserTracker.getCurrentUserId()));
            }
        });
    }

    private void setMode(int mode) {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, mode,
                mUserTracker.getCurrentUserId());
    }

    private void setBrightness(int brightness) {
        mDisplayManager.setTemporaryBrightness(brightness);
    }

    private void setBrightnessAdj(float adj) {
        mDisplayManager.setTemporaryAutoBrightnessAdjustment(adj);
    }

    private void updateIcon(boolean automatic) {
        if (mIcon != null) {
            mIcon.setImageResource(automatic && SHOW_AUTOMATIC_ICON ?
                    com.android.systemui.R.drawable.ic_qs_brightness_auto_on :
                    com.android.systemui.R.drawable.ic_qs_brightness_auto_off);
        }
    }

    private void updateVrMode(boolean isEnabled) {
        if (mIsVrModeEnabled != isEnabled) {
            mIsVrModeEnabled = isEnabled;
            mBackgroundHandler.post(mUpdateSliderRunnable);
        }
    }

    private void updateSlider(int val, boolean inVrMode) {
        final int min;
        final int max;
        if (inVrMode) {
            min = mMinimumBacklightForVr;
            max = mMaximumBacklightForVr;
        } else {
            min = mMinimumBacklight;
            max = mMaximumBacklight;
        }
        if (val == convertGammaToLinear(mControl.getValue(), min, max)) {
            // If we have more resolution on the slider than we do in the actual setting, then
            // multiple slider positions will map to the same setting value. Thus, if we see a
            // setting value here that maps to the current slider position, we don't bother to
            // calculate the new slider position since it may differ and look like a brightness
            // change to the user even though it isn't one.
            return;
        }
        final int sliderVal = convertLinearToGamma(val, min, max);
        animateSliderTo(sliderVal);
    }

    private void animateSliderTo(int target) {
        if (!mControlValueInitialized) {
            // Don't animate the first value since it's default state isn't meaningful to users.
            mControl.setValue(target);
            mControlValueInitialized = true;
        }
        if (mSliderAnimator != null && mSliderAnimator.isStarted()) {
            mSliderAnimator.cancel();
        }
        mSliderAnimator = ValueAnimator.ofInt(mControl.getValue(), target);
        mSliderAnimator.addUpdateListener((ValueAnimator animation) -> {
            mExternalChange = true;
            mControl.setValue((int)animation.getAnimatedValue());
            mExternalChange = false;
        });
        mSliderAnimator.setDuration(SLIDER_ANIMATION_DURATION);
        mSliderAnimator.start();
    }

    /**
     * A function for converting from the linear space that the setting works in to the
     * gamma space that the slider works in.
     *
     * The gamma space effectively provides us a way to make linear changes to the slider that
     * result in linear changes in perception. If we made changes to the slider in the linear space
     * then we'd see an approximately logarithmic change in perception (c.f. Fechner's Law).
     *
     * Internally, this implements the Hybrid Log Gamma opto-electronic transfer function, which is
     * a slight improvement to the typical gamma transfer function for displays whose max
     * brightness exceeds the 120 nit reference point, but doesn't set a specific reference
     * brightness like the PQ function does.
     *
     * Note that this transfer function is only valid if the display's backlight value is a linear
     * control. If it's calibrated to be something non-linear, then a different transfer function
     * should be used.
     *
     * @param val The brightness setting value.
     * @param min The minimum acceptable value for the setting.
     * @param max The maximum acceptable value for the setting.
     *
     * @return The corresponding slider value
     */
    private static final int convertLinearToGamma(int val, int min, int max) {
        // For some reason, HLG normalizes to the range [0, 12] rather than [0, 1]
        final float normalizedVal = MathUtils.norm(min, max, val) * 12;
        final float ret;
        if (normalizedVal <= 1f) {
            ret = MathUtils.sqrt(normalizedVal) * R;
        } else {
            ret = A * MathUtils.log(normalizedVal - B) + C;
        }

        return Math.round(MathUtils.lerp(0, SLIDER_MAX, ret));
    }

    /**
     * A function for converting from the gamma space that the slider works in to the
     * linear space that the setting works in.
     *
     * The gamma space effectively provides us a way to make linear changes to the slider that
     * result in linear changes in perception. If we made changes to the slider in the linear space
     * then we'd see an approximately logarithmic change in perception (c.f. Fechner's Law).
     *
     * Internally, this implements the Hybrid Log Gamma electro-optical transfer function, which is
     * a slight improvement to the typical gamma transfer function for displays whose max
     * brightness exceeds the 120 nit reference point, but doesn't set a specific reference
     * brightness like the PQ function does.
     *
     * Note that this transfer function is only valid if the display's backlight value is a linear
     * control. If it's calibrated to be something non-linear, then a different transfer function
     * should be used.
     *
     * @param val The slider value.
     * @param min The minimum acceptable value for the setting.
     * @param max The maximum acceptable value for the setting.
     *
     * @return The corresponding setting value.
     */
    private static final int convertGammaToLinear(int val, int min, int max) {
        final float normalizedVal = MathUtils.norm(0, SLIDER_MAX, val);
        final float ret;
        if (normalizedVal <= R) {
            ret = MathUtils.sq(normalizedVal/R);
        } else {
            ret = MathUtils.exp((normalizedVal - C) / A) + B;
        }

        // HLG is normalized to the range [0, 12], so we need to re-normalize to the range [0, 1]
        // in order to derive the correct setting value.
        return Math.round(MathUtils.lerp(min, max, ret / 12));
    }
}
