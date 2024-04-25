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

package com.android.systemui.assist.ui;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DisplayUtilsTest extends SysuiTestCase {

    @Mock
    Resources mResources;
    @Mock
    Context mMockContext;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testGetCornerRadii_noOverlay() {
        assertEquals(0, DisplayUtils.getCornerRadiusBottom(mContext));
        assertEquals(0, DisplayUtils.getCornerRadiusTop(mContext));
    }

    @Test
    public void testGetCornerRadii_onlyDefaultOverridden() {
        when(mResources.getDimensionPixelSize(R.dimen.config_rounded_mask_size)).thenReturn(100);
        when(mMockContext.getResources()).thenReturn(mResources);

        assertEquals(100, DisplayUtils.getCornerRadiusBottom(mMockContext));
        assertEquals(100, DisplayUtils.getCornerRadiusTop(mMockContext));
    }

    @Test
    public void testGetCornerRadii_allOverridden() {
        when(mResources.getDimensionPixelSize(R.dimen.config_rounded_mask_size)).thenReturn(100);
        when(mResources.getDimensionPixelSize(R.dimen.config_rounded_mask_size_top)).thenReturn(
                150);
        when(mResources.getDimensionPixelSize(R.dimen.config_rounded_mask_size_bottom)).thenReturn(
                200);
        when(mMockContext.getResources()).thenReturn(mResources);

        assertEquals(200, DisplayUtils.getCornerRadiusBottom(mMockContext));
        assertEquals(150, DisplayUtils.getCornerRadiusTop(mMockContext));
    }
}
