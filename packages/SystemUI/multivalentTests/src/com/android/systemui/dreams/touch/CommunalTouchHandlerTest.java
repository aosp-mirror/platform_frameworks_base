/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.dreams.touch;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;

import android.view.GestureDetector;
import android.view.MotionEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.CentralSurfaces;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class CommunalTouchHandlerTest extends SysuiTestCase {
    @Mock
    CentralSurfaces mCentralSurfaces;
    @Mock
    NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock
    DreamTouchHandler.TouchSession mTouchSession;
    CommunalTouchHandler mTouchHandler;

    private static final int INITIATION_WIDTH = 20;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTouchHandler = new CommunalTouchHandler(
                Optional.of(mCentralSurfaces),
                mNotificationShadeWindowController,
                INITIATION_WIDTH);
    }

    @Test
    public void testSessionStartForcesShadeOpen() {
        mTouchHandler.onSessionStart(mTouchSession);
        verify(mNotificationShadeWindowController).setForcePluginOpen(true, mTouchHandler);
    }

    @Test
    public void testEventPropagation() {
        final MotionEvent motionEvent = Mockito.mock(MotionEvent.class);

        final ArgumentCaptor<InputChannelCompat.InputEventListener>
                inputEventListenerArgumentCaptor =
                ArgumentCaptor.forClass(InputChannelCompat.InputEventListener.class);

        mTouchHandler.onSessionStart(mTouchSession);
        verify(mTouchSession).registerInputListener(inputEventListenerArgumentCaptor.capture());
        inputEventListenerArgumentCaptor.getValue().onInputEvent(motionEvent);
        verify(mCentralSurfaces).handleDreamTouch(motionEvent);
    }

    @Test
    public void testTouchPilferingOnScroll() {
        final MotionEvent motionEvent1 = Mockito.mock(MotionEvent.class);
        final MotionEvent motionEvent2 = Mockito.mock(MotionEvent.class);

        final ArgumentCaptor<GestureDetector.OnGestureListener> gestureListenerArgumentCaptor =
                ArgumentCaptor.forClass(GestureDetector.OnGestureListener.class);

        mTouchHandler.onSessionStart(mTouchSession);
        verify(mTouchSession).registerGestureListener(gestureListenerArgumentCaptor.capture());

        assertThat(gestureListenerArgumentCaptor.getValue()
                .onScroll(motionEvent1, motionEvent2, 1, 1))
                .isTrue();
    }
}
