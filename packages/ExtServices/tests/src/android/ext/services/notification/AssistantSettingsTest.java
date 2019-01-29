/**
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

package android.ext.services.notification;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.ContentResolver;
import android.os.Handler;
import android.os.Looper;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.testing.TestableContext;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class AssistantSettingsTest {
    private static final int USER_ID = 5;

    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext(), null);

    @Mock Runnable mOnUpdateRunnable;

    private ContentResolver mResolver;
    private AssistantSettings mAssistantSettings;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mResolver = mContext.getContentResolver();
        Handler handler = new Handler(Looper.getMainLooper());

        // To bypass real calls to global settings values, set the Settings values here.
        Settings.Global.putFloat(mResolver,
                Settings.Global.BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT, 0.8f);
        Settings.Global.putInt(mResolver, Settings.Global.BLOCKING_HELPER_STREAK_LIMIT, 2);
        Settings.Secure.putInt(mResolver, Settings.Secure.NOTIFICATION_NEW_INTERRUPTION_MODEL, 1);

        mAssistantSettings = AssistantSettings.createForTesting(
                handler, mResolver, USER_ID, mOnUpdateRunnable);
    }

    @Test
    public void testGenerateRepliesDisabled() {
        DeviceConfig.setProperty(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_REPLIES,
                "false",
                false /* makeDefault */);
        mAssistantSettings.onDeviceConfigPropertyChanged(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_REPLIES,
                "false");

        assertFalse(mAssistantSettings.mGenerateReplies);
    }

    @Test
    public void testGenerateRepliesEnabled() {
        DeviceConfig.setProperty(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_REPLIES,
                "true",
                false /* makeDefault */);
        mAssistantSettings.onDeviceConfigPropertyChanged(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_REPLIES,
                "true");

        assertTrue(mAssistantSettings.mGenerateReplies);
    }

    @Test
    public void testGenerateRepliesEmptyFlag() {
        DeviceConfig.setProperty(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_REPLIES,
                "false",
                false /* makeDefault */);
        mAssistantSettings.onDeviceConfigPropertyChanged(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_REPLIES,
                "false");

        assertFalse(mAssistantSettings.mGenerateReplies);

        DeviceConfig.setProperty(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_REPLIES,
                "",
                false /* makeDefault */);
        mAssistantSettings.onDeviceConfigPropertyChanged(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_REPLIES,
                "");

        // Go back to the default value.
        assertTrue(mAssistantSettings.mGenerateReplies);
    }

    @Test
    public void testGenerateActionsDisabled() {
        DeviceConfig.setProperty(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_ACTIONS,
                "false",
                false /* makeDefault */);
        mAssistantSettings.onDeviceConfigPropertyChanged(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_ACTIONS,
                "false");

        assertFalse(mAssistantSettings.mGenerateActions);
    }

    @Test
    public void testGenerateActionsEnabled() {
        DeviceConfig.setProperty(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_ACTIONS,
                "true",
                false /* makeDefault */);
        mAssistantSettings.onDeviceConfigPropertyChanged(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_ACTIONS,
                "true");

        assertTrue(mAssistantSettings.mGenerateActions);
    }

    @Test
    public void testGenerateActionsEmptyFlag() {
        DeviceConfig.setProperty(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_ACTIONS,
                "false",
                false /* makeDefault */);
        mAssistantSettings.onDeviceConfigPropertyChanged(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_ACTIONS,
                "false");

        assertFalse(mAssistantSettings.mGenerateActions);

        DeviceConfig.setProperty(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_ACTIONS,
                "",
                false /* makeDefault */);
        mAssistantSettings.onDeviceConfigPropertyChanged(
                DeviceConfig.NotificationAssistant.NAMESPACE,
                DeviceConfig.NotificationAssistant.GENERATE_ACTIONS,
                "");

        // Go back to the default value.
        assertTrue(mAssistantSettings.mGenerateActions);
    }

    @Test
    public void testStreakLimit() {
        verify(mOnUpdateRunnable, never()).run();

        // Update settings value.
        int newStreakLimit = 4;
        Settings.Global.putInt(mResolver,
                Settings.Global.BLOCKING_HELPER_STREAK_LIMIT, newStreakLimit);

        // Notify for the settings value we updated.
        mAssistantSettings.onChange(false, Settings.Global.getUriFor(
                Settings.Global.BLOCKING_HELPER_STREAK_LIMIT));

        assertEquals(newStreakLimit, mAssistantSettings.mStreakLimit);
        verify(mOnUpdateRunnable).run();
    }

    @Test
    public void testDismissToViewRatioLimit() {
        verify(mOnUpdateRunnable, never()).run();

        // Update settings value.
        float newDismissToViewRatioLimit = 3f;
        Settings.Global.putFloat(mResolver,
                Settings.Global.BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT,
                newDismissToViewRatioLimit);

        // Notify for the settings value we updated.
        mAssistantSettings.onChange(false, Settings.Global.getUriFor(
                Settings.Global.BLOCKING_HELPER_DISMISS_TO_VIEW_RATIO_LIMIT));

        assertEquals(newDismissToViewRatioLimit, mAssistantSettings.mDismissToViewRatioLimit, 1e-6);
        verify(mOnUpdateRunnable).run();
    }
}
