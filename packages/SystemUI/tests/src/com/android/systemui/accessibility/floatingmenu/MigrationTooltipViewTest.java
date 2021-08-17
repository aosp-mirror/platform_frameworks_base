/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link MigrationTooltipView}. */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class MigrationTooltipViewTest extends SysuiTestCase {

    private TextView mTextView;
    private final Position mPlaceholderPosition = new Position(0.0f, 0.0f);

    @Before
    public void setUp() {
        final AccessibilityFloatingMenuView menuView = new AccessibilityFloatingMenuView(mContext,
                mPlaceholderPosition);
        final MigrationTooltipView toolTipView = new MigrationTooltipView(mContext, menuView);
        mTextView = toolTipView.findViewById(R.id.text);
    }

    @Test
    public void onCreate_setLinkMovementMethod() {
        assertThat(mTextView.getMovementMethod()).isInstanceOf(LinkMovementMethod.class);
    }

    @Test
    public void onCreate_setDescription_matchTextAndSpanNum() {
        final CharSequence expectedTextWithoutSpan =
                AnnotationLinkSpan.linkify(mContext.getText(
                R.string.accessibility_floating_button_migration_tooltip)).toString();
        final SpannableString spannableString = (SpannableString) mTextView.getText();
        final int AnnotationLinkSpanNum =
                spannableString.getSpans(/* queryStart= */ 0, spannableString.length(),
                        AnnotationLinkSpan.class).length;

        assertThat(AnnotationLinkSpanNum).isEqualTo(1);
        assertThat(mTextView.getText().toString().contentEquals(expectedTextWithoutSpan)).isTrue();
    }
}
