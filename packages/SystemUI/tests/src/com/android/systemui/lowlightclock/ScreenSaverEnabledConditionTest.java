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

package com.android.systemui.lowlightclock;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.settings.SecureSettings;

import kotlinx.coroutines.CoroutineScope;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class ScreenSaverEnabledConditionTest extends SysuiTestCase {
    @Mock
    private Resources mResources;
    @Mock
    private SecureSettings mSecureSettings;
    @Mock
    CoroutineScope mScope;
    @Captor
    private ArgumentCaptor<ContentObserver> mSettingsObserverCaptor;
    private ScreenSaverEnabledCondition mCondition;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        // Default dreams to enabled by default
        doReturn(true).when(mResources).getBoolean(
                com.android.internal.R.bool.config_dreamsEnabledByDefault);

        mCondition = new ScreenSaverEnabledCondition(mScope, mResources, mSecureSettings);
    }

    @Test
    public void testScreenSaverInitiallyEnabled() {
        setScreenSaverEnabled(true);
        mCondition.start();
        assertThat(mCondition.isConditionMet()).isTrue();
    }

    @Test
    public void testScreenSaverInitiallyDisabled() {
        setScreenSaverEnabled(false);
        mCondition.start();
        assertThat(mCondition.isConditionMet()).isFalse();
    }

    @Test
    public void testScreenSaverStateChanges() {
        setScreenSaverEnabled(false);
        mCondition.start();
        assertThat(mCondition.isConditionMet()).isFalse();

        setScreenSaverEnabled(true);
        final ContentObserver observer = captureSettingsObserver();
        observer.onChange(/* selfChange= */ false);
        assertThat(mCondition.isConditionMet()).isTrue();
    }

    private void setScreenSaverEnabled(boolean enabled) {
        when(mSecureSettings.getIntForUser(eq(Settings.Secure.SCREENSAVER_ENABLED), anyInt(),
                eq(UserHandle.USER_CURRENT))).thenReturn(enabled ? 1 : 0);
    }

    private ContentObserver captureSettingsObserver() {
        verify(mSecureSettings).registerContentObserverForUserSync(
                eq(Settings.Secure.SCREENSAVER_ENABLED),
                mSettingsObserverCaptor.capture(), eq(UserHandle.USER_CURRENT));
        return mSettingsObserverCaptor.getValue();
    }
}
