/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.view.textclassifier;

import static com.google.common.truth.Truth.assertWithMessage;

import android.annotation.Nullable;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class TextClassifierEventTest {

    private static final TextClassificationContext TC_CONTEXT =
            new TextClassificationContext.Builder("pkg", TextClassifier.WIDGET_TYPE_TEXTVIEW)
                    .build();

    private static final TextSelection TEXT_SELECTION = new TextSelection.Builder(10, 20)
            .setEntityType(TextClassifier.TYPE_ADDRESS, 1)
            .setId("id1")
            .build();

    private static final TextClassification TEXT_CLASSIFICATION = new TextClassification.Builder()
            .setEntityType(TextClassifier.TYPE_DATE, 1)
            .setId("id2")
            .build();

    @Test
    public void toSelectionEvent_selectionStarted() {
        final TextClassificationSessionId sessionId = new TextClassificationSessionId();
        final SelectionEvent expected = SelectionEvent.createSelectionStartedEvent(
                SelectionEvent.INVOCATION_MANUAL, 0);
        expected.setTextClassificationSessionContext(TC_CONTEXT);
        expected.setSessionId(sessionId);

        final TextClassifierEvent event = new TextClassifierEvent.TextSelectionEvent.Builder(
                TextClassifierEvent.TYPE_SELECTION_STARTED)
                .setEventContext(TC_CONTEXT)
                .build();
        event.mHiddenTempSessionId = sessionId;

        assertEquals(expected, event.toSelectionEvent());
    }

    @Test
    public void toSelectionEvent_smartSelectionMulti() {
        final int start = -1;
        final int end = 2;
        final int eventIndex = 1;
        final SelectionEvent expected = SelectionEvent.createSelectionModifiedEvent(
                0, 3, TEXT_SELECTION);
        expected.setInvocationMethod(SelectionEvent.INVOCATION_MANUAL);
        expected.setEventType(SelectionEvent.EVENT_SMART_SELECTION_MULTI);
        expected.setStart(start);
        expected.setEnd(end);
        expected.setEventIndex(eventIndex);
        expected.setTextClassificationSessionContext(TC_CONTEXT);

        final String entityType = TEXT_SELECTION.getEntity(0);
        final TextClassifierEvent event = new TextClassifierEvent.TextSelectionEvent.Builder(
                TextClassifierEvent.TYPE_SMART_SELECTION_MULTI)
                .setEventContext(TC_CONTEXT)
                .setResultId(TEXT_SELECTION.getId())
                .setRelativeWordStartIndex(start)
                .setRelativeWordEndIndex(end)
                .setEntityTypes(entityType)
                .setScores(TEXT_SELECTION.getConfidenceScore(entityType))
                .setEventIndex(eventIndex)
                .build();

        assertEquals(expected, event.toSelectionEvent());
    }

    @Test
    public void toSelectionEvent_smartSelectionSingle() {
        final int start = 0;
        final int end = 1;
        final int eventIndex = 2;
        final SelectionEvent expected = SelectionEvent.createSelectionModifiedEvent(
                0, 1, TEXT_SELECTION);
        expected.setInvocationMethod(SelectionEvent.INVOCATION_MANUAL);
        expected.setEventType(SelectionEvent.EVENT_SMART_SELECTION_SINGLE);
        expected.setStart(start);
        expected.setEnd(end);
        expected.setEventIndex(eventIndex);
        expected.setTextClassificationSessionContext(TC_CONTEXT);

        final String entityType = TEXT_SELECTION.getEntity(0);
        final TextClassifierEvent event = new TextClassifierEvent.TextSelectionEvent.Builder(
                TextClassifierEvent.TYPE_SMART_SELECTION_SINGLE)
                .setEventContext(TC_CONTEXT)
                .setResultId(TEXT_SELECTION.getId())
                .setRelativeWordStartIndex(start)
                .setRelativeWordEndIndex(end)
                .setEntityTypes(entityType)
                .setScores(TEXT_SELECTION.getConfidenceScore(entityType))
                .setEventIndex(eventIndex)
                .build();

        assertEquals(expected, event.toSelectionEvent());
    }

    @Test
    public void toSelectionEvent_resetSelection() {
        final int start = 0;
        final int end = 1;
        final int smartStart = -1;
        final int smartEnd = 2;
        final int eventIndex = 3;
        final SelectionEvent expected = SelectionEvent.createSelectionActionEvent(
                0, 1, SelectionEvent.ACTION_RESET, TEXT_CLASSIFICATION);
        expected.setInvocationMethod(SelectionEvent.INVOCATION_MANUAL);
        expected.setStart(start);
        expected.setEnd(end);
        expected.setSmartStart(smartStart);
        expected.setSmartEnd(smartEnd);
        expected.setEventIndex(eventIndex);
        expected.setTextClassificationSessionContext(TC_CONTEXT);

        final String entityType = TEXT_CLASSIFICATION.getEntity(0);
        final TextClassifierEvent event = new TextClassifierEvent.TextSelectionEvent.Builder(
                TextClassifierEvent.TYPE_SELECTION_RESET)
                .setEventContext(TC_CONTEXT)
                .setResultId(TEXT_CLASSIFICATION.getId())
                .setRelativeSuggestedWordStartIndex(smartStart)
                .setRelativeSuggestedWordEndIndex(smartEnd)
                .setRelativeWordStartIndex(start)
                .setRelativeWordEndIndex(end)
                .setEntityTypes(TEXT_CLASSIFICATION.getEntity(0))
                .setScores(TEXT_CLASSIFICATION.getConfidenceScore(entityType))
                .setEventIndex(eventIndex)
                .build();

        assertEquals(expected, event.toSelectionEvent());
    }

    @Test
    public void toSelectionEvent_modifySelection() {
        final int start = -1;
        final int end = 5;
        final int eventIndex = 4;
        final SelectionEvent expected = SelectionEvent.createSelectionModifiedEvent(0, 1);
        expected.setInvocationMethod(SelectionEvent.INVOCATION_MANUAL);
        expected.setStart(start);
        expected.setEnd(end);
        expected.setEventIndex(eventIndex);
        expected.setTextClassificationSessionContext(TC_CONTEXT);

        final TextClassifierEvent event = new TextClassifierEvent.TextSelectionEvent.Builder(
                TextClassifierEvent.TYPE_SELECTION_MODIFIED)
                .setEventContext(TC_CONTEXT)
                .setRelativeWordStartIndex(start)
                .setRelativeWordEndIndex(end)
                .setEventIndex(eventIndex)
                .build();

        assertEquals(expected, event.toSelectionEvent());
    }

    @Test
    public void toSelectionEvent_copyAction() {
        final int start = 3;
        final int end = 4;
        final int eventIndex = 5;
        final SelectionEvent expected = SelectionEvent.createSelectionActionEvent(
                5, 6, SelectionEvent.ACTION_COPY);
        expected.setInvocationMethod(SelectionEvent.INVOCATION_MANUAL);
        expected.setStart(start);
        expected.setEnd(end);
        expected.setEventIndex(eventIndex);
        expected.setTextClassificationSessionContext(TC_CONTEXT);

        final TextClassifierEvent event = new TextClassifierEvent.TextSelectionEvent.Builder(
                TextClassifierEvent.TYPE_COPY_ACTION)
                .setEventContext(TC_CONTEXT)
                .setRelativeWordStartIndex(start)
                .setRelativeWordEndIndex(end)
                .setEventIndex(eventIndex)
                .build();

        assertEquals(expected, event.toSelectionEvent());
    }

    @Test
    public void toSelectionEvent_selectionDismissed() {
        final int eventIndex = 6;
        final SelectionEvent expected = SelectionEvent.createSelectionActionEvent(
                0, 1, SelectionEvent.ACTION_ABANDON);
        expected.setInvocationMethod(SelectionEvent.INVOCATION_MANUAL);
        expected.setEventIndex(eventIndex);

        final TextClassifierEvent event = new TextClassifierEvent.TextSelectionEvent.Builder(
                TextClassifierEvent.TYPE_SELECTION_DESTROYED)
                .setEventIndex(eventIndex)
                .build();

        assertEquals(expected, event.toSelectionEvent());
    }

    @Test
    public void toSelectionEvent_link_smartAction() {
        final int eventIndex = 2;
        final SelectionEvent expected = SelectionEvent.createSelectionActionEvent(
                1, 9, SelectionEvent.ACTION_SMART_SHARE, TEXT_CLASSIFICATION);
        expected.setInvocationMethod(SelectionEvent.INVOCATION_LINK);
        // TODO: TextLinkifyEvent API is missing APIs to set text indices. See related comment in
        // TextClassifierEvent.
        expected.setEventIndex(eventIndex);
        expected.setTextClassificationSessionContext(TC_CONTEXT);

        final String entityType = TEXT_CLASSIFICATION.getEntity(0);
        final TextClassifierEvent event = new TextClassifierEvent.TextLinkifyEvent.Builder(
                TextClassifierEvent.TYPE_SMART_ACTION)
                .setEventContext(TC_CONTEXT)
                .setResultId(TEXT_CLASSIFICATION.getId())
                .setEntityTypes(entityType)
                .setScores(TEXT_CLASSIFICATION.getConfidenceScore(entityType))
                .setActionIndices(0)
                .setEventIndex(eventIndex)
                .build();

        assertEquals(expected, event.toSelectionEvent());
    }

    @Test
    public void toSelectionEvent_nonSelectionOrLinkifyEvent() {
        final TextClassifierEvent convActionEvent =
                new TextClassifierEvent.ConversationActionsEvent.Builder(
                        TextClassifierEvent.TYPE_ACTIONS_GENERATED)
                        .build();
        assertWithMessage("conversation action event")
                .that(convActionEvent.toSelectionEvent()).isNull();

        final TextClassifierEvent langDetEvent =
                new TextClassifierEvent.ConversationActionsEvent.Builder(
                        TextClassifierEvent.TYPE_ACTIONS_GENERATED)
                        .setEventContext(TC_CONTEXT)
                        .build();
        assertWithMessage("language detection event")
                .that(langDetEvent.toSelectionEvent()).isNull();
    }

    private static void assertEquals(
            @Nullable SelectionEvent expected, @Nullable SelectionEvent actual) {
        if (expected == null && actual == null) return;
        if (expected == actual) return;
        assertWithMessage("actual").that(actual).isNotNull();
        assertWithMessage("expected").that(expected).isNotNull();
        assertWithMessage("eventType")
                .that(actual.getEventType()).isEqualTo(expected.getEventType());
        assertWithMessage("packageName")
                .that(actual.getPackageName()).isEqualTo(expected.getPackageName());
        assertWithMessage("widgetType")
                .that(actual.getWidgetType()).isEqualTo(expected.getWidgetType());
        assertWithMessage("widgetVersion")
                .that(actual.getWidgetVersion()).isEqualTo(expected.getWidgetVersion());
        assertWithMessage("invocationMethod")
                .that(actual.getInvocationMethod()).isEqualTo(expected.getInvocationMethod());
        assertWithMessage("resultId")
                .that(actual.getResultId()).isEqualTo(expected.getResultId());
        assertWithMessage("sessionId")
                .that(actual.getSessionId()).isEqualTo(expected.getSessionId());
        assertWithMessage("entityType")
                .that(actual.getEntityType()).isEqualTo(expected.getEntityType());
        assertWithMessage("eventIndex")
                .that(actual.getEventIndex()).isEqualTo(expected.getEventIndex());
        assertWithMessage("start")
                .that(actual.getStart()).isEqualTo(expected.getStart());
        assertWithMessage("end")
                .that(actual.getEnd()).isEqualTo(expected.getEnd());
        assertWithMessage("smartStart")
                .that(actual.getSmartStart()).isEqualTo(expected.getSmartStart());
        assertWithMessage("smartEnd")
                .that(actual.getSmartEnd()).isEqualTo(expected.getSmartEnd());
    }
}
