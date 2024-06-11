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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.media.controls.ui.view.MediaHost;
import com.android.systemui.util.animation.UniqueObjectHostView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class MediaComplicationViewControllerTest extends SysuiTestCase {
    @Mock
    private MediaHost mMediaHost;

    @Mock
    private UniqueObjectHostView mView;

    @Mock
    private FrameLayout mComplicationContainer;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mMediaHost.hostView = mView;
    }

    @Test
    public void testMediaHostViewInteraction() {
        final MediaComplicationViewController controller = new MediaComplicationViewController(
                mComplicationContainer, mMediaHost);

        controller.init();

        controller.onViewAttached();
        verify(mComplicationContainer).addView(eq(mView));

        controller.onViewDetached();
        verify(mComplicationContainer).removeView(eq(mView));
    }
}
