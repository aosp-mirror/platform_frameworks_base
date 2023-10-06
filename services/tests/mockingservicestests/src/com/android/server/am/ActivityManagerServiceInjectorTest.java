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

package com.android.server.am;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.UserManager;
import android.util.Log;
import android.view.Display;

import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.am.ActivityManagerService.Injector;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

import java.util.Arrays;

/**
 * Run as {@code atest
 * FrameworksMockingServicesTests:com.android.server.am.ActivityManagerServiceInjectorTest}
 */
public final class ActivityManagerServiceInjectorTest {

    private static final String TAG = ActivityManagerServiceInjectorTest.class.getSimpleName();

    private final Display mDefaultDisplay = validDisplay(DEFAULT_DISPLAY);

    @Mock private Context mContext;
    @Mock private DisplayManager mDisplayManager;

    private Injector mInjector;

    @Before
    public void setFixture() {
        mInjector = new Injector(mContext);

        when(mContext.getSystemService(DisplayManager.class)).thenReturn(mDisplayManager);
    }

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .spyStatic(UserManager.class)
            .build();

    @Test
    public void testGetDisplayIdsForStartingBackgroundUsers_notSupported() {
        mockUmIsUsersOnSecondaryDisplaysEnabled(false);

        int [] displayIds = mInjector.getDisplayIdsForStartingVisibleBackgroundUsers();

        assertWithMessage("mAms.getDisplayIdsForStartingBackgroundUsers()")
                .that(displayIds).isNull();
    }

    @Test
    public void testGetDisplayIdsForStartingBackgroundUsers_noDisplaysAtAll() {
        mockUmIsUsersOnSecondaryDisplaysEnabled(true);
        mockGetDisplays();

        int[] displayIds = mInjector.getDisplayIdsForStartingVisibleBackgroundUsers();

        assertWithMessage("mAms.getDisplayIdsForStartingBackgroundUsers()")
                .that(displayIds).isNull();
    }

    @Test
    public void testGetDisplayIdsForStartingBackgroundUsers_defaultDisplayOnly() {
        mockUmIsUsersOnSecondaryDisplaysEnabled(true);
        mockGetDisplays(mDefaultDisplay);

        int[] displayIds = mInjector.getDisplayIdsForStartingVisibleBackgroundUsers();

        assertWithMessage("mAms.getDisplayIdsForStartingBackgroundUsers()")
                .that(displayIds).isNull();
    }

    @Test
    public void testGetDisplayIdsForStartingBackgroundUsers_noDefaultDisplay() {
        mockUmIsUsersOnSecondaryDisplaysEnabled(true);
        mockGetDisplays(validDisplay(42));

        int[] displayIds = mInjector.getDisplayIdsForStartingVisibleBackgroundUsers();

        assertWithMessage("mAms.getDisplayIdsForStartingBackgroundUsers()")
                .that(displayIds).isNull();
    }

    @Test
    public void testGetDisplayIdsForStartingBackgroundUsers_mixed() {
        mockUmIsUsersOnSecondaryDisplaysEnabled(true);
        mockGetDisplays(mDefaultDisplay, validDisplay(42), invalidDisplay(108));

        int[] displayIds = mInjector.getDisplayIdsForStartingVisibleBackgroundUsers();

        assertWithMessage("mAms.getDisplayIdsForStartingBackgroundUsers()")
                .that(displayIds).isNotNull();
        assertWithMessage("mAms.getDisplayIdsForStartingBackgroundUsers()")
                .that(displayIds).asList().containsExactly(42);
    }

    // Extra test to make sure the array is properly copied...
    @Test
    public void testGetDisplayIdsForStartingBackgroundUsers_mixed_invalidFirst() {
        mockUmIsUsersOnSecondaryDisplaysEnabled(true);
        mockGetDisplays(invalidDisplay(108), mDefaultDisplay, validDisplay(42));

        int[] displayIds = mInjector.getDisplayIdsForStartingVisibleBackgroundUsers();

        assertWithMessage("mAms.getDisplayIdsForStartingBackgroundUsers()")
                .that(displayIds).asList().containsExactly(42);
    }

    private Display validDisplay(int displayId) {
        return mockDisplay(displayId, /* valid= */ true);
    }

    private Display invalidDisplay(int displayId) {
        return mockDisplay(displayId, /* valid= */ false);
    }

    private Display mockDisplay(int displayId, boolean valid) {
        Display display = mock(Display.class);

        when(display.getDisplayId()).thenReturn(displayId);
        when(display.isValid()).thenReturn(valid);

        return display;
    }

    private void mockGetDisplays(Display... displays) {
        Log.d(TAG, "mockGetDisplays(): " + Arrays.toString(displays));
        when(mDisplayManager.getDisplays()).thenReturn(displays);
    }

    private void mockUmIsUsersOnSecondaryDisplaysEnabled(boolean enabled) {
        Log.d(TAG, "Mocking UserManager.isUsersOnSecondaryDisplaysEnabled() to return " + enabled);
        doReturn(enabled).when(() -> UserManager.isVisibleBackgroundUsersEnabled());
    }
}
