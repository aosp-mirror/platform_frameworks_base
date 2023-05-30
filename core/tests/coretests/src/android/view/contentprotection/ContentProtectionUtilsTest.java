/*
 * Copyright (C) 2013 The Android Open Source Project
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

    private static final ContentCaptureEvent EVENT = createEvent();

    private static final ViewNode VIEW_NODE = new ViewNode();

    private static final ViewNode VIEW_NODE_WITH_TEXT = createViewNodeWithText();

    @Test
    public void event_getEventText_null() {
        String actual = ContentProtectionUtils.getEventText(EVENT);

        assertThat(actual).isNull();
    }

    @Test
    public void event_getEventText_notNull() {
        ContentCaptureEvent event = createEvent();
        event.setText(TEXT);

        String actual = ContentProtectionUtils.getEventText(event);

        assertThat(actual).isEqualTo(TEXT);
    }

    @Test
    public void event_getViewNodeText_null() {
        String actual = ContentProtectionUtils.getViewNodeText(EVENT);

        assertThat(actual).isNull();
    }

    @Test
    public void event_getViewNodeText_notNull() {
        ContentCaptureEvent event = createEvent();
        event.setViewNode(VIEW_NODE_WITH_TEXT);

        String actual = ContentProtectionUtils.getViewNodeText(event);

        assertThat(actual).isEqualTo(TEXT);
    }

    @Test
    public void viewNode_getViewNodeText_null() {
        String actual = ContentProtectionUtils.getViewNodeText(VIEW_NODE);

        assertThat(actual).isNull();
    }

    @Test
    public void viewNode_getViewNodeText_notNull() {
        String actual = ContentProtectionUtils.getViewNodeText(VIEW_NODE_WITH_TEXT);

        assertThat(actual).isEqualTo(TEXT);
    }

    private static ContentCaptureEvent createEvent() {
        return new ContentCaptureEvent(/* sessionId= */ 123, TYPE_SESSION_STARTED);
    }

    private static ViewNode createViewNodeWithText() {
        View view = new View(ApplicationProvider.getApplicationContext());
        ViewStructureImpl viewStructure = new ViewStructureImpl(view);
        viewStructure.setText(TEXT);
        return viewStructure.getNode();
    }
}
