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

package com.android.systemui.screenshot.appclips;

import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_FAILED;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_SUCCESS;
import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED;

import static com.android.systemui.flags.Flags.SCREENSHOT_APP_CLIPS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.statusbar.IAppClipsService;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.flags.FeatureFlags;
import com.android.wm.shell.bubbles.Bubbles;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public final class AppClipsServiceTest extends SysuiTestCase {

    private static final Intent FAKE_INTENT = new Intent();
    private static final int FAKE_TASK_ID = 42;
    private static final String EMPTY = "";

    @Mock @Application private Context mMockContext;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private Optional<Bubbles> mOptionalBubbles;
    @Mock private Bubbles mBubbles;
    @Mock private DevicePolicyManager mDevicePolicyManager;

    private AppClipsService mAppClipsService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void flagOff_shouldReturnFalse() throws RemoteException {
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(false);

        assertThat(getInterfaceWithRealContext()
                .canLaunchCaptureContentActivityForNote(FAKE_TASK_ID)).isFalse();
    }

    @Test
    public void flagOff_internal_shouldReturnFailed() throws RemoteException {
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(false);

        assertThat(getInterfaceWithRealContext()
                .canLaunchCaptureContentActivityForNoteInternal(FAKE_TASK_ID))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_FAILED);
    }

    @Test
    public void emptyBubbles_shouldReturnFalse() throws RemoteException {
        mockForEmptyBubbles();

        assertThat(getInterfaceWithRealContext()
                .canLaunchCaptureContentActivityForNote(FAKE_TASK_ID)).isFalse();
    }

    @Test
    public void emptyBubbles_internal_shouldReturnFailed() throws RemoteException {
        mockForEmptyBubbles();

        assertThat(getInterfaceWithRealContext()
                .canLaunchCaptureContentActivityForNoteInternal(FAKE_TASK_ID))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_FAILED);
    }

    @Test
    public void taskIdNotAppBubble_shouldReturnFalse() throws RemoteException {
        mockForTaskIdNotAppBubble();

        assertThat(getInterfaceWithRealContext()
                .canLaunchCaptureContentActivityForNote(FAKE_TASK_ID)).isFalse();
    }

    @Test
    public void taskIdNotAppBubble_internal_shouldReturnWindowUnsupported() throws RemoteException {
        mockForTaskIdNotAppBubble();

        assertThat(getInterfaceWithRealContext()
                .canLaunchCaptureContentActivityForNoteInternal(FAKE_TASK_ID))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_WINDOW_MODE_UNSUPPORTED);
    }

    @Test
    public void dpmScreenshotBlocked_shouldReturnFalse() throws RemoteException {
        mockForScreenshotBlocked();

        assertThat(getInterfaceWithRealContext()
                .canLaunchCaptureContentActivityForNote(FAKE_TASK_ID)).isFalse();
    }

    @Test
    public void dpmScreenshotBlocked_internal_shouldReturnBlockedByAdmin() throws RemoteException {
        mockForScreenshotBlocked();

        assertThat(getInterfaceWithRealContext()
                .canLaunchCaptureContentActivityForNoteInternal(FAKE_TASK_ID))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_BLOCKED_BY_ADMIN);
    }

    @Test
    public void configComponentNameNotValid_shouldReturnFalse() throws RemoteException {
        mockForInvalidConfigComponentName();

        assertThat(getInterfaceWithMockContext()
                .canLaunchCaptureContentActivityForNote(FAKE_TASK_ID)).isFalse();
    }

    @Test
    public void configComponentNameNotValid_internal_shouldReturnFailed() throws RemoteException {
        mockForInvalidConfigComponentName();

        assertThat(getInterfaceWithMockContext()
                .canLaunchCaptureContentActivityForNoteInternal(FAKE_TASK_ID))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_FAILED);
    }


    @Test
    public void allPrerequisitesSatisfy_shouldReturnTrue() throws RemoteException {
        mockToSatisfyAllPrerequisites();

        assertThat(getInterfaceWithRealContext()
                .canLaunchCaptureContentActivityForNote(FAKE_TASK_ID)).isTrue();
    }

    @Test
    public void allPrerequisitesSatisfy_internal_shouldReturnSuccess() throws RemoteException {
        mockToSatisfyAllPrerequisites();

        assertThat(getInterfaceWithRealContext()
                .canLaunchCaptureContentActivityForNoteInternal(FAKE_TASK_ID))
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_SUCCESS);
    }

    private void mockForEmptyBubbles() {
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(true);
        when(mOptionalBubbles.isEmpty()).thenReturn(true);
    }

    private void mockForTaskIdNotAppBubble() {
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(true);
        when(mOptionalBubbles.isEmpty()).thenReturn(false);
        when(mOptionalBubbles.get()).thenReturn(mBubbles);
        when(mBubbles.isAppBubbleTaskId(eq((FAKE_TASK_ID)))).thenReturn(false);
    }

    private void mockForScreenshotBlocked() {
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(true);
        when(mOptionalBubbles.isEmpty()).thenReturn(false);
        when(mOptionalBubbles.get()).thenReturn(mBubbles);
        when(mBubbles.isAppBubbleTaskId(eq((FAKE_TASK_ID)))).thenReturn(true);
        when(mDevicePolicyManager.getScreenCaptureDisabled(eq(null))).thenReturn(true);
    }

    private void mockForInvalidConfigComponentName() {
        when(mMockContext.getString(anyInt())).thenReturn(EMPTY);
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(true);
        when(mOptionalBubbles.isEmpty()).thenReturn(false);
        when(mOptionalBubbles.get()).thenReturn(mBubbles);
        when(mBubbles.isAppBubbleTaskId(eq((FAKE_TASK_ID)))).thenReturn(true);
        when(mDevicePolicyManager.getScreenCaptureDisabled(eq(null))).thenReturn(false);
    }


    private void mockToSatisfyAllPrerequisites() {
        when(mFeatureFlags.isEnabled(SCREENSHOT_APP_CLIPS)).thenReturn(true);
        when(mOptionalBubbles.isEmpty()).thenReturn(false);
        when(mOptionalBubbles.get()).thenReturn(mBubbles);
        when(mBubbles.isAppBubbleTaskId(eq((FAKE_TASK_ID)))).thenReturn(true);
        when(mDevicePolicyManager.getScreenCaptureDisabled(eq(null))).thenReturn(false);
    }

    private IAppClipsService getInterfaceWithRealContext() {
        mAppClipsService = new AppClipsService(getContext(), mFeatureFlags,
                mOptionalBubbles, mDevicePolicyManager);
        return getInterfaceFromService(mAppClipsService);
    }

    private IAppClipsService getInterfaceWithMockContext() {
        mAppClipsService = new AppClipsService(mMockContext, mFeatureFlags,
                mOptionalBubbles, mDevicePolicyManager);
        return getInterfaceFromService(mAppClipsService);
    }

    private static IAppClipsService getInterfaceFromService(AppClipsService appClipsService) {
        IBinder iBinder = appClipsService.onBind(FAKE_INTENT);
        return IAppClipsService.Stub.asInterface(iBinder);
    }
}
