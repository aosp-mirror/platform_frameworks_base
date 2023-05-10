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

package android.app;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Looper;
import android.os.UserManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class UiAutomationTest {

    private static final int SECONDARY_DISPLAY_ID = 42;
    private static final int DISPLAY_ID_ASSIGNED_TO_USER = 108;

    private final Looper mLooper = Looper.getMainLooper();

    @Mock
    private Context mContext;
    @Mock
    private UserManager mUserManager;
    @Mock
    private IUiAutomationConnection mConnection;

    @Before
    public void setFixtures() {
        when(mContext.getMainLooper()).thenReturn(mLooper);
        mockSystemService(UserManager.class, mUserManager);

        // Set default expectations
        mockVisibleBackgroundUsersSupported(/* supported= */ false);
        mockUserVisibility(/* visible= */ true);
        // make sure it's not used, unless explicitly mocked
        mockDisplayAssignedToUser(INVALID_DISPLAY);
        mockContextDisplay(DEFAULT_DISPLAY);
    }

    @Test
    public void testContextConstructor_nullContext() {
        assertThrows(IllegalArgumentException.class,
                () -> new UiAutomation((Context) null, mConnection));
    }

    @Test
    public void testContextConstructor_nullConnection() {
        assertThrows(IllegalArgumentException.class,
                () -> new UiAutomation(mContext, (IUiAutomationConnection) null));
    }

    @Test
    public void testGetDisplay_contextWithSecondaryDisplayId() {
        mockContextDisplay(SECONDARY_DISPLAY_ID);

        UiAutomation uiAutomation = new UiAutomation(mContext, mConnection);

        // It's always DEFAULT_DISPLAY regardless, unless the device supports visible bg users
        assertWithMessage("getDisplayId()").that(uiAutomation.getDisplayId())
                .isEqualTo(DEFAULT_DISPLAY);
    }

    @Test
    public void testGetDisplay_contextWithInvalidDisplayId() {
        mockContextDisplay(INVALID_DISPLAY);

        UiAutomation uiAutomation = new UiAutomation(mContext, mConnection);

        assertWithMessage("getDisplayId()").that(uiAutomation.getDisplayId())
                .isEqualTo(DEFAULT_DISPLAY);
    }

    @Test
    public void testGetDisplay_visibleBgUsers() {
        mockVisibleBackgroundUsersSupported(/* supported= */ true);
        mockContextDisplay(SECONDARY_DISPLAY_ID);
        // Should be using display from context, not from user
        mockDisplayAssignedToUser(DISPLAY_ID_ASSIGNED_TO_USER);

        UiAutomation uiAutomation = new UiAutomation(mContext, mConnection);

        assertWithMessage("getDisplayId()").that(uiAutomation.getDisplayId())
                .isEqualTo(SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testGetDisplay_visibleBgUsers_contextWithInvalidDisplayId() {
        mockVisibleBackgroundUsersSupported(/* supported= */ true);
        mockContextDisplay(INVALID_DISPLAY);
        mockDisplayAssignedToUser(DISPLAY_ID_ASSIGNED_TO_USER);

        UiAutomation uiAutomation = new UiAutomation(mContext, mConnection);

        assertWithMessage("getDisplayId()").that(uiAutomation.getDisplayId())
                .isEqualTo(DISPLAY_ID_ASSIGNED_TO_USER);
    }

    private <T> void mockSystemService(Class<T> svcClass, T svc) {
        String svcName = svcClass.getName();
        when(mContext.getSystemServiceName(svcClass)).thenReturn(svcName);
        when(mContext.getSystemService(svcName)).thenReturn(svc);
    }

    private void mockVisibleBackgroundUsersSupported(boolean supported) {
        when(mUserManager.isVisibleBackgroundUsersSupported()).thenReturn(supported);
    }

    private void mockContextDisplay(int displayId) {
        when(mContext.getDisplayId()).thenReturn(displayId);
    }

    private void mockDisplayAssignedToUser(int displayId) {
        when(mUserManager.getMainDisplayIdAssignedToUser()).thenReturn(displayId);
    }

    private void mockUserVisibility(boolean visible) {
        when(mUserManager.isUserVisible()).thenReturn(visible);
    }
}
