/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.display.brightness;

import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.os.PowerManager;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.BrightnessSetting;
import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.strategy.DisplayBrightnessStrategy;

import java.io.PrintWriter;

/**
 * Deploys different DozeBrightnessStrategy to choose the current brightness for a specified
 * display. Applies the chosen brightness.
 */
public final class DisplayBrightnessController {
    // The ID of the display tied to this DisplayBrightnessController
    private final int mDisplayId;

    // The lock which is to be used to synchronize the resources being used in this class
    private final Object mLock = new Object();

    // The default screen brightness to be used when no value is available in BrightnessSetting.
    private final float mScreenBrightnessDefault;

    // This is used to persist the changes happening to the brightness.
    private final BrightnessSetting mBrightnessSetting;

    // A runnable to update the clients registered via DisplayManagerGlobal
    // .EVENT_DISPLAY_BRIGHTNESS_CHANGED about the brightness change. Called when
    // mCurrentScreenBrightness is updated.
    private Runnable mOnBrightnessChangeRunnable;

    // The screen brightness that has changed but not taken effect yet. If this is different
    // from the current screen brightness then this is coming from something other than us
    // and should be considered a user interaction.
    @GuardedBy("mLock")
    private float mPendingScreenBrightness;

    // The last observed screen brightness, either set by us or by the settings app on
    // behalf of the user.
    @GuardedBy("mLock")
    private float mCurrentScreenBrightness;

    // The last brightness that was set by the user and not temporary. Set to
    // PowerManager.BRIGHTNESS_INVALID_FLOAT when a brightness has yet to be recorded.
    @GuardedBy("mLock")
    private float mLastUserSetScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;

    // The listener which is to be notified everytime there is a change in the brightness in the
    // BrightnessSetting.
    private BrightnessSetting.BrightnessSettingListener mBrightnessSettingListener;

    // Selects an appropriate strategy based on the request provided by the clients.
    @GuardedBy("mLock")
    private DisplayBrightnessStrategySelector mDisplayBrightnessStrategySelector;

    // Currently selected DisplayBrightnessStrategy.
    @GuardedBy("mLock")
    private DisplayBrightnessStrategy mDisplayBrightnessStrategy;

    /**
     * The constructor of DisplayBrightnessController.
     */
    public DisplayBrightnessController(Context context, Injector injector, int displayId,
            float defaultScreenBrightness, BrightnessSetting brightnessSetting,
            Runnable onBrightnessChangeRunnable) {
        if (injector == null) {
            injector = new Injector();
        }
        mDisplayId = displayId;
        // TODO: b/186428377 update brightness setting when display changes
        mBrightnessSetting = brightnessSetting;
        mPendingScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mCurrentScreenBrightness = getScreenBrightnessSetting();
        mOnBrightnessChangeRunnable = onBrightnessChangeRunnable;
        mScreenBrightnessDefault = BrightnessUtils.clampAbsoluteBrightness(defaultScreenBrightness);
        mDisplayBrightnessStrategySelector = injector.getDisplayBrightnessStrategySelector(context,
                displayId);
    }

    /**
     * Updates the display brightness. This delegates the responsibility of selecting an appropriate
     * strategy to DisplayBrightnessStrategySelector, which is then applied to evaluate the
     * DisplayBrightnessState. In the future,
     * 1. This will account for clamping the brightness if needed.
     * 2. This will notify the system about the updated brightness
     *
     * @param displayPowerRequest The request to update the brightness
     * @param targetDisplayState  The target display state of the system
     */
    public DisplayBrightnessState updateBrightness(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest,
            int targetDisplayState) {
        synchronized (mLock) {
            mDisplayBrightnessStrategy = mDisplayBrightnessStrategySelector.selectStrategy(
                    displayPowerRequest, targetDisplayState);
            return mDisplayBrightnessStrategy.updateBrightness(displayPowerRequest);
        }
    }

    /**
     * Sets the temporary brightness
     */
    public void setTemporaryBrightness(Float temporaryBrightness) {
        synchronized (mLock) {
            setTemporaryBrightnessLocked(temporaryBrightness);
        }
    }

    /**
     * Sets the brightness to follow
     */
    public void setBrightnessToFollow(Float brightnessToFollow) {
        synchronized (mLock) {
            mDisplayBrightnessStrategySelector.getFollowerDisplayBrightnessStrategy()
                    .setBrightnessToFollow(brightnessToFollow);
        }
    }

    /**
     * Returns a boolean flag indicating if the light sensor is to be used to decide the screen
     * brightness when dozing
     */
    public boolean isAllowAutoBrightnessWhileDozingConfig() {
        synchronized (mLock) {
            return mDisplayBrightnessStrategySelector.isAllowAutoBrightnessWhileDozingConfig();
        }
    }

    /**
     * Sets the current screen brightness to the supplied value, and notifies all the listeners
     * requesting for change events on brightness change.
     */
    public void setCurrentScreenBrightness(float brightnessValue) {
        synchronized (mLock) {
            if (brightnessValue != mCurrentScreenBrightness) {
                mCurrentScreenBrightness = brightnessValue;
                mOnBrightnessChangeRunnable.run();
            }
        }
    }

    /**
     * Returns the last observed screen brightness.
     */
    public float getCurrentBrightness() {
        synchronized (mLock) {
            return mCurrentScreenBrightness;
        }
    }

    /**
     * Returns the screen brightness which has changed but has not taken any effect so far.
     */
    public float getPendingScreenBrightness() {
        synchronized (mLock) {
            return mPendingScreenBrightness;
        }
    }

