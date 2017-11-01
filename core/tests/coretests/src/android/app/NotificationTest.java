/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.app;

import static com.android.internal.util.NotificationColorUtil.satisfiesTextContrast;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.media.session.MediaSession;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NotificationTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
    }

    @Test
    public void testColorizedByPermission() {
        Notification n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_CAN_COLORIZE, true)
                .setColorized(true)
                .build();
        assertTrue(n.isColorized());

        n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_CAN_COLORIZE, true)
                .build();
        assertFalse(n.isColorized());

        n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_CAN_COLORIZE, false)
                .setColorized(true)
                .build();
        assertFalse(n.isColorized());
    }

    @Test
    public void testColorizedByForeground() {
        Notification n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .setColorized(true)
                .build();
        assertTrue(n.isColorized());

        n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, true)
                .build();
        assertFalse(n.isColorized());

        n = new Notification.Builder(mContext, "test")
                .setFlag(Notification.FLAG_FOREGROUND_SERVICE, false)
                .setColorized(true)
                .build();
        assertFalse(n.isColorized());
    }

    @Test
    public void testColorSatisfiedWhenBgDarkTextDarker() {
        Notification.Builder builder = getMediaNotification();
        Notification n = builder.build();

        assertTrue(n.isColorized());

        // An initial guess where the foreground color is actually darker than an already dark bg
        int backgroundColor = 0xff585868;
        int initialForegroundColor = 0xff505868;
        builder.setColorPalette(backgroundColor, initialForegroundColor);
        int primaryTextColor = builder.getPrimaryTextColor();
        assertTrue(satisfiesTextContrast(primaryTextColor, backgroundColor));
        int secondaryTextColor = builder.getSecondaryTextColor();
        assertTrue(satisfiesTextContrast(secondaryTextColor, backgroundColor));
    }

    @Test
    public void testHasCompletedProgress_noProgress() {
        Notification n = new Notification.Builder(mContext).build();

        assertFalse(n.hasCompletedProgress());
    }

    @Test
    public void testHasCompletedProgress_complete() {
        Notification n = new Notification.Builder(mContext)
                .setProgress(100, 100, true)
                .build();
        Notification n2 = new Notification.Builder(mContext)
                .setProgress(10, 10, false)
                .build();
        assertTrue(n.hasCompletedProgress());
        assertTrue(n2.hasCompletedProgress());
    }

    @Test
    public void testHasCompletedProgress_notComplete() {
        Notification n = new Notification.Builder(mContext)
                .setProgress(100, 99, true)
                .build();
        Notification n2 = new Notification.Builder(mContext)
                .setProgress(10, 4, false)
                .build();
        assertFalse(n.hasCompletedProgress());
        assertFalse(n2.hasCompletedProgress());
    }

    @Test
    public void testHasCompletedProgress_zeroMax() {
        Notification n = new Notification.Builder(mContext)
                .setProgress(0, 0, true)
                .build();
        assertFalse(n.hasCompletedProgress());
    }

    private Notification.Builder getMediaNotification() {
        MediaSession session = new MediaSession(mContext, "test");
        return new Notification.Builder(mContext, "color")
                .setSmallIcon(com.android.internal.R.drawable.emergency_icon)
                .setContentTitle("Title")
                .setContentText("Text")
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()));
    }
}
