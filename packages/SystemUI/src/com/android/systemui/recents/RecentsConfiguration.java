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
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.TypedValue;


/** A static Recents configuration for the current context
 * NOTE: We should not hold any references to a Context from a static instance */
public class RecentsConfiguration {
    static RecentsConfiguration sInstance;

    DisplayMetrics mDisplayMetrics;

    public Rect systemInsets = new Rect();

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
        mDisplayMetrics = context.getResources().getDisplayMetrics();

        boolean isPortrait = context.getResources().getConfiguration().orientation ==
                Configuration.ORIENTATION_PORTRAIT;
    }

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
