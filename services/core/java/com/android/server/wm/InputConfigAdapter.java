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

package com.android.server.wm;

import android.os.InputConfig;
import android.view.InputWindowHandle.InputConfigFlags;
import android.view.WindowManager.LayoutParams;

import java.util.List;

/**
 * A helper to determine the {@link InputConfigFlags} that control the behavior of an input window
 * from several WM attributes.
 */
class InputConfigAdapter {
    private InputConfigAdapter() {}

    /** Describes a mapping from a flag value to a {@link InputConfigFlags} value. */
    private static class FlagMapping {
        final int mFlag;
        final int mInputConfig;
        final boolean mInverted;

        FlagMapping(int flag, int inputConfig, boolean inverted) {
            mFlag = flag;
            mInputConfig = inputConfig;
            mInverted = inverted;
        }
    }

    /**
     * A mapping from {@link LayoutParams.InputFeatureFlags} to {@link InputConfigFlags} for
     * input configurations that can be mapped directly from a corresponding LayoutParams input
     * feature.
     */
    private static final List<FlagMapping> INPUT_FEATURE_TO_CONFIG_MAP = List.of(
            new FlagMapping(
                    LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL,
                    InputConfig.NO_INPUT_CHANNEL, false /* inverted */),
            new FlagMapping(
                    LayoutParams.INPUT_FEATURE_DISABLE_USER_ACTIVITY,
                    InputConfig.DISABLE_USER_ACTIVITY, false /* inverted */),
            new FlagMapping(
                    LayoutParams.INPUT_FEATURE_SPY,
                    InputConfig.SPY, false /* inverted */));

    @InputConfigFlags
    private static final int INPUT_FEATURE_TO_CONFIG_MASK =
            computeMask(INPUT_FEATURE_TO_CONFIG_MAP);

    /**
     * A mapping from {@link LayoutParams.Flags} to {@link InputConfigFlags} for input
     * configurations that can be mapped directly from a corresponding LayoutParams flag.
     *
     * NOTE: The layout params flag {@link LayoutParams#FLAG_NOT_FOCUSABLE} is not handled by this
     * adapter, and must be handled explicitly.
     */
    private static final List<FlagMapping> LAYOUT_PARAM_FLAG_TO_CONFIG_MAP = List.of(
            new FlagMapping(
                    LayoutParams.FLAG_NOT_TOUCHABLE,
                    InputConfig.NOT_TOUCHABLE, false /* inverted */),
            new FlagMapping(
                    LayoutParams.FLAG_SPLIT_TOUCH,
                    InputConfig.PREVENT_SPLITTING, true /* inverted */),
            new FlagMapping(
                    LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    InputConfig.WATCH_OUTSIDE_TOUCH, false /* inverted */),
            new FlagMapping(
                    LayoutParams.FLAG_SLIPPERY,
                    InputConfig.SLIPPERY, false /* inverted */));

    @InputConfigFlags
    private static final int LAYOUT_PARAM_FLAG_TO_CONFIG_MASK =
            computeMask(LAYOUT_PARAM_FLAG_TO_CONFIG_MAP);

    /**
     * Returns a mask of all the input config flags configured by
     * {@link #getInputConfigFromWindowParams(int, int, int)}.
     */
    @InputConfigFlags
    static int getMask() {
        return LAYOUT_PARAM_FLAG_TO_CONFIG_MASK | INPUT_FEATURE_TO_CONFIG_MASK
                | InputConfig.IS_WALLPAPER;
    }

    /**
     * Get the {@link InputConfigFlags} value that provides the input window behavior specified by
     * the given WindowManager attributes.
     *
     * Use {@link #getMask()} to get the mask of all the input config flags set by this method.
     *
     * @param type the window type
     * @param flags the window flags
     * @param inputFeatures the input feature flags
     */
    @InputConfigFlags
    static int getInputConfigFromWindowParams(@LayoutParams.WindowType int type,
            @LayoutParams.Flags int flags, @LayoutParams.InputFeatureFlags int inputFeatures) {
        return (type == LayoutParams.TYPE_WALLPAPER ? InputConfig.IS_WALLPAPER : 0)
                | applyMapping(flags, LAYOUT_PARAM_FLAG_TO_CONFIG_MAP)
                | applyMapping(inputFeatures, INPUT_FEATURE_TO_CONFIG_MAP);
    }

    @InputConfigFlags
    private static int applyMapping(int flags, List<FlagMapping> flagToConfigMap) {
        int inputConfig = 0;
        for (final FlagMapping mapping : flagToConfigMap) {
            final boolean flagSet = (flags & mapping.mFlag) != 0;
            if (flagSet != mapping.mInverted) {
                inputConfig |= mapping.mInputConfig;
            }
        }
        return inputConfig;
    }

    @InputConfigFlags
    private static int computeMask(List<FlagMapping> flagToConfigMap) {
        int mask = 0;
        for (final FlagMapping mapping : flagToConfigMap) {
            mask |= mapping.mInputConfig;
        }
        return mask;
    }
}
