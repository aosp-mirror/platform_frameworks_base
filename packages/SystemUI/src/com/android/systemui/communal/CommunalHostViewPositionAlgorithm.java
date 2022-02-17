/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.communal;

import android.util.Log;

import com.android.systemui.statusbar.phone.NotificationPanelViewController;

/**
 * {@link CommunalHostViewPositionAlgorithm} calculates the position of the communal view given
 * input such as the notification panel position.
 */
public class CommunalHostViewPositionAlgorithm {
    private static final String TAG = "CommunalPositionAlg";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * @see NotificationPanelViewController#getExpandedFraction()
     */
    private float mPanelExpansion;

    /**
     * Height of {@link CommunalHostView}.
     */
    private int mCommunalHeight;

    /**
     * A data container for the result of the position algorithm.
     */
    public static class Result {
        /**
         * The y translation of the clock.
         */
        public int communalY;
    }

    /**
     * Sets the conditions under which the result should be calculated from.
     * @param panelExpansion The percentage the keyguard panel has been moved upwards.
     * @param communalHeight The height of the communal panel.
     */
    public void setup(float panelExpansion, int communalHeight) {
        if (DEBUG) {
            Log.d(TAG, "setup. panelExpansion:" + panelExpansion);
        }
        mPanelExpansion = panelExpansion;
        mCommunalHeight = communalHeight;
    }

    /**
     * Calculates the position based on factors input through {link {@link #setup(float, int)}}.
     * @param result The resulting calculations.
     */
    public void run(Result result) {
        // The panel expansion relates to the keyguard expansion. At full expansion, the communal
        // view should be aligned at the top (0). Otherwise, it should be shifted offscreen by the
        // unexpanded amount.
        result.communalY = (int) ((1 - mPanelExpansion) * -mCommunalHeight);
    }
}
