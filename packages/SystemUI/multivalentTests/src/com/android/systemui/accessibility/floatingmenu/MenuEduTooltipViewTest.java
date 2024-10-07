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

package com.android.systemui.accessibility.floatingmenu;

import static com.google.common.truth.Truth.assertThat;

import android.content.res.Resources;
import android.testing.TestableLooper;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MenuEduTooltipView}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class MenuEduTooltipViewTest extends SysuiTestCase {
    private MenuViewAppearance mMenuViewAppearance;
    private MenuEduTooltipView mMenuEduTooltipView;

    @Before
    public void setUp() throws Exception {
        final WindowManager windowManager = mContext.getSystemService(WindowManager.class);
        mMenuViewAppearance = new MenuViewAppearance(mContext, windowManager);
        mMenuEduTooltipView = new MenuEduTooltipView(mContext, mMenuViewAppearance);
    }

    @Test
    public void show_matchMessageText() {
        final CharSequence text = "Message";

        mMenuEduTooltipView.show(text);

        final TextView messageView = mMenuEduTooltipView.findViewById(R.id.text);
        assertThat(messageView.getText().toString().contentEquals(text)).isTrue();
    }

    @Test
    public void show_menuOnLeft_onRightOfAnchor() {
        mMenuViewAppearance.setPercentagePosition(
                new Position(/* percentageX= */ 0.0f, /* percentageY= */ 0.0f));
        final CharSequence text = "Message";

        mMenuEduTooltipView.show(text);
        final int tooltipViewX = (int) (mMenuViewAppearance.getMenuPosition().x
                + mMenuViewAppearance.getMenuWidth());

        assertThat(mMenuEduTooltipView.getX()).isEqualTo(tooltipViewX);
    }

    @Test
    public void show_menuCloseToLeftOfCenter_onLeftOfAnchor() {
        mMenuViewAppearance.setPercentagePosition(
                new Position(/* percentageX= */ 0.4f, /* percentageY= */ 0.0f));
        final CharSequence text = "Message";

        mMenuEduTooltipView.show(text);
        final int tooltipViewX = (int) (mMenuViewAppearance.getMenuPosition().x
                + mMenuViewAppearance.getMenuWidth());

        assertThat(mMenuEduTooltipView.getX()).isEqualTo(tooltipViewX);
    }

    @Test
    public void show_menuOnRight_onLeftOfAnchor() {
        mMenuViewAppearance.setPercentagePosition(
                new Position(/* percentageX= */ 1.0f, /* percentageY= */ 0.0f));
        final Resources res = getContext().getResources();
        final int arrowWidth =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_tooltip_arrow_width);
        final int arrowMargin =
                res.getDimensionPixelSize(R.dimen.accessibility_floating_tooltip_arrow_margin);
        final CharSequence text = "Message";

        mMenuEduTooltipView.show(text);
        final TextView messageView = mMenuEduTooltipView.findViewById(R.id.text);
        final int layoutWidth = messageView.getMeasuredWidth() + arrowWidth + arrowMargin;

        assertThat(mMenuEduTooltipView.getX()).isEqualTo(
                mMenuViewAppearance.getMenuPosition().x - layoutWidth);
    }
}
