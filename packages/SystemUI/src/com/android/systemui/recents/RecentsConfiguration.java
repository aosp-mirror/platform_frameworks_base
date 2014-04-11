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
 * limitations under the License.
 */

package com.android.systemui.recents;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import com.android.systemui.R;


/** A static Recents configuration for the current context
 * NOTE: We should not hold any references to a Context from a static instance */
public class RecentsConfiguration {
    static RecentsConfiguration sInstance;

    DisplayMetrics mDisplayMetrics;

    public Rect systemInsets = new Rect();
    public Rect displayRect = new Rect();

    public float animationPxMovementPerSecond;

    public int filteringCurrentViewsMinAnimDuration;
    public int filteringNewViewsMinAnimDuration;
    public int taskBarEnterAnimDuration;

    public boolean launchedWithThumbnailAnimation;

    /** Private constructor */
    private RecentsConfiguration() {}

    /** Updates the configuration to the current context */
    public static RecentsConfiguration reinitialize(Context context) {
        if (sInstance == null) {
            sInstance = new RecentsConfiguration();
        }
        sInstance.update(context);
        return sInstance;
    }

    /** Returns the current recents configuration */
    public static RecentsConfiguration getInstance() {
        return sInstance;
    }

    /** Updates the state, given the specified context */
    void update(Context context) {
        Resources res = context.getResources();
        DisplayMetrics dm = res.getDisplayMetrics();
        mDisplayMetrics = dm;

        boolean isLandscape = res.getConfiguration().orientation ==
                Configuration.ORIENTATION_LANDSCAPE;
        Console.log(Constants.DebugFlags.UI.MeasureAndLayout,
                "[RecentsConfiguration|orientation]", isLandscape ? "Landscape" : "Portrait",
                Console.AnsiGreen);

        displayRect.set(0, 0, dm.widthPixels, dm.heightPixels);
        animationPxMovementPerSecond =
                res.getDimensionPixelSize(R.dimen.recents_animation_movement_in_dps_per_second);
        filteringCurrentViewsMinAnimDuration =
                res.getInteger(R.integer.recents_filter_animate_current_views_min_duration);
        filteringNewViewsMinAnimDuration =
                res.getInteger(R.integer.recents_filter_animate_new_views_min_duration);
        taskBarEnterAnimDuration =
                res.getInteger(R.integer.recents_animate_task_bar_enter_duration);
    }

    /** Updates the system insets */
    public void updateSystemInsets(Rect insets) {
        systemInsets.set(insets);
    }

    /** Converts from DPs to PXs */
    public int pxFromDp(float size) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                size, mDisplayMetrics));
    }
    /** Converts from SPs to PXs */
    public int pxFromSp(float size) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                size, mDisplayMetrics));
    }
}
