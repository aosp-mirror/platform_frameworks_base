/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row.wrapper;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.SeekBar;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NotificationMediaTemplateViewWrapperTest extends SysuiTestCase {

    private ExpandableNotificationRow mRow;
    private Notification mNotif;
    private View mView;
    private NotificationMediaTemplateViewWrapper mWrapper;

    @Mock
    private MetricsLogger mMetricsLogger;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        allowTestableLooperAsMainThread();

        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);

        // These tests are for regular media style notifications, not controls in quick settings
        Settings.System.putInt(mContext.getContentResolver(), "qs_media_player", 0);
    }

    private void makeTestNotification(long duration, boolean allowSeeking) throws Exception {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");

        MediaMetadata metadata = new MediaMetadata.Builder()
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build();
        MediaSession session = new MediaSession(mContext, "TEST_CHANNEL");
        session.setMetadata(metadata);

        PlaybackState playbackState = new PlaybackState.Builder()
                .setActions(allowSeeking ? PlaybackState.ACTION_SEEK_TO : 0)
                .build();

        session.setPlaybackState(playbackState);

        builder.setStyle(new Notification.MediaStyle()
                .setMediaSession(session.getSessionToken())
        );

        mNotif = builder.build();
        assertTrue(mNotif.hasMediaSession());

        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        mRow = helper.createRow(mNotif);

        RemoteViews views = new RemoteViews(mContext.getPackageName(),
                com.android.internal.R.layout.notification_template_material_big_media);
        mView = views.apply(mContext, null);
        mWrapper = new NotificationMediaTemplateViewWrapper(mContext,
                mView, mRow);
        mWrapper.onContentUpdated(mRow);
    }

    @Test
    public void testLogging_NoSeekbar() throws Exception {
        // Media sessions with duration <= 0 should not include a seekbar
        makeTestNotification(0, false);

        verify(mMetricsLogger).write(argThat(logMaker ->
                logMaker.getCategory() == MetricsEvent.MEDIA_NOTIFICATION_SEEKBAR
                        && logMaker.getType() == MetricsEvent.TYPE_CLOSE
        ));

        verify(mMetricsLogger, times(0)).write(argThat(logMaker ->
                logMaker.getCategory() == MetricsEvent.MEDIA_NOTIFICATION_SEEKBAR
                        && logMaker.getType() == MetricsEvent.TYPE_OPEN
        ));
    }

    @Test
    public void testLogging_HasSeekbarNoScrubber() throws Exception {
        // Media sessions that do not support seeking should have a seekbar, but no scrubber
        makeTestNotification(1000, false);

        verify(mMetricsLogger).write(argThat(logMaker ->
                logMaker.getCategory() == MetricsEvent.MEDIA_NOTIFICATION_SEEKBAR
                        && logMaker.getType() == MetricsEvent.TYPE_OPEN
        ));

        // Ensure the callback runs at least once
        mWrapper.mOnUpdateTimerTick.run();

        verify(mMetricsLogger).write(argThat(logMaker ->
                logMaker.getCategory() == MetricsEvent.MEDIA_NOTIFICATION_SEEKBAR
                && logMaker.getType() == MetricsEvent.TYPE_DETAIL
                && logMaker.getSubtype() == 0
        ));
    }

    @Test
    public void testLogging_HasSeekbarAndScrubber() throws Exception {
        makeTestNotification(1000, true);

        verify(mMetricsLogger).write(argThat(logMaker ->
                logMaker.getCategory() == MetricsEvent.MEDIA_NOTIFICATION_SEEKBAR
                        && logMaker.getType() == MetricsEvent.TYPE_OPEN
        ));

        verify(mMetricsLogger).write(argThat(logMaker ->
                logMaker.getCategory() == MetricsEvent.MEDIA_NOTIFICATION_SEEKBAR
                        && logMaker.getType() == MetricsEvent.TYPE_DETAIL
                        && logMaker.getSubtype() == 1
        ));
    }

    @Test
    public void testLogging_UpdateSeekbar() throws Exception {
        makeTestNotification(1000, true);

        SeekBar seekbar = mView.findViewById(
                com.android.internal.R.id.notification_media_progress_bar);
        assertTrue(seekbar != null);

        mWrapper.mSeekListener.onStopTrackingTouch(seekbar);

        verify(mMetricsLogger).write(argThat(logMaker ->
                logMaker.getCategory() == MetricsEvent.MEDIA_NOTIFICATION_SEEKBAR
                        && logMaker.getType() == MetricsEvent.TYPE_UPDATE));
    }
}
