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
package com.android.keyguard.clock;

import static com.google.common.truth.Truth.assertThat;

import android.content.res.Resources;
import android.graphics.Color;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.colorextraction.SysuiColorExtractor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public final class AnalogClockControllerTest extends SysuiTestCase {

    private AnalogClockController mClockController;
    @Mock SysuiColorExtractor mMockColorExtractor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        Resources res = getContext().getResources();
        LayoutInflater layoutInflater = LayoutInflater.from(getContext());
        mClockController = new AnalogClockController(res, layoutInflater,
                mMockColorExtractor);
    }

    @Test
    public void setDarkAmount_AOD() {
        ViewGroup smallClockFrame = (ViewGroup) mClockController.getView();
        View smallClock = smallClockFrame.getChildAt(0);
        // WHEN dark amount is set to AOD
        mClockController.setDarkAmount(1f);
        // THEN small clock should be shown.
        assertThat(smallClock.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void setColorPalette_setDigitalClock() {
        ViewGroup smallClock = (ViewGroup) mClockController.getView();
        // WHEN color palette is set
        mClockController.setColorPalette(true, new int[]{Color.RED});
        // THEN child of small clock should have text color set.
        TextView digitalClock = (TextView) smallClock.getChildAt(0);
        assertThat(digitalClock.getCurrentTextColor()).isEqualTo(Color.RED);
    }
}
