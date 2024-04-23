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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.display.DisplayManagerInternal;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy;
import com.android.server.display.brightness.strategy.AutomaticBrightnessStrategy2;
import com.android.server.display.brightness.strategy.BoostBrightnessStrategy;
import com.android.server.display.brightness.strategy.DisplayBrightnessStrategy;
import com.android.server.display.brightness.strategy.DozeBrightnessStrategy;
import com.android.server.display.brightness.strategy.FollowerBrightnessStrategy;
import com.android.server.display.brightness.strategy.InvalidBrightnessStrategy;
import com.android.server.display.brightness.strategy.OffloadBrightnessStrategy;
import com.android.server.display.brightness.strategy.OverrideBrightnessStrategy;
import com.android.server.display.brightness.strategy.ScreenOffBrightnessStrategy;
import com.android.server.display.brightness.strategy.TemporaryBrightnessStrategy;
import com.android.server.display.feature.DisplayManagerFlags;

import java.io.PrintWriter;

/**
 * This maintains the logic needed to decide the eligible display brightness strategy.
 */
public class DisplayBrightnessStrategySelector {
    private static final String TAG = "DisplayBrightnessStrategySelector";
    // True if light sensor is to be used to automatically determine doze screen brightness.
    private final boolean mAllowAutoBrightnessWhileDozingConfig;

    // The brightness strategy used to manage the brightness state when the display is dozing.
    private final DozeBrightnessStrategy mDozeBrightnessStrategy;
    // The brightness strategy used to manage the brightness state when the display is in
    // screen off state.
    private final ScreenOffBrightnessStrategy mScreenOffBrightnessStrategy;
    // The brightness strategy used to manage the brightness state when the request state is
    // invalid.
    private final OverrideBrightnessStrategy mOverrideBrightnessStrategy;
    // The brightness strategy used to manage the brightness state in temporary state
    private final TemporaryBrightnessStrategy mTemporaryBrightnessStrategy;
    // The brightness strategy used to manage the brightness state when boost is requested
    private final BoostBrightnessStrategy mBoostBrightnessStrategy;
    // The brightness strategy used for additional displays
    private final FollowerBrightnessStrategy mFollowerBrightnessStrategy;
    // The brightness strategy used to manage the brightness state when the request is invalid.
    private final InvalidBrightnessStrategy mInvalidBrightnessStrategy;
    // Controls brightness when automatic (adaptive) brightness is running.
    private final AutomaticBrightnessStrategy2 mAutomaticBrightnessStrategy;

    // The automatic strategy which controls the brightness when adaptive mode is ON.
    private final AutomaticBrightnessStrategy mAutomaticBrightnessStrategy1;

    // The deprecated AutomaticBrightnessStrategy. Avoid using it for any new features without
    // consulting with the display frameworks team. Use {@link AutomaticBrightnessStrategy} instead.
    // This will be removed once the flag
    // {@link DisplayManagerFlags#isRefactorDisplayPowerControllerEnabled is fully rolled out
    private final AutomaticBrightnessStrategy2 mAutomaticBrightnessStrategy2;
    // Controls the brightness if adaptive brightness is on and there exists an active offload
    // session. Brightness value is provided by the offload session.
    @Nullable
    private final OffloadBrightnessStrategy mOffloadBrightnessStrategy;

    // A collective representation of all the strategies that the selector is aware of. This is
    // non null, but the strategies this is tracking can be null
    @NonNull
    private final DisplayBrightnessStrategy[] mDisplayBrightnessStrategies;

    @NonNull
    private final DisplayManagerFlags mDisplayManagerFlags;

    // We take note of the old brightness strategy so that we can know when the strategy changes.
    private String mOldBrightnessStrategyName;

    private final int mDisplayId;

