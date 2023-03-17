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

package com.android.server.broadcastradio;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.compat.CompatChanges;
import android.os.Binder;
import android.os.UserHandle;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

/**
 * Tests for {@link com.android.server.broadcastradio.RadioServiceUserController}
 */
public final class RadioServiceUserControllerTest extends ExtendedRadioMockitoTestCase {

    private static final int USER_ID_1 = 11;
    private static final int USER_ID_2 = 12;

    @Mock
    private UserHandle mUserHandleMock;

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder.spyStatic(ActivityManager.class).spyStatic(Binder.class)
                .spyStatic(CompatChanges.class);
    }

    @Before
    public void setUp() {
        doReturn(mUserHandleMock).when(() -> Binder.getCallingUserHandle());
        doReturn(USER_ID_1).when(() -> ActivityManager.getCurrentUser());
    }

    @Test
    public void isCurrentUser_forCurrentUser_returnsFalse() {
        when(mUserHandleMock.getIdentifier()).thenReturn(USER_ID_1);

        assertWithMessage("Current user")
                .that(RadioServiceUserController.isCurrentOrSystemUser()).isTrue();
    }

    @Test
    public void isCurrentUser_forNonCurrentUser_returnsFalse() {
        when(mUserHandleMock.getIdentifier()).thenReturn(USER_ID_2);

        assertWithMessage("Non-current user")
                .that(RadioServiceUserController.isCurrentOrSystemUser()).isFalse();
    }

    @Test
    public void isCurrentUser_forSystemUser_returnsTrue() {
        when(mUserHandleMock.getIdentifier()).thenReturn(UserHandle.USER_SYSTEM);

        assertWithMessage("System user")
                .that(RadioServiceUserController.isCurrentOrSystemUser()).isTrue();
    }

    @Test
    public void isCurrentUser_withActivityManagerFails_returnsFalse() {
        doThrow(new RuntimeException()).when(() -> ActivityManager.getCurrentUser());

        assertWithMessage("User when activity manager fails")
                .that(RadioServiceUserController.isCurrentOrSystemUser()).isFalse();
    }
}
