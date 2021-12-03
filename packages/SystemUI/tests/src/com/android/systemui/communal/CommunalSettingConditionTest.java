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

package com.android.systemui.communal;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.communal.conditions.CommunalCondition;
import com.android.systemui.communal.conditions.CommunalSettingCondition;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.utils.os.FakeHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class CommunalSettingConditionTest extends SysuiTestCase {
    private FakeSettings mSecureSettings;
    private CommunalSettingCondition mCondition;

    @Before
    public void setup() {
        final FakeHandler handler = new FakeHandler(Looper.getMainLooper());
        mSecureSettings = new FakeSettings();
        mCondition = new CommunalSettingCondition(handler, mSecureSettings);
    }

    @Test
    public void addCallback_communalSettingEnabled_immediatelyReportsTrue() {
        updateCommunalSetting(true);

        final CommunalCondition.Callback callback = mock(CommunalCondition.Callback.class);
        mCondition.addCallback(callback);
        verify(callback).onConditionChanged(mCondition, true);
    }

    @Test
    public void addCallback_communalSettingDisabled_noReport() {
        updateCommunalSetting(false);

        final CommunalCondition.Callback callback = mock(CommunalCondition.Callback.class);
        mCondition.addCallback(callback);
        verify(callback, never()).onConditionChanged(eq(mCondition), anyBoolean());
    }

    @Test
    public void updateCallback_communalSettingEnabled_reportsTrue() {
        updateCommunalSetting(false);

        final CommunalCondition.Callback callback = mock(CommunalCondition.Callback.class);
        mCondition.addCallback(callback);
        clearInvocations(callback);

        updateCommunalSetting(true);
        verify(callback).onConditionChanged(mCondition, true);
    }

    @Test
    public void updateCallback_communalSettingDisabled_reportsFalse() {
        updateCommunalSetting(true);

        final CommunalCondition.Callback callback = mock(CommunalCondition.Callback.class);
        mCondition.addCallback(callback);
        clearInvocations(callback);

        updateCommunalSetting(false);
        verify(callback).onConditionChanged(mCondition, false);
    }

    @Test
    public void updateCallback_communalSettingDidNotChange_neverReportDup() {
        updateCommunalSetting(true);

        final CommunalCondition.Callback callback = mock(CommunalCondition.Callback.class);
        mCondition.addCallback(callback);
        clearInvocations(callback);

        updateCommunalSetting(true);
        verify(callback, never()).onConditionChanged(mCondition, true);
    }

    private void updateCommunalSetting(boolean value) {
        mSecureSettings.putIntForUser(Settings.Secure.COMMUNAL_MODE_ENABLED, value ? 1 : 0,
                UserHandle.USER_SYSTEM);
    }
}
