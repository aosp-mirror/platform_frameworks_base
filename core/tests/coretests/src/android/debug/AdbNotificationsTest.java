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

package android.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.app.Notification;
import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public final class AdbNotificationsTest {
    private Context mContext;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void testCreateNotification_UsbTransportType() throws Exception {
        CharSequence title = mContext.getResources().getText(
                com.android.internal.R.string.adb_active_notification_title);
        CharSequence message = mContext.getResources().getText(
                com.android.internal.R.string.adb_active_notification_message);

        Notification notification = AdbNotifications.createNotification(mContext,
                AdbTransportType.USB);

        // Verify that the adb notification for usb connections has the correct text.
        assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_TITLE, ""));
        assertEquals(message, notification.extras.getCharSequence(Notification.EXTRA_TEXT, ""));
        // Verify the PendingIntent has an explicit intent (b/153356209), if there is a
        // PendingIntent attached.
        if (notification.contentIntent != null) {
            assertFalse(TextUtils.isEmpty(notification.contentIntent.getIntent().getPackage()));
        }
    }

    @Test
    public void testCreateNotification_WifiTransportType() throws Exception {
        CharSequence title = mContext.getResources().getText(
                com.android.internal.R.string.adbwifi_active_notification_title);
        CharSequence message = mContext.getResources().getText(
                com.android.internal.R.string.adbwifi_active_notification_message);

        Notification notification = AdbNotifications.createNotification(mContext,
                AdbTransportType.WIFI);

        // Verify that the adb notification for usb connections has the correct text.
        assertEquals(title, notification.extras.getCharSequence(Notification.EXTRA_TITLE, ""));
        assertEquals(message, notification.extras.getCharSequence(Notification.EXTRA_TEXT, ""));
        // Verify the PendingIntent has an explicit intent (b/153356209), if there is a
        // PendingIntent attached.
        if (notification.contentIntent != null) {
            assertFalse(TextUtils.isEmpty(notification.contentIntent.getIntent().getPackage()));
        }
    }
}
