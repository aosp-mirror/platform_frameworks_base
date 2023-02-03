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

package com.android.inputmethod.stresstest;

import static android.app.NotificationManager.IMPORTANCE_HIGH;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeFalse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.platform.test.annotations.RootPermissionTest;
import android.platform.test.rule.UnlockScreenRule;
import android.provider.Settings;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@RootPermissionTest
@RunWith(AndroidJUnit4.class)
public final class NotificationTest {

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(10);

    private static final String CHANNEL_ID = "TEST_CHANNEL";
    private static final String CHANNEL_NAME = "Test channel";

    private static final String REPLY_INPUT_KEY = "REPLY_KEY";
    private static final String REPLY_INPUT_LABEL = "Test reply label";
    private static final String ACTION_REPLY = "com.android.inputmethod.stresstest.ACTION_REPLY";
    private static final String REPLY_ACTION_LABEL = "Test reply";
    private static final int REPLY_REQUEST_CODE = 1;

    private static final String NOTIFICATION_TITLE = "Test notification";
    private static final String NOTIFICATION_CONTENT = "Test notification content";
    private static final int NOTIFICATION_ID = 2000;

    // This is for AOSP System UI for phones. When testing customized System UI, please modify here.
    private static final BySelector REPLY_SEND_BUTTON_SELECTOR =
            By.res("com.android.systemui", "remote_input_send");

    @Rule
    public UnlockScreenRule mUnlockScreenRule = new UnlockScreenRule();

    @Rule
    public ScreenCaptureRule mScreenCaptureRule =
            new ScreenCaptureRule("/sdcard/InputMethodStressTest");

    private Context mContext;
    private NotificationManager mNotificationManager;
    private UiDevice mUiDevice;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mNotificationManager = mContext.getSystemService(NotificationManager.class);
        PackageManager pm = mContext.getPackageManager();
        // Do not run on Automotive.
        assumeFalse(pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE));
        // Do not run on TV. Direct Reply isn't supported on TV.
        assumeFalse(pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK_ONLY));
    }

    @After
    public void tearDown() {
        mNotificationManager.cancelAll();
        mUiDevice.pressHome();
    }

    @Test
    public void testDirectReply() {
        postMessagingNotification();
        mUiDevice.openNotification();
        // The text can be shown as-is, or all-caps, depending on the system.
        Pattern actionLabelPattern = Pattern.compile(REPLY_ACTION_LABEL, Pattern.CASE_INSENSITIVE);
        mUiDevice.wait(Until.findObject(By.text(actionLabelPattern)), TIMEOUT).click();
        // Verify that IME is visible.
        assertThat(mUiDevice.wait(Until.findObject(By.pkg(getImePackage(mContext))), TIMEOUT))
                .isNotNull();
        // Type something, which enables the Send button, then click the Send button.
        mUiDevice.pressKeyCode(KeyEvent.KEYCODE_A);
        mUiDevice.pressKeyCode(KeyEvent.KEYCODE_B);
        mUiDevice.pressKeyCode(KeyEvent.KEYCODE_C);
        mUiDevice.wait(Until.findObject(REPLY_SEND_BUTTON_SELECTOR.enabled(true)), TIMEOUT).click();
        // Verify that IME is gone.
        assertThat(mUiDevice.wait(Until.gone(By.pkg(getImePackage(mContext))), TIMEOUT)).isTrue();
    }

    private void postMessagingNotification() {
        // Register the channel. It's safe to register the same channel again and again.
        NotificationChannel channel =
                new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, IMPORTANCE_HIGH);
        mNotificationManager.createNotificationChannel(channel);

        // Post inline reply notification.
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                mContext, REPLY_REQUEST_CODE, new Intent().setAction(ACTION_REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        RemoteInput remoteInput = new RemoteInput.Builder(REPLY_INPUT_KEY)
                .setLabel(REPLY_INPUT_LABEL)
                .build();
        Icon icon = Icon.createWithResource(mContext, android.R.drawable.ic_menu_edit);
        Notification.Action action =
                new Notification.Action.Builder(icon, REPLY_ACTION_LABEL, pendingIntent)
                        .addRemoteInput(remoteInput)
                        .build();
        Notification notification = new Notification.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(NOTIFICATION_TITLE)
                .setContentText(NOTIFICATION_CONTENT)
                .addAction(action)
                .build();
        mNotificationManager.notify(NOTIFICATION_ID, notification);
    }

    private static String getImePackage(Context context) {
        String imeId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName cn = ComponentName.unflattenFromString(imeId);
        assertThat(cn).isNotNull();
        return cn.getPackageName();
    }
}
