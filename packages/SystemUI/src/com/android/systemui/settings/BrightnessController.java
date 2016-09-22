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

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
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
import android.provider.Settings;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.Log;
import android.widget.ImageView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;

import java.util.ArrayList;

public class BrightnessController implements ToggleSlider.Listener {
    private static final String TAG = "StatusBar.BrightnessController";
    private static final boolean SHOW_AUTOMATIC_ICON = false;

    /**
     * {@link android.provider.Settings.System#SCREEN_AUTO_BRIGHTNESS_ADJ} uses the range [-1, 1].
     * Using this factor, it is converted to [0, BRIGHTNESS_ADJ_RESOLUTION] for the SeekBar.
     */
    public static final float BRIGHTNESS_ADJ_RESOLUTION = 2048;

    private static final int MSG_UPDATE_ICON = 0;
    private static final int MSG_UPDATE_SLIDER = 1;
    private static final int MSG_SET_CHECKED = 2;
    private static final int MSG_ATTACH_LISTENER = 3;
    private static final int MSG_DETACH_LISTENER = 4;
    private static final int MSG_VR_MODE_CHANGED = 5;

    private final int mMinimumBacklight;
    private final int mMaximumBacklight;
    private final int mMinimumBacklightForVr;
    private final int mMaximumBacklightForVr;

    private final Context mContext;
    private final ImageView mIcon;
    private final ToggleSlider mControl;
    private final boolean mAutomaticAvailable;
    private final IPowerManager mPower;
    private final CurrentUserTracker mUserTracker;
    private final IVrManager mVrManager;

    private Handler mBackgroundHandler;
    private final BrightnessObserver mBrightnessObserver;

    private ArrayList<BrightnessStateChangeCallback> mChangeCallbacks =
            new ArrayList<BrightnessStateChangeCallback>();

    private volatile boolean mAutomatic;  // Brightness adjusted automatically using ambient light.
    private volatile boolean mIsVrModeEnabled;
    private boolean mListening;
    private boolean mExternalChange;

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
        private final Uri BRIGHTNESS_ADJ_URI =
                Settings.System.getUriFor(Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ);

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
            } else if (BRIGHTNESS_URI.equals(uri) && !mAutomatic) {
                mBackgroundHandler.post(mUpdateSliderRunnable);
            } else if (BRIGHTNESS_FOR_VR_URI.equals(uri)) {
                mBackgroundHandler.post(mUpdateSliderRunnable);
            } else if (BRIGHTNESS_ADJ_URI.equals(uri) && mAutomatic) {
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
            cr.registerContentObserver(
                    BRIGHTNESS_ADJ_URI,
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
            if (mIsVrModeEnabled) {
                int value = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_FOR_VR, mMaximumBacklight,
                        UserHandle.USER_CURRENT);
                mHandler.obtainMessage(MSG_UPDATE_SLIDER,
                        mMaximumBacklightForVr - mMinimumBacklightForVr,
                        value - mMinimumBacklightForVr).sendToTarget();
            } else if (mAutomatic) {
                float value = Settings.System.getFloatForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0,
                        UserHandle.USER_CURRENT);
                mHandler.obtainMessage(MSG_UPDATE_SLIDER, (int) BRIGHTNESS_ADJ_RESOLUTION,
                        (int) ((value + 1) * BRIGHTNESS_ADJ_RESOLUTION / 2f)).sendToTarget();
            } else {
                int value;
                value = Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, mMaximumBacklight,
                        UserHandle.USER_CURRENT);
                mHandler.obtainMessage(MSG_UPDATE_SLIDER, mMaximumBacklight - mMinimumBacklight,
                        value - mMinimumBacklight).sendToTarget();
            }
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
                        mControl.setMax(msg.arg1);
                        mControl.setValue(msg.arg2);
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
        mBackgroundHandler = new Handler(Looper.getMainLooper());
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mBackgroundHandler.post(mUpdateModeRunnable);
                mBackgroundHandler.post(mUpdateSliderRunnable);
            }
        };
        mBrightnessObserver = new BrightnessObserver(mHandler);

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mMinimumBacklight = pm.getMinimumScreenBrightnessSetting();
        mMaximumBacklight = pm.getMaximumScreenBrightnessSetting();
        mMinimumBacklightForVr = pm.getMinimumScreenBrightnessForVrSetting();
        mMaximumBacklightForVr = pm.getMaximumScreenBrightnessForVrSetting();

        mAutomaticAvailable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_automatic_brightness_available);
        mPower = IPowerManager.Stub.asInterface(ServiceManager.getService("power"));
        mVrManager = IVrManager.Stub.asInterface(ServiceManager.getService("vrmanager"));
    }

    public void setBackgroundLooper(Looper backgroundLooper) {
        mBackgroundHandler = new Handler(backgroundLooper);
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
    }

    @Override
    public void onChanged(ToggleSlider view, boolean tracking, boolean automatic, int value,
            boolean stopTracking) {
        updateIcon(mAutomatic);
        if (mExternalChange) return;

        if (mIsVrModeEnabled) {
            final int val = value + mMinimumBacklightForVr;
            if (stopTracking) {
                MetricsLogger.action(mContext, MetricsEvent.ACTION_BRIGHTNESS_FOR_VR, val);
            }
            setBrightness(val);
            if (!tracking) {
                AsyncTask.execute(new Runnable() {
                        public void run() {
                            Settings.System.putIntForUser(mContext.getContentResolver(),
                                    Settings.System.SCREEN_BRIGHTNESS_FOR_VR, val,
                                    UserHandle.USER_CURRENT);
                        }
                    });
            }
        } else if (!mAutomatic) {
            final int val = value + mMinimumBacklight;
            if (stopTracking) {
                MetricsLogger.action(mContext, MetricsEvent.ACTION_BRIGHTNESS, val);
            }
            setBrightness(val);
            if (!tracking) {
                AsyncTask.execute(new Runnable() {
                        public void run() {
                            Settings.System.putIntForUser(mContext.getContentResolver(),
                                    Settings.System.SCREEN_BRIGHTNESS, val,
                                    UserHandle.USER_CURRENT);
                        }
                    });
            }
        } else {
            final float adj = value / (BRIGHTNESS_ADJ_RESOLUTION / 2f) - 1;
            if (stopTracking) {
                MetricsLogger.action(mContext, MetricsEvent.ACTION_BRIGHTNESS_AUTO, value);
            }
            setBrightnessAdj(adj);
            if (!tracking) {
                AsyncTask.execute(new Runnable() {
                    public void run() {
                        Settings.System.putFloatForUser(mContext.getContentResolver(),
                                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, adj,
                                UserHandle.USER_CURRENT);
                    }
                });
            }
        }

        for (BrightnessStateChangeCallback cb : mChangeCallbacks) {
            cb.onBrightnessLevelChanged();
        }
    }

    private void setMode(int mode) {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, mode,
                mUserTracker.getCurrentUserId());
    }

    private void setBrightness(int brightness) {
        try {
            mPower.setTemporaryScreenBrightnessSettingOverride(brightness);
        } catch (RemoteException ex) {
        }
    }

    private void setBrightnessAdj(float adj) {
        try {
            mPower.setTemporaryScreenAutoBrightnessAdjustmentSettingOverride(adj);
        } catch (RemoteException ex) {
        }
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
}
