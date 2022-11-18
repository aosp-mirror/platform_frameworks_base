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

package com.android.systemui.biometrics;

import static com.google.common.truth.Truth.assertThat;

import android.content.res.Configuration;
import android.graphics.Color;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class UdfpsEnrollViewTest extends SysuiTestCase {

    private static String ENROLL_PROGRESS_COLOR_LIGHT = "#699FF3";
    private static String ENROLL_PROGRESS_COLOR_DARK = "#7DA7F1";

    @Test
    public void fingerprintUdfpsEnroll_usesCorrectThemeCheckmarkFillColor() {
        final Configuration config = mContext.getResources().getConfiguration();
        final boolean isDarkThemeOn = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        final int currentColor = mContext.getColor(R.color.udfps_enroll_progress);

        assertThat(currentColor).isEqualTo(Color.parseColor(isDarkThemeOn
                ? ENROLL_PROGRESS_COLOR_DARK : ENROLL_PROGRESS_COLOR_LIGHT));
    }
}