    /**
     * The constructor of DozeBrightnessStrategy.
     */
    public DisplayBrightnessStrategySelector(Context context, Injector injector, int displayId,
            DisplayManagerFlags flags) {
        if (injector == null) {
            injector = new Injector();
        }
        mDisplayManagerFlags = flags;
        mDisplayId = displayId;
        mDozeBrightnessStrategy = injector.getDozeBrightnessStrategy();
        mScreenOffBrightnessStrategy = injector.getScreenOffBrightnessStrategy();
        mOverrideBrightnessStrategy = injector.getOverrideBrightnessStrategy();
        mTemporaryBrightnessStrategy = injector.getTemporaryBrightnessStrategy();
        mBoostBrightnessStrategy = injector.getBoostBrightnessStrategy();
        mFollowerBrightnessStrategy = injector.getFollowerBrightnessStrategy(displayId);
        mInvalidBrightnessStrategy = injector.getInvalidBrightnessStrategy();
        mAutomaticBrightnessStrategy1 =
                (!mDisplayManagerFlags.isRefactorDisplayPowerControllerEnabled()) ? null
                        : injector.getAutomaticBrightnessStrategy1(context, displayId);
        mAutomaticBrightnessStrategy2 =
                (mDisplayManagerFlags.isRefactorDisplayPowerControllerEnabled()) ? null
                        : injector.getAutomaticBrightnessStrategy2(context, displayId);
        mAutomaticBrightnessStrategy =
                (mDisplayManagerFlags.isRefactorDisplayPowerControllerEnabled())
                        ? mAutomaticBrightnessStrategy1 : mAutomaticBrightnessStrategy2;
        if (flags.isDisplayOffloadEnabled()) {
            mOffloadBrightnessStrategy = injector
                    .getOffloadBrightnessStrategy(mDisplayManagerFlags);
        } else {
            mOffloadBrightnessStrategy = null;
        }
        mDisplayBrightnessStrategies = new DisplayBrightnessStrategy[]{mInvalidBrightnessStrategy,
                mScreenOffBrightnessStrategy, mDozeBrightnessStrategy, mFollowerBrightnessStrategy,
                mBoostBrightnessStrategy, mOverrideBrightnessStrategy, mTemporaryBrightnessStrategy,
                mAutomaticBrightnessStrategy1, mOffloadBrightnessStrategy};
        mAllowAutoBrightnessWhileDozingConfig = context.getResources().getBoolean(
                R.bool.config_allowAutoBrightnessWhileDozing);
        mOldBrightnessStrategyName = mInvalidBrightnessStrategy.getName();
    }

    /**
     * Selects the appropriate DisplayBrightnessStrategy based on the request and the display state
     * to which the display is transitioning
     */
    @NonNull
    public DisplayBrightnessStrategy selectStrategy(
            StrategySelectionRequest strategySelectionRequest) {
        DisplayBrightnessStrategy displayBrightnessStrategy = mInvalidBrightnessStrategy;
        int targetDisplayState = strategySelectionRequest.getTargetDisplayState();
        DisplayManagerInternal.DisplayPowerRequest displayPowerRequest = strategySelectionRequest
                .getDisplayPowerRequest();
        if (targetDisplayState == Display.STATE_OFF) {
            displayBrightnessStrategy = mScreenOffBrightnessStrategy;
        } else if (shouldUseDozeBrightnessStrategy(displayPowerRequest)) {
            displayBrightnessStrategy = mDozeBrightnessStrategy;
        } else if (BrightnessUtils.isValidBrightnessValue(
                mFollowerBrightnessStrategy.getBrightnessToFollow())) {
            displayBrightnessStrategy = mFollowerBrightnessStrategy;
        } else if (displayPowerRequest.boostScreenBrightness) {
            displayBrightnessStrategy = mBoostBrightnessStrategy;
        } else if (BrightnessUtils
                .isValidBrightnessValue(displayPowerRequest.screenBrightnessOverride)) {
            displayBrightnessStrategy = mOverrideBrightnessStrategy;
        } else if (BrightnessUtils.isValidBrightnessValue(
                mTemporaryBrightnessStrategy.getTemporaryScreenBrightness())) {
            displayBrightnessStrategy = mTemporaryBrightnessStrategy;
        } else if (mDisplayManagerFlags.isRefactorDisplayPowerControllerEnabled()
                && isAutomaticBrightnessStrategyValid(strategySelectionRequest)) {
            displayBrightnessStrategy = mAutomaticBrightnessStrategy1;
        } else if (mAutomaticBrightnessStrategy.shouldUseAutoBrightness()
                && mOffloadBrightnessStrategy != null && BrightnessUtils.isValidBrightnessValue(
                mOffloadBrightnessStrategy.getOffloadScreenBrightness())) {
            displayBrightnessStrategy = mOffloadBrightnessStrategy;
        }

        if (mDisplayManagerFlags.isRefactorDisplayPowerControllerEnabled()) {
            postProcess(constructStrategySelectionNotifyRequest(displayBrightnessStrategy,
                    strategySelectionRequest));
        }

        if (!mOldBrightnessStrategyName.equals(displayBrightnessStrategy.getName())) {
            Slog.i(TAG,
                    "Changing the DisplayBrightnessStrategy from " + mOldBrightnessStrategyName
                            + " to " + displayBrightnessStrategy.getName() + " for display "
                            + mDisplayId);
            mOldBrightnessStrategyName = displayBrightnessStrategy.getName();
        }
        return displayBrightnessStrategy;
    }

    public TemporaryBrightnessStrategy getTemporaryDisplayBrightnessStrategy() {
        return mTemporaryBrightnessStrategy;
    }

    public FollowerBrightnessStrategy getFollowerDisplayBrightnessStrategy() {
        return mFollowerBrightnessStrategy;
    }

    public AutomaticBrightnessStrategy2 getAutomaticBrightnessStrategy() {
        return mAutomaticBrightnessStrategy;
    }

    @Nullable
    public OffloadBrightnessStrategy getOffloadBrightnessStrategy() {
        return mOffloadBrightnessStrategy;
    }

