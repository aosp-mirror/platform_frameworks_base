/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.display.AmbientDisplayConfiguration;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;
import android.util.MathUtils;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.AlwaysOnDisplayPolicy;
import com.android.systemui.doze.DozeScreenState;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.DevicePostureController;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.unfold.FoldAodAnimationController;
import com.android.systemui.unfold.SysUIUnfoldComponent;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.inject.Inject;

/**
 * Retrieve doze information
 */
@SysUISingleton
public class DozeParameters implements
        TunerService.Tunable,
        com.android.systemui.plugins.statusbar.DozeParameters,
        Dumpable, ConfigurationController.ConfigurationListener,
        StatusBarStateController.StateListener, FoldAodAnimationController.FoldAodAnimationStatus {
    private static final int MAX_DURATION = 60 * 1000;
    public static final boolean FORCE_NO_BLANKING =
            SystemProperties.getBoolean("debug.force_no_blanking", false);
    public static final boolean FORCE_BLANKING =
            SystemProperties.getBoolean("debug.force_blanking", false);

    private final AmbientDisplayConfiguration mAmbientDisplayConfiguration;
    private final PowerManager mPowerManager;

    private final AlwaysOnDisplayPolicy mAlwaysOnPolicy;
    private final Resources mResources;
    private final BatteryController mBatteryController;
    private final FeatureFlags mFeatureFlags;
    private final ScreenOffAnimationController mScreenOffAnimationController;
    private final FoldAodAnimationController mFoldAodAnimationController;
    private final UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;

    private final Set<Callback> mCallbacks = new HashSet<>();

    private boolean mDozeAlwaysOn;
    private boolean mControlScreenOffAnimation;
    private boolean mIsQuickPickupEnabled;

    private boolean mKeyguardShowing;
    @VisibleForTesting
    final KeyguardUpdateMonitorCallback mKeyguardVisibilityCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardVisibilityChanged(boolean showing) {
                    mKeyguardShowing = showing;
                    updateControlScreenOff();
                }

                @Override
                public void onShadeExpandedChanged(boolean expanded) {
                    updateControlScreenOff();
                }

                @Override
                public void onUserSwitchComplete(int newUserId) {
                    updateQuickPickupEnabled();
                }
            };

    @Inject
    protected DozeParameters(
            Context context,
            @Background Handler handler,
            @Main Resources resources,
            AmbientDisplayConfiguration ambientDisplayConfiguration,
            AlwaysOnDisplayPolicy alwaysOnDisplayPolicy,
            PowerManager powerManager,
            BatteryController batteryController,
            TunerService tunerService,
            DumpManager dumpManager,
            FeatureFlags featureFlags,
            ScreenOffAnimationController screenOffAnimationController,
            Optional<SysUIUnfoldComponent> sysUiUnfoldComponent,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ConfigurationController configurationController,
            StatusBarStateController statusBarStateController) {
        mResources = resources;
        mAmbientDisplayConfiguration = ambientDisplayConfiguration;
        mAlwaysOnPolicy = alwaysOnDisplayPolicy;
        mBatteryController = batteryController;
        dumpManager.registerDumpable("DozeParameters", this);

        mControlScreenOffAnimation = !getDisplayNeedsBlanking();
        mPowerManager = powerManager;
        mPowerManager.setDozeAfterScreenOff(!mControlScreenOffAnimation);
        mFeatureFlags = featureFlags;
        mScreenOffAnimationController = screenOffAnimationController;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;

        keyguardUpdateMonitor.registerCallback(mKeyguardVisibilityCallback);
        tunerService.addTunable(
                this,
                Settings.Secure.DOZE_ALWAYS_ON,
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
        configurationController.addCallback(this);
        statusBarStateController.addCallback(this);

        mFoldAodAnimationController = sysUiUnfoldComponent
                .map(SysUIUnfoldComponent::getFoldAodAnimationController).orElse(null);

        if (mFoldAodAnimationController != null) {
            mFoldAodAnimationController.addCallback(this);
        }

        SettingsObserver quickPickupSettingsObserver = new SettingsObserver(context, handler);
        quickPickupSettingsObserver.observe();
    }

    private void updateQuickPickupEnabled() {
        mIsQuickPickupEnabled =
                mAmbientDisplayConfiguration.quickPickupSensorEnabled(UserHandle.USER_CURRENT);
    }

    public boolean getDisplayStateSupported() {
        return getBoolean("doze.display.supported", R.bool.doze_display_state_supported);
    }

    public boolean getDozeSuspendDisplayStateSupported() {
        return mResources.getBoolean(R.bool.doze_suspend_display_state_supported);
    }

    public int getPulseDuration() {
        return getPulseInDuration() + getPulseVisibleDuration() + getPulseOutDuration();
    }

    public float getScreenBrightnessDoze() {
        return mResources.getInteger(
                com.android.internal.R.integer.config_screenBrightnessDoze) / 255f;
    }

    public int getPulseInDuration() {
        return getInt("doze.pulse.duration.in", R.integer.doze_pulse_duration_in);
    }

    public int getPulseVisibleDuration() {
        return getInt("doze.pulse.duration.visible", R.integer.doze_pulse_duration_visible);
    }

    public int getPulseOutDuration() {
        return getInt("doze.pulse.duration.out", R.integer.doze_pulse_duration_out);
    }

    public boolean getPulseOnSigMotion() {
        return getBoolean("doze.pulse.sigmotion", R.bool.doze_pulse_on_significant_motion);
    }

    public boolean getVibrateOnSigMotion() {
        return SystemProperties.getBoolean("doze.vibrate.sigmotion", false);
    }

    public boolean getVibrateOnPickup() {
        return SystemProperties.getBoolean("doze.vibrate.pickup", false);
    }

    public boolean getProxCheckBeforePulse() {
        return getBoolean("doze.pulse.proxcheck", R.bool.doze_proximity_check_before_pulse);
    }

    /**
     * @return true if we should only register for sensors that use the proximity sensor when the
     * display state is {@link android.view.Display.STATE_OFF},
     * {@link android.view.Display.STATE_DOZE} or {@link android.view.Display.STATE_DOZE_SUSPEND}
     */
    public boolean getSelectivelyRegisterSensorsUsingProx() {
        return getBoolean("doze.prox.selectively_register",
                R.bool.doze_selectively_register_prox);
    }

    public int getPickupVibrationThreshold() {
        return getInt("doze.pickup.vibration.threshold", R.integer.doze_pickup_vibration_threshold);
    }

    public int getQuickPickupAodDuration() {
        return getInt("doze.gesture.quickpickup.duration",
                R.integer.doze_quick_pickup_aod_duration);
    }

    /**
     * For how long a wallpaper can be visible in AoD before it fades aways.
     * @return duration in millis.
     */
    public long getWallpaperAodDuration() {
        if (shouldControlScreenOff()) {
            return DozeScreenState.ENTER_DOZE_HIDE_WALLPAPER_DELAY;
        }
        return mAlwaysOnPolicy.wallpaperVisibilityDuration;
    }

    /**
     * How long it takes for the wallpaper fade away (Animation duration.)
     * @return duration in millis.
     */
    public long getWallpaperFadeOutDuration() {
        return mAlwaysOnPolicy.wallpaperFadeOutDuration;
    }

    /**
     * Checks if always on is available and enabled for the current user.
     * @return {@code true} if enabled and available.
     */
    public boolean getAlwaysOn() {
        return mDozeAlwaysOn && !mBatteryController.isAodPowerSave();
    }

    /**
     * Whether the quick pickup gesture is supported and enabled for the device.
     */
    public boolean isQuickPickupEnabled() {
        return mIsQuickPickupEnabled;
    }

    /**
     * Some screens need to be completely black before changing the display power mode,
     * unexpected behavior might happen if this parameter isn't respected.
     *
     * @return {@code true} if screen needs to be completely black before a power transition.
     */
    public boolean getDisplayNeedsBlanking() {
        return FORCE_BLANKING || !FORCE_NO_BLANKING && mResources.getBoolean(
                com.android.internal.R.bool.config_displayBlanksAfterDoze);
    }

    public boolean shouldControlScreenOff() {
        return mControlScreenOffAnimation;
    }

    public void setControlScreenOffAnimation(boolean controlScreenOffAnimation) {
        if (mControlScreenOffAnimation == controlScreenOffAnimation) {
            return;
        }
        mControlScreenOffAnimation = controlScreenOffAnimation;
        mPowerManager.setDozeAfterScreenOff(!controlScreenOffAnimation);
    }

    public void updateControlScreenOff() {
        if (!getDisplayNeedsBlanking()) {
            final boolean controlScreenOff =
                    getAlwaysOn() && (mKeyguardShowing || shouldControlUnlockedScreenOff());
            setControlScreenOffAnimation(controlScreenOff);
        }
    }

    /**
     * Whether we're capable of controlling the screen off animation if we want to. This isn't
     * possible if AOD isn't even enabled or if the flag is disabled, or if the display needs
     * blanking.
     */
    public boolean canControlUnlockedScreenOff() {
        return getAlwaysOn()
                && mFeatureFlags.isEnabled(Flags.LOCKSCREEN_ANIMATIONS)
                && !getDisplayNeedsBlanking();
    }

    /**
     * Whether we want to control the screen off animation when the device is unlocked. If we do,
     * we'll animate in AOD before turning off the screen, rather than simply fading to black and
     * then abruptly showing AOD.
     *
     * There are currently several reasons we might not want to control the screen off even if we
     * are able to, such as the shade being expanded, being in landscape, or having animations
     * disabled for a11y.
     */
    public boolean shouldControlUnlockedScreenOff() {
        return mUnlockedScreenOffAnimationController.shouldPlayUnlockedScreenOffAnimation();
    }

    public boolean shouldDelayKeyguardShow() {
        return mScreenOffAnimationController.shouldDelayKeyguardShow();
    }

    public boolean shouldClampToDimBrightness() {
        return mScreenOffAnimationController.shouldClampDozeScreenBrightness();
    }

    public boolean shouldShowLightRevealScrim() {
        return mScreenOffAnimationController.shouldShowLightRevealScrim();
    }

    public boolean shouldAnimateDozingChange() {
        return mScreenOffAnimationController.shouldAnimateDozingChange();
    }

    /**
     * When this method returns true then moving display state to power save mode will be
     * delayed for a few seconds. This might be useful to play animations without reducing FPS.
     */
    public boolean shouldDelayDisplayDozeTransition() {
        return willAnimateFromLockScreenToAod()
                || mScreenOffAnimationController.shouldDelayDisplayDozeTransition();
    }

    private boolean willAnimateFromLockScreenToAod() {
        return getAlwaysOn() && mKeyguardShowing;
    }

    private boolean getBoolean(String propName, int resId) {
        return SystemProperties.getBoolean(propName, mResources.getBoolean(resId));
    }

    private int getInt(String propName, int resId) {
        int value = SystemProperties.getInt(propName, mResources.getInteger(resId));
        return MathUtils.constrain(value, 0, MAX_DURATION);
    }

    public int getPulseVisibleDurationExtended() {
        return 2 * getPulseVisibleDuration();
    }

    public boolean doubleTapReportsTouchCoordinates() {
        return mResources.getBoolean(R.bool.doze_double_tap_reports_touch_coordinates);
    }

    /**
     * Whether the single tap sensor uses the proximity sensor for this device posture.
     */
    public boolean singleTapUsesProx(@DevicePostureController.DevicePostureInt int devicePosture) {
        return getPostureSpecificBool(
                mResources.getIntArray(R.array.doze_single_tap_uses_prox_posture_mapping),
                singleTapUsesProx(),
                devicePosture
        );
    }

    /**
     * Whether the single tap sensor uses the proximity sensor.
     */
    private boolean singleTapUsesProx() {
        return mResources.getBoolean(R.bool.doze_single_tap_uses_prox);
    }

    /**
     * Whether the long press sensor uses the proximity sensor.
     */
    public boolean longPressUsesProx() {
        return mResources.getBoolean(R.bool.doze_long_press_uses_prox);
    }

    /**
     * Gets the brightness string array per posture. Brightness names along with
     * doze_brightness_sensor_type is used to determine the brightness sensor to use for
     * the current posture.
     */
    public String[] brightnessNames() {
        return mResources.getStringArray(R.array.doze_brightness_sensor_name_posture_mapping);
    }

    /**
     * Callback to listen for DozeParameter changes.
     */
    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Remove callback that listens for DozeParameter changes.
     */
    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        mDozeAlwaysOn = mAmbientDisplayConfiguration.alwaysOnEnabled(UserHandle.USER_CURRENT);

        if (key.equals(Settings.Secure.DOZE_ALWAYS_ON)) {
            updateControlScreenOff();
        }

        for (Callback callback : mCallbacks) {
            callback.onAlwaysOnChange();
        }
        mScreenOffAnimationController.onAlwaysOnChanged(getAlwaysOn());
    }

    @Override
    public void onConfigChanged(Configuration newConfig) {
        updateControlScreenOff();
    }

    @Override
    public void onStatePostChange() {
        updateControlScreenOff();
    }

    @Override
    public void onFoldToAodAnimationChanged() {
        updateControlScreenOff();
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.print("getAlwaysOn(): "); pw.println(getAlwaysOn());
        pw.print("getDisplayStateSupported(): "); pw.println(getDisplayStateSupported());
        pw.print("getPulseDuration(): "); pw.println(getPulseDuration());
        pw.print("getPulseInDuration(): "); pw.println(getPulseInDuration());
        pw.print("getPulseInVisibleDuration(): "); pw.println(getPulseVisibleDuration());
        pw.print("getPulseOutDuration(): "); pw.println(getPulseOutDuration());
        pw.print("getPulseOnSigMotion(): "); pw.println(getPulseOnSigMotion());
        pw.print("getVibrateOnSigMotion(): "); pw.println(getVibrateOnSigMotion());
        pw.print("getVibrateOnPickup(): "); pw.println(getVibrateOnPickup());
        pw.print("getProxCheckBeforePulse(): "); pw.println(getProxCheckBeforePulse());
        pw.print("getPickupVibrationThreshold(): "); pw.println(getPickupVibrationThreshold());
        pw.print("getSelectivelyRegisterSensorsUsingProx(): ");
        pw.println(getSelectivelyRegisterSensorsUsingProx());
        pw.print("isQuickPickupEnabled(): "); pw.println(isQuickPickupEnabled());
    }

    private boolean getPostureSpecificBool(
            int[] postureMapping,
            boolean defaultSensorBool,
            int posture) {
        boolean bool = defaultSensorBool;
        if (posture < postureMapping.length) {
            bool = postureMapping[posture] != 0;
        } else {
            Log.e("DozeParameters", "Unsupported doze posture " + posture);
        }

        return bool;
    }

    interface Callback {
        /**
         * Invoked when the value of getAlwaysOn may have changed.
         */
        void onAlwaysOnChange();
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri mQuickPickupGesture =
                Settings.Secure.getUriFor(Settings.Secure.DOZE_QUICK_PICKUP_GESTURE);
        private final Uri mPickupGesture =
                Settings.Secure.getUriFor(Settings.Secure.DOZE_PICK_UP_GESTURE);
        private final Uri mAlwaysOnEnabled =
                Settings.Secure.getUriFor(Settings.Secure.DOZE_ALWAYS_ON);
        private final Context mContext;

        SettingsObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(mQuickPickupGesture, false, this,
                    UserHandle.USER_ALL);
            resolver.registerContentObserver(mPickupGesture, false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(mAlwaysOnEnabled, false, this, UserHandle.USER_ALL);
            update(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        public void update(Uri uri) {
            if (uri == null
                    || mQuickPickupGesture.equals(uri)
                    || mPickupGesture.equals(uri)
                    || mAlwaysOnEnabled.equals(uri)) {
                // the quick pickup gesture is dependent on alwaysOn being disabled and
                // the pickup gesture being enabled
                updateQuickPickupEnabled();
            }
        }
    }
}
