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

import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.view.ContentRecordingSession;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

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
    private static final IBinder TEST_TOKEN = new RecordingTestToken();
    private final ContentRecordingSession mDefaultSession =
            ContentRecordingSession.createDisplaySession(
                    TEST_TOKEN);

    @Before
    public void setup() {
        spyOn(mDisplayContent);
        mDefaultSession.setDisplayId(DEFAULT_DISPLAY);
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
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(TEST_TOKEN);
        // WHEN updating the session.
        controller.setContentRecordingSessionLocked(session, mWm);
        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        // THEN the invalid session was not accepted.
        assertThat(resultingSession).isNull();
    }

    @Test
    public void testSetContentRecordingSessionLocked_invalidToken_notAccepted() {
        ContentRecordingController controller = new ContentRecordingController();
        // GIVEN an invalid display session (null token).
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(null);
        session.setDisplayId(DEFAULT_DISPLAY);
        // WHEN updating the session.
        controller.setContentRecordingSessionLocked(session, mWm);
        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        // THEN the invalid session was not accepted.
        assertThat(resultingSession).isNull();
    }

    @Test
    public void testSetContentRecordingSessionLocked_newDisplaySession_accepted() {
        ContentRecordingController controller = new ContentRecordingController();
        // GIVEN a valid display session.
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(TEST_TOKEN);
        session.setDisplayId(DEFAULT_DISPLAY);
        // WHEN updating the session.
        controller.setContentRecordingSessionLocked(session, mWm);
        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        // THEN the valid session was accepted.
        assertThat(resultingSession).isEqualTo(session);
        verify(mDisplayContent, atLeastOnce()).setContentRecordingSession(session);
    }

    @Test
    public void testSetContentRecordingSessionLocked_updateCurrentDisplaySession_notAccepted() {
        ContentRecordingController controller = new ContentRecordingController();
        // GIVEN a valid display session already in place.
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(TEST_TOKEN);
        session.setDisplayId(DEFAULT_DISPLAY);
        controller.setContentRecordingSessionLocked(session, mWm);
        verify(mDisplayContent, atLeastOnce()).setContentRecordingSession(session);

        // WHEN updating the session.
        ContentRecordingSession sessionUpdate = ContentRecordingSession.createDisplaySession(
                new RecordingTestToken());
        sessionUpdate.setDisplayId(DEFAULT_DISPLAY);
        controller.setContentRecordingSessionLocked(sessionUpdate, mWm);

        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        // THEN the session was not accepted.
        assertThat(resultingSession).isEqualTo(session);
        verify(mDisplayContent, never()).setContentRecordingSession(sessionUpdate);
    }

    @Test
    public void testSetContentRecordingSessionLocked_disableCurrentDisplaySession_accepted() {
        ContentRecordingController controller = new ContentRecordingController();
        // GIVEN a valid display session already in place.
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(TEST_TOKEN);
        session.setDisplayId(DEFAULT_DISPLAY);
        controller.setContentRecordingSessionLocked(session, mWm);
        verify(mDisplayContent, atLeastOnce()).setContentRecordingSession(session);

        // WHEN updating the session.
        ContentRecordingSession sessionUpdate = null;
        controller.setContentRecordingSessionLocked(sessionUpdate, mWm);

        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        // THEN the valid session was accepted.
        assertThat(resultingSession).isEqualTo(sessionUpdate);
        // Do not need to update the display content, since it will handle stopping the session
        // via state change callbacks.
    }

    @Test
    public void testSetContentRecordingSessionLocked_takeOverCurrentDisplaySession_accepted() {
        ContentRecordingController controller = new ContentRecordingController();
        // GIVEN a valid display session already in place.
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(TEST_TOKEN);
        session.setDisplayId(DEFAULT_DISPLAY);
        controller.setContentRecordingSessionLocked(session, mWm);
        verify(mDisplayContent, atLeastOnce()).setContentRecordingSession(session);

        // WHEN updating the session.
        final DisplayContent virtualDisplay = new TestDisplayContent.Builder(mAtm,
                mDisplayInfo).build();
        ContentRecordingSession sessionUpdate = ContentRecordingSession.createDisplaySession(
                TEST_TOKEN);
        sessionUpdate.setDisplayId(virtualDisplay.getDisplayId());
        controller.setContentRecordingSessionLocked(sessionUpdate, mWm);

        ContentRecordingSession resultingSession = controller.getContentRecordingSessionLocked();
        // THEN the valid session was accepted.
        assertThat(resultingSession).isEqualTo(sessionUpdate);
        verify(virtualDisplay).setContentRecordingSession(sessionUpdate);
        // THEN the recording was paused on the prior display.
        verify(mDisplayContent).pauseRecording();

    }

    private static class RecordingTestToken extends Binder {
    }
}
