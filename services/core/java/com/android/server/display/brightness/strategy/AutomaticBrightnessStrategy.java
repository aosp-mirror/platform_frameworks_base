/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.display.brightness.strategy;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.display.BrightnessConfiguration;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.AutomaticBrightnessController;
import com.android.server.display.brightness.BrightnessEvent;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.BrightnessUtils;

import java.io.PrintWriter;

/**
 * Helps manage the brightness based on the ambient environment (Ambient Light/lux sensor) using
 * mappings from lux to nits to brightness, configured in the
 * {@link com.android.server.display.DisplayDeviceConfig} class. This class inherently assumes
 * that it is being executed from the power thread, and hence doesn't synchronize
 * any of its resources
 */
public class AutomaticBrightnessStrategy {
    private final Context mContext;
    // The DisplayId of the associated logical display
    private final int mDisplayId;
    // The last auto brightness adjustment that was set by the user and is not temporary. Set to
    // Float.NaN when an auto-brightness adjustment hasn't been recorded yet.
    private float mAutoBrightnessAdjustment;
    // The pending auto brightness adjustment that will take effect on the next power state update.
    private float mPendingAutoBrightnessAdjustment;
    // The temporary auto brightness adjustment. This was historically used when a user interacts
    // with the adjustment slider but hasn't settled on a choice yet.
    // Set to PowerManager.BRIGHTNESS_INVALID_FLOAT when there's no temporary adjustment set.
    private float mTemporaryAutoBrightnessAdjustment;
    // Indicates if the temporary auto brightness adjustment has been applied while updating the
    // associated display brightness
    private boolean mAppliedTemporaryAutoBrightnessAdjustment;
    // Indicates if the auto brightness adjustment has happened.
    private boolean mAutoBrightnessAdjustmentChanged;
    // Indicates the reasons for the auto-brightness adjustment
    private int mAutoBrightnessAdjustmentReasonsFlags = 0;
    // Indicates if the short term model should be reset before fetching the new brightness
    // Todo(273543270): Short term model is an internal information of
    //  AutomaticBrightnessController and shouldn't be exposed outside of that class
    private boolean mShouldResetShortTermModel = false;
    // Remembers whether the auto-brightness has been applied in the latest brightness update.
    private boolean mAppliedAutoBrightness = false;
    // The controller for the automatic brightness level.
    @Nullable
    private AutomaticBrightnessController mAutomaticBrightnessController;
    // The system setting denoting if the auto-brightness for the current user is enabled or not
    private boolean mUseAutoBrightness = false;
    // Indicates if the auto-brightness is currently enabled or not. It's possible that even if
    // the user has enabled the auto-brightness from the settings, it is disabled because the
    // display is off
    private boolean mIsAutoBrightnessEnabled = false;
    // Indicates if auto-brightness is disabled due to the display being off. Needed for metric
    // purposes.
    private boolean mAutoBrightnessDisabledDueToDisplayOff;
    // If the auto-brightness model for the last manual changes done by the user.
    private boolean mIsShortTermModelActive = false;

    // The BrightnessConfiguration currently being used
    // Todo(273543270): BrightnessConfiguration is an internal implementation detail of
    //  AutomaticBrightnessController, and AutomaticBrightnessStrategy shouldn't be aware of its
    //  existence.
    @Nullable
    private BrightnessConfiguration mBrightnessConfiguration;

    public AutomaticBrightnessStrategy(Context context, int displayId) {
        mContext = context;
        mDisplayId = displayId;
        mAutoBrightnessAdjustment = getAutoBrightnessAdjustmentSetting();
        mPendingAutoBrightnessAdjustment = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mTemporaryAutoBrightnessAdjustment = PowerManager.BRIGHTNESS_INVALID_FLOAT;
    }

