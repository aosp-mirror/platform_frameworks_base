/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.qs.external;

import android.content.Context;
import android.content.res.ColorStateList;
import android.service.quicksettings.Tile;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.R;

public class TileColorPicker {
    @VisibleForTesting static final int[] DISABLE_STATE_SET = {-android.R.attr.state_enabled};
    @VisibleForTesting static final int[] ENABLE_STATE_SET = {android.R.attr.state_enabled,
            android.R.attr.state_activated};
    @VisibleForTesting static final int[] INACTIVE_STATE_SET = {-android.R.attr.state_activated};
    private static TileColorPicker sInstance;

    private ColorStateList mColorStateList;

    private TileColorPicker(Context context) {
        mColorStateList = context.getResources().
                getColorStateList(R.color.tint_color_selector, context.getTheme());
    }

    public static TileColorPicker getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new TileColorPicker(context);
        }
        return sInstance;
    }

    public int getColor(int state) {
        final int defaultColor = 0;

        switch (state) {
            case Tile.STATE_UNAVAILABLE:
                return mColorStateList.getColorForState(DISABLE_STATE_SET, defaultColor);
            case Tile.STATE_INACTIVE:
                return mColorStateList.getColorForState(INACTIVE_STATE_SET, defaultColor);
            case Tile.STATE_ACTIVE:
                return mColorStateList.getColorForState(ENABLE_STATE_SET, defaultColor);
            default:
                return mColorStateList.getColorForState(ENABLE_STATE_SET, defaultColor);
        }
    }

}
