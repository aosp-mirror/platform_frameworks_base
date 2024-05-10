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
package com.android.server.pm;

import org.junit.Test;

/**
 * Tests for {@link UserVisibilityMediator} tests for devices that support concurrent Multiple
 * Users on Multiple Displays (A.K.A {@code MUMD}).
 *
 * <p>Run as
 * {@code atest FrameworksMockingServicesTests:com.android.server.pm.UserVisibilityMediatorMUMDTest}
 */
public final class UserVisibilityMediatorMUMDTest
        extends UserVisibilityMediatorVisibleBackgroundUserTestCase {

    public UserVisibilityMediatorMUMDTest() throws Exception {
        super(/* backgroundUsersOnDisplaysEnabled= */ true,
                /* backgroundUserOnDefaultDisplayAllowed= */ false);
    }

    @Test
    public void testStartVisibleBgUser_onDefaultDisplay() throws Exception {
        int userId = visibleBgUserCannotBeStartedOnDefaultDisplayTest();

        assertInvisibleUserCannotBeAssignedExtraDisplay(userId, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testStartBgUser_onDefaultDisplay_visible() throws Exception {
        visibleBgUserCannotBeStartedOnDefaultDisplayTest();
    }
}
