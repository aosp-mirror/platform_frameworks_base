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

package com.android.server.wm;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_PRESENTATION;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.view.DisplayInfo;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;


/**
 * Tests for {@link PossibleDisplayInfoMapper}.
 *
 * Build/Install/Run:
 * atest WmTests:PossibleDisplayInfoMapperTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class PossibleDisplayInfoMapperTests extends WindowTestsBase {

    private PossibleDisplayInfoMapper mDisplayInfoMapper;
    private final Set<DisplayInfo> mPossibleDisplayInfo = new ArraySet<>();
    private DisplayInfo mDefaultDisplayInfo;
    private DisplayInfo mSecondDisplayInfo;

    @Before
    public void setUp() throws Exception {
        mDisplayInfoMapper = mWm.mPossibleDisplayInfoMapper;
        final DisplayInfo baseDisplayInfo = mWm.mRoot.getDisplayContent(
                DEFAULT_DISPLAY).getDisplayInfo();
        when(mWm.mDisplayManagerInternal.getPossibleDisplayInfo(anyInt())).thenReturn(
                mPossibleDisplayInfo);

        mDefaultDisplayInfo = new DisplayInfo(baseDisplayInfo);
        initializeDisplayInfo(mDefaultDisplayInfo, DEFAULT_DISPLAY, new Rect(0, 0, 500, 800));
        mSecondDisplayInfo = new DisplayInfo(baseDisplayInfo);
        // Use the same display id for any display in the same group, due to the assumption that
        // any display in the same grouped can be swapped out for each other (while maintaining the
        // display id).
        initializeDisplayInfo(mSecondDisplayInfo, DEFAULT_DISPLAY, new Rect(0, 0, 600, 1600));
        mSecondDisplayInfo.flags |= FLAG_PRESENTATION;
    }

    @Test
    public void testInitialization_isEmpty() {
        // Empty after initializing.
        assertThat(mDisplayInfoMapper.getPossibleDisplayInfos(DEFAULT_DISPLAY)).isEmpty();

        // Still empty after updating.
        mDisplayInfoMapper.updatePossibleDisplayInfos(DEFAULT_DISPLAY);
        assertThat(mDisplayInfoMapper.getPossibleDisplayInfos(DEFAULT_DISPLAY)).isEmpty();
    }

    @Test
    public void testUpdatePossibleDisplayInfos_singleDisplay() {
        mPossibleDisplayInfo.add(mDefaultDisplayInfo);
        mDisplayInfoMapper.updatePossibleDisplayInfos(DEFAULT_DISPLAY);

        Set<DisplayInfo> displayInfos = mDisplayInfoMapper.getPossibleDisplayInfos(DEFAULT_DISPLAY);
        // An entry for each possible rotation, for a display that can be in a single state.
        assertThat(displayInfos.size()).isEqualTo(4);
        assertPossibleDisplayInfoEntries(displayInfos, mDefaultDisplayInfo);
    }

    @Test
    public void testUpdatePossibleDisplayInfos_secondDisplayAdded_sameGroup() {
        mPossibleDisplayInfo.add(mDefaultDisplayInfo);
        mDisplayInfoMapper.updatePossibleDisplayInfos(DEFAULT_DISPLAY);

        assertThat(mDisplayInfoMapper.getPossibleDisplayInfos(DEFAULT_DISPLAY).size()).isEqualTo(4);

        // Add another display layout to the set of supported states.
        mPossibleDisplayInfo.add(mSecondDisplayInfo);
        mDisplayInfoMapper.updatePossibleDisplayInfos(DEFAULT_DISPLAY);

        Set<DisplayInfo> displayInfos = mDisplayInfoMapper.getPossibleDisplayInfos(DEFAULT_DISPLAY);
        Set<DisplayInfo> defaultDisplayInfos = new ArraySet<>();
        Set<DisplayInfo> secondDisplayInfos = new ArraySet<>();
        for (DisplayInfo di : displayInfos) {
            if ((di.flags & FLAG_PRESENTATION) != 0) {
                secondDisplayInfos.add(di);
            } else {
                defaultDisplayInfos.add(di);
            }
        }
        // An entry for each possible rotation, for the default display.
        assertThat(defaultDisplayInfos).hasSize(4);
        assertPossibleDisplayInfoEntries(defaultDisplayInfos, mDefaultDisplayInfo);

        // An entry for each possible rotation, for the second display.
        assertThat(secondDisplayInfos).hasSize(4);
        assertPossibleDisplayInfoEntries(secondDisplayInfos, mSecondDisplayInfo);
    }

    @Test
    public void testUpdatePossibleDisplayInfos_secondDisplayAdded_differentGroup() {
        mPossibleDisplayInfo.add(mDefaultDisplayInfo);
        mDisplayInfoMapper.updatePossibleDisplayInfos(DEFAULT_DISPLAY);

        assertThat(mDisplayInfoMapper.getPossibleDisplayInfos(DEFAULT_DISPLAY).size()).isEqualTo(4);

        // Add another display to a different group.
        mSecondDisplayInfo.displayId = DEFAULT_DISPLAY + 1;
        mSecondDisplayInfo.displayGroupId = mDefaultDisplayInfo.displayGroupId + 1;
        mPossibleDisplayInfo.add(mSecondDisplayInfo);
        mDisplayInfoMapper.updatePossibleDisplayInfos(mSecondDisplayInfo.displayId);

        Set<DisplayInfo> displayInfos = mDisplayInfoMapper.getPossibleDisplayInfos(DEFAULT_DISPLAY);
        // An entry for each possible rotation, for the default display.
        assertThat(displayInfos).hasSize(4);
        assertPossibleDisplayInfoEntries(displayInfos, mDefaultDisplayInfo);

        Set<DisplayInfo> secondStateEntries =
                mDisplayInfoMapper.getPossibleDisplayInfos(mSecondDisplayInfo.displayId);
        // An entry for each possible rotation, for the second display.
        assertThat(secondStateEntries).hasSize(4);
        assertPossibleDisplayInfoEntries(secondStateEntries, mSecondDisplayInfo);
    }

    private static void initializeDisplayInfo(DisplayInfo outDisplayInfo, int displayId,
            Rect logicalBounds) {
        outDisplayInfo.displayId = displayId;
        outDisplayInfo.rotation = ROTATION_0;
        outDisplayInfo.logicalWidth = logicalBounds.width();
        outDisplayInfo.logicalHeight = logicalBounds.height();
    }

    private static void assertPossibleDisplayInfoEntries(Set<DisplayInfo> displayInfos,
            DisplayInfo expectedDisplayInfo) {
        boolean[] seenEveryRotation = new boolean[4];
        for (DisplayInfo displayInfo : displayInfos) {
            final int rotation = displayInfo.rotation;
            seenEveryRotation[rotation] = true;
            assertThat(displayInfo.displayId).isEqualTo(expectedDisplayInfo.displayId);
            assertEqualsRotatedDisplayInfo(displayInfo, expectedDisplayInfo);
        }
        assertThat(seenEveryRotation).isEqualTo(new boolean[]{true, true, true, true});
    }

    private static void assertEqualsRotatedDisplayInfo(DisplayInfo actual, DisplayInfo expected) {
        if (actual.rotation == ROTATION_0 || actual.rotation == ROTATION_180) {
            assertThat(actual.logicalWidth).isEqualTo(expected.logicalWidth);
            assertThat(actual.logicalHeight).isEqualTo(expected.logicalHeight);
        } else {
            assertThat(actual.logicalWidth).isEqualTo(expected.logicalHeight);
            assertThat(actual.logicalHeight).isEqualTo(expected.logicalWidth);
        }
    }
}
