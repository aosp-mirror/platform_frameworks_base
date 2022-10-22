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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification.MediaStyle;
import android.media.session.MediaSession;
import android.service.notification.NotificationListenerService;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.media.controls.util.MediaFeatureFlag;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.icon.IconManager;

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
    @Mock private IStatusBarService mStatusBarService;
    @Mock private IconManager mIconManager;

    private MediaCoordinator mCoordinator;
    private NotifFilter mFilter;
    private NotifCollectionListener mListener;

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
        finishSetupWithMediaFeatureFlagEnabled(false);
        // WHEN the media filter is asked about an entry
        final boolean shouldFilter = mFilter.shouldFilterOut(mOtherEntry, 0);
        // THEN it shouldn't be filtered
        assertThat(shouldFilter).isFalse();
    }

    @Test
    public void shouldFilterOtherNotificationWhenEnabled() {
        // GIVEN that the media feature is enabled
        finishSetupWithMediaFeatureFlagEnabled(true);
        // WHEN the media filter is asked about an entry
        final boolean shouldFilter = mFilter.shouldFilterOut(mOtherEntry, 0);
        // THEN it shouldn't be filtered
        assertThat(shouldFilter).isFalse();
    }

    @Test
    public void shouldFilterMediaNotificationWhenDisabled() {
        // GIVEN that the media feature is disabled
        finishSetupWithMediaFeatureFlagEnabled(false);
        // WHEN the media filter is asked about a media entry
        final boolean shouldFilter = mFilter.shouldFilterOut(mMediaEntry, 0);
        // THEN it shouldn't be filtered
        assertThat(shouldFilter).isFalse();
    }

    @Test
    public void shouldFilterMediaNotificationWhenEnabled() {
        // GIVEN that the media feature is enabled
        finishSetupWithMediaFeatureFlagEnabled(true);
        // WHEN the media filter is asked about a media entry
        final boolean shouldFilter = mFilter.shouldFilterOut(mMediaEntry, 0);
        // THEN it should be filtered
        assertThat(shouldFilter).isTrue();
    }

    @Test
    public void inflateNotificationIconsMediaDisabled() throws InflationException {
        finishSetupWithMediaFeatureFlagEnabled(false);

        mListener.onEntryInit(mOtherEntry);
        mFilter.shouldFilterOut(mOtherEntry, 0);
        verify(mIconManager, never()).createIcons(eq(mMediaEntry));
    }

    @Test
    public void inflateNotificationIconsMediaEnabled() throws InflationException {
        finishSetupWithMediaFeatureFlagEnabled(true);

        mListener.onEntryInit(mOtherEntry);
        mFilter.shouldFilterOut(mOtherEntry, 0);
        verify(mIconManager, never()).createIcons(eq(mMediaEntry));
    }

    @Test
    public void inflateMediaNotificationIconsMediaDisabled() throws InflationException {
        finishSetupWithMediaFeatureFlagEnabled(false);

        mListener.onEntryInit(mMediaEntry);
        mFilter.shouldFilterOut(mMediaEntry, 0);
        verify(mIconManager, never()).createIcons(eq(mMediaEntry));
    }

    @Test
    public void inflateMediaNotificationIconsMediaEnabled() throws InflationException {
        finishSetupWithMediaFeatureFlagEnabled(true);

        mListener.onEntryInit(mMediaEntry);
        mListener.onEntryAdded(mMediaEntry);
        verify(mIconManager, never()).createIcons(eq(mMediaEntry));
        verify(mIconManager, never()).updateIcons(eq(mMediaEntry));

        mFilter.shouldFilterOut(mMediaEntry, 0);
        verify(mIconManager, times(1)).createIcons(eq(mMediaEntry));
        verify(mIconManager, never()).updateIcons(eq(mMediaEntry));

        mFilter.shouldFilterOut(mMediaEntry, 0);
        verify(mIconManager, times(1)).createIcons(eq(mMediaEntry));
        verify(mIconManager, times(1)).updateIcons(eq(mMediaEntry));

        mListener.onEntryRemoved(mMediaEntry, NotificationListenerService.REASON_CANCEL);
        mListener.onEntryCleanUp(mMediaEntry);
        mListener.onEntryInit(mMediaEntry);
        verify(mIconManager, times(1)).createIcons(eq(mMediaEntry));
        verify(mIconManager, times(1)).updateIcons(eq(mMediaEntry));

        mFilter.shouldFilterOut(mMediaEntry, 0);
        verify(mIconManager, times(2)).createIcons(eq(mMediaEntry));
        verify(mIconManager, times(1)).updateIcons(eq(mMediaEntry));
    }

    @Test
    public void inflationException() throws InflationException {
        finishSetupWithMediaFeatureFlagEnabled(true);

        mListener.onEntryInit(mMediaEntry);
        mListener.onEntryAdded(mMediaEntry);
        verify(mIconManager, never()).createIcons(eq(mMediaEntry));
        verify(mIconManager, never()).updateIcons(eq(mMediaEntry));

        doThrow(InflationException.class).when(mIconManager).createIcons(eq(mMediaEntry));
        mFilter.shouldFilterOut(mMediaEntry, 0);
        verify(mIconManager, times(1)).createIcons(eq(mMediaEntry));
        verify(mIconManager, never()).updateIcons(eq(mMediaEntry));

        mFilter.shouldFilterOut(mMediaEntry, 0);
        verify(mIconManager, times(1)).createIcons(eq(mMediaEntry));
        verify(mIconManager, never()).updateIcons(eq(mMediaEntry));

        mListener.onEntryUpdated(mMediaEntry);
        verify(mIconManager, times(1)).createIcons(eq(mMediaEntry));
        verify(mIconManager, never()).updateIcons(eq(mMediaEntry));

        doNothing().when(mIconManager).createIcons(eq(mMediaEntry));
        mFilter.shouldFilterOut(mMediaEntry, 0);
        verify(mIconManager, times(2)).createIcons(eq(mMediaEntry));
        verify(mIconManager, never()).updateIcons(eq(mMediaEntry));
    }

    private void finishSetupWithMediaFeatureFlagEnabled(boolean mediaFeatureFlagEnabled) {
        when(mMediaFeatureFlag.getEnabled()).thenReturn(mediaFeatureFlagEnabled);
        mCoordinator = new MediaCoordinator(mMediaFeatureFlag, mStatusBarService, mIconManager);

        ArgumentCaptor<NotifFilter> filterCaptor = ArgumentCaptor.forClass(NotifFilter.class);
        ArgumentCaptor<NotifCollectionListener> listenerCaptor =
                ArgumentCaptor.forClass(NotifCollectionListener.class);
        mCoordinator.attach(mNotifPipeline);
        verify(mNotifPipeline).addPreGroupFilter(filterCaptor.capture());
        verify(mNotifPipeline).addCollectionListener(listenerCaptor.capture());

        mFilter = filterCaptor.getValue();
        mListener = listenerCaptor.getValue();
    }
}
