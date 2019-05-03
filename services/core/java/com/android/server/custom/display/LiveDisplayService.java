/*
 * Copyright (C) 2016 The CyanogenMod Project
 *               2019 The LineageOS Project
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
package com.android.server.custom.display;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager.ServiceType;
import android.os.PowerManagerInternal;
import android.os.PowerSaveState;
import android.os.Process;
import android.os.UserHandle;
import android.view.Display;

import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

import com.android.server.custom.common.UserContentObserver;
import com.android.server.custom.display.TwilightTracker.TwilightListener;
import com.android.server.custom.display.TwilightTracker.TwilightState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import com.android.internal.custom.app.LineageContextConstants;
import com.android.internal.custom.hardware.HSIC;
import com.android.internal.custom.hardware.ILiveDisplayService;
import com.android.internal.custom.hardware.LiveDisplayConfig;
import android.provider.Settings;

import static com.android.internal.custom.hardware.LiveDisplayManager.FEATURE_MANAGED_OUTDOOR_MODE;
import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_DAY;
import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_FIRST;
import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_LAST;
import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_OFF;
import static com.android.internal.custom.hardware.LiveDisplayManager.MODE_OUTDOOR;

/**
 * LiveDisplay is an advanced set of features for improving
 * display quality under various ambient conditions.
 *
 * The service is constructed with a set of LiveDisplayFeatures
 * which provide capabilities such as outdoor mode, night mode,
 * and calibration. It interacts with LineageHardwareService to relay
 * changes down to the lower layers.
 */
public class LiveDisplayService extends SystemService {

    private static final String TAG = "LiveDisplay";

    private final Context mContext;
    private final Handler mHandler;
    private final ServiceThread mHandlerThread;

    private DisplayManager mDisplayManager;
    private ModeObserver mModeObserver;
    private final TwilightTracker mTwilightTracker;

    private boolean mAwaitingNudge = true;
    private boolean mSunset = false;

    private final List<LiveDisplayFeature> mFeatures = new ArrayList<LiveDisplayFeature>();

    private ColorTemperatureController mCTC;
    private DisplayHardwareController mDHC;
    private OutdoorModeController mOMC;
    private PictureAdjustmentController mPAC;

    private LiveDisplayConfig mConfig;

    static int MODE_CHANGED = 1;
    static int DISPLAY_CHANGED = 2;
    static int TWILIGHT_CHANGED = 4;
    static int ALL_CHANGED = 255;

    // PowerManager ServiceType to use when we're only
    // interested in gleaning global battery saver state.
    private static final int SERVICE_TYPE_DUMMY = ServiceType.LOCATION;

