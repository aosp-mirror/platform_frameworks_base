/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.wm;

import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;

import static com.google.common.truth.Truth.assertThat;

import android.car.settings.CarSettings;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class BarControlPolicyTest extends SysuiTestCase {

    private static final String PACKAGE_NAME = "sample.app";

    @Before
    public void setUp() {
        BarControlPolicy.reset();
    }

    @After
    public void tearDown() {
        Settings.Global.clearProviderForTest();
    }

    @Test
    public void reloadFromSetting_notSet_doesNotSetFilters() {
        BarControlPolicy.reloadFromSetting(mContext);

        assertThat(BarControlPolicy.sImmersiveStatusFilter).isNull();
    }

    @Test
    public void reloadFromSetting_invalidPolicyControlString_doesNotSetFilters() {
        String text = "sample text";
        Settings.Global.putString(
                mContext.getContentResolver(),
                CarSettings.Global.SYSTEM_BAR_VISIBILITY_OVERRIDE,
                text
        );

        BarControlPolicy.reloadFromSetting(mContext);

        assertThat(BarControlPolicy.sImmersiveStatusFilter).isNull();
    }

    @Test
    public void reloadFromSetting_validPolicyControlString_setsFilters() {
        String text = "immersive.status=" + PACKAGE_NAME;
        Settings.Global.putString(
                mContext.getContentResolver(),
                CarSettings.Global.SYSTEM_BAR_VISIBILITY_OVERRIDE,
                text
        );

        BarControlPolicy.reloadFromSetting(mContext);

        assertThat(BarControlPolicy.sImmersiveStatusFilter).isNotNull();
    }

    @Test
    public void reloadFromSetting_filtersSet_doesNotSetFiltersAgain() {
        String text = "immersive.status=" + PACKAGE_NAME;
        Settings.Global.putString(
                mContext.getContentResolver(),
                CarSettings.Global.SYSTEM_BAR_VISIBILITY_OVERRIDE,
                text
        );

        BarControlPolicy.reloadFromSetting(mContext);

        assertThat(BarControlPolicy.reloadFromSetting(mContext)).isFalse();
    }

    @Test
    public void getBarVisibilities_policyControlNotSet_showsSystemBars() {
        int[] visibilities = BarControlPolicy.getBarVisibilities(PACKAGE_NAME);

        assertThat(visibilities[0]).isEqualTo(statusBars() | navigationBars());
        assertThat(visibilities[1]).isEqualTo(0);
    }

    @Test
    public void getBarVisibilities_immersiveStatusForAppAndMatchingApp_hidesStatusBar() {
        Settings.Global.putString(
                mContext.getContentResolver(),
                CarSettings.Global.SYSTEM_BAR_VISIBILITY_OVERRIDE,
                "immersive.status=" + PACKAGE_NAME);
        BarControlPolicy.reloadFromSetting(mContext);

        int[] visibilities = BarControlPolicy.getBarVisibilities(PACKAGE_NAME);

        assertThat(visibilities[0]).isEqualTo(navigationBars());
        assertThat(visibilities[1]).isEqualTo(statusBars());
    }

    @Test
    public void getBarVisibilities_immersiveStatusForAppAndNonMatchingApp_showsSystemBars() {
        Settings.Global.putString(
                mContext.getContentResolver(),
                CarSettings.Global.SYSTEM_BAR_VISIBILITY_OVERRIDE,
                "immersive.status=" + PACKAGE_NAME);
        BarControlPolicy.reloadFromSetting(mContext);

        int[] visibilities = BarControlPolicy.getBarVisibilities("sample2.app");

        assertThat(visibilities[0]).isEqualTo(statusBars() | navigationBars());
        assertThat(visibilities[1]).isEqualTo(0);
    }

    @Test
    public void getBarVisibilities_immersiveStatusForAppsAndNonApp_showsSystemBars() {
        Settings.Global.putString(
                mContext.getContentResolver(),
                CarSettings.Global.SYSTEM_BAR_VISIBILITY_OVERRIDE,
                "immersive.status=apps");
        BarControlPolicy.reloadFromSetting(mContext);

        int[] visibilities = BarControlPolicy.getBarVisibilities(PACKAGE_NAME);

        assertThat(visibilities[0]).isEqualTo(statusBars() | navigationBars());
        assertThat(visibilities[1]).isEqualTo(0);
    }

    @Test
    public void getBarVisibilities_immersiveFullForAppAndMatchingApp_hidesSystemBars() {
        Settings.Global.putString(
                mContext.getContentResolver(),
                CarSettings.Global.SYSTEM_BAR_VISIBILITY_OVERRIDE,
                "immersive.full=" + PACKAGE_NAME);
        BarControlPolicy.reloadFromSetting(mContext);

        int[] visibilities = BarControlPolicy.getBarVisibilities(PACKAGE_NAME);

        assertThat(visibilities[0]).isEqualTo(0);
        assertThat(visibilities[1]).isEqualTo(statusBars() | navigationBars());
    }

    @Test
    public void getBarVisibilities_immersiveFullForAppAndNonMatchingApp_showsSystemBars() {
        Settings.Global.putString(
                mContext.getContentResolver(),
                CarSettings.Global.SYSTEM_BAR_VISIBILITY_OVERRIDE,
                "immersive.full=" + PACKAGE_NAME);
        BarControlPolicy.reloadFromSetting(mContext);

        int[] visibilities = BarControlPolicy.getBarVisibilities("sample2.app");

        assertThat(visibilities[0]).isEqualTo(statusBars() | navigationBars());
        assertThat(visibilities[1]).isEqualTo(0);
    }

    @Test
    public void getBarVisibilities_immersiveFullForAppsAndNonApp_showsSystemBars() {
        Settings.Global.putString(
                mContext.getContentResolver(),
                CarSettings.Global.SYSTEM_BAR_VISIBILITY_OVERRIDE,
                "immersive.full=apps");
        BarControlPolicy.reloadFromSetting(mContext);

        int[] visibilities = BarControlPolicy.getBarVisibilities(PACKAGE_NAME);

        assertThat(visibilities[0]).isEqualTo(statusBars() | navigationBars());
        assertThat(visibilities[1]).isEqualTo(0);
    }
}
