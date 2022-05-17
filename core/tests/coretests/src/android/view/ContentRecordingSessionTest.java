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
    private static final IBinder WINDOW_TOKEN = new Binder("DisplayContentWindowToken");

    @Test
    public void testParcelable() {
        ContentRecordingSession session = ContentRecordingSession.createTaskSession(WINDOW_TOKEN);
        session.setDisplayId(DISPLAY_ID);

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
    }

    @Test
    public void testDisplayConstructor() {
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(
                WINDOW_TOKEN);
        assertThat(session.getContentToRecord()).isEqualTo(RECORD_CONTENT_DISPLAY);
        assertThat(session.getTokenToRecord()).isEqualTo(WINDOW_TOKEN);
    }

    @Test
    public void testIsValid() {
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(
                WINDOW_TOKEN);
        assertThat(ContentRecordingSession.isValid(session)).isFalse();

        session.setDisplayId(DEFAULT_DISPLAY);
        assertThat(ContentRecordingSession.isValid(session)).isTrue();

        session.setDisplayId(INVALID_DISPLAY);
        assertThat(ContentRecordingSession.isValid(session)).isFalse();
    }

    @Test
    public void testIsSameDisplay() {
        assertThat(ContentRecordingSession.isSameDisplay(null, null)).isFalse();
        ContentRecordingSession session = ContentRecordingSession.createDisplaySession(
                WINDOW_TOKEN);
        session.setDisplayId(DEFAULT_DISPLAY);
        assertThat(ContentRecordingSession.isSameDisplay(session, null)).isFalse();

        ContentRecordingSession incomingSession = ContentRecordingSession.createDisplaySession(
                WINDOW_TOKEN);
        incomingSession.setDisplayId(DEFAULT_DISPLAY);
        assertThat(ContentRecordingSession.isSameDisplay(session, incomingSession)).isTrue();

        incomingSession.setDisplayId(DEFAULT_DISPLAY + 1);
        assertThat(ContentRecordingSession.isSameDisplay(session, incomingSession)).isFalse();
    }

    @Test
    public void testEquals() {
        ContentRecordingSession session = ContentRecordingSession.createTaskSession(WINDOW_TOKEN);
        session.setDisplayId(DISPLAY_ID);

        ContentRecordingSession session2 = ContentRecordingSession.createTaskSession(WINDOW_TOKEN);
        session2.setDisplayId(DISPLAY_ID);
        assertThat(session).isEqualTo(session2);
    }
}
