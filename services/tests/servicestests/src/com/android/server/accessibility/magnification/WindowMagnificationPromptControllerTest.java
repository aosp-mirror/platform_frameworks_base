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

package com.android.server.accessibility.magnification;

import static android.content.pm.PackageManager.FEATURE_WINDOW_MAGNIFICATION;
import static android.provider.Settings.Secure.ACCESSIBILITY_SHOW_WINDOW_MAGNIFICATION_PROMPT;

import static com.android.internal.messages.nano.SystemMessageProto.SystemMessage.NOTE_A11Y_WINDOW_MAGNIFICATION_FEATURE;
import static com.android.server.accessibility.magnification.WindowMagnificationPromptController.ACTION_TURN_ON_IN_SETTINGS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link WindowMagnificationPromptController}.
 */
public class WindowMagnificationPromptControllerTest {

    private static final int TEST_USER = 0;

    @Mock
    private NotificationManager mNotificationManager;
    @Mock
    private StatusBarManager mStatusBarManager;
    @Rule
    public A11yTestableContext mTestableContext = new A11yTestableContext(
            InstrumentationRegistry.getContext());
    private ContentResolver mResolver = mTestableContext.getContentResolver();
    private WindowMagnificationPromptController mWindowMagnificationPromptController;
    private BroadcastReceiver mReceiver;

    /**
     *  return whether window magnification is supported for current test context.
     */
    private boolean isWindowModeSupported() {
        return mTestableContext.getPackageManager().hasSystemFeature(FEATURE_WINDOW_MAGNIFICATION);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableContext.addMockSystemService(NotificationManager.class, mNotificationManager);
        mTestableContext.addMockSystemService(StatusBarManager.class, mStatusBarManager);
        setWindowMagnificationPromptSettings(true);
        mWindowMagnificationPromptController = new WindowMagnificationPromptController(
                mTestableContext, TEST_USER);

        // skip test if window magnification is not supported to prevent fail results.
        Assume.assumeTrue(isWindowModeSupported());
    }

    @After
    public void tearDown() throws Exception {
        mWindowMagnificationPromptController.onDestroy();
    }

    @Test
    public void showNotificationIfNeeded_promptSettingsIsOn_showNotification() {
        mWindowMagnificationPromptController.showNotificationIfNeeded();

        verify(mNotificationManager).notify(eq(NOTE_A11Y_WINDOW_MAGNIFICATION_FEATURE), any(
                Notification.class));
    }

    @Test
    public void tapTurnOnAction_isShown_cancelNotificationAndLaunchMagnificationSettings() {
        showNotificationAndAssert();

        final Intent intent = new Intent(ACTION_TURN_ON_IN_SETTINGS);
        mReceiver.onReceive(mTestableContext, intent);

        verify(mNotificationManager).cancel(NOTE_A11Y_WINDOW_MAGNIFICATION_FEATURE);
        verifyLaunchMagnificationSettings();
    }

    @Test
    public void tapTurnOnAction_isShown_settingsValueIsFalseAndUnregisterReceiver() {
        showNotificationAndAssert();

        final Intent intent = new Intent(ACTION_TURN_ON_IN_SETTINGS);
        mReceiver.onReceive(mTestableContext, intent);

        assertThat(Settings.Secure.getIntForUser(mResolver,
                ACCESSIBILITY_SHOW_WINDOW_MAGNIFICATION_PROMPT, -1, TEST_USER)).isEqualTo(0);
        verify(mTestableContext.getSpyContext()).unregisterReceiver(mReceiver);
    }

    @Test
    public void tapDismissAction_isShown_cancelNotificationAndUnregisterReceiver() {
        showNotificationAndAssert();

        final Intent intent = new Intent(WindowMagnificationPromptController.ACTION_DISMISS);
        mReceiver.onReceive(mTestableContext, intent);

        verify(mNotificationManager).cancel(NOTE_A11Y_WINDOW_MAGNIFICATION_FEATURE);
        verify(mTestableContext.getSpyContext()).unregisterReceiver(mReceiver);
    }

    @Test
    public void promptSettingsChangeToFalse_isShown_cancelNotificationAndUnregisterReceiver() {
        showNotificationAndAssert();

        setWindowMagnificationPromptSettings(false);

        verify(mNotificationManager).cancel(NOTE_A11Y_WINDOW_MAGNIFICATION_FEATURE);
        verify(mTestableContext.getSpyContext()).unregisterReceiver(mReceiver);
    }

    @Test
    public void onDestroy_isShown_cancelNotificationAndUnregisterReceiver() {
        showNotificationAndAssert();

        mWindowMagnificationPromptController.onDestroy();

        verify(mNotificationManager).cancel(NOTE_A11Y_WINDOW_MAGNIFICATION_FEATURE);
        verify(mTestableContext.getSpyContext()).unregisterReceiver(mReceiver);
    }

    private void verifyLaunchMagnificationSettings() {
        final ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        final ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        final ArgumentCaptor<UserHandle> userHandleCaptor = ArgumentCaptor.forClass(
                UserHandle.class);
        verify(mTestableContext.getSpyContext()).startActivityAsUser(intentCaptor.capture(),
                bundleCaptor.capture(), userHandleCaptor.capture());
        assertThat(intentCaptor.getValue().getAction()).isEqualTo(
                Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS);
        assertThat(userHandleCaptor.getValue().getIdentifier()).isEqualTo(TEST_USER);
        verify(mStatusBarManager).collapsePanels();
    }

    private void showNotificationAndAssert() {
        mWindowMagnificationPromptController.showNotificationIfNeeded();
        mReceiver = mWindowMagnificationPromptController.mNotificationActionReceiver;
        assertThat(mReceiver).isNotNull();
    }

    private void setWindowMagnificationPromptSettings(boolean enable) {
        Settings.Secure.putIntForUser(mResolver, ACCESSIBILITY_SHOW_WINDOW_MAGNIFICATION_PROMPT,
                enable ? 1 : 0, TEST_USER);
        if (mWindowMagnificationPromptController != null) {
            mWindowMagnificationPromptController.onPromptSettingsValueChanged();
        }
    }

    private class A11yTestableContext extends TestableContext {

        private Context mSpyContext;

        A11yTestableContext(Context base) {
            super(base);
            mSpyContext = Mockito.mock(Context.class);
        }

        @Override
        public void startActivityAsUser(Intent intent, Bundle options, UserHandle user) {
            mSpyContext.startActivityAsUser(intent, options, user);
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter,
                String broadcastPermission, Handler scheduler) {
            return mSpyContext.registerReceiver(receiver, filter, broadcastPermission, scheduler);
        }

        @Override
        public void unregisterReceiver(BroadcastReceiver receiver) {
            mSpyContext.unregisterReceiver(receiver);
        }

        Context getSpyContext() {
            return mSpyContext;
        }
    }
}
