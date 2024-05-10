/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.widget;

import static com.google.common.truth.Truth.assertThat;

import android.content.res.Resources;
import android.graphics.Paint;

import com.android.settingslib.widget.adaptiveicon.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class AdaptiveOutlineDrawableTest {

    @Test
    public void constructor_initPaint() {
        final Resources resources = RuntimeEnvironment.application.getResources();
        final AdaptiveOutlineDrawable drawable = new AdaptiveOutlineDrawable(resources, null);

        assertThat(drawable.mOutlinePaint.getStyle()).isEqualTo(Paint.Style.STROKE);
        assertThat(drawable.mOutlinePaint.getStrokeWidth()).isWithin(0.01f).of(
                resources.getDimension(R.dimen.adaptive_outline_stroke));
    }

}
