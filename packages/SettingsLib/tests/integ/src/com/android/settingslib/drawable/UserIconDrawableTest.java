/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settingslib.drawable;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.ColorInt;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import com.android.settingslib.R;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class UserIconDrawableTest {

    private UserIconDrawable mDrawable;

    @Test
    public void getConstantState_shouldNotBeNull() {
        final Bitmap b = BitmapFactory.decodeResource(
                InstrumentationRegistry.getTargetContext().getResources(),
                R.drawable.home);
        mDrawable = new UserIconDrawable(100 /* size */).setIcon(b).bake();
        assertThat(mDrawable.getConstantState()).isNotNull();
    }

    @Test
    public void setTintList_shouldBeApplied() {
        @ColorInt final int targetColor = Color.BLUE;
        final PorterDuff.Mode mode = Mode.SRC_OVER;

        final Bitmap b = Bitmap.createBitmap(1, 1, Config.ARGB_8888);
        UserIconDrawable drawable = new UserIconDrawable().setIcon(b);
        drawable.setBounds(0, 0, 100, 100);

        int[][] stateSet = new int[][] { {} };
        int[] colors = new int[] { targetColor };
        drawable.setTintList(new ColorStateList(stateSet, colors));
        drawable.setTintMode(mode);

        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.draw(canvas);

        assertThat(bitmap.getPixel(0, 0)).isEqualTo(Color.BLUE);
    }
}
