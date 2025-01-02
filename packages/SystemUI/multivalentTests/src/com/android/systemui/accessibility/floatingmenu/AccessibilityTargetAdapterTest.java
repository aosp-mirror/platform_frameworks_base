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

import static com.android.internal.accessibility.AccessibilityShortcutController.ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.accessibility.dialog.AccessibilityTarget;
import com.android.settingslib.bluetooth.HearingAidDeviceManager.ConnectionStatus;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.floatingmenu.AccessibilityTargetAdapter.ViewHolder;
import com.android.systemui.res.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

/** Tests for {@link AccessibilityTargetAdapter}. */
@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class AccessibilityTargetAdapterTest extends SysuiTestCase {
    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    private static final ComponentName TEST_NAME = new ComponentName("test.pkg", "test.activitty");
    private static final int PAYLOAD_HEARING_STATUS_DRAWABLE = 1;

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
        final View rootView = LayoutInflater.from(mContext).inflate(
                R.layout.accessibility_floating_menu_item, null);
        mViewHolder = new ViewHolder(rootView);
        when(mAccessibilityTarget.getIcon()).thenReturn(mIcon);
        when(mAccessibilityTarget.getId()).thenReturn(TEST_NAME.flattenToString());
        when(mIcon.getConstantState()).thenReturn(mConstantState);

        mTargets.add(mAccessibilityTarget);
        mAdapter = new AccessibilityTargetAdapter(mTargets);
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

    @Test
    @EnableFlags(
            com.android.settingslib.flags.Flags.FLAG_HEARING_DEVICE_SET_CONNECTION_STATUS_REPORT)
    public void onHearingDeviceStatusChanged_disconnected_getExpectedStateDescription() {
        when(mAccessibilityTarget.getId()).thenReturn(
                ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());
        int indexInTarget = 0;

        mAdapter.onHearingDeviceStatusChanged(ConnectionStatus.DISCONNECTED, indexInTarget);
        mAdapter.onBindViewHolder(mViewHolder, indexInTarget);

        assertThat(mViewHolder.itemView.getStateDescription().toString().contentEquals(
                "Disconnected")).isTrue();
    }

    @Test
    @EnableFlags(
            com.android.settingslib.flags.Flags.FLAG_HEARING_DEVICE_SET_CONNECTION_STATUS_REPORT)
    public void onBindViewHolder_withPayloadDisconnected_getExpectedStateDescription() {
        when(mAccessibilityTarget.getId()).thenReturn(
                ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());
        int indexInTarget = 0;

        mAdapter.onHearingDeviceStatusChanged(ConnectionStatus.DISCONNECTED, indexInTarget);
        mAdapter.onBindViewHolder(mViewHolder, indexInTarget,
                List.of(PAYLOAD_HEARING_STATUS_DRAWABLE));

        assertThat(mViewHolder.itemView.getStateDescription().toString().contentEquals(
                "Disconnected")).isTrue();
    }

    @Test
    @EnableFlags(
            com.android.settingslib.flags.Flags.FLAG_HEARING_DEVICE_SET_CONNECTION_STATUS_REPORT)
    public void setBadgeOnLeftSide_false_rightBadgeVisibleAndLeftBadgeInvisible() {
        when(mAccessibilityTarget.getId())
                .thenReturn(ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());

        mAdapter.setBadgeOnLeftSide(false);
        mAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mRightBadgeView.getVisibility()).isEqualTo(View.VISIBLE);
        assertThat(mViewHolder.mLeftBadgeView.getVisibility()).isEqualTo(View.INVISIBLE);
    }

    @Test
    @EnableFlags(
            com.android.settingslib.flags.Flags.FLAG_HEARING_DEVICE_SET_CONNECTION_STATUS_REPORT)
    public void setBadgeOnLeftSide_rightBadgeInvisibleAndLeftBadgeVisible() {
        when(mAccessibilityTarget.getId())
                .thenReturn(ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());

        mAdapter.setBadgeOnLeftSide(true);
        mAdapter.onBindViewHolder(mViewHolder, 0);

        assertThat(mViewHolder.mRightBadgeView.getVisibility()).isEqualTo(View.INVISIBLE);
        assertThat(mViewHolder.mLeftBadgeView.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    @EnableFlags(
            com.android.settingslib.flags.Flags.FLAG_HEARING_DEVICE_SET_CONNECTION_STATUS_REPORT)
    public void setBadgeOnLeftSide_bindViewHolderPayloads_rightBadgeInvisibleAndLeftBadgeVisible() {
        when(mAccessibilityTarget.getId())
                .thenReturn(ACCESSIBILITY_HEARING_AIDS_COMPONENT_NAME.flattenToString());

        mAdapter.setBadgeOnLeftSide(true);
        mAdapter.onBindViewHolder(mViewHolder, 0, List.of(PAYLOAD_HEARING_STATUS_DRAWABLE));

        assertThat(mViewHolder.mRightBadgeView.getVisibility()).isEqualTo(View.INVISIBLE);
        assertThat(mViewHolder.mLeftBadgeView.getVisibility()).isEqualTo(View.VISIBLE);
    }
}
