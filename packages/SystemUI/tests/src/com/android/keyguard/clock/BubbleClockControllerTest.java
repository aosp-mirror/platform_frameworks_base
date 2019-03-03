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
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public final class BubbleClockControllerTest extends SysuiTestCase {

    private BubbleClockController mClockController;

    @Before
    public void setUp() {
        Resources res = getContext().getResources();
        LayoutInflater layoutInflater = LayoutInflater.from(getContext());
        mClockController = new BubbleClockController(res, layoutInflater);
    }

    @Test
    public void setDarkAmount_fadeIn() {
        ViewGroup smallClockFrame = (ViewGroup) mClockController.getView();
        View smallClock = smallClockFrame.getChildAt(0);
        // WHEN dark amount is set to AOD
        mClockController.setDarkAmount(1f);
        // THEN small clock should not be shown.
        assertThat(smallClock.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void setTextColor_setDigitalClock() {
        ViewGroup smallClock = (ViewGroup) mClockController.getView();
        // WHEN text color is set
        mClockController.setTextColor(42);
        // THEN child of small clock should have text color set.
        TextView digitalClock = (TextView) smallClock.getChildAt(0);
        assertThat(digitalClock.getCurrentTextColor()).isEqualTo(42);
    }
}
