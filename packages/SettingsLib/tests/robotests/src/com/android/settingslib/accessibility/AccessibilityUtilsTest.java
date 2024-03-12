/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.accessibility;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.content.Context;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.settingslib.testutils.shadow.ShadowSecure;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowSecure.class})
public class AccessibilityUtilsTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.application;
    }

    @Test
    public void getEnabledServicesFromSettings_noService_emptyResult() {
        assertThat(AccessibilityUtils.getEnabledServicesFromSettings(mContext)).isEmpty();
    }

    @Test
    public void getEnabledServicesFromSettings_badFormat_emptyResult() {
        ShadowSecure.putStringForUser(
                mContext.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ":",
                UserHandle.myUserId());

        assertThat(AccessibilityUtils.getEnabledServicesFromSettings(mContext)).isEmpty();
    }

    @Test
    public void getEnabledServicesFromSettings_1Service_1result() {
        final ComponentName cn = new ComponentName("pkg", "serv");
        ShadowSecure.putStringForUser(
                mContext.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                cn.flattenToString() + ":",
                UserHandle.myUserId());

        assertThat(AccessibilityUtils.getEnabledServicesFromSettings(mContext))
                .containsExactly(cn);
    }

    @Test
    public void getEnabledServicesFromSettings_2Services_2results() {
        final ComponentName cn1 = new ComponentName("pkg", "serv");
        final ComponentName cn2 = new ComponentName("pkg", "serv2");
        ShadowSecure.putStringForUser(
                mContext.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                cn1.flattenToString() + ":" + cn2.flattenToString(),
                UserHandle.myUserId());

        assertThat(AccessibilityUtils.getEnabledServicesFromSettings(mContext))
                .containsExactly(cn1, cn2);
    }
}
