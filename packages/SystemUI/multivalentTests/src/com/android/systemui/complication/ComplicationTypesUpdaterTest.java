/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.complication;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.settingslib.dream.DreamBackend;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.condition.SelfExecutingMonitor;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.shared.condition.Monitor;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.FakeSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ComplicationTypesUpdaterTest extends SysuiTestCase {
    @Mock
    private Context mContext;
    @Mock
    private DreamBackend mDreamBackend;
    private FakeSettings mSecureSettings;
    @Mock
    private DreamOverlayStateController mDreamOverlayStateController;
    @Captor
    private ArgumentCaptor<ContentObserver> mSettingsObserverCaptor;

    private final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    private ComplicationTypesUpdater mController;

    private Monitor mMonitor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mDreamBackend.getEnabledComplications()).thenReturn(new HashSet<>());
        mSecureSettings = new FakeSettings();

        mMonitor = SelfExecutingMonitor.createInstance();
        mController = new ComplicationTypesUpdater(mDreamBackend, mExecutor,
                mSecureSettings, mDreamOverlayStateController, mMonitor);
    }

    @Test
    public void testPushUpdateToDreamOverlayStateControllerImmediatelyOnStart() {
        // DreamOverlayStateController shouldn't be updated before start().
        verify(mDreamOverlayStateController, never()).setAvailableComplicationTypes(anyInt());

        mController.start();
        mExecutor.runAllReady();

        // DreamOverlayStateController updated immediately on start().
        verify(mDreamOverlayStateController).setAvailableComplicationTypes(anyInt());
    }

    @Test
    public void testPushUpdateToDreamOverlayStateControllerOnChange() {
        mController.start();
        mExecutor.runAllReady();

        when(mDreamBackend.getEnabledComplications()).thenReturn(new HashSet<>(Arrays.asList(
                DreamBackend.COMPLICATION_TYPE_TIME, DreamBackend.COMPLICATION_TYPE_WEATHER,
                DreamBackend.COMPLICATION_TYPE_AIR_QUALITY)));

        // Update the setting to trigger any content observers
        mSecureSettings.putBoolForUser(
                Settings.Secure.SCREENSAVER_COMPLICATIONS_ENABLED, true,
                UserHandle.myUserId());
        mExecutor.runAllReady();

        verify(mDreamOverlayStateController).setAvailableComplicationTypes(
                Complication.COMPLICATION_TYPE_TIME | Complication.COMPLICATION_TYPE_WEATHER
                        | Complication.COMPLICATION_TYPE_AIR_QUALITY);
    }
}
