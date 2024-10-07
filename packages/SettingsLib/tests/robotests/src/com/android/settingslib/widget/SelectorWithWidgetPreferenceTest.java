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

package com.android.settingslib.widget;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.app.Application;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;
import androidx.test.core.app.ApplicationProvider;

import com.android.settingslib.widget.preference.selector.R;
import com.android.settingslib.widget.selectorwithwidgetpreference.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class SelectorWithWidgetPreferenceTest {

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    private Application mContext;
    private SelectorWithWidgetPreference mPreference;

    private View mExtraWidgetContainer;
    private View mExtraWidget;

    private boolean mIsClickListenerCalled;
    private View.OnClickListener mClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            mIsClickListenerCalled = true;
        }
    };

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mPreference = new SelectorWithWidgetPreference(mContext);

        View view = LayoutInflater.from(mContext)
                .inflate(mPreference.getLayoutResource(), null /* root */);
        PreferenceViewHolder preferenceViewHolder =
                PreferenceViewHolder.createInstanceForTests(view);
        mPreference.onBindViewHolder(preferenceViewHolder);

        mExtraWidgetContainer = view.findViewById(R.id.selector_extra_widget_container);
        mExtraWidget = view.findViewById(R.id.selector_extra_widget);
    }

    @Test
    public void shouldHaveRadioPreferenceLayout() {
        assertThat(mPreference.getLayoutResource()).isEqualTo(
                R.layout.preference_selector_with_widget);
    }

    @Test
    public void shouldHaveRadioButtonWidgetLayoutByDefault() {
        assertThat(mPreference.getWidgetLayoutResource())
                .isEqualTo(R.layout.preference_widget_radiobutton);
    }

    @Test
    public void shouldHaveCheckBoxWidgetLayoutIfSet() {
        mPreference = new SelectorWithWidgetPreference(mContext, true);
        assertThat(mPreference.getWidgetLayoutResource())
                .isEqualTo(R.layout.preference_widget_checkbox);
    }

    @Test
    public void iconSpaceReservedShouldBeFalse() {
        assertThat(mPreference.isIconSpaceReserved()).isFalse();
    }

    @Test
    public void onBindViewHolder_withSummary_containerShouldBeVisible() {
        mPreference.setSummary("some summary");
        View view = LayoutInflater.from(mContext)
                .inflate(mPreference.getLayoutResource(), null /* root */);
        PreferenceViewHolder preferenceViewHolder =
                PreferenceViewHolder.createInstanceForTests(view);

        mPreference.onBindViewHolder(preferenceViewHolder);

        View summaryContainer = view.findViewById(R.id.summary_container);
        assertEquals(View.VISIBLE, summaryContainer.getVisibility());
    }

    @Test
    public void onBindViewHolder_emptySummary_containerShouldBeGone() {
        mPreference.setSummary("");
        View view = LayoutInflater.from(mContext)
                .inflate(mPreference.getLayoutResource(), null /* root */);
        PreferenceViewHolder preferenceViewHolder =
                PreferenceViewHolder.createInstanceForTests(view);

        mPreference.onBindViewHolder(preferenceViewHolder);

        View summaryContainer = view.findViewById(R.id.summary_container);
        assertEquals(View.GONE, summaryContainer.getVisibility());
    }

    @Test
    @DisableFlags(Flags.FLAG_ALLOW_SET_TITLE_MAX_LINES)
    public void onBindViewHolder_titleMaxLinesSet_flagOff_titleMaxLinesMatchesDefault() {
        final int titleMaxLines = 5;
        AttributeSet attributeSet = Robolectric.buildAttributeSet()
                .addAttribute(R.attr.titleMaxLines, String.valueOf(titleMaxLines))
                .build();
        mPreference = new SelectorWithWidgetPreference(mContext, attributeSet);
        View view = LayoutInflater.from(mContext)
                .inflate(mPreference.getLayoutResource(), null /* root */);
        PreferenceViewHolder preferenceViewHolder =
                PreferenceViewHolder.createInstanceForTests(view);

        mPreference.onBindViewHolder(preferenceViewHolder);

        TextView title = (TextView) preferenceViewHolder.findViewById(android.R.id.title);
        assertThat(title.getMaxLines()).isEqualTo(SelectorWithWidgetPreference.DEFAULT_MAX_LINES);
    }

    @Test
    @EnableFlags(Flags.FLAG_ALLOW_SET_TITLE_MAX_LINES)
    public void onBindViewHolder_noTitleMaxLinesSet_titleMaxLinesMatchesDefault() {
        AttributeSet attributeSet = Robolectric.buildAttributeSet().build();
        mPreference = new SelectorWithWidgetPreference(mContext, attributeSet);
        View view = LayoutInflater.from(mContext)
                .inflate(mPreference.getLayoutResource(), null /* root */);
        PreferenceViewHolder preferenceViewHolder =
                PreferenceViewHolder.createInstanceForTests(view);

        mPreference.onBindViewHolder(preferenceViewHolder);

        TextView title = (TextView) preferenceViewHolder.findViewById(android.R.id.title);
        assertThat(title.getMaxLines()).isEqualTo(SelectorWithWidgetPreference.DEFAULT_MAX_LINES);
    }

    @Test
    @EnableFlags(Flags.FLAG_ALLOW_SET_TITLE_MAX_LINES)
    public void onBindViewHolder_titleMaxLinesSet_titleMaxLinesUpdated() {
        final int titleMaxLines = 5;
        AttributeSet attributeSet = Robolectric.buildAttributeSet()
                .addAttribute(R.attr.titleMaxLines, String.valueOf(titleMaxLines))
                .build();
        mPreference = new SelectorWithWidgetPreference(mContext, attributeSet);
        View view = LayoutInflater.from(mContext)
                .inflate(mPreference.getLayoutResource(), null /* root */);
        PreferenceViewHolder preferenceViewHolder =
                PreferenceViewHolder.createInstanceForTests(view);

        mPreference.onBindViewHolder(preferenceViewHolder);

        TextView title = (TextView) preferenceViewHolder.findViewById(android.R.id.title);
        assertThat(title.getMaxLines()).isEqualTo(titleMaxLines);
    }

    @Test
    public void onBindViewHolder_appliesWidgetContentDescription() {
        mPreference = new SelectorWithWidgetPreference(mContext);
        View view = LayoutInflater.from(mContext)
                .inflate(mPreference.getLayoutResource(), /* root= */ null);
        PreferenceViewHolder preferenceViewHolder =
                PreferenceViewHolder.createInstanceForTests(view);

        mPreference.setExtraWidgetContentDescription("this is clearer");
        mPreference.onBindViewHolder(preferenceViewHolder);

        View widget = preferenceViewHolder.findViewById(R.id.selector_extra_widget);
        assertThat(widget.getContentDescription().toString()).isEqualTo("this is clearer");

        mPreference.setExtraWidgetContentDescription(null);
        mPreference.onBindViewHolder(preferenceViewHolder);

        assertThat(widget.getContentDescription().toString()).isEqualTo("Settings");
    }

    @Test
    public void nullSummary_containerShouldBeGone() {
        mPreference.setSummary(null);
        View view = LayoutInflater.from(mContext)
                .inflate(mPreference.getLayoutResource(), null /* root */);
        PreferenceViewHolder preferenceViewHolder =
                PreferenceViewHolder.createInstanceForTests(view);

        mPreference.onBindViewHolder(preferenceViewHolder);

        View summaryContainer = view.findViewById(R.id.summary_container);
        assertEquals(View.GONE, summaryContainer.getVisibility());
    }

    @Test
    public void setAppendixVisibility_setGone_shouldBeGone() {
        mPreference.setAppendixVisibility(View.GONE);
        View view = LayoutInflater.from(mContext)
                .inflate(mPreference.getLayoutResource(), null /* root */);
        PreferenceViewHolder holder =
                PreferenceViewHolder.createInstanceForTests(view);

        mPreference.onBindViewHolder(holder);

        assertThat(holder.findViewById(R.id.appendix).getVisibility()).isEqualTo(View.GONE);
    }

    @Test
    public void setExtraWidgetListener_setNull_extraWidgetShouldInvisible() {
        mPreference.setExtraWidgetOnClickListener(null);

        assertEquals(View.GONE, mExtraWidgetContainer.getVisibility());
    }

    @Test
    public void setExtraWidgetListener_extraWidgetShouldVisible() {
        mPreference.setExtraWidgetOnClickListener(mClickListener);

        assertEquals(View.VISIBLE, mExtraWidgetContainer.getVisibility());
    }

    @Test
    public void onClickListener_setExtraWidgetOnClickListener_ShouldCalled() {
        mPreference.setExtraWidgetOnClickListener(mClickListener);

        assertThat(mIsClickListenerCalled).isFalse();
        mExtraWidget.callOnClick();
        assertThat(mIsClickListenerCalled).isTrue();
    }
}
