/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import static java.util.Collections.emptyList;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

/**
 * Tests for {@link HearingDevicesToolItemParser}.
 */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class HearingDevicesToolItemParserTest extends SysuiTestCase {
    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private ActivityInfo mActivityInfo;
    @Mock
    private Drawable mDrawable;
    private static final String TEST_PKG = "pkg";
    private static final String TEST_CLS = "cls";
    private static final ComponentName TEST_COMPONENT = new ComponentName(TEST_PKG, TEST_CLS);
    private static final String TEST_NO_EXIST_PKG = "NoPkg";
    private static final String TEST_NO_EXIST_CLS = "NoCls";
    private static final ComponentName TEST_NO_EXIST_COMPONENT = new ComponentName(
            TEST_NO_EXIST_PKG, TEST_NO_EXIST_CLS);

    private static final String TEST_LABEL = "label";

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        mContext.setMockPackageManager(mPackageManager);

        when(mPackageManager.getActivityInfo(eq(TEST_COMPONENT), anyInt())).thenReturn(
                mActivityInfo);
        when(mPackageManager.getActivityInfo(eq(TEST_NO_EXIST_COMPONENT), anyInt())).thenThrow(
                new PackageManager.NameNotFoundException());
        when(mActivityInfo.loadLabel(mPackageManager)).thenReturn(TEST_LABEL);
        when(mActivityInfo.loadIcon(mPackageManager)).thenReturn(mDrawable);
        when(mActivityInfo.getComponentName()).thenReturn(TEST_COMPONENT);
    }

    @Test
    public void parseStringArray_noString_emptyResult() {
        assertThat(HearingDevicesToolItemParser.parseStringArray(mContext, new String[]{},
                new String[]{})).isEqualTo(emptyList());
    }

    @Test
    public void parseStringArray_oneToolName_oneExpectedToolItem() {
        String[] toolName = new String[]{TEST_PKG + "/" + TEST_CLS};

        List<ToolItem> toolItemList = HearingDevicesToolItemParser.parseStringArray(mContext,
                toolName, new String[]{});

        assertThat(toolItemList.size()).isEqualTo(1);
        assertThat(toolItemList.get(0).getToolName()).isEqualTo(TEST_LABEL);
        assertThat(toolItemList.get(0).getToolIntent().getComponent()).isEqualTo(TEST_COMPONENT);
    }

    @Test
    public void parseStringArray_fourToolName_maxThreeToolItem() {
        String componentNameString = TEST_PKG + "/" + TEST_CLS;
        String[] fourToolName =
                new String[]{componentNameString, componentNameString, componentNameString,
                        componentNameString};

        List<ToolItem> toolItemList = HearingDevicesToolItemParser.parseStringArray(mContext,
                fourToolName, new String[]{});
        assertThat(toolItemList.size()).isEqualTo(HearingDevicesToolItemParser.MAX_NUM);
    }

    @Test
    public void parseStringArray_oneWrongFormatToolName_noToolItem() {
        String[] wrongFormatToolName = new String[]{TEST_PKG};

        List<ToolItem> toolItemList = HearingDevicesToolItemParser.parseStringArray(mContext,
                wrongFormatToolName, new String[]{});
        assertThat(toolItemList.size()).isEqualTo(0);
    }

    @Test
    public void parseStringArray_oneNotExistToolName_noToolItem() {
        String[] notExistToolName = new String[]{TEST_NO_EXIST_PKG + "/" + TEST_NO_EXIST_CLS};

        List<ToolItem> toolItemList = HearingDevicesToolItemParser.parseStringArray(mContext,
                notExistToolName, new String[]{});
        assertThat(toolItemList.size()).isEqualTo(0);
    }
}
