/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationManager;
import android.os.Handler;
import android.provider.Settings;
import android.service.notification.ZenModeConfig;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.ZenModeController.Callback;
import com.android.systemui.util.settings.FakeGlobalSettings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SmallTest
@RunWith(AndroidJUnit4.class)
@RunWithLooper
public class ZenModeControllerImplTest extends SysuiTestCase {

    private Callback mCallback;
    @Mock
    NotificationManager mNm;
    @Mock
    ZenModeConfig mConfig;
    @Mock
    BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    DumpManager mDumpManager;
    @Mock
    UserTracker mUserTracker;
    private ZenModeControllerImpl mController;

    private final FakeGlobalSettings mGlobalSettings = new FakeGlobalSettings();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(NotificationManager.class, mNm);
        when(mNm.getZenModeConfig()).thenReturn(mConfig);

        mController = new ZenModeControllerImpl(
                mContext,
                Handler.createAsync(TestableLooper.get(this).getLooper()),
                Handler.createAsync(TestableLooper.get(this).getLooper()),
                mBroadcastDispatcher,
                mDumpManager,
                mGlobalSettings,
                mUserTracker);
    }

    @Test
    public void testRemoveDuringCallback() {
        mCallback = new Callback() {
            @Override
            public void onConfigChanged(ZenModeConfig config) {
                mController.removeCallback(mCallback);
            }
        };
        mController.addCallback(mCallback);
        Callback mockCallback = mock(Callback.class);
        mController.addCallback(mockCallback);
        mController.fireConfigChanged(null);
        verify(mockCallback).onConfigChanged(eq(null));
    }

    @Test
    public void testAreNotificationsHiddenInShade_zenOffShadeSuppressed() {
        mConfig.suppressedVisualEffects =
                NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
        mController.updateZenMode(Settings.Global.ZEN_MODE_OFF);
        mController.updateZenModeConfig();

        assertFalse(mController.areNotificationsHiddenInShade());
    }

    @Test
    public void testAreNotificationsHiddenInShade_zenOnShadeNotSuppressed() {
        NotificationManager.Policy policy = new NotificationManager.Policy(0, 0, 0,
                NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR);
        when(mNm.getConsolidatedNotificationPolicy()).thenReturn(policy);
        mController.updateConsolidatedNotificationPolicy();
        mController.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        assertFalse(mController.areNotificationsHiddenInShade());
    }

    @Test
    public void testAreNotificationsHiddenInShade_zenOnShadeSuppressed() {
        NotificationManager.Policy policy = new NotificationManager.Policy(0, 0, 0,
                NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST);
        when(mNm.getConsolidatedNotificationPolicy()).thenReturn(policy);
        mController.updateConsolidatedNotificationPolicy();
        mController.updateZenMode(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        assertTrue(mController.areNotificationsHiddenInShade());
    }

    @Test
    public void testModeChange() {
        List<Integer> states = List.of(
                Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                Settings.Global.ZEN_MODE_NO_INTERRUPTIONS,
                Settings.Global.ZEN_MODE_ALARMS,
                Settings.Global.ZEN_MODE_ALARMS
        );

        for (Integer state : states) {
            mGlobalSettings.putInt(Settings.Global.ZEN_MODE, state);
            TestableLooper.get(this).processAllMessages();
            assertEquals(state.intValue(), mController.getZen());
        }
    }

    @Test
    public void testModeChange_callbackNotified() {
        final AtomicInteger currentState = new AtomicInteger(-1);

        ZenModeController.Callback callback = new Callback() {
            @Override
            public void onZenChanged(int zen) {
                currentState.set(zen);
            }
        };

        mController.addCallback(callback);

        List<Integer> states = List.of(
                Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                Settings.Global.ZEN_MODE_NO_INTERRUPTIONS,
                Settings.Global.ZEN_MODE_ALARMS,
                Settings.Global.ZEN_MODE_ALARMS
        );

        for (Integer state : states) {
            mGlobalSettings.putInt(Settings.Global.ZEN_MODE, state);
            TestableLooper.get(this).processAllMessages();
            assertEquals(state.intValue(), currentState.get());
        }

    }

    @Test
    public void testCallbackRemovedWhileDispatching_doesntCrash() {
        final AtomicBoolean remove = new AtomicBoolean(false);
        mGlobalSettings.putInt(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_OFF);
        TestableLooper.get(this).processAllMessages();
        final ZenModeController.Callback callback = new ZenModeController.Callback() {
            @Override
            public void onZenChanged(int zen) {
                if (remove.get()) {
                    mController.removeCallback(this);
                }
            }
        };
        mController.addCallback(callback);
        mController.addCallback(new ZenModeController.Callback() {});

        remove.set(true);

        mGlobalSettings.putInt(Settings.Global.ZEN_MODE, Settings.Global.ZEN_MODE_NO_INTERRUPTIONS);
        TestableLooper.get(this).processAllMessages();
    }
}
