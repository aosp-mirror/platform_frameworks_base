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

package com.android.wm.shell;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.bubbles.BubbleController;
import com.android.wm.shell.bubbles.BubbleOverflow;
import com.android.wm.shell.bubbles.BubbleStackView;
import com.android.wm.shell.bubbles.TestableBubblePositioner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for {@link com.android.wm.shell.bubbles.BubbleOverflow}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BubbleOverflowTest extends ShellTestCase {

    private TestableBubblePositioner mPositioner;
    private BubbleOverflow mOverflow;

    @Mock
    private BubbleController mBubbleController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mPositioner = new TestableBubblePositioner(mContext, mock(WindowManager.class));
        when(mBubbleController.getPositioner()).thenReturn(mPositioner);
        when(mBubbleController.getStackView()).thenReturn(mock(BubbleStackView.class));

        mOverflow = new BubbleOverflow(mContext, mPositioner);
    }

    @Test
    public void test_initialize() {
        assertThat(mOverflow.getExpandedView()).isNull();

        mOverflow.initialize(mBubbleController);

        assertThat(mOverflow.getExpandedView()).isNotNull();
        assertThat(mOverflow.getExpandedView().getBubbleKey()).isEqualTo(BubbleOverflow.KEY);
    }

    @Test
    public void test_cleanUpExpandedState() {
        mOverflow.createExpandedView();
        assertThat(mOverflow.getExpandedView()).isNotNull();

        mOverflow.cleanUpExpandedState();
        assertThat(mOverflow.getExpandedView()).isNull();
    }

}
