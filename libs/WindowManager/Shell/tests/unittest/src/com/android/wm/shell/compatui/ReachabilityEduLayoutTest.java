/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.compatui;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link LetterboxEduDialogLayout}.
 *
 * Build/Install/Run:
 * atest WMShellUnitTests:ReachabilityEduLayoutTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ReachabilityEduLayoutTest extends ShellTestCase {

    private ReachabilityEduLayout mLayout;
    private View mMoveUpButton;
    private View mMoveDownButton;
    private View mMoveLeftButton;
    private View mMoveRightButton;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLayout = (ReachabilityEduLayout) LayoutInflater.from(mContext)
                .inflate(R.layout.reachability_ui_layout, null);
        mMoveLeftButton = mLayout.findViewById(R.id.reachability_move_left_button);
        mMoveRightButton = mLayout.findViewById(R.id.reachability_move_right_button);
        mMoveUpButton = mLayout.findViewById(R.id.reachability_move_up_button);
        mMoveDownButton = mLayout.findViewById(R.id.reachability_move_down_button);
    }

    @Test
    public void testOnFinishInflate() {
        assertNotNull(mMoveUpButton);
        assertNotNull(mMoveDownButton);
        assertNotNull(mMoveLeftButton);
        assertNotNull(mMoveRightButton);
    }

    @Test
    public void handleVisibility_activityNotLetterboxed_buttonsAreHidden() {
        mLayout.handleVisibility(/* isActivityLetterboxed */ false,
                /* letterboxVerticalPosition */  -1, /* letterboxHorizontalPosition */ -1,
                /* availableWidth */  0, /* availableHeight */ 0, /* fromDoubleTap */ false);
        assertEquals(View.INVISIBLE, mMoveUpButton.getVisibility());
        assertEquals(View.INVISIBLE, mMoveDownButton.getVisibility());
        assertEquals(View.INVISIBLE, mMoveLeftButton.getVisibility());
        assertEquals(View.INVISIBLE, mMoveRightButton.getVisibility());
    }
}