    static class State {
        public boolean mLowPowerMode = false;
        public boolean mScreenOn = false;
        public int mMode = -1;
        public TwilightState mTwilight = null;

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "[mLowPowerMode=%b, mScreenOn=%b, mMode=%d, mTwilight=%s",
                    mLowPowerMode, mScreenOn, mMode,
                    (mTwilight == null ? "NULL" : mTwilight.toString()));
        }
    }

    private final State mState = new State();

    public LiveDisplayService(Context context) {
        super(context);

        mContext = context;

        mHandlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_DEFAULT, false /*allowIo*/);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mTwilightTracker = new TwilightTracker(context);
    }

    @Override
    public void onStart() {
        publishBinderService(LineageContextConstants.LINEAGE_LIVEDISPLAY_SERVICE, mBinder);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {

            mAwaitingNudge = getSunsetCounter() < 1;

            mDHC = new DisplayHardwareController(mContext, mHandler);
            mFeatures.add(mDHC);

            mCTC = new ColorTemperatureController(mContext, mHandler, mDHC);
            mFeatures.add(mCTC);

            mOMC = new OutdoorModeController(mContext, mHandler);
            mFeatures.add(mOMC);

            mPAC = new PictureAdjustmentController(mContext, mHandler);
            mFeatures.add(mPAC);

            // Get capabilities, throw out any unused features
            final BitSet capabilities = new BitSet();
            for (Iterator<LiveDisplayFeature> it = mFeatures.iterator(); it.hasNext();) {
                final LiveDisplayFeature feature = it.next();
                if (!feature.getCapabilities(capabilities)) {
                    it.remove();
                }
            }

            // static config
            int defaultMode = mContext.getResources().getInteger(
                    com.android.internal.R.integer.config_defaultLiveDisplayMode);

            mConfig = new LiveDisplayConfig(capabilities, defaultMode,
                    mCTC.getDefaultDayTemperature(), mCTC.getDefaultNightTemperature(),
                    mOMC.getDefaultAutoOutdoorMode(), mDHC.getDefaultAutoContrast(),
                    mDHC.getDefaultCABC(), mDHC.getDefaultColorEnhancement(),
                    mCTC.getColorTemperatureRange(), mCTC.getColorBalanceRange(),
                    mPAC.getHueRange(), mPAC.getSaturationRange(),
                    mPAC.getIntensityRange(), mPAC.getContrastRange(),
                    mPAC.getSaturationThresholdRange());

            // listeners
            mDisplayManager = (DisplayManager) getContext().getSystemService(
                    Context.DISPLAY_SERVICE);
            mDisplayManager.registerDisplayListener(mDisplayListener, null);
            mState.mScreenOn = mDisplayManager.getDisplay(
                    Display.DEFAULT_DISPLAY).getState() == Display.STATE_ON;

            PowerManagerInternal pmi = LocalServices.getService(PowerManagerInternal.class);
            pmi.registerLowPowerModeObserver(mLowPowerModeListener);
            // ServiceType does not matter when retrieving global saver mode.
            mState.mLowPowerMode =
                    pmi.getLowPowerState(SERVICE_TYPE_DUMMY).globalBatterySaverEnabled;

            mTwilightTracker.registerListener(mTwilightListener, mHandler);
            mState.mTwilight = mTwilightTracker.getCurrentState();

            if (mConfig.hasModeSupport()) {
                mModeObserver = new ModeObserver(mHandler);
                mState.mMode = mModeObserver.getMode();
            }

            // start and update all features
            for (int i = 0; i < mFeatures.size(); i++) {
                mFeatures.get(i).start();
            }

            updateFeatures(ALL_CHANGED);
        }
    }

    private void updateFeatures(final int flags) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < mFeatures.size(); i++) {
                    mFeatures.get(i).update(flags, mState);
                }
            }
        });
    }

    private final IBinder mBinder = new ILiveDisplayService.Stub() {

        @Override
        public LiveDisplayConfig getConfig() {
            return mConfig;
        }

        @Override
        public int getMode() {
            if (mConfig != null && mConfig.hasModeSupport()) {
                return mModeObserver.getMode();
            } else {
                return MODE_OFF;
            }
        }

        @Override
        public boolean setMode(int mode) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.MANAGE_LIVEDISPLAY", null);
            if (!mConfig.hasModeSupport()) {
                return false;
            }
            return mModeObserver.setMode(mode);
        }

        @Override
        public float[] getColorAdjustment() {
            return mDHC.getColorAdjustment();
        }

        @Override
        public boolean setColorAdjustment(float[] adj) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.MANAGE_LIVEDISPLAY", null);
            return mDHC.setColorAdjustment(adj);
        }

        @Override
        public boolean isAutoContrastEnabled() {
            return mDHC.isAutoContrastEnabled();
        }

        @Override
        public  boolean setAutoContrastEnabled(boolean enabled) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.MANAGE_LIVEDISPLAY", null);
            return mDHC.setAutoContrastEnabled(enabled);
        }

        @Override
        public boolean isCABCEnabled() {
            return mDHC.isCABCEnabled();
        }

        @Override
        public boolean setCABCEnabled(boolean enabled) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.MANAGE_LIVEDISPLAY", null);
            return mDHC.setCABCEnabled(enabled);
        }

        @Override
        public boolean isColorEnhancementEnabled() {
            return mDHC.isColorEnhancementEnabled();
        }

        @Override
        public boolean setColorEnhancementEnabled(boolean enabled) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.MANAGE_LIVEDISPLAY", null);
            return mDHC.setColorEnhancementEnabled(enabled);
        }

        @Override
        public boolean isAutomaticOutdoorModeEnabled() {
            return mOMC.isAutomaticOutdoorModeEnabled();
        }

        @Override
        public boolean setAutomaticOutdoorModeEnabled(boolean enabled) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.MANAGE_LIVEDISPLAY", null);
            return mOMC.setAutomaticOutdoorModeEnabled(enabled);
        }

        @Override
        public int getDayColorTemperature() {
            return mCTC.getDayColorTemperature();
        }

        @Override
        public boolean setDayColorTemperature(int temperature) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.MANAGE_LIVEDISPLAY", null);
            mCTC.setDayColorTemperature(temperature);
            return true;
        }

        @Override
        public int getNightColorTemperature() {
            return mCTC.getNightColorTemperature();
        }

        @Override
        public boolean setNightColorTemperature(int temperature) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.MANAGE_LIVEDISPLAY", null);
            mCTC.setNightColorTemperature(temperature);
            return true;
        }

        @Override
        public int getColorTemperature() {
            return mCTC.getColorTemperature();
        }

        @Override
        public HSIC getPictureAdjustment() { return mPAC.getPictureAdjustment(); }

        @Override
        public boolean setPictureAdjustment(final HSIC hsic) { return mPAC.setPictureAdjustment(hsic); }

        @Override
        public HSIC getDefaultPictureAdjustment() { return mPAC.getDefaultPictureAdjustment(); }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

            pw.println();
            pw.println("LiveDisplay Service State:");
            pw.println("  mState=" + mState.toString());
            pw.println("  mConfig=" + mConfig.toString());
            pw.println("  mAwaitingNudge=" + mAwaitingNudge);

            for (int i = 0; i < mFeatures.size(); i++) {
                mFeatures.get(i).dump(pw);
            }
        }

        @Override
        public boolean isNight() {
            final TwilightState twilight = mTwilightTracker.getCurrentState();
            return twilight != null && twilight.isNight();
        }
    };

    // Listener for screen on/off events
    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY) {
                boolean screenOn = isScreenOn();
                if (screenOn != mState.mScreenOn) {
                    mState.mScreenOn = screenOn;
                    updateFeatures(DISPLAY_CHANGED);
                }
            }
        }
    };


    // Display postprocessing can have power impact.
    private PowerManagerInternal.LowPowerModeListener mLowPowerModeListener =
            new PowerManagerInternal.LowPowerModeListener() {
        @Override
        public void onLowPowerModeChanged(PowerSaveState state) {
            final boolean lowPowerMode = state.globalBatterySaverEnabled;
            if (lowPowerMode != mState.mLowPowerMode) {
                mState.mLowPowerMode = lowPowerMode;
                updateFeatures(MODE_CHANGED);
            }
         }

         @Override
         public int getServiceType() {
             return SERVICE_TYPE_DUMMY;
         }
    };

    // Watch for mode changes
    private final class ModeObserver extends UserContentObserver {

        private final Uri MODE_SETTING =
                Settings.System.getUriFor(Settings.System.DISPLAY_TEMPERATURE_MODE);

        ModeObserver(Handler handler) {
            super(handler);

            final ContentResolver cr = mContext.getContentResolver();
            cr.registerContentObserver(MODE_SETTING, false, this, UserHandle.USER_ALL);

            observe();
        }

        @Override
        protected void update() {
            int mode = getMode();
            if (mode != mState.mMode) {
                mState.mMode = mode;

                updateFeatures(MODE_CHANGED);
            }
        }

        int getMode() {
            return getInt(Settings.System.DISPLAY_TEMPERATURE_MODE,
                    mConfig.getDefaultMode());
        }

        boolean setMode(int mode) {
            if (mConfig.hasFeature(mode) && mode >= MODE_FIRST && mode <= MODE_LAST) {
                putInt(Settings.System.DISPLAY_TEMPERATURE_MODE, mode);
                if (mode != mConfig.getDefaultMode()) {
                    stopNudgingMe();
                }
                return true;
            }
            return false;
        }
    }

    // Night watchman
    private final TwilightListener mTwilightListener = new TwilightListener() {
        @Override
        public void onTwilightStateChanged() {
            mState.mTwilight = mTwilightTracker.getCurrentState();
            updateFeatures(TWILIGHT_CHANGED);
            nudge();
        }
    };

    private boolean isScreenOn() {
        return mDisplayManager.getDisplay(
                Display.DEFAULT_DISPLAY).getState() == Display.STATE_ON;
    }

    private int getSunsetCounter() {
        // Counter used to determine when we should tell the user about this feature.
        // If it's not used after 3 sunsets, we'll show the hint once.
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.LIVE_DISPLAY_HINTED,
                -3,
                UserHandle.USER_CURRENT);
    }


    private void updateSunsetCounter(int count) {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.LIVE_DISPLAY_HINTED,
                count,
                UserHandle.USER_CURRENT);
        mAwaitingNudge = count > 0;
    }

    private void stopNudgingMe() {
        if (mAwaitingNudge) {
            updateSunsetCounter(1);
        }
    }

    /**
     * Show a friendly notification to the user about the potential benefits of decreasing
     * blue light at night. Do this only once if the feature has not been used after
     * three sunsets. It would be great to enable this by default, but we don't want
     * the change of screen color to be considered a "bug" by a user who doesn't
     * understand what's happening.
     *
     * @param state
     */
    private void nudge() {
        final TwilightState twilight = mTwilightTracker.getCurrentState();
        if (!mAwaitingNudge || twilight == null) {
            return;
        }

        int counter = getSunsetCounter();

        // check if we should send the hint only once after sunset
        boolean transition = twilight.isNight() && !mSunset;
        mSunset = twilight.isNight();
        if (!transition) {
            return;
        }

        if (counter <= 0) {
            counter++;
            updateSunsetCounter(counter);
        }
        if (counter == 0) {
            //show the notification and don't come back here
            final Intent intent = new Intent("com.android.settings.LIVEDISPLAY_SETTINGS");
            PendingIntent result = PendingIntent.getActivity(
                    mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder builder = new Notification.Builder(mContext)
                    .setContentTitle(mContext.getResources().getString(
                            com.android.internal.R.string.live_display_title))
                    .setContentText(mContext.getResources().getString(
                            com.android.internal.R.string.live_display_hint))
                    .setSmallIcon(com.android.internal.R.drawable.ic_livedisplay_notif)
                    .setStyle(new Notification.BigTextStyle().bigText(mContext.getResources()
                             .getString(
                                     com.android.internal.R.string.live_display_hint)))
                    .setContentIntent(result)
                    .setAutoCancel(true);

            NotificationManager nm =
                    (NotificationManager)mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notifyAsUser(null, 1, builder.build(), UserHandle.CURRENT);

            updateSunsetCounter(1);
        }
    }

    private int getInt(String setting, int defValue) {
        return Settings.System.getIntForUser(mContext.getContentResolver(),
                setting, defValue, UserHandle.USER_CURRENT);
    }

    private void putInt(String setting, int value) {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                setting, value, UserHandle.USER_CURRENT);
    }
}
