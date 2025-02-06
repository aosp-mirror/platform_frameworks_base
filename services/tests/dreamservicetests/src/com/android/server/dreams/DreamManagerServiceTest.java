/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.dreams;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.BatteryManagerInternal;
import android.os.PowerManagerInternal;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.LocalServiceKeeperRule;
import com.android.server.SystemService;
import com.android.server.input.InputManagerInternal;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Test class for {@link DreamManagerService}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamManagerServiceTest {
    private ContextWrapper mContextSpy;

    @Mock
    private ActivityManagerInternal mActivityManagerInternalMock;
    @Mock
    private BatteryManagerInternal mBatteryManagerInternal;

    @Mock
    private InputManagerInternal mInputManagerInternal;
    @Mock
    private PowerManagerInternal mPowerManagerInternalMock;

    @Mock
    private BatteryManager mBatteryManager;
    @Mock
    private UserManager mUserManagerMock;

    @Rule
    public LocalServiceKeeperRule mLocalServiceKeeperRule = new LocalServiceKeeperRule();


    @Rule
    public final TestableContext mContext = new TestableContext(
            getInstrumentation().getContext());

    private TestHandler mTestHandler;

    @Before
    public void setUp() throws Exception {
        mTestHandler = new TestHandler(/* callback= */ null);
        MockitoAnnotations.initMocks(this);
        mContextSpy = spy(mContext);

        mLocalServiceKeeperRule.overrideLocalService(
                ActivityManagerInternal.class, mActivityManagerInternalMock);
        mLocalServiceKeeperRule.overrideLocalService(
                BatteryManagerInternal.class, mBatteryManagerInternal);
        mLocalServiceKeeperRule.overrideLocalService(
                InputManagerInternal.class, mInputManagerInternal);
        mLocalServiceKeeperRule.overrideLocalService(
                PowerManagerInternal.class, mPowerManagerInternalMock);

        when(mContextSpy.getSystemService(BatteryManager.class)).thenReturn(mBatteryManager);
        when(mContextSpy.getSystemService(UserManager.class)).thenReturn(mUserManagerMock);
    }

    private DreamManagerService createService() {
        return new DreamManagerService(mContextSpy, mTestHandler);
    }

    @Test
    public void testSettingsQueryUserChange() {
        // Enable dreams.
        Settings.Secure.putIntForUser(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ENABLED, 1,
                UserHandle.USER_CURRENT);

        // Initialize dream service so settings are read.
        final DreamManagerService service = createService();
        service.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        // Dreams are enabled.
        assertThat(service.dreamsEnabled()).isTrue();

        // Disable dreams.
        Settings.Secure.putIntForUser(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ENABLED, 0,
                UserHandle.USER_CURRENT);

        // Switch users, dreams are disabled.
        service.onUserSwitching(null, null);
        assertThat(service.dreamsEnabled()).isFalse();
    }

    @Test
    public void testDreamConditionActive_onDock() {
        // Enable dreaming on dock.
        Settings.Secure.putIntForUser(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK, 1,
                UserHandle.USER_CURRENT);

        // Initialize service so settings are read.
        final DreamManagerService service = createService();
        service.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
        assertThat(service.dreamConditionActiveInternal()).isFalse();

        // Dock event receiver is registered.
        ArgumentCaptor<BroadcastReceiver> receiverCaptor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContextSpy).registerReceiver(receiverCaptor.capture(),
                argThat((arg) -> arg.hasAction(Intent.ACTION_DOCK_EVENT)));

        // Device is docked.
        Intent dockIntent = new Intent(Intent.ACTION_DOCK_EVENT);
        dockIntent.putExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_HE_DESK);
        receiverCaptor.getValue().onReceive(null, dockIntent);

        // Dream condition is active.
        assertThat(service.dreamConditionActiveInternal()).isTrue();
    }

    @Test
    public void testDreamConditionActive_postured() {
        // Enable dreaming while postured.
        Settings.Secure.putIntForUser(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK, 0,
                UserHandle.USER_CURRENT);
        Settings.Secure.putIntForUser(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_POSTURED, 1,
                UserHandle.USER_CURRENT);

        // Initialize service so settings are read.
        final DreamManagerService service = createService();
        service.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);
        assertThat(service.dreamConditionActiveInternal()).isFalse();

        // Device is postured.
        service.setDevicePosturedInternal(true);

        // Dream condition is active.
        assertThat(service.dreamConditionActiveInternal()).isTrue();
    }

    @Test
    public void testDreamConditionActive_charging() {
        // Enable dreaming while charging only.
        Settings.Secure.putIntForUser(mContextSpy.getContentResolver(),
                Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP, 1,
                UserHandle.USER_CURRENT);

        // Device is charging.
        when(mBatteryManager.isCharging()).thenReturn(true);

        // Initialize service so settings are read.
        final DreamManagerService service = createService();
        service.onBootPhase(SystemService.PHASE_THIRD_PARTY_APPS_CAN_START);

        // Dream condition is active.
        assertThat(service.dreamConditionActiveInternal()).isTrue();
    }
}
