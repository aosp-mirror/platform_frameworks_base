/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.server.accessibility.autoclick;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Test cases for {@link AutoclickTypePanel}. */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class AutoclickTypePanelTest {
    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Rule
    public TestableContext mTestableContext =
            new TestableContext(getInstrumentation().getContext());

    private AutoclickTypePanel mAutoclickTypePanel;
    @Mock private WindowManager mMockWindowManager;
    private LinearLayout mLeftClickButton;
    private LinearLayout mRightClickButton;
    private LinearLayout mDoubleClickButton;
    private LinearLayout mDragButton;
    private LinearLayout mScrollButton;

    @Before
    public void setUp() {
        mTestableContext.addMockSystemService(Context.WINDOW_SERVICE, mMockWindowManager);

        mAutoclickTypePanel = new AutoclickTypePanel(mTestableContext, mMockWindowManager);
        View contentView = mAutoclickTypePanel.getContentViewForTesting();
        mLeftClickButton = contentView.findViewById(R.id.accessibility_autoclick_left_click_layout);
        mRightClickButton =
                contentView.findViewById(R.id.accessibility_autoclick_right_click_layout);
        mDoubleClickButton =
                contentView.findViewById(R.id.accessibility_autoclick_double_click_layout);
        mScrollButton = contentView.findViewById(R.id.accessibility_autoclick_scroll_layout);
        mDragButton = contentView.findViewById(R.id.accessibility_autoclick_drag_layout);
    }

    @Test
    public void AutoclickTypePanel_initialState_expandedFalse() {
        assertThat(mAutoclickTypePanel.getExpansionStateForTesting()).isFalse();
    }

    @Test
    public void AutoclickTypePanel_initialState_correctButtonVisibility() {
        // On initialization, only left button is visible.
        assertThat(mLeftClickButton.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mRightClickButton.getVisibility()).isEqualTo(View.GONE);
        assertThat(mDoubleClickButton.getVisibility()).isEqualTo(View.GONE);
        assertThat(mDragButton.getVisibility()).isEqualTo(View.GONE);
        assertThat(mScrollButton.getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void togglePanelExpansion_onClick_expandedTrue() {
        // On clicking left click button, the panel is expanded and all buttons are visible.
        mLeftClickButton.callOnClick();

        assertThat(mAutoclickTypePanel.getExpansionStateForTesting()).isTrue();
        assertThat(mLeftClickButton.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mRightClickButton.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mDoubleClickButton.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mDragButton.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mScrollButton.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void togglePanelExpansion_onClickAgain_expandedFalse() {
        // By first click, the panel is expanded.
        mLeftClickButton.callOnClick();
        assertThat(mAutoclickTypePanel.getExpansionStateForTesting()).isTrue();

        // Clicks any button in the expanded state, the panel is expected to collapse
        // with only the clicked button visible.
        mScrollButton.callOnClick();

        assertThat(mAutoclickTypePanel.getExpansionStateForTesting()).isFalse();
        assertThat(mScrollButton.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mRightClickButton.getVisibility()).isEqualTo(View.GONE);
        assertThat(mLeftClickButton.getVisibility()).isEqualTo(View.GONE);
        assertThat(mDoubleClickButton.getVisibility()).isEqualTo(View.GONE);
        assertThat(mDragButton.getVisibility()).isEqualTo(View.GONE);
    }
}
