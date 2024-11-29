/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;

import android.annotation.NonNull;
import android.platform.test.annotations.Presubmit;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import org.junit.Test;

/**
 * Test class for {@link DisplayRotationCoordinator}
 *
 * Build/Install/Run:
 *  atest DisplayRotationCoordinatorTests
 */
@SmallTest
@Presubmit
public class DisplayRotationCoordinatorTests {

    private static final int FIRST_DISPLAY_ID = 1;
    private static final int SECOND_DISPLAY_ID = 2;

    @NonNull
    private final DisplayRotationCoordinator mCoordinator = new DisplayRotationCoordinator();

    @Test
    public void testDefaultDisplayRotationChangedWhenNoCallbackRegistered() {
        // Does not cause NPE
        mCoordinator.onDefaultDisplayRotationChanged(Surface.ROTATION_90);
    }

    @Test (expected = UnsupportedOperationException.class)
    public void testSecondRegistrationWithoutRemovingFirstWhenDifferentDisplay() {
        Runnable callback1 = mock(Runnable.class);
        Runnable callback2 = mock(Runnable.class);
        mCoordinator.setDefaultDisplayRotationChangedCallback(FIRST_DISPLAY_ID, callback1);
        mCoordinator.setDefaultDisplayRotationChangedCallback(SECOND_DISPLAY_ID, callback2);
        assertEquals(callback1, mCoordinator.mDefaultDisplayRotationChangedCallback);
    }

    @Test
    public void testSecondRegistrationWithoutRemovingFirstWhenSameDisplay() {
        Runnable callback1 = mock(Runnable.class);
        Runnable callback2 = mock(Runnable.class);
        mCoordinator.setDefaultDisplayRotationChangedCallback(FIRST_DISPLAY_ID, callback1);
        mCoordinator.setDefaultDisplayRotationChangedCallback(FIRST_DISPLAY_ID, callback2);
        assertEquals(callback2, mCoordinator.mDefaultDisplayRotationChangedCallback);
    }

    @Test
    public void testRemoveIncorrectRegistration() {
        Runnable callback1 = mock(Runnable.class);
        Runnable callback2 = mock(Runnable.class);
        mCoordinator.setDefaultDisplayRotationChangedCallback(FIRST_DISPLAY_ID, callback1);
        mCoordinator.removeDefaultDisplayRotationChangedCallback(callback2);
        assertEquals(callback1, mCoordinator.mDefaultDisplayRotationChangedCallback);

        // FIRST_DISPLAY_ID is still able to register another callback because the previous
        // removal should not have succeeded.
        mCoordinator.setDefaultDisplayRotationChangedCallback(FIRST_DISPLAY_ID, callback2);
        assertEquals(callback2, mCoordinator.mDefaultDisplayRotationChangedCallback);
    }

    @Test
    public void testSecondRegistrationAfterRemovingFirst() {
        Runnable callback1 = mock(Runnable.class);
        mCoordinator.setDefaultDisplayRotationChangedCallback(FIRST_DISPLAY_ID, callback1);
        mCoordinator.removeDefaultDisplayRotationChangedCallback(callback1);

        Runnable callback2 = mock(Runnable.class);
        mCoordinator.setDefaultDisplayRotationChangedCallback(SECOND_DISPLAY_ID, callback2);

        mCoordinator.onDefaultDisplayRotationChanged(Surface.ROTATION_90);
        verify(callback2).run();
        verify(callback1, never()).run();
    }

    @Test
    public void testRegisterThenDefaultDisplayRotationChanged() {
        Runnable callback = mock(Runnable.class);
        mCoordinator.setDefaultDisplayRotationChangedCallback(FIRST_DISPLAY_ID, callback);
        assertEquals(Surface.ROTATION_0, mCoordinator.getDefaultDisplayCurrentRotation());
        verify(callback, never()).run();

        mCoordinator.onDefaultDisplayRotationChanged(Surface.ROTATION_90);
        verify(callback).run();
        assertEquals(Surface.ROTATION_90, mCoordinator.getDefaultDisplayCurrentRotation());
    }

    @Test
    public void testDefaultDisplayRotationChangedThenRegister() {
        mCoordinator.onDefaultDisplayRotationChanged(Surface.ROTATION_90);
        Runnable callback = mock(Runnable.class);
        mCoordinator.setDefaultDisplayRotationChangedCallback(FIRST_DISPLAY_ID, callback);
        verify(callback).run();
        assertEquals(Surface.ROTATION_90, mCoordinator.getDefaultDisplayCurrentRotation());
    }
}
