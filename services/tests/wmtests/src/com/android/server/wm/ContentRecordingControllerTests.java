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

package com.android.server.wm;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;

import android.platform.test.annotations.Presubmit;
import android.view.ContentRecordingSession;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for the {@link ContentRecordingController} class.
 *
 * Build/Install/Run:
 * atest WmTests:ContentRecordingControllerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class ContentRecordingControllerTests extends WindowTestsBase {
    private final ContentRecordingSession mDefaultSession =
            ContentRecordingSession.createDisplaySession(DEFAULT_DISPLAY);
    private final ContentRecordingSession mWaitingDisplaySession =
            ContentRecordingSession.createDisplaySession(DEFAULT_DISPLAY);

    private int mVirtualDisplayId;
    private DisplayContent mVirtualDisplayContent;
    private WindowContainer.RemoteToken mRootTaskToken;

    @Mock
    private WindowContainer mTaskWindowContainer;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        // GIVEN the VirtualDisplay associated with the session (so the display has state ON).
        mVirtualDisplayContent = new TestDisplayContent.Builder(mAtm, 500, 600).build();
        mVirtualDisplayId = mVirtualDisplayContent.getDisplayId();
        mWm.mRoot.onDisplayAdded(mVirtualDisplayId);
        spyOn(mVirtualDisplayContent);

        mDefaultSession.setVirtualDisplayId(mVirtualDisplayId);
        mWaitingDisplaySession.setVirtualDisplayId(mVirtualDisplayId);
        mWaitingDisplaySession.setWaitingForConsent(true);

        mRootTaskToken = new WindowContainer.RemoteToken(mTaskWindowContainer);
        mTaskWindowContainer.mRemoteToken = mRootTaskToken;
    }

    @Test
    public void testGetContentRecordingSessionLocked() {
        ContentRecordingController controller = new ContentRecordingController();
        assertThat(controller.getContentRecordingSessionLocked()).isNull();
    }

    @Test
    public void testSetContentRecordingSessionLocked_defaultSession() {
        ContentRecordingController controller = new ContentRecordingController();
        controller.setContentRecordingSessionLocked(mDefaultSession, mWm);
        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        assertThat(resultingSession).isEqualTo(mDefaultSession);
    }

    @Test
    public void testSetContentRecordingSessionLocked_invalidDisplayId_notAccepted() {
        ContentRecordingController controller = new ContentRecordingController();
        // GIVEN an invalid display session (no display id is set).
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(
                DEFAULT_DISPLAY);
        // WHEN updating the session.
        controller.setContentRecordingSessionLocked(session, mWm);
        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        // THEN the invalid session was not accepted.
        assertThat(resultingSession).isNull();
    }

    @Test
    public void testSetContentRecordingSessionLocked_newSession_accepted() {
        ContentRecordingController controller = new ContentRecordingController();
        // GIVEN a valid display session.
        // WHEN updating the session.
        controller.setContentRecordingSessionLocked(mDefaultSession, mWm);
        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        // THEN the valid session was accepted.
        assertThat(resultingSession).isEqualTo(mDefaultSession);
        verify(mVirtualDisplayContent, atLeastOnce()).setContentRecordingSession(mDefaultSession);
    }

    @Test
    public void testSetContentRecordingSessionLocked_updateSession_noLongerWaiting_accepted() {
        ContentRecordingController controller = new ContentRecordingController();
        // GIVEN a valid display session already in place.
        controller.setContentRecordingSessionLocked(mWaitingDisplaySession, mWm);
        verify(mVirtualDisplayContent, atLeastOnce()).setContentRecordingSession(
                mWaitingDisplaySession);
        verify(mVirtualDisplayContent, atLeastOnce()).updateRecording();

        // WHEN updating the session on the same display, so no longer waiting to record.
        ContentRecordingSession sessionUpdate = ContentRecordingSession.createTaskSession(
                mRootTaskToken.toWindowContainerToken().asBinder());
        sessionUpdate.setVirtualDisplayId(mVirtualDisplayId);
        sessionUpdate.setWaitingForConsent(false);
        controller.setContentRecordingSessionLocked(sessionUpdate, mWm);

        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        // THEN the session was accepted.
        assertThat(resultingSession).isEqualTo(sessionUpdate);
        verify(mVirtualDisplayContent, atLeastOnce()).setContentRecordingSession(sessionUpdate);
        verify(mVirtualDisplayContent, atLeastOnce()).updateRecording();
    }

    @Test
    public void testSetContentRecordingSessionLocked_invalidUpdateSession_notWaiting_notAccepted() {
        ContentRecordingController controller = new ContentRecordingController();
        // GIVEN a valid display session already in place.
        controller.setContentRecordingSessionLocked(mDefaultSession, mWm);
        verify(mVirtualDisplayContent, atLeastOnce()).setContentRecordingSession(mDefaultSession);

        // WHEN updating the session on the same display.
        ContentRecordingSession sessionUpdate = ContentRecordingSession.createTaskSession(
                mRootTaskToken.toWindowContainerToken().asBinder());
        sessionUpdate.setVirtualDisplayId(mVirtualDisplayId);
        controller.setContentRecordingSessionLocked(sessionUpdate, mWm);

        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        // THEN the session was not accepted.
        assertThat(resultingSession).isEqualTo(mDefaultSession);
        verify(mVirtualDisplayContent, never()).setContentRecordingSession(sessionUpdate);
    }

    @Test
    public void testSetContentRecordingSessionLocked_disableCurrentSession_accepted() {
        ContentRecordingController controller = new ContentRecordingController();
        // GIVEN a valid display session already in place.
        controller.setContentRecordingSessionLocked(mDefaultSession, mWm);
        verify(mVirtualDisplayContent, atLeastOnce()).setContentRecordingSession(mDefaultSession);

        // WHEN updating the session.
        ContentRecordingSession sessionUpdate = null;
        controller.setContentRecordingSessionLocked(sessionUpdate, mWm);

        // THEN the valid session was accepted.
        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        assertThat(resultingSession).isEqualTo(sessionUpdate);
        // Do not need to update the display content, since it will handle stopping the session
        // via state change callbacks.
    }

    @Test
    public void testSetContentRecordingSessionLocked_takeOverCurrentSession_accepted() {
        ContentRecordingController controller = new ContentRecordingController();
        // GIVEN a valid display session already in place.
        controller.setContentRecordingSessionLocked(mDefaultSession, mWm);
        verify(mVirtualDisplayContent, atLeastOnce()).setContentRecordingSession(mDefaultSession);

        // WHEN updating the session.
        final DisplayContent virtualDisplay = new TestDisplayContent.Builder(mAtm, 500,
                600).build();
        ContentRecordingSession sessionUpdate = ContentRecordingSession.createDisplaySession(
                DEFAULT_DISPLAY);
        assertThat(virtualDisplay.getDisplayId()).isNotEqualTo(mVirtualDisplayId);
        sessionUpdate.setVirtualDisplayId(virtualDisplay.getDisplayId());
        controller.setContentRecordingSessionLocked(sessionUpdate, mWm);

        // THEN the valid session was accepted.
        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        assertThat(resultingSession).isEqualTo(sessionUpdate);
        verify(virtualDisplay, atLeastOnce()).setContentRecordingSession(sessionUpdate);
        // THEN the recording was paused on the prior display.
        verify(mVirtualDisplayContent).pauseRecording();
    }
}
