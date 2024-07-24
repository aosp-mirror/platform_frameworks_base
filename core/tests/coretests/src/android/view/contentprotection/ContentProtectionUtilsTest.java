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

package android.view.contentprotection;

import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_STARTED;

import static com.google.common.truth.Truth.assertThat;

import android.view.View;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.ViewNode;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test for {@link ContentProtectionUtils}.
 *
 * <p>Run with: {@code atest
 * FrameworksCoreTests:android.view.contentprotection.ContentProtectionUtilsTest}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ContentProtectionUtilsTest {

    private static final String TEXT = "TEST_TEXT";

    private static final String TEXT_LOWER = TEXT.toLowerCase();

    @Test
    public void getEventTextLower_null() {
        String actual = ContentProtectionUtils.getEventTextLower(createEvent());

        assertThat(actual).isNull();
    }

    @Test
    public void getEventTextLower_notNull() {
        String actual = ContentProtectionUtils.getEventTextLower(createEventWithText());

        assertThat(actual).isEqualTo(TEXT_LOWER);
    }

    @Test
    public void getViewNodeTextLower_null() {
        String actual = ContentProtectionUtils.getViewNodeTextLower(new ViewNode());

        assertThat(actual).isNull();
    }

    @Test
    public void getViewNodeTextLower_notNull() {
        String actual = ContentProtectionUtils.getViewNodeTextLower(createViewNodeWithText());

        assertThat(actual).isEqualTo(TEXT_LOWER);
    }

    @Test
    public void getHintTextLower_null() {
        String actual = ContentProtectionUtils.getHintTextLower(new ViewNode());

        assertThat(actual).isNull();
    }

    @Test
    public void getHintTextLower_notNull() {
        String actual = ContentProtectionUtils.getHintTextLower(createViewNodeWithHint());

        assertThat(actual).isEqualTo(TEXT_LOWER);
    }

    private static ContentCaptureEvent createEvent() {
        return new ContentCaptureEvent(/* sessionId= */ 123, TYPE_SESSION_STARTED);
    }

    private static ContentCaptureEvent createEventWithText() {
        ContentCaptureEvent event = createEvent();
        event.setText(TEXT);
        return event;
    }

    private static ViewStructureImpl createViewStructureImpl() {
        View view = new View(ApplicationProvider.getApplicationContext());
        return new ViewStructureImpl(view);
    }

    private static ViewNode createViewNodeWithText() {
        ViewStructureImpl viewStructureImpl = createViewStructureImpl();
        viewStructureImpl.setText(TEXT);
        return viewStructureImpl.getNode();
    }

    private static ViewNode createViewNodeWithHint() {
        ViewStructureImpl viewStructureImpl = createViewStructureImpl();
        viewStructureImpl.setHint(TEXT);
        return viewStructureImpl.getNode();
    }
}
