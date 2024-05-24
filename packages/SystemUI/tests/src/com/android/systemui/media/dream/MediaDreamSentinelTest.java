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

package com.android.systemui.media.dream;

import static com.android.systemui.flags.Flags.DREAM_MEDIA_COMPLICATION;

import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.complication.DreamMediaEntryComplication;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager;
import com.android.systemui.media.controls.shared.model.MediaData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class MediaDreamSentinelTest extends SysuiTestCase {
    @Mock
    MediaDataManager mMediaDataManager;

    @Mock
    DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    DreamMediaEntryComplication mMediaEntryComplication;

    @Mock
    FeatureFlags mFeatureFlags;

    final String mKey = "key";
    final String mOldKey = "old_key";

    @Mock
    MediaData mData;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mFeatureFlags.isEnabled(DREAM_MEDIA_COMPLICATION)).thenReturn(true);
    }

    @Test
    public void testOnMediaDataLoaded_complicationAddition() {
        final MediaDreamSentinel sentinel = new MediaDreamSentinel(mMediaDataManager,
                mDreamOverlayStateController, mMediaEntryComplication, mFeatureFlags);
        sentinel.start();

        final MediaDataManager.Listener listener = captureMediaDataListener();

        when(mMediaDataManager.hasActiveMedia()).thenReturn(false);
        listener.onMediaDataLoaded(mKey, mOldKey, mData, /* immediately= */ true,
                /* receivedSmartspaceCardLatency= */ 0, /* isSsReactived= */ false);
        verify(mDreamOverlayStateController, never()).addComplication(any());

        when(mMediaDataManager.hasActiveMedia()).thenReturn(true);
        listener.onMediaDataLoaded(mKey, mOldKey, mData, /* immediately= */true,
                /* receivedSmartspaceCardLatency= */0, /* isSsReactived= */ false);
        verify(mDreamOverlayStateController).addComplication(eq(mMediaEntryComplication));
        verify(mDreamOverlayStateController, never()).addComplication(
                not(eq(mMediaEntryComplication)));
    }

    @Test
    public void testOnMediaDataRemoved_complicationRemoval() {
        final MediaDreamSentinel sentinel = new MediaDreamSentinel(mMediaDataManager,
                mDreamOverlayStateController, mMediaEntryComplication, mFeatureFlags);
        sentinel.start();

        final MediaDataManager.Listener listener = captureMediaDataListener();

        when(mMediaDataManager.hasActiveMedia()).thenReturn(true);
        listener.onMediaDataLoaded(mKey, mOldKey, mData, /* immediately= */true,
                /* receivedSmartspaceCardLatency= */0, /* isSsReactived= */ false);

        listener.onMediaDataRemoved(mKey);
        verify(mDreamOverlayStateController, never()).removeComplication(any());

        when(mMediaDataManager.hasActiveMedia()).thenReturn(false);
        listener.onMediaDataRemoved(mKey);
        verify(mDreamOverlayStateController).removeComplication(eq(mMediaEntryComplication));
    }

    @Test
    public void testOnMediaDataLoaded_complicationRemoval() {
        final MediaDreamSentinel sentinel = new MediaDreamSentinel(mMediaDataManager,
                mDreamOverlayStateController, mMediaEntryComplication, mFeatureFlags);
        sentinel.start();

        final MediaDataManager.Listener listener = captureMediaDataListener();

        when(mMediaDataManager.hasActiveMedia()).thenReturn(true);
        listener.onMediaDataLoaded(mKey, mOldKey, mData, /* immediately= */true,
                /* receivedSmartspaceCardLatency= */0, /* isSsReactived= */ false);
        verify(mDreamOverlayStateController, never()).removeComplication(any());

        listener.onMediaDataLoaded(mKey, mOldKey, mData, /* immediately= */true,
                /* receivedSmartspaceCardLatency= */0, /* isSsReactived= */ false);
        verify(mDreamOverlayStateController, never()).removeComplication(any());

        when(mMediaDataManager.hasActiveMedia()).thenReturn(false);
        listener.onMediaDataLoaded(mKey, mOldKey, mData, /* immediately= */true,
                /* receivedSmartspaceCardLatency= */0, /* isSsReactived= */ false);
        verify(mDreamOverlayStateController).removeComplication(eq(mMediaEntryComplication));
    }

    @Test
    public void testOnMediaDataLoaded_mediaComplicationDisabled_doesNotAddComplication() {
        when(mFeatureFlags.isEnabled(DREAM_MEDIA_COMPLICATION)).thenReturn(false);

        final MediaDreamSentinel sentinel = new MediaDreamSentinel(mMediaDataManager,
                mDreamOverlayStateController, mMediaEntryComplication, mFeatureFlags);

        sentinel.start();

        final MediaDataManager.Listener listener = captureMediaDataListener();
        when(mMediaDataManager.hasActiveMedia()).thenReturn(true);
        listener.onMediaDataLoaded(mKey, mOldKey, mData, /* immediately= */true,
                /* receivedSmartspaceCardLatency= */0, /* isSsReactived= */ false);
        verify(mDreamOverlayStateController, never()).addComplication(any());
    }

    private MediaDataManager.Listener captureMediaDataListener() {
        final ArgumentCaptor<MediaDataManager.Listener> listenerCaptor =
                ArgumentCaptor.forClass(MediaDataManager.Listener.class);
        verify(mMediaDataManager).addListener(listenerCaptor.capture());

        return listenerCaptor.getValue();
    }
}
