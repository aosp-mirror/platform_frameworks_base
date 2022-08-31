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

package com.android.systemui.dreams.complication;

import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.media.dream.MediaDreamComplication;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DreamMediaEntryComplicationTest extends SysuiTestCase {
    @Mock
    private View mView;

    @Mock
    private DreamOverlayStateController mDreamOverlayStateController;

    @Mock
    private MediaDreamComplication mMediaComplication;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    /**
     * Ensures clicking media entry chip adds/removes media complication.
     */
    @Test
    public void testClick() {
        final DreamMediaEntryComplication.DreamMediaEntryViewController viewController =
                new DreamMediaEntryComplication.DreamMediaEntryViewController(
                        mView,
                        mDreamOverlayStateController,
                        mMediaComplication);

        final ArgumentCaptor<View.OnClickListener> clickListenerCaptor =
                ArgumentCaptor.forClass(View.OnClickListener.class);
        verify(mView).setOnClickListener(clickListenerCaptor.capture());

        clickListenerCaptor.getValue().onClick(mView);
        verify(mView).setSelected(true);
        verify(mDreamOverlayStateController).addComplication(mMediaComplication);
        clickListenerCaptor.getValue().onClick(mView);
        verify(mView).setSelected(false);
        verify(mDreamOverlayStateController).removeComplication(mMediaComplication);
    }

    /**
     * Ensures media complication is removed when the view is detached.
     */
    @Test
    public void testOnViewDetached() {
        final DreamMediaEntryComplication.DreamMediaEntryViewController viewController =
                new DreamMediaEntryComplication.DreamMediaEntryViewController(
                        mView,
                        mDreamOverlayStateController,
                        mMediaComplication);

        viewController.onViewDetached();
        verify(mView).setSelected(false);
        verify(mDreamOverlayStateController).removeComplication(mMediaComplication);
    }
}
