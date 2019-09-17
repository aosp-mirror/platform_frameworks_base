/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.IPinnedStackListener;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:PinnedStackControllerTest
 */
@SmallTest
@Presubmit
public class PinnedStackControllerTest extends WindowTestsBase {

    private static final int SHELF_HEIGHT = 300;

    @Mock private IPinnedStackListener mIPinnedStackListener;
    @Mock private IPinnedStackListener.Stub mIPinnedStackListenerStub;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mIPinnedStackListener.asBinder()).thenReturn(mIPinnedStackListenerStub);
    }

    @Test
    public void setShelfHeight_shelfVisibilityChangedTriggered() throws RemoteException {
        mWm.mAtmService.mSupportsPictureInPicture = true;
        mWm.registerPinnedStackListener(DEFAULT_DISPLAY, mIPinnedStackListener);

        verify(mIPinnedStackListener).onImeVisibilityChanged(false, 0);
        verify(mIPinnedStackListener).onShelfVisibilityChanged(false, 0);
        verify(mIPinnedStackListener).onMovementBoundsChanged(any(), any(), any(), eq(false),
                eq(false), anyInt());
        verify(mIPinnedStackListener).onActionsChanged(any());
        verify(mIPinnedStackListener).onMinimizedStateChanged(anyBoolean());

        reset(mIPinnedStackListener);

        mWm.setShelfHeight(true, SHELF_HEIGHT);
        verify(mIPinnedStackListener).onShelfVisibilityChanged(true, SHELF_HEIGHT);
        verify(mIPinnedStackListener).onMovementBoundsChanged(any(), any(), any(), eq(false),
                eq(true), anyInt());
        verify(mIPinnedStackListener, never()).onImeVisibilityChanged(anyBoolean(), anyInt());
    }
}
