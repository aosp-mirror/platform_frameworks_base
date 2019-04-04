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

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BubbleStackViewTest extends SysuiTestCase {
    private BubbleStackView mStackView;
    @Mock private Bubble mBubble;
    @Mock private NotificationEntry mNotifEntry;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mStackView = new BubbleStackView(mContext, new BubbleData(), null);
        mBubble.entry = mNotifEntry;
    }

    @Test
    public void testAnimateInFlyoutForBubble() throws InterruptedException {
        when(mNotifEntry.getUpdateMessage(any())).thenReturn("Test Flyout Message.");
        mStackView.animateInFlyoutForBubble(mBubble);

        // Wait for the fade in.
        Thread.sleep(200);

        // Flyout should be visible and showing our text.
        assertEquals(1f, mStackView.findViewById(R.id.bubble_flyout).getAlpha(), .01f);
        assertEquals("Test Flyout Message.",
                ((TextView) mStackView.findViewById(R.id.bubble_flyout_text)).getText());

        // Wait until it should have gone away.
        Thread.sleep(BubbleStackView.FLYOUT_HIDE_AFTER + 200);

        // Flyout should be gone.
        assertEquals(View.GONE, mStackView.findViewById(R.id.bubble_flyout).getVisibility());
    }
}