    /**
     * Sets up the automatic brightness states of this class. Also configures
     * AutomaticBrightnessController accounting for any manual changes made by the user.
     */
    public void setAutoBrightnessState(int targetDisplayState,
            boolean allowAutoBrightnessWhileDozingConfig, int brightnessReason, int policy,
            float lastUserSetScreenBrightness, boolean userSetBrightnessChanged) {
        final boolean autoBrightnessEnabledInDoze =
                allowAutoBrightnessWhileDozingConfig
                        && Display.isDozeState(targetDisplayState);
        mIsAutoBrightnessEnabled = shouldUseAutoBrightness()
                && (targetDisplayState == Display.STATE_ON || autoBrightnessEnabledInDoze)
                && brightnessReason != BrightnessReason.REASON_OVERRIDE
                && brightnessReason != BrightnessReason.REASON_OFFLOAD
                && mAutomaticBrightnessController != null;
        mAutoBrightnessDisabledDueToDisplayOff = shouldUseAutoBrightness()
                && !(targetDisplayState == Display.STATE_ON || autoBrightnessEnabledInDoze);
        final int autoBrightnessState = mIsAutoBrightnessEnabled
                    && brightnessReason != BrightnessReason.REASON_FOLLOWER
                ? AutomaticBrightnessController.AUTO_BRIGHTNESS_ENABLED
                : mAutoBrightnessDisabledDueToDisplayOff
                        ? AutomaticBrightnessController.AUTO_BRIGHTNESS_OFF_DUE_TO_DISPLAY_STATE
                        : AutomaticBrightnessController.AUTO_BRIGHTNESS_DISABLED;

        accommodateUserBrightnessChanges(userSetBrightnessChanged, lastUserSetScreenBrightness,
                policy, mBrightnessConfiguration, autoBrightnessState);
    }

    public boolean isAutoBrightnessEnabled() {
        return mIsAutoBrightnessEnabled;
    }

    public boolean isAutoBrightnessDisabledDueToDisplayOff() {
        return mAutoBrightnessDisabledDueToDisplayOff;
    }

    /**
     * Updates the {@link BrightnessConfiguration} that is currently being used by the associated
     * display.
     */
    public void setBrightnessConfiguration(BrightnessConfiguration brightnessConfiguration,
            boolean shouldResetShortTermModel) {
        mBrightnessConfiguration = brightnessConfiguration;
        setShouldResetShortTermModel(shouldResetShortTermModel);
    }

    /**
     * Promotes the pending auto-brightness adjustments which are yet to be applied to the current
     * adjustments. Note that this is not applying the new adjustments to the AutoBrightness mapping
     * strategies, but is only accommodating the changes in this class.
     */
    public boolean processPendingAutoBrightnessAdjustments() {
        mAutoBrightnessAdjustmentChanged = false;
        if (Float.isNaN(mPendingAutoBrightnessAdjustment)) {
            return false;
        }
        if (mAutoBrightnessAdjustment == mPendingAutoBrightnessAdjustment) {
            mPendingAutoBrightnessAdjustment = Float.NaN;
            return false;
        }
        mAutoBrightnessAdjustment = mPendingAutoBrightnessAdjustment;
        mPendingAutoBrightnessAdjustment = Float.NaN;
        mTemporaryAutoBrightnessAdjustment = Float.NaN;
        mAutoBrightnessAdjustmentChanged = true;
        return true;
    }

    /**
     * Updates the associated AutomaticBrightnessController
     */
    public void setAutomaticBrightnessController(
            AutomaticBrightnessController automaticBrightnessController) {
        if (automaticBrightnessController == mAutomaticBrightnessController) {
            return;
        }
        if (mAutomaticBrightnessController != null) {
            mAutomaticBrightnessController.stop();
        }
        mAutomaticBrightnessController = automaticBrightnessController;
    }

    /**
     * Returns if the auto-brightness of the associated display has been enabled or not
     */
    public boolean shouldUseAutoBrightness() {
        return mUseAutoBrightness;
    }

    /**
     * Sets the auto-brightness state of the associated display. Called when the user makes a change
     * in the system setting to enable/disable the auto-brightness.
     */
    public void setUseAutoBrightness(boolean useAutoBrightness) {
        mUseAutoBrightness = useAutoBrightness;
    }

    /**
     * Returns if the user made brightness change events(Typically when they interact with the
     * brightness slider) were accommodated in the auto-brightness mapping strategies. This doesn't
     * account for the latest changes that have been made by the user.
     */
    public boolean isShortTermModelActive() {
        return mIsShortTermModelActive;
    }

