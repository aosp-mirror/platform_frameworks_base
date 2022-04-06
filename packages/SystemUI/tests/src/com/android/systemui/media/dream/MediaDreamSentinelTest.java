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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.media.MediaData;
import com.android.systemui.media.MediaDataManager;

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
    MediaDreamComplication mComplication;

    final String mKey = "key";
    final String mOldKey = "old_key";

    @Mock
    MediaData mData;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testComplicationAddition() {
        final MediaDreamSentinel sentinel = new MediaDreamSentinel(mContext, mMediaDataManager,
                mDreamOverlayStateController, mComplication);

        sentinel.start();

        ArgumentCaptor<MediaDataManager.Listener> listenerCaptor =
                ArgumentCaptor.forClass(MediaDataManager.Listener.class);
        verify(mMediaDataManager).addListener(listenerCaptor.capture());

        final MediaDataManager.Listener listener = listenerCaptor.getValue();

        when(mMediaDataManager.hasActiveMedia()).thenReturn(false);
        listener.onMediaDataLoaded(mKey, mOldKey, mData, /* immediately= */ true,
                /* receivedSmartspaceCardLatency= */ 0, /* isSsReactived= */ false);
        verify(mDreamOverlayStateController, never()).addComplication(any());

        when(mMediaDataManager.hasActiveMedia()).thenReturn(true);
        listener.onMediaDataLoaded(mKey, mOldKey, mData, /* immediately= */true,
                /* receivedSmartspaceCardLatency= */0, /* isSsReactived= */ false);
        verify(mDreamOverlayStateController).addComplication(eq(mComplication));

        listener.onMediaDataRemoved(mKey);
        verify(mDreamOverlayStateController, never()).removeComplication(any());

        when(mMediaDataManager.hasActiveMedia()).thenReturn(false);
        listener.onMediaDataRemoved(mKey);
        verify(mDreamOverlayStateController).removeComplication(eq(mComplication));
    }

}
