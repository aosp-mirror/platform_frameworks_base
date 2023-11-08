/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.fuelgauge;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowAccessibilityManager;

import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
public class BatteryUtilsTest {
    private static final String DEFAULT_TTS_PACKAGE = "com.abc.talkback";
    private static final String ACCESSIBILITY_PACKAGE = "com.def.talkback";

    private Context mContext;
    private AccessibilityManager mAccessibilityManager;
    private ShadowAccessibilityManager mShadowAccessibilityManager;

    @Mock
    private AccessibilityServiceInfo mAccessibilityServiceInfo1;
    @Mock
    private AccessibilityServiceInfo mAccessibilityServiceInfo2;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext = spy(ApplicationProvider.getApplicationContext());
        doReturn(mContext).when(mContext).getApplicationContext();
        mAccessibilityManager = spy(mContext.getSystemService(AccessibilityManager.class));
        mShadowAccessibilityManager = shadowOf(mAccessibilityManager);
        doReturn(mAccessibilityManager).when(mContext)
                .getSystemService(AccessibilityManager.class);

        setTtsPackageName(DEFAULT_TTS_PACKAGE);
        doReturn(Arrays.asList(mAccessibilityServiceInfo1, mAccessibilityServiceInfo2))
                .when(mAccessibilityManager)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        doReturn(ACCESSIBILITY_PACKAGE + "/.TalkbackService").when(mAccessibilityServiceInfo1)
                .getId();
        doReturn("dummy_package_name").when(mAccessibilityServiceInfo2).getId();
    }

    @Test
    public void getBatteryIntent_registerReceiver() {
        BatteryUtils.getBatteryIntent(mContext);
        verify(mContext).registerReceiver(eq(null), any(IntentFilter.class));
    }

    @Test
    public void getA11yPackageNames_returnDefaultTtsPackageName() {
        mShadowAccessibilityManager.setEnabled(false);

        assertThat(BatteryUtils.getA11yPackageNames(mContext))
                .containsExactly(DEFAULT_TTS_PACKAGE);
    }

    @Test
    public void getA11yPackageNames_returnExpectedPackageNames() {
        mShadowAccessibilityManager.setEnabled(true);

        assertThat(BatteryUtils.getA11yPackageNames(mContext))
                .containsExactly(DEFAULT_TTS_PACKAGE, ACCESSIBILITY_PACKAGE);
    }

    private void setTtsPackageName(String defaultTtsPackageName) {
        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.TTS_DEFAULT_SYNTH, defaultTtsPackageName);
    }
}