    /**
     * Sets the pending screen brightness setting, representing a value which is requested, but not
     * yet processed.
     * @param brightnessValue The value to which the pending screen brightness is to be set.
     */
    public void setPendingScreenBrightness(float brightnessValue) {
        synchronized (mLock) {
            mPendingScreenBrightness = brightnessValue;
        }
    }

    /**
     * We want to return true if the user has set the screen brightness.
     * RBC on, off, and intensity changes will return false.
     * Slider interactions whilst in RBC will return true, just as when in non-rbc.
     */
    public boolean updateUserSetScreenBrightness() {
        synchronized (mLock) {
            if (!BrightnessUtils.isValidBrightnessValue(mPendingScreenBrightness)) {
                return false;
            }
            if (mCurrentScreenBrightness == mPendingScreenBrightness) {
                mPendingScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
                setTemporaryBrightnessLocked(PowerManager.BRIGHTNESS_INVALID_FLOAT);
                return false;
            }
            setCurrentScreenBrightness(mPendingScreenBrightness);
            mLastUserSetScreenBrightness = mPendingScreenBrightness;
            mPendingScreenBrightness = PowerManager.BRIGHTNESS_INVALID_FLOAT;
            setTemporaryBrightnessLocked(PowerManager.BRIGHTNESS_INVALID_FLOAT);
            return true;
        }
    }

    /**
     * Registers the BrightnessSettingListener with the BrightnessSetting, which will be notified
     * everytime there is a change in the brightness.
     */
    public void registerBrightnessSettingChangeListener(
            BrightnessSetting.BrightnessSettingListener brightnessSettingListener) {
        mBrightnessSettingListener = brightnessSettingListener;
        mBrightnessSetting.registerListener(mBrightnessSettingListener);
    }

    /**
     * Returns the last user set brightness which is not temporary.
     */
    public float getLastUserSetScreenBrightness() {
        synchronized (mLock) {
            return mLastUserSetScreenBrightness;
        }
    }

    /**
     * Returns the current screen brightnessSetting which is responsible for saving the brightness
     * in the persistent store
     */
    public float getScreenBrightnessSetting() {
        float brightness = mBrightnessSetting.getBrightness();
        synchronized (mLock) {
            if (Float.isNaN(brightness)) {
                brightness = mScreenBrightnessDefault;
            }
            return BrightnessUtils.clampAbsoluteBrightness(brightness);
        }
    }

    /**
     * Notifies the brightnessSetting to persist the supplied brightness value.
     */
    public void setBrightness(float brightnessValue) {
        // Update the setting, which will eventually call back into DPC to have us actually
        // update the display with the new value.
        mBrightnessSetting.setBrightness(brightnessValue);
    }

    /**
     * Sets the current screen brightness, and notifies the BrightnessSetting about the change.
     */
    public void updateScreenBrightnessSetting(float brightnessValue) {
        synchronized (mLock) {
            if (!BrightnessUtils.isValidBrightnessValue(brightnessValue)
                    || brightnessValue == mCurrentScreenBrightness) {
                return;
            }
            setCurrentScreenBrightness(brightnessValue);
            setBrightness(brightnessValue);
        }
    }

    /**
     * Stops the associated listeners when the display is stopped. Invoked when the {@link
     * #mDisplayId} is being removed.
     */
    public void stop() {
        if (mBrightnessSetting != null) {
            mBrightnessSetting.unregisterListener(mBrightnessSettingListener);
        }
    }

    /**
     * Used to dump the state.
     *
     * @param writer The PrintWriter used to dump the state.
     */
    public void dump(PrintWriter writer) {
        writer.println();
        writer.println("DisplayBrightnessController:");
        writer.println("  mDisplayId=: " + mDisplayId);
        writer.println("  mScreenBrightnessDefault=" + mScreenBrightnessDefault);
        synchronized (mLock) {
            writer.println("  mPendingScreenBrightness=" + mPendingScreenBrightness);
            writer.println("  mCurrentScreenBrightness=" + mCurrentScreenBrightness);
            writer.println("  mLastUserSetScreenBrightness="
                    + mLastUserSetScreenBrightness);
            if (mDisplayBrightnessStrategy != null) {
                writer.println("  Last selected DisplayBrightnessStrategy= "
                        + mDisplayBrightnessStrategy.getName());
            }
            IndentingPrintWriter ipw = new IndentingPrintWriter(writer, " ");
            mDisplayBrightnessStrategySelector.dump(ipw);
        }
    }

    @VisibleForTesting
    static class Injector {
        DisplayBrightnessStrategySelector getDisplayBrightnessStrategySelector(Context context,
                int displayId) {
            return new DisplayBrightnessStrategySelector(context, /* injector= */ null, displayId);
        }
    }

    @VisibleForTesting
    BrightnessSetting.BrightnessSettingListener getBrightnessSettingListenerLocked() {
        return mBrightnessSettingListener;
    }

    /**
     * Returns the current selected DisplayBrightnessStrategy
     */
    @VisibleForTesting
    DisplayBrightnessStrategy getCurrentDisplayBrightnessStrategyLocked() {
        synchronized (mLock) {
            return mDisplayBrightnessStrategy;
        }
    }

    private void setTemporaryBrightnessLocked(float temporaryBrightness) {
        synchronized (mLock) {
            mDisplayBrightnessStrategySelector.getTemporaryDisplayBrightnessStrategy()
                    .setTemporaryScreenBrightness(temporaryBrightness);
        }
    }
}
