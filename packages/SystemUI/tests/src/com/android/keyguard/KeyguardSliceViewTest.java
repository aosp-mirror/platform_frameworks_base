/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.keyguard;

import android.graphics.Color;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;

import com.android.systemui.SysuiTestCase;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class KeyguardSliceViewTest extends SysuiTestCase {
    private KeyguardSliceView mKeyguardSliceView;

    @Before
    public void setUp() throws Exception {
        mKeyguardSliceView = (KeyguardSliceView) LayoutInflater.from(getContext())
                .inflate(R.layout.keyguard_status_area, null);
    }

    @Test
    public void getTextColor_whiteTextWhenAOD() {
        // Set text color to red since the default is white and test would always pass
        mKeyguardSliceView.setTextColor(Color.RED);
        mKeyguardSliceView.setDark(0);
        Assert.assertEquals("Should be using regular text color", Color.RED,
                mKeyguardSliceView.getTextColor());
        mKeyguardSliceView.setDark(1);
        Assert.assertEquals("Should be using AOD text color", Color.WHITE,
                mKeyguardSliceView.getTextColor());
    }
}