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

package android.view;

import static android.view.ContentRecordingSession.RECORD_CONTENT_DISPLAY;
import static android.view.ContentRecordingSession.RECORD_CONTENT_TASK;
import static android.view.ContentRecordingSession.TASK_ID_UNKNOWN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static com.google.common.truth.Truth.assertThat;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link ContentRecordingSession} class.
 *
 * Build/Install/Run:
 * atest FrameworksCoreTests:ContentRecordingSessionTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class ContentRecordingSessionTest {
    private static final int DISPLAY_ID = 1;
    private static final int TASK_ID = 123;
    private static final IBinder WINDOW_TOKEN = new Binder("DisplayContentWindowToken");

    @Test
    public void testParcelable() {
        ContentRecordingSession session = ContentRecordingSession.createTaskSession(WINDOW_TOKEN);
        session.setVirtualDisplayId(DISPLAY_ID);

        Parcel parcel = Parcel.obtain();
        session.writeToParcel(parcel, 0 /* flags */);
        parcel.setDataPosition(0);
        ContentRecordingSession session2 = ContentRecordingSession.CREATOR.createFromParcel(parcel);
        assertThat(session).isEqualTo(session2);
        parcel.recycle();
    }

    @Test
    public void testTaskConstructor() {
        ContentRecordingSession session = ContentRecordingSession.createTaskSession(WINDOW_TOKEN);
        assertThat(session.getContentToRecord()).isEqualTo(RECORD_CONTENT_TASK);
        assertThat(session.getTokenToRecord()).isEqualTo(WINDOW_TOKEN);
        assertThat(session.getTaskId()).isEqualTo(TASK_ID_UNKNOWN);
    }

    @Test
    public void testSecondaryTaskConstructor() {
        ContentRecordingSession session =
                ContentRecordingSession.createTaskSession(WINDOW_TOKEN, TASK_ID);
        assertThat(session.getContentToRecord()).isEqualTo(RECORD_CONTENT_TASK);
        assertThat(session.getTokenToRecord()).isEqualTo(WINDOW_TOKEN);
        assertThat(session.getTaskId()).isEqualTo(TASK_ID);
    }

    @Test
    public void testDisplayConstructor() {
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(
                DEFAULT_DISPLAY);
        assertThat(session.getContentToRecord()).isEqualTo(RECORD_CONTENT_DISPLAY);
        assertThat(session.getTokenToRecord()).isNull();
        assertThat(session.getTaskId()).isEqualTo(TASK_ID_UNKNOWN);
    }

    @Test
    public void testIsValid_displaySession() {
        // Canonical display session.
        ContentRecordingSession displaySession = ContentRecordingSession.createDisplaySession(
                DEFAULT_DISPLAY);
        displaySession.setVirtualDisplayId(DEFAULT_DISPLAY);
        assertThat(ContentRecordingSession.isValid(displaySession)).isTrue();

        // Virtual display id values.
        ContentRecordingSession displaySession0 = ContentRecordingSession.createDisplaySession(
                DEFAULT_DISPLAY);
        assertThat(ContentRecordingSession.isValid(displaySession0)).isFalse();

        ContentRecordingSession displaySession1 = ContentRecordingSession.createDisplaySession(
                DEFAULT_DISPLAY);
        displaySession1.setVirtualDisplayId(INVALID_DISPLAY);
        assertThat(ContentRecordingSession.isValid(displaySession1)).isFalse();

        // Display id values.
        ContentRecordingSession displaySession2 = ContentRecordingSession.createDisplaySession(
                INVALID_DISPLAY);
        displaySession2.setVirtualDisplayId(DEFAULT_DISPLAY);
        assertThat(ContentRecordingSession.isValid(displaySession2)).isFalse();

        displaySession2.setDisplayToRecord(DEFAULT_DISPLAY);
        assertThat(ContentRecordingSession.isValid(displaySession2)).isTrue();
    }

    @Test
    public void testIsValid_taskSession() {
        // Canonical task session.
        ContentRecordingSession taskSession = ContentRecordingSession.createTaskSession(
                WINDOW_TOKEN);
        taskSession.setVirtualDisplayId(DEFAULT_DISPLAY);
        assertThat(ContentRecordingSession.isValid(taskSession)).isTrue();

        // Virtual display id values.
        ContentRecordingSession taskSession0 = ContentRecordingSession.createTaskSession(
                WINDOW_TOKEN);
        assertThat(ContentRecordingSession.isValid(taskSession0)).isFalse();

        ContentRecordingSession taskSession1 = ContentRecordingSession.createTaskSession(
                WINDOW_TOKEN);
        taskSession1.setVirtualDisplayId(INVALID_DISPLAY);
        assertThat(ContentRecordingSession.isValid(taskSession1)).isFalse();

        // Window container values.
        ContentRecordingSession taskSession3 = ContentRecordingSession.createTaskSession(null);
        taskSession3.setVirtualDisplayId(DEFAULT_DISPLAY);
        assertThat(ContentRecordingSession.isValid(taskSession3)).isFalse();

        ContentRecordingSession taskSession4 = ContentRecordingSession.createTaskSession(
                WINDOW_TOKEN);
        taskSession4.setVirtualDisplayId(DEFAULT_DISPLAY);
        taskSession4.setTokenToRecord(null);
        assertThat(ContentRecordingSession.isValid(taskSession4)).isFalse();
    }

    @Test
    public void testIsProjectionOnSameDisplay() {
        assertThat(ContentRecordingSession.isProjectionOnSameDisplay(null, null)).isFalse();
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(
                DEFAULT_DISPLAY);
        session.setVirtualDisplayId(DEFAULT_DISPLAY);
        assertThat(ContentRecordingSession.isProjectionOnSameDisplay(session, null)).isFalse();

        ContentRecordingSession incomingSession = ContentRecordingSession.createDisplaySession(
                DEFAULT_DISPLAY);
        incomingSession.setVirtualDisplayId(DEFAULT_DISPLAY);
        assertThat(ContentRecordingSession.isProjectionOnSameDisplay(session,
                incomingSession)).isTrue();

        incomingSession.setVirtualDisplayId(DEFAULT_DISPLAY + 1);
        assertThat(ContentRecordingSession.isProjectionOnSameDisplay(session,
                incomingSession)).isFalse();
    }

    @Test
    public void testEquals() {
        ContentRecordingSession session = ContentRecordingSession.createTaskSession(WINDOW_TOKEN);
        session.setVirtualDisplayId(DISPLAY_ID);

        ContentRecordingSession session2 = ContentRecordingSession.createTaskSession(WINDOW_TOKEN);
        session2.setVirtualDisplayId(DISPLAY_ID);
        assertThat(session).isEqualTo(session2);
    }
}
