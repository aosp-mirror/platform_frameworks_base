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

import static org.mockito.Mockito.mock;

import android.text.SpannableStringBuilder;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

/** Tests for {@link AnnotationLinkSpan}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AnnotationLinkSpanTest extends SysuiTestCase {

    private AnnotationLinkSpan.LinkInfo mLinkInfo;

    @Before
    public void setUp() {
        mLinkInfo = new AnnotationLinkSpan.LinkInfo(
                AnnotationLinkSpan.LinkInfo.DEFAULT_ANNOTATION,
                mock(View.OnClickListener.class));
    }

    @Test
    public void linkifyText_textAttachedWithSpan() {
        final CharSequence text = getContext().getText(
                R.string.accessibility_floating_button_migration_tooltip);
        final SpannableStringBuilder builder =
                (SpannableStringBuilder) AnnotationLinkSpan.linkify(text, mLinkInfo);
        final int AnnotationLinkSpanNum =
                builder.getSpans(/* queryStart= */ 0, builder.length(),
                        AnnotationLinkSpan.class).length;

        assertThat(AnnotationLinkSpanNum).isEqualTo(1);
    }

    @Test
    public void linkifyText_withoutAnnotationTag_textWithoutSpan() {
        final CharSequence text = "text without any annotation tag";
        final SpannableStringBuilder builder =
                (SpannableStringBuilder) AnnotationLinkSpan.linkify(text, mLinkInfo);
        final int AnnotationLinkSpanNum =
                builder.getSpans(/* queryStart= */ 0, builder.length(),
                        AnnotationLinkSpan.class).length;

        assertThat(AnnotationLinkSpanNum).isEqualTo(0);
    }

    @Test
    public void linkifyText_twoLinkInfoWithSameAnnotation_listenerInvoked() {
        final AtomicBoolean isClicked = new AtomicBoolean(false);
        final CharSequence text = getContext().getText(
                R.string.accessibility_floating_button_migration_tooltip);
        final View.OnClickListener firstListener = v -> isClicked.set(true);
        final AnnotationLinkSpan.LinkInfo firstLinkInfo = new AnnotationLinkSpan.LinkInfo(
                AnnotationLinkSpan.LinkInfo.DEFAULT_ANNOTATION, firstListener);

        final SpannableStringBuilder builder =
                (SpannableStringBuilder) AnnotationLinkSpan.linkify(text, firstLinkInfo, mLinkInfo);
        final AnnotationLinkSpan[] firstAnnotationLinkSpan =
                builder.getSpans(/* queryStart= */ 0, builder.length(),
                        AnnotationLinkSpan.class);
        firstAnnotationLinkSpan[0].onClick(mock(View.class));

        assertThat(isClicked.get()).isTrue();
    }
}
