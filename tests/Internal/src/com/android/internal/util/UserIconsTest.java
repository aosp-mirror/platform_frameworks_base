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

package com.android.internal.util;

import static com.google.common.truth.Truth.assertThat;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class UserIconsTest {

    @Test
    public void convertToBitmapAtUserIconSize_sizeIsCorrect() {
        Resources res = InstrumentationRegistry.getTargetContext().getResources();
        Drawable icon = UserIcons.getDefaultUserIcon(res, 0, true);
        Bitmap bitmap = UserIcons.convertToBitmapAtUserIconSize(res, icon);
        int expectedSize = res.getDimensionPixelSize(R.dimen.user_icon_size);

        assertThat(bitmap.getWidth()).isEqualTo(expectedSize);
        assertThat(bitmap.getHeight()).isEqualTo(expectedSize);
    }

}
