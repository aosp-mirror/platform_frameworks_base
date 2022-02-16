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

package com.android.systemui.statusbar.notification.collection.coordinator;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification.MediaStyle;
import android.media.session.MediaSession;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.media.MediaFeatureFlag;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public final class MediaCoordinatorTest extends SysuiTestCase {

    private MediaSession mMediaSession;
    private NotificationEntry mOtherEntry;
    private NotificationEntry mMediaEntry;

    @Mock private NotifPipeline mNotifPipeline;
    @Mock private MediaFeatureFlag mMediaFeatureFlag;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mOtherEntry = new NotificationEntryBuilder().build();
        mMediaSession = new MediaSession(mContext, "TEST_MEDIA_SESSION");
        NotificationEntryBuilder builder = new NotificationEntryBuilder();
        builder.modifyNotification(mContext).setStyle(
                new MediaStyle().setMediaSession(mMediaSession.getSessionToken()));
        mMediaEntry = builder.build();
    }

    @After
    public void tearDown() {
        mMediaSession.release();
    }

    @Test
    public void shouldFilterOtherNotificationWhenDisabled() {
        // GIVEN that the media feature is disabled
        when(mMediaFeatureFlag.getEnabled()).thenReturn(false);
        MediaCoordinator coordinator = new MediaCoordinator(mMediaFeatureFlag);
        // WHEN the media filter is asked about an entry
        NotifFilter filter = captureFilter(coordinator);
        final boolean shouldFilter = filter.shouldFilterOut(mOtherEntry, 0);
        // THEN it shouldn't be filtered
        assertThat(shouldFilter).isFalse();
    }

    @Test
    public void shouldFilterOtherNotificationWhenEnabled() {
        // GIVEN that the media feature is enabled
        when(mMediaFeatureFlag.getEnabled()).thenReturn(true);
        MediaCoordinator coordinator = new MediaCoordinator(mMediaFeatureFlag);
        // WHEN the media filter is asked about an entry
        NotifFilter filter = captureFilter(coordinator);
        final boolean shouldFilter = filter.shouldFilterOut(mOtherEntry, 0);
        // THEN it shouldn't be filtered
        assertThat(shouldFilter).isFalse();
    }

    @Test
    public void shouldFilterMediaNotificationWhenDisabled() {
        // GIVEN that the media feature is disabled
        when(mMediaFeatureFlag.getEnabled()).thenReturn(false);
        MediaCoordinator coordinator = new MediaCoordinator(mMediaFeatureFlag);
        // WHEN the media filter is asked about a media entry
        NotifFilter filter = captureFilter(coordinator);
        final boolean shouldFilter = filter.shouldFilterOut(mMediaEntry, 0);
        // THEN it shouldn't be filtered
        assertThat(shouldFilter).isFalse();
    }

    @Test
    public void shouldFilterMediaNotificationWhenEnabled() {
        // GIVEN that the media feature is enabled
        when(mMediaFeatureFlag.getEnabled()).thenReturn(true);
        MediaCoordinator coordinator = new MediaCoordinator(mMediaFeatureFlag);
        // WHEN the media filter is asked about a media entry
        NotifFilter filter = captureFilter(coordinator);
        final boolean shouldFilter = filter.shouldFilterOut(mMediaEntry, 0);
        // THEN it should be filtered
        assertThat(shouldFilter).isTrue();
    }

    private NotifFilter captureFilter(MediaCoordinator coordinator) {
        ArgumentCaptor<NotifFilter> filterCaptor = ArgumentCaptor.forClass(NotifFilter.class);
        coordinator.attach(mNotifPipeline);
        verify(mNotifPipeline).addFinalizeFilter(filterCaptor.capture());
        return filterCaptor.getValue();
    }
}
