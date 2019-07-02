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

package com.android.systemui.bubbles;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotSame;

import static org.mockito.Mockito.verify;

import android.graphics.Color;
import android.graphics.PointF;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BubbleFlyoutViewTest extends SysuiTestCase {
    private BubbleFlyoutView mFlyout;
    private TextView mFlyoutText;
    private float[] mDotCenter = new float[2];

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mFlyout = new BubbleFlyoutView(getContext());

        mFlyoutText = mFlyout.findViewById(R.id.bubble_flyout_text);
        mDotCenter[0] = 30;
        mDotCenter[1] = 30;
    }

    @Test
    public void testShowFlyout_isVisible() {
        mFlyout.setupFlyoutStartingAsDot(
                "Hello", new PointF(100, 100), 500, true, Color.WHITE, null, null, mDotCenter);
        mFlyout.setVisibility(View.VISIBLE);

        assertEquals("Hello", mFlyoutText.getText());
        assertEquals(View.VISIBLE, mFlyout.getVisibility());
    }

    @Test
    public void testFlyoutHide_runsCallback() {
        Runnable after = Mockito.mock(Runnable.class);
        mFlyout.setupFlyoutStartingAsDot(
                "Hello", new PointF(100, 100), 500, true, Color.WHITE, null, after, mDotCenter);
        mFlyout.hideFlyout();

        verify(after).run();
    }

    @Test
    public void testSetCollapsePercent() {
        mFlyout.setupFlyoutStartingAsDot(
                "Hello", new PointF(100, 100), 500, true, Color.WHITE, null, null, mDotCenter);
        mFlyout.setVisibility(View.VISIBLE);

        mFlyout.setCollapsePercent(1f);
        assertEquals(0f, mFlyoutText.getAlpha(), 0.01f);
        assertNotSame(0f, mFlyoutText.getTranslationX()); // Should have moved to collapse.

        mFlyout.setCollapsePercent(0f);
        assertEquals(1f, mFlyoutText.getAlpha(), 0.01f);
        assertEquals(0f, mFlyoutText.getTranslationX());
    }
}
