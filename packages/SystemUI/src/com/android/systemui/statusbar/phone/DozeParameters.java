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

import android.content.res.Resources;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.MathUtils;

import androidx.annotation.NonNull;

import com.android.systemui.Dumpable;
import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.AlwaysOnDisplayPolicy;
import com.android.systemui.doze.DozeScreenState;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.tuner.TunerService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;

/**
 * Retrieve doze information
 */
@SysUISingleton
public class DozeParameters implements
        TunerService.Tunable,
        com.android.systemui.plugins.statusbar.DozeParameters,
        Dumpable {
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
    private final UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;

    private final Set<Callback> mCallbacks = new HashSet<>();

    private boolean mDozeAlwaysOn;
    private boolean mControlScreenOffAnimation;

    @Inject
    protected DozeParameters(
            @Main Resources resources,
            AmbientDisplayConfiguration ambientDisplayConfiguration,
            AlwaysOnDisplayPolicy alwaysOnDisplayPolicy,
            PowerManager powerManager,
            BatteryController batteryController,
            TunerService tunerService,
            DumpManager dumpManager,
            FeatureFlags featureFlags,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController) {
        mResources = resources;
        mAmbientDisplayConfiguration = ambientDisplayConfiguration;
        mAlwaysOnPolicy = alwaysOnDisplayPolicy;
        mBatteryController = batteryController;
        dumpManager.registerDumpable("DozeParameters", this);

        mControlScreenOffAnimation = !getDisplayNeedsBlanking();
        mPowerManager = powerManager;
        mPowerManager.setDozeAfterScreenOff(!mControlScreenOffAnimation);
        mFeatureFlags = featureFlags;
        mUnlockedScreenOffAnimationController = unlockedScreenOffAnimationController;

        tunerService.addTunable(
                this,
                Settings.Secure.DOZE_ALWAYS_ON,
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED);
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

    public boolean isQuickPickupEnabled() {
        return mAmbientDisplayConfiguration.quickPickupSensorEnabled(UserHandle.USER_CURRENT);
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

    /**
     * Whether we want to control the screen off animation when the device is unlocked. If we do,
     * we'll animate in AOD before turning off the screen, rather than simply fading to black and
     * then abruptly showing AOD.
     */
    public boolean shouldControlUnlockedScreenOff() {
        return mUnlockedScreenOffAnimationController.shouldPlayUnlockedScreenOffAnimation();
    }

    /**
     * Whether we're capable of controlling the screen off animation if we want to. This isn't
     * possible if AOD isn't even enabled or if the flag is disabled.
     */
    public boolean canControlUnlockedScreenOff() {
        return getAlwaysOn()
                && mFeatureFlags.useNewLockscreenAnimations()
                && !getDisplayNeedsBlanking();
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
     * Whether the single tap sensor uses the proximity sensor.
     */
    public boolean singleTapUsesProx() {
        return mResources.getBoolean(R.bool.doze_single_tap_uses_prox);
    }

    /**
     * Whether the long press sensor uses the proximity sensor.
     */
    public boolean longPressUsesProx() {
        return mResources.getBoolean(R.bool.doze_long_press_uses_prox);
    }

    /**
     * Whether the brightness sensor uses the proximity sensor.
     */
    public boolean brightnessUsesProx() {
        return mResources.getBoolean(R.bool.doze_brightness_uses_prox);
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
        for (Callback callback : mCallbacks) {
            callback.onAlwaysOnChange();
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
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
        pw.print("brightnessUsesProx(): "); pw.println(brightnessUsesProx());
    }

    interface Callback {
        /**
         * Invoked when the value of getAlwaysOn may have changed.
         */
        void onAlwaysOnChange();
    }
}
