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

package com.android.server.display.brightness.strategy;

import android.os.PowerManager;

import com.android.server.display.DisplayBrightnessState;
import com.android.server.display.brightness.BrightnessReason;
import com.android.server.display.brightness.BrightnessUtils;
import com.android.server.display.brightness.StrategyExecutionRequest;
import com.android.server.display.brightness.StrategySelectionNotifyRequest;

import java.io.PrintWriter;

/**
 * Manages the brightness of an additional display that copies the brightness value from the lead
 * display when the device is using concurrent displays.
 */
public class FollowerBrightnessStrategy implements DisplayBrightnessStrategy {

    // The ID of the LogicalDisplay using this strategy.
    private final int mDisplayId;

    // Set to PowerManager.BRIGHTNESS_INVALID_FLOAT when there's no brightness to follow set.
    private float mBrightnessToFollow;

    // Indicates whether we should ramp slowly to the brightness value to follow.
    private boolean mBrightnessToFollowSlowChange;

    public FollowerBrightnessStrategy(int displayId) {
        mDisplayId = displayId;
        mBrightnessToFollow = PowerManager.BRIGHTNESS_INVALID_FLOAT;
        mBrightnessToFollowSlowChange = false;
    }

    @Override
    public DisplayBrightnessState updateBrightness(
            StrategyExecutionRequest strategyExecutionRequest) {
        // Todo(b/241308599): Introduce a validator class and add validations before setting
        // the brightness
        return BrightnessUtils.constructDisplayBrightnessState(BrightnessReason.REASON_FOLLOWER,
                mBrightnessToFollow, mBrightnessToFollow, getName(), mBrightnessToFollowSlowChange);
    }

    @Override
    public String getName() {
        return "FollowerBrightnessStrategy";
    }

    public float getBrightnessToFollow() {
        return mBrightnessToFollow;
    }

    /**
     * Updates brightness value and brightness slowChange flag
     **/
    public void setBrightnessToFollow(float brightnessToFollow, boolean slowChange) {
        mBrightnessToFollow = brightnessToFollow;
        mBrightnessToFollowSlowChange = slowChange;
    }

    /**
     * Dumps the state of this class.
     */
    @Override
    public void dump(PrintWriter writer) {
        writer.println("FollowerBrightnessStrategy:");
        writer.println("  mDisplayId=" + mDisplayId);
        writer.println("  mBrightnessToFollow:" + mBrightnessToFollow);
        writer.println("  mBrightnessToFollowSlowChange:" + mBrightnessToFollowSlowChange);
    }

    @Override
    public void strategySelectionPostProcessor(
            StrategySelectionNotifyRequest strategySelectionNotifyRequest) {
        // DO NOTHING
    }

    @Override
    public int getReason() {
        return BrightnessReason.REASON_FOLLOWER;
    }
}
