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
import static junit.framework.Assert.assertTrue;

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

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mFlyout = new BubbleFlyoutView(getContext());

        mFlyoutText = mFlyout.findViewById(R.id.bubble_flyout_text);
    }

    @Test
    public void testShowFlyout_isVisible() {
        mFlyout.showFlyout("Hello", new PointF(100, 100), 500, true, Color.WHITE, null);
        assertEquals("Hello", mFlyoutText.getText());
        assertEquals(View.VISIBLE, mFlyout.getVisibility());
        assertEquals(1f, mFlyoutText.getAlpha(), .01f);
    }

    @Test
    public void testFlyoutHide_runsCallback() {
        Runnable after = Mockito.mock(Runnable.class);
        mFlyout.showFlyout("Hello", new PointF(100, 100), 500, true, Color.WHITE, after);
        mFlyout.hideFlyout();

        verify(after).run();
    }

    @Test
    public void testSetCollapsePercent() {
        mFlyout.showFlyout("Hello", new PointF(100, 100), 500, true, Color.WHITE, null);

        float initialTranslationZ = mFlyout.getTranslationZ();

        mFlyout.setCollapsePercent(1f);
        assertEquals(0f, mFlyoutText.getAlpha(), 0.01f);
        assertNotSame(0f, mFlyoutText.getTranslationX()); // Should have moved to collapse.
        assertTrue(mFlyout.getTranslationZ() < initialTranslationZ); // Should be descending.

        mFlyout.setCollapsePercent(0f);
        assertEquals(1f, mFlyoutText.getAlpha(), 0.01f);
        assertEquals(0f, mFlyoutText.getTranslationX());
        assertEquals(initialTranslationZ, mFlyout.getTranslationZ());

    }
}
