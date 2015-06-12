/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.app;


import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.RepetitiveTest;
import android.test.TimedTest;

import java.util.Random;

/**
 * Test which spams notification manager with a large number of notifications, for both stress and
 * performance testing.
 */
public class NotificationStressTest extends InstrumentationTestCase {

    private static final int NUM_ITERATIONS = 200;
    private static final int[] ICONS = new int[] {
        android.R.drawable.stat_notify_call_mute,
        android.R.drawable.stat_notify_chat,
        android.R.drawable.stat_notify_error,
        android.R.drawable.stat_notify_missed_call,
        android.R.drawable.stat_notify_more,
        android.R.drawable.stat_notify_sdcard,
        android.R.drawable.stat_notify_sdcard_prepare,
        android.R.drawable.stat_notify_sdcard_usb,
        android.R.drawable.stat_notify_sync,
        android.R.drawable.stat_notify_sync_noanim,
        android.R.drawable.stat_notify_voicemail,
    };

    private final Random mRandom = new Random();
    private Context mContext;
    private NotificationManager mNotificationManager;
    private int notifyId = 0;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContext = getInstrumentation().getContext();
        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mNotificationManager.cancelAll();
    }

    @RepetitiveTest(numIterations=NUM_ITERATIONS)
    public void testNotificationStress() {
        // Cancel one of every five notifications to vary load on notification manager
        if (notifyId % 5 == 4) {
            mNotificationManager.cancel(notifyId - 4);
        }
        sendNotification(notifyId++, "testNotificationStressNotify");
    }

    private void sendNotification(int id, CharSequence text) {
        // Fill in arbitrary content
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com"));
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent, 0);
        CharSequence title = text + " " + id;
        CharSequence subtitle = String.valueOf(System.currentTimeMillis());
        // Create "typical" notification with random icon
        Notification notification = new Notification.Builder(mContext)
                .setSmallIcon(ICONS[mRandom.nextInt(ICONS.length)])
                .setTicker(text)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(title)
                .setContentText(subtitle)
                .setContentIntent(pendingIntent)
                .build();
        mNotificationManager.notify(id, notification);
        SystemClock.sleep(10);
    }
}