    /**
     * Returns a boolean flag indicating if the light sensor is to be used to decide the screen
     * brightness when dozing
     */
    public boolean isAllowAutoBrightnessWhileDozingConfig() {
        return mAllowAutoBrightnessWhileDozingConfig;
    }

    /**
     * Dumps the state of this class.
     */
    public void dump(PrintWriter writer) {
        writer.println();
        writer.println("DisplayBrightnessStrategySelector:");
        writer.println("  mDisplayId= " + mDisplayId);
        writer.println("  mOldBrightnessStrategyName= " + mOldBrightnessStrategyName);
        writer.println(
                "  mAllowAutoBrightnessWhileDozingConfig= "
                        + mAllowAutoBrightnessWhileDozingConfig);
        IndentingPrintWriter ipw = new IndentingPrintWriter(writer, " ");
        for (DisplayBrightnessStrategy displayBrightnessStrategy : mDisplayBrightnessStrategies) {
            if (displayBrightnessStrategy != null) {
                displayBrightnessStrategy.dump(ipw);
            }
        }
    }

    private boolean isAutomaticBrightnessStrategyValid(
            StrategySelectionRequest strategySelectionRequest) {
        mAutomaticBrightnessStrategy1.setAutoBrightnessState(
                strategySelectionRequest.getTargetDisplayState(),
                mAllowAutoBrightnessWhileDozingConfig,
                BrightnessReason.REASON_UNKNOWN,
                strategySelectionRequest.getDisplayPowerRequest().policy,
                strategySelectionRequest.getLastUserSetScreenBrightness(),
                strategySelectionRequest.isUserSetBrightnessChanged());
        return mAutomaticBrightnessStrategy1.isAutoBrightnessValid();
    }

    private StrategySelectionNotifyRequest constructStrategySelectionNotifyRequest(
            DisplayBrightnessStrategy selectedDisplayBrightnessStrategy,
            StrategySelectionRequest strategySelectionRequest) {
        return new StrategySelectionNotifyRequest(
                        strategySelectionRequest.getDisplayPowerRequest(),
                strategySelectionRequest.getTargetDisplayState(),
                selectedDisplayBrightnessStrategy,
                strategySelectionRequest.getLastUserSetScreenBrightness(),
                strategySelectionRequest.isUserSetBrightnessChanged(),
                isAllowAutoBrightnessWhileDozingConfig());
    }

    private void postProcess(StrategySelectionNotifyRequest strategySelectionNotifyRequest) {
        for (DisplayBrightnessStrategy displayBrightnessStrategy : mDisplayBrightnessStrategies) {
            if (displayBrightnessStrategy != null) {
                displayBrightnessStrategy.strategySelectionPostProcessor(
                        strategySelectionNotifyRequest);
            }
        }
    }

    /**
     * Validates if the conditions are met to qualify for the DozeBrightnessStrategy.
     */
    private boolean shouldUseDozeBrightnessStrategy(
            DisplayManagerInternal.DisplayPowerRequest displayPowerRequest) {
        // We are not checking the targetDisplayState, but rather relying on the policy because
        // a user can define a different display state(displayPowerRequest.dozeScreenState) too
        // in the request with the Doze policy
        return displayPowerRequest.policy == DisplayManagerInternal.DisplayPowerRequest.POLICY_DOZE
                && !mAllowAutoBrightnessWhileDozingConfig
                && BrightnessUtils.isValidBrightnessValue(displayPowerRequest.dozeScreenBrightness);
    }

    @VisibleForTesting
    static class Injector {
        ScreenOffBrightnessStrategy getScreenOffBrightnessStrategy() {
            return new ScreenOffBrightnessStrategy();
        }

        DozeBrightnessStrategy getDozeBrightnessStrategy() {
            return new DozeBrightnessStrategy();
        }

        OverrideBrightnessStrategy getOverrideBrightnessStrategy() {
            return new OverrideBrightnessStrategy();
        }

        TemporaryBrightnessStrategy getTemporaryBrightnessStrategy() {
            return new TemporaryBrightnessStrategy();
        }

        BoostBrightnessStrategy getBoostBrightnessStrategy() {
            return new BoostBrightnessStrategy();
        }

        FollowerBrightnessStrategy getFollowerBrightnessStrategy(int displayId) {
            return new FollowerBrightnessStrategy(displayId);
        }

        InvalidBrightnessStrategy getInvalidBrightnessStrategy() {
            return new InvalidBrightnessStrategy();
        }

        AutomaticBrightnessStrategy getAutomaticBrightnessStrategy1(Context context,
                int displayId) {
            return new AutomaticBrightnessStrategy(context, displayId);
        }

        AutomaticBrightnessStrategy2 getAutomaticBrightnessStrategy2(Context context,
                int displayId) {
            return new AutomaticBrightnessStrategy2(context, displayId);
        }

        OffloadBrightnessStrategy getOffloadBrightnessStrategy(
                DisplayManagerFlags displayManagerFlags) {
            return new OffloadBrightnessStrategy(displayManagerFlags);
        }
    }
}
