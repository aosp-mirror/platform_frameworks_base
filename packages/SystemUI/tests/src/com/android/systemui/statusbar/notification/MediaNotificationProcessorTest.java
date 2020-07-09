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

package com.android.systemui.statusbar.notification;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertNotSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.annotation.Nullable;
import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.test.suitebuilder.annotation.SmallTest;
import android.widget.RemoteViews;

import androidx.palette.graphics.Palette;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class MediaNotificationProcessorTest extends SysuiTestCase {

    private static final int BITMAP_WIDTH = 10;
    private static final int BITMAP_HEIGHT = 10;

    /**
     * Color tolerance is borrowed from the AndroidX test utilities for Palette.
     */
    private static final int COLOR_TOLERANCE = 8;

    private MediaNotificationProcessor mProcessor;
    private Bitmap mBitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
    private ImageGradientColorizer mColorizer;
    @Nullable private Bitmap mArtwork;

    @Before
    public void setUp() {
        mColorizer = spy(new TestableColorizer(mBitmap));
        mProcessor = new MediaNotificationProcessor(getContext(), getContext(), mColorizer);
    }

    @After
    public void tearDown() {
        if (mArtwork != null) {
            mArtwork.recycle();
            mArtwork = null;
        }
    }

    @Test
    public void testColorizedWithLargeIcon() {
        Notification.Builder builder = new Notification.Builder(getContext()).setSmallIcon(
                R.drawable.ic_person)
                .setContentTitle("Title")
                .setLargeIcon(mBitmap)
                .setContentText("Text");
        Notification notification = builder.build();
        mProcessor.processNotification(notification, builder);
        verify(mColorizer).colorize(any(), anyInt(), anyBoolean());
    }

    @Test
    public void testNotColorizedWithoutLargeIcon() {
        Notification.Builder builder = new Notification.Builder(getContext()).setSmallIcon(
                R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");
        Notification notification = builder.build();
        mProcessor.processNotification(notification, builder);
        verifyZeroInteractions(mColorizer);
    }

    @Test
    public void testRemoteViewsReset() {
        Notification.Builder builder = new Notification.Builder(getContext()).setSmallIcon(
                R.drawable.ic_person)
                .setContentTitle("Title")
                .setStyle(new Notification.MediaStyle())
                .setLargeIcon(mBitmap)
                .setContentText("Text");
        Notification notification = builder.build();
        RemoteViews remoteViews = new RemoteViews(getContext().getPackageName(),
                R.layout.custom_view_dark);
        notification.contentView = remoteViews;
        notification.bigContentView = remoteViews;
        notification.headsUpContentView = remoteViews;
        mProcessor.processNotification(notification, builder);
        verify(mColorizer).colorize(any(), anyInt(), anyBoolean());
        RemoteViews contentView = builder.createContentView();
        assertNotSame(contentView, remoteViews);
        contentView = builder.createBigContentView();
        assertNotSame(contentView, remoteViews);
        contentView = builder.createHeadsUpContentView();
        assertNotSame(contentView, remoteViews);
    }

    @Test
    public void findBackgroundSwatch_white() {
        // Given artwork that is completely white.
        mArtwork = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mArtwork);
        canvas.drawColor(Color.WHITE);
        // WHEN the background swatch is computed
        Palette.Swatch swatch = MediaNotificationProcessor.findBackgroundSwatch(mArtwork);
        // THEN the swatch color is white
        assertCloseColors(swatch.getRgb(), Color.WHITE);
    }

    @Test
    public void findBackgroundSwatch_red() {
        // Given artwork that is completely red.
        mArtwork = Bitmap.createBitmap(BITMAP_WIDTH, BITMAP_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(mArtwork);
        canvas.drawColor(Color.RED);
        // WHEN the background swatch is computed
        Palette.Swatch swatch = MediaNotificationProcessor.findBackgroundSwatch(mArtwork);
        // THEN the swatch color is red
        assertCloseColors(swatch.getRgb(), Color.RED);
    }

    static void assertCloseColors(int expected, int actual) {
        assertThat((float) Color.red(expected)).isWithin(COLOR_TOLERANCE).of(Color.red(actual));
        assertThat((float) Color.green(expected)).isWithin(COLOR_TOLERANCE).of(Color.green(actual));
        assertThat((float) Color.blue(expected)).isWithin(COLOR_TOLERANCE).of(Color.blue(actual));
    }

    public static class TestableColorizer extends ImageGradientColorizer {
        private final Bitmap mBitmap;

        private TestableColorizer(Bitmap bitmap) {
            mBitmap = bitmap;
        }

        @Override
        public Bitmap colorize(Drawable drawable, int backgroundColor, boolean isRtl) {
            return mBitmap;
        }
    }
}
