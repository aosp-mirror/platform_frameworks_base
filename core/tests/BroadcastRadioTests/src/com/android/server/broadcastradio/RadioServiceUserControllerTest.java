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

import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.os.Binder;
import android.os.UserHandle;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

/**
 * Tests for {@link com.android.server.broadcastradio.RadioServiceUserController}
 */
public final class RadioServiceUserControllerTest extends ExtendedRadioMockitoTestCase {

    private static final int USER_ID_1 = 11;
    private static final int USER_ID_2 = 12;
    private RadioServiceUserController mUserController;

    @Mock
    private UserHandle mUserHandleMock;

    @Rule
    public final Expect expect = Expect.create();

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder.spyStatic(ActivityManager.class).spyStatic(Binder.class);
    }

    @Before
    public void setUp() {
        doReturn(mUserHandleMock).when(() -> Binder.getCallingUserHandle());
        doReturn(USER_ID_1).when(() -> ActivityManager.getCurrentUser());
        mUserController = new RadioServiceUserControllerImpl();
    }

    @Test
    public void isCurrentOrSystemUser_forCurrentUser_returnsFalse() {
        when(mUserHandleMock.getIdentifier()).thenReturn(USER_ID_1);

        expect.withMessage("Current user")
                .that(mUserController.isCurrentOrSystemUser()).isTrue();
    }

    @Test
    public void isCurrentOrSystemUser_forNonCurrentUser_returnsFalse() {
        when(mUserHandleMock.getIdentifier()).thenReturn(USER_ID_2);

        expect.withMessage("Non-current user")
                .that(mUserController.isCurrentOrSystemUser()).isFalse();
    }

    @Test
    public void isCurrentOrSystemUser_forSystemUser_returnsTrue() {
        when(mUserHandleMock.getIdentifier()).thenReturn(USER_ID_1);
        when(mUserHandleMock.getIdentifier()).thenReturn(UserHandle.USER_SYSTEM);

        expect.withMessage("System user")
                .that(mUserController.isCurrentOrSystemUser()).isTrue();
    }

    @Test
    public void isCurrentOrSystemUser_withActivityManagerFailure_returnsFalse() {
        when(mUserHandleMock.getIdentifier()).thenReturn(USER_ID_1);
        doThrow(new RuntimeException()).when(ActivityManager::getCurrentUser);

        expect.withMessage("User when activity manager fails")
                .that(mUserController.isCurrentOrSystemUser()).isFalse();
    }

    @Test
    public void getCurrentUser() {
        expect.withMessage("Current user")
                .that(mUserController.getCurrentUser()).isEqualTo(USER_ID_1);
    }

    @Test
    public void getCurrentUser_withActivityManagerFailure_returnsUserNull() {
        when(mUserHandleMock.getIdentifier()).thenReturn(USER_ID_1);
        doThrow(new RuntimeException()).when(ActivityManager::getCurrentUser);

        expect.withMessage("Current user when activity manager fails")
                .that(mUserController.getCurrentUser()).isEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void getCallingUserId() {
        when(mUserHandleMock.getIdentifier()).thenReturn(USER_ID_1);

        expect.withMessage("Calling user id")
                .that(mUserController.getCallingUserId()).isEqualTo(USER_ID_1);
    }
}
