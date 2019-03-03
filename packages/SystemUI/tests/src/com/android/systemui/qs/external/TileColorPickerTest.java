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
 * limitations under the License.
 */
package com.android.systemui.qs.external;

import static junit.framework.Assert.assertEquals;

import android.content.res.ColorStateList;
import android.service.quicksettings.Tile;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TileColorPickerTest extends SysuiTestCase {
    private static final int DEFAULT_COLOR = 0;

    private TileColorPicker mTileColorPicker;
    private ColorStateList mTintColorStateList;

    @Before
    public void setUp() {
        mTileColorPicker = TileColorPicker.getInstance(mContext);
        mTintColorStateList = mContext.getResources().
                getColorStateList(R.color.tint_color_selector, mContext.getTheme());
    }

    @Test
    public void testGetColor_StateUnavailable_ReturnUnavailableColor() {
        final int color = mTileColorPicker.getColor(Tile.STATE_UNAVAILABLE);
        final int expectedColor = mTintColorStateList.getColorForState(
                TileColorPicker.DISABLE_STATE_SET, DEFAULT_COLOR);

        assertEquals(expectedColor, color);
    }

    @Test
    public void testGetColor_StateInactive_ReturnInactiveColor() {
        final int color = mTileColorPicker.getColor(Tile.STATE_INACTIVE);
        final int expectedColor = mTintColorStateList.getColorForState(
                TileColorPicker.INACTIVE_STATE_SET, DEFAULT_COLOR);

        assertEquals(expectedColor, color);
    }

    @Test
    public void testGetColor_StateActive_ReturnActiveColor() {
        final int color = mTileColorPicker.getColor(Tile.STATE_ACTIVE);
        final int expectedColor = mTintColorStateList.getColorForState(
                TileColorPicker.ENABLE_STATE_SET, DEFAULT_COLOR);

        assertEquals(expectedColor, color);
    }
}