    /**
     * Sets the pending auto-brightness adjustments in the system settings. Executed
     * when there is a change in the brightness system setting, or when there is a user switch.
     */
    public void updatePendingAutoBrightnessAdjustments() {
        final float adj = Settings.System.getFloatForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0.0f, UserHandle.USER_CURRENT);
        mPendingAutoBrightnessAdjustment = Float.isNaN(adj) ? Float.NaN
                : BrightnessUtils.clampBrightnessAdjustment(adj);
    }

    /**
     * Sets the temporary auto-brightness adjustments
     */
    public void setTemporaryAutoBrightnessAdjustment(float temporaryAutoBrightnessAdjustment) {
        mTemporaryAutoBrightnessAdjustment = temporaryAutoBrightnessAdjustment;
    }

    /**
     * Dumps the state of this class.
     */
    public void dump(PrintWriter writer) {
        writer.println("AutomaticBrightnessStrategy:");
        writer.println("  mDisplayId=" + mDisplayId);
        writer.println("  mAutoBrightnessAdjustment=" + mAutoBrightnessAdjustment);
        writer.println("  mPendingAutoBrightnessAdjustment=" + mPendingAutoBrightnessAdjustment);
        writer.println(
                "  mTemporaryAutoBrightnessAdjustment=" + mTemporaryAutoBrightnessAdjustment);
        writer.println("  mShouldResetShortTermModel=" + mShouldResetShortTermModel);
        writer.println("  mAppliedAutoBrightness=" + mAppliedAutoBrightness);
        writer.println("  mAutoBrightnessAdjustmentChanged=" + mAutoBrightnessAdjustmentChanged);
        writer.println("  mAppliedTemporaryAutoBrightnessAdjustment="
                + mAppliedTemporaryAutoBrightnessAdjustment);
        writer.println("  mUseAutoBrightness=" + mUseAutoBrightness);
        writer.println("  mWasShortTermModelActive=" + mIsShortTermModelActive);
        writer.println("  mAutoBrightnessAdjustmentReasonsFlags="
                + mAutoBrightnessAdjustmentReasonsFlags);
    }

    /**
     * Indicates if any auto-brightness adjustments have happened since the last auto-brightness was
     * set.
     */
    public boolean getAutoBrightnessAdjustmentChanged() {
        return mAutoBrightnessAdjustmentChanged;
    }

    /**
     * Returns whether the latest temporary auto-brightness adjustments have been applied or not
     */
    public boolean isTemporaryAutoBrightnessAdjustmentApplied() {
        return mAppliedTemporaryAutoBrightnessAdjustment;
    }

    /**
     * Evaluates the target automatic brightness of the associated display.
     * @param brightnessEvent Event object to populate with details about why the specific
     *                        brightness was chosen.
     */
    public float getAutomaticScreenBrightness(BrightnessEvent brightnessEvent) {
        float brightness = (mAutomaticBrightnessController != null)
                ? mAutomaticBrightnessController.getAutomaticScreenBrightness(brightnessEvent)
                : PowerManager.BRIGHTNESS_INVALID_FLOAT;
        adjustAutomaticBrightnessStateIfValid(brightness);
        return brightness;
    }

    /**
     * Gets the auto-brightness adjustment flag change reason
     */
    public int getAutoBrightnessAdjustmentReasonsFlags() {
        return mAutoBrightnessAdjustmentReasonsFlags;
    }

    /**
     * Returns if the auto brightness has been applied
     */
    public boolean hasAppliedAutoBrightness() {
        return mAppliedAutoBrightness;
    }

    /**
     * Used to adjust the state of this class when the automatic brightness value for the
     * associated display is valid
     */
    @VisibleForTesting
    void adjustAutomaticBrightnessStateIfValid(float brightnessState) {
        mAutoBrightnessAdjustmentReasonsFlags = isTemporaryAutoBrightnessAdjustmentApplied()
                ? BrightnessReason.ADJUSTMENT_AUTO_TEMP
                : BrightnessReason.ADJUSTMENT_AUTO;
        float newAutoBrightnessAdjustment =
                (mAutomaticBrightnessController != null)
                        ? mAutomaticBrightnessController.getAutomaticScreenBrightnessAdjustment()
                        : 0.0f;
        if (!Float.isNaN(newAutoBrightnessAdjustment)
                && mAutoBrightnessAdjustment != newAutoBrightnessAdjustment) {
            // If the auto-brightness controller has decided to change the adjustment value
            // used, make sure that's reflected in settings.
            putAutoBrightnessAdjustmentSetting(newAutoBrightnessAdjustment);
        } else {
            mAutoBrightnessAdjustmentReasonsFlags = 0;
        }
    }

    /**
     * Sets up the system to reset the short term model. Note that this will not reset the model
     * right away, but ensures that the reset happens whenever the next brightness change happens
     */
    @VisibleForTesting
    void setShouldResetShortTermModel(boolean shouldResetShortTermModel) {
        mShouldResetShortTermModel = shouldResetShortTermModel;
    }

    @VisibleForTesting
    boolean shouldResetShortTermModel() {
        return mShouldResetShortTermModel;
    }

    @VisibleForTesting
    float getAutoBrightnessAdjustment() {
        return mAutoBrightnessAdjustment;
    }

    @VisibleForTesting
    float getPendingAutoBrightnessAdjustment() {
        return mPendingAutoBrightnessAdjustment;
    }

    @VisibleForTesting
    float getTemporaryAutoBrightnessAdjustment() {
        return mTemporaryAutoBrightnessAdjustment;
    }

    @VisibleForTesting
    void putAutoBrightnessAdjustmentSetting(float adjustment) {
        if (mDisplayId == Display.DEFAULT_DISPLAY) {
            mAutoBrightnessAdjustment = adjustment;
            Settings.System.putFloatForUser(mContext.getContentResolver(),
                    Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, adjustment,
                    UserHandle.USER_CURRENT);
        }
    }

    /**
     * Sets if the auto-brightness is applied on the latest brightness change.
     */
    public void setAutoBrightnessApplied(boolean autoBrightnessApplied) {
        mAppliedAutoBrightness = autoBrightnessApplied;
    }

    /**
     * Accommodates the latest manual changes made by the user. Also updates {@link
     * AutomaticBrightnessController} about the changes and configures it accordingly.
     */
    @VisibleForTesting
    void accommodateUserBrightnessChanges(boolean userSetBrightnessChanged,
            float lastUserSetScreenBrightness, int policy,
            BrightnessConfiguration brightnessConfiguration, int autoBrightnessState) {
        // Update the pending auto-brightness adjustments if any. This typically checks and adjusts
        // the state of the class if the user moves the brightness slider and has settled to a
        // different value
        processPendingAutoBrightnessAdjustments();
        // Update the temporary auto-brightness adjustments if any. This typically checks and
        // adjusts the state of this class if the user is in the process of moving the brightness
        // slider, but hasn't settled to any value yet
        float autoBrightnessAdjustment = updateTemporaryAutoBrightnessAdjustments();
        mIsShortTermModelActive = false;
        // Configure auto-brightness.
        if (mAutomaticBrightnessController != null) {
            // Accommodate user changes if any in the auto-brightness model
            mAutomaticBrightnessController.configure(autoBrightnessState,
                    brightnessConfiguration,
                    lastUserSetScreenBrightness,
                    userSetBrightnessChanged, autoBrightnessAdjustment,
                    mAutoBrightnessAdjustmentChanged, policy, mShouldResetShortTermModel);
            mShouldResetShortTermModel = false;
            // We take note if the user brightness point is still being used in the current
            // auto-brightness model.
            mIsShortTermModelActive = mAutomaticBrightnessController.hasUserDataPoints();
        }
    }

    /**
     * Evaluates if there are any temporary auto-brightness adjustments which is not applied yet.
     * Temporary brightness adjustments happen when the user moves the brightness slider in the
     * auto-brightness mode, but hasn't settled to a value yet
     */
    private float updateTemporaryAutoBrightnessAdjustments() {
        mAppliedTemporaryAutoBrightnessAdjustment =
                !Float.isNaN(mTemporaryAutoBrightnessAdjustment);
        // We do not update the mAutoBrightnessAdjustment with mTemporaryAutoBrightnessAdjustment
        // since we have not settled to a value yet
        return mAppliedTemporaryAutoBrightnessAdjustment
                ? mTemporaryAutoBrightnessAdjustment : mAutoBrightnessAdjustment;
    }

    /**
     * Returns the auto-brightness adjustment that is set in the system setting.
     */
    private float getAutoBrightnessAdjustmentSetting() {
        final float adj = Settings.System.getFloatForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_AUTO_BRIGHTNESS_ADJ, 0.0f, UserHandle.USER_CURRENT);
        return Float.isNaN(adj) ? 0.0f : BrightnessUtils.clampBrightnessAdjustment(adj);
    }
}
