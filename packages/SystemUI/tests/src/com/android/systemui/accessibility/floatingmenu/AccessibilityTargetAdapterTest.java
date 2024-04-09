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

import static org.mockito.Mockito.when;

import android.graphics.drawable.Drawable;
import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.floatingmenu.AccessibilityTargetAdapter.ViewHolder;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/** Tests for {@link AccessibilityTargetAdapter}. */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class AccessibilityTargetAdapterTest extends SysuiTestCase {
    @Mock
    private AccessibilityTarget mAccessibilityTarget;

    @Mock
    private Drawable mIcon;

    @Mock
    private Drawable.ConstantState mConstantState;

    private ViewHolder mViewHolder;
    private AccessibilityTargetAdapter mAdapter;
    private final List<AccessibilityTarget> mTargets = new ArrayList<>();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mTargets.add(mAccessibilityTarget);
        mAdapter = new AccessibilityTargetAdapter(mTargets);

        final View rootView = LayoutInflater.from(mContext).inflate(
                R.layout.accessibility_floating_menu_item, null);
        mViewHolder = new ViewHolder(rootView);
        when(mAccessibilityTarget.getIcon()).thenReturn(mIcon);
        when(mIcon.getConstantState()).thenReturn(mConstantState);
    }

    @Test
    public void onBindViewHolder_setIconWidthHeight_matchResult() {
        final int iconWidthHeight = 50;
        mAdapter.setIconWidthHeight(iconWidthHeight);

        mAdapter.onBindViewHolder(mViewHolder, 0);
        final int actualIconWith = mViewHolder.mIconView.getLayoutParams().width;

        assertThat(actualIconWith).isEqualTo(iconWidthHeight);
    }

    @Test
    public void getContentDescription_invisibleToggleTarget_descriptionWithoutState() {
        when(mAccessibilityTarget.getFragmentType()).thenReturn(/* InvisibleToggle */ 1);
        when(mAccessibilityTarget.getLabel()).thenReturn("testLabel");
        when(mAccessibilityTarget.getStateDescription()).thenReturn("testState");

        mAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.itemView.getContentDescription().toString().contentEquals(
                "testLabel")).isTrue();
    }

    @Test
    public void getStateDescription_toggleTarget_switchOff_stateOffText() {
        when(mAccessibilityTarget.getFragmentType()).thenReturn(/* Toggle */ 2);
        when(mAccessibilityTarget.getStateDescription()).thenReturn("testState");

        mAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.itemView.getStateDescription().toString().contentEquals(
                "testState")).isTrue();
    }
}
