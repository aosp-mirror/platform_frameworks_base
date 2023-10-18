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

import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_APPEARED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_DISAPPEARED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_TEXT_CHANGED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.os.test.TestLooper;
import android.view.View;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.IContentCaptureManager;
import android.view.contentcapture.ViewNode;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.util.RingBuffer;

import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.verification.VerificationMode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Test for {@link ContentProtectionEventProcessor}.
 *
 * <p>Run with: {@code atest
 * FrameworksCoreTests:android.view.contentprotection.ContentProtectionEventProcessorTest}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class ContentProtectionEventProcessorTest {

    private static final String PACKAGE_NAME = "com.test.package.name";

    private static final String TEXT_REQUIRED1 = "TEXT REQUIRED1 TEXT";

    private static final String TEXT_REQUIRED2 = "TEXT REQUIRED2 TEXT";

    private static final String TEXT_OPTIONAL1 = "TEXT OPTIONAL1 TEXT";

    private static final String TEXT_OPTIONAL2 = "TEXT OPTIONAL2 TEXT";

    private static final String TEXT_CONTAINS_OPTIONAL3 = "TEXTOPTIONAL3TEXT";

    private static final String TEXT_SHARED = "TEXT SHARED TEXT";

    private static final String TEXT_SAFE = "TEXT SAFE TEXT";

    private static final List<List<String>> REQUIRED_GROUPS =
            List.of(List.of("required1", "missing"), List.of("required2", "shared"));

    private static final List<List<String>> OPTIONAL_GROUPS =
            List.of(List.of("optional1"), List.of("optional2", "optional3"), List.of("shared"));

    private static final ContentCaptureEvent PROCESS_EVENT = createProcessEvent();

    private static final ContentCaptureEvent[] BUFFERED_EVENTS =
            new ContentCaptureEvent[] {PROCESS_EVENT};

    private static final Set<Integer> EVENT_TYPES_TO_STORE =
            ImmutableSet.of(TYPE_VIEW_APPEARED, TYPE_VIEW_DISAPPEARED, TYPE_VIEW_TEXT_CHANGED);

    private static final int BUFFER_SIZE = 150;

    private static final int OPTIONAL_GROUPS_THRESHOLD = 1;

    private static final ContentCaptureOptions.ContentProtectionOptions OPTIONS =
            new ContentCaptureOptions.ContentProtectionOptions(
                    /* enableReceiver= */ true,
                    BUFFER_SIZE,
                    REQUIRED_GROUPS,
                    OPTIONAL_GROUPS,
                    OPTIONAL_GROUPS_THRESHOLD);

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private RingBuffer<ContentCaptureEvent> mMockEventBuffer;

    @Mock private IContentCaptureManager mMockContentCaptureManager;

    private final Context mContext = ApplicationProvider.getApplicationContext();

    private final TestLooper mTestLooper = new TestLooper();

    @NonNull private ContentProtectionEventProcessor mContentProtectionEventProcessor;

    @Before
    public void setup() {
        mContentProtectionEventProcessor =
                new ContentProtectionEventProcessor(
                        mMockEventBuffer,
                        new Handler(mTestLooper.getLooper()),
                        mMockContentCaptureManager,
                        PACKAGE_NAME,
                        OPTIONS);
    }

    @Test
    public void processEvent_buffer_storesOnlySubsetOfEventTypes() {
        List<ContentCaptureEvent> expectedEvents = new ArrayList<>();
        for (int type = -100; type <= 100; type++) {
            ContentCaptureEvent event = createEvent(type);
            if (EVENT_TYPES_TO_STORE.contains(type)) {
                expectedEvents.add(event);
            }

            mContentProtectionEventProcessor.processEvent(event);
        }

        assertThat(expectedEvents).hasSize(EVENT_TYPES_TO_STORE.size());
        expectedEvents.forEach((expectedEvent) -> verify(mMockEventBuffer).append(expectedEvent));
        verifyNoMoreInteractions(mMockEventBuffer);
    }

    @Test
    public void processEvent_buffer_setsTextIdEntry_withoutExistingViewNode() {
        ContentCaptureEvent event = createStoreEvent();

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(event.getViewNode()).isNotNull();
        assertThat(event.getViewNode().getTextIdEntry()).isEqualTo(PACKAGE_NAME);
        verify(mMockEventBuffer).append(event);
    }

    @Test
    public void processEvent_buffer_setsTextIdEntry_withExistingViewNode() {
        ViewNode viewNode = new ViewNode();
        viewNode.setTextIdEntry(PACKAGE_NAME + "TO BE OVERWRITTEN");
        ContentCaptureEvent event = createStoreEvent();
        event.setViewNode(viewNode);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(event.getViewNode()).isSameInstanceAs(viewNode);
        assertThat(viewNode.getTextIdEntry()).isEqualTo(PACKAGE_NAME);
        verify(mMockEventBuffer).append(event);
    }

    @Test
    public void processEvent_loginDetected_true_eventText() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);

        mContentProtectionEventProcessor.processEvent(
                createProcessEvent(
                        /* eventText= */ TEXT_REQUIRED1,
                        /* viewNodeText= */ null,
                        /* hintText= */ null));
        mContentProtectionEventProcessor.processEvent(
                createProcessEvent(
                        /* eventText= */ TEXT_REQUIRED2,
                        /* viewNodeText= */ null,
                        /* hintText= */ null));
        mContentProtectionEventProcessor.processEvent(
                createProcessEvent(
                        /* eventText= */ TEXT_OPTIONAL1,
                        /* viewNodeText= */ null,
                        /* hintText= */ null));

        assertLoginDetected();
    }

    @Test
    public void processEvent_loginDetected_true_viewNodeText() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);

        mContentProtectionEventProcessor.processEvent(
                createProcessEvent(
                        /* eventText= */ null,
                        /* viewNodeText= */ TEXT_REQUIRED1,
                        /* hintText= */ null));
        mContentProtectionEventProcessor.processEvent(
                createProcessEvent(
                        /* eventText= */ null,
                        /* viewNodeText= */ TEXT_REQUIRED2,
                        /* hintText= */ null));
        mContentProtectionEventProcessor.processEvent(
                createProcessEvent(
                        /* eventText= */ null,
                        /* viewNodeText= */ TEXT_OPTIONAL1,
                        /* hintText= */ null));

        assertLoginDetected();
    }

    @Test
    public void processEvent_loginDetected_true_hintText() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);

        mContentProtectionEventProcessor.processEvent(
                createProcessEvent(
                        /* eventText= */ null,
                        /* viewNodeText= */ null,
                        /* hintText= */ TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(
                createProcessEvent(
                        /* eventText= */ null,
                        /* viewNodeText= */ null,
                        /* hintText= */ TEXT_REQUIRED2));
        mContentProtectionEventProcessor.processEvent(
                createProcessEvent(
                        /* eventText= */ null,
                        /* viewNodeText= */ null,
                        /* hintText= */ TEXT_OPTIONAL1));

        assertLoginDetected();
    }

    @Test
    public void processEvent_loginDetected_true_differentOptionalGroup() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);

        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED2));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_OPTIONAL2));

        assertLoginDetected();
    }

    @Test
    public void processEvent_loginDetected_true_usesContains() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);

        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED2));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_CONTAINS_OPTIONAL3));

        assertLoginDetected();
    }

    @Test
    public void processEvent_loginDetected_false_missingRequiredGroups() {
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_OPTIONAL1));

        assertLoginNotDetected();
    }

    @Test
    public void processEvent_loginDetected_false_missingOptionalGroups() {
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED2));

        assertLoginNotDetected();
    }

    @Test
    public void processEvent_loginDetected_false_safeText() {
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED2));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_SAFE));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_SAFE));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_SAFE));

        assertLoginNotDetected();
    }

    @Test
    public void processEvent_loginDetected_false_sharedTextOnce() {
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_SHARED));

        assertLoginNotDetected();
    }

    @Test
    public void processEvent_loginDetected_true_sharedTextMultiple() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);

        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_SHARED));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_SHARED));

        assertLoginDetected();
    }

    @Test
    public void processEvent_loginDetected_false_inspectsOnlyTypeViewAppeared() {
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED2));

        for (int type = -100; type <= 100; type++) {
            if (type == TYPE_VIEW_APPEARED) {
                continue;
            }
            ContentCaptureEvent event = createEvent(type);
            event.setText(TEXT_OPTIONAL1);
            mContentProtectionEventProcessor.processEvent(event);
        }

        assertLoginNotDetected();
    }

    @Test
    public void processEvent_loginDetected_true_belowResetLimit() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);

        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED2));

        for (int i = 0; i < BUFFER_SIZE - 2; i++) {
            mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);
        }

        assertLoginNotDetected();

        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_OPTIONAL1));

        assertLoginDetected();
    }

    @Test
    public void processEvent_loginDetected_false_aboveResetLimit() {
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED2));

        for (int i = 0; i < BUFFER_SIZE - 1; i++) {
            mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);
        }

        assertLoginNotDetected();

        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_OPTIONAL1));

        assertLoginNotDetected();
    }

    @Test
    public void processEvent_multipleLoginsDetected_belowFlushThreshold() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);

        for (int i = 0; i < 2; i++) {
            mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
            mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED2));
            mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_OPTIONAL1));
            mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);
        }

        assertLoginDetected();
    }

    @Test
    public void processEvent_multipleLoginsDetected_aboveFlushThreshold() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);

        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED2));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_OPTIONAL1));
        mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);

        mContentProtectionEventProcessor.mLastFlushTime = Instant.now().minusSeconds(5);

        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED1));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_REQUIRED2));
        mContentProtectionEventProcessor.processEvent(createProcessEvent(TEXT_OPTIONAL1));
        mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);

        assertLoginDetected(times(2));
    }

    private static ContentCaptureEvent createEvent(int type) {
        return new ContentCaptureEvent(/* sessionId= */ 123, type);
    }

    private static ContentCaptureEvent createStoreEvent() {
        return createEvent(TYPE_VIEW_TEXT_CHANGED);
    }

    private static ContentCaptureEvent createProcessEvent() {
        return createEvent(TYPE_VIEW_APPEARED);
    }

    private ContentCaptureEvent createProcessEvent(@Nullable String eventText) {
        return createProcessEvent(eventText, /* viewNodeText= */ null, /* hintText= */ null);
    }

    private ContentCaptureEvent createProcessEvent(
            @Nullable String eventText, @Nullable String viewNodeText, @Nullable String hintText) {
        View view = new View(mContext);
        ViewStructureImpl viewStructure = new ViewStructureImpl(view);
        if (viewNodeText != null) {
            viewStructure.setText(viewNodeText);
        }
        if (hintText != null) {
            viewStructure.setHint(hintText);
        }

        ContentCaptureEvent event = createProcessEvent();
        event.setViewNode(viewStructure.getNode());
        if (eventText != null) {
            event.setText(eventText);
        }

        return event;
    }

    private void assertLoginNotDetected() {
        mTestLooper.dispatchAll();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    private void assertLoginDetected() throws Exception {
        assertLoginDetected(times(1));
    }

    private void assertLoginDetected(@NonNull VerificationMode verificationMode) throws Exception {
        mTestLooper.dispatchAll();
        verify(mMockEventBuffer, verificationMode).clear();
        verify(mMockEventBuffer, verificationMode).toArray();

        ArgumentCaptor<ParceledListSlice> captor = ArgumentCaptor.forClass(ParceledListSlice.class);
        verify(mMockContentCaptureManager, verificationMode).onLoginDetected(captor.capture());

        assertThat(captor.getValue()).isNotNull();
        List<ContentCaptureEvent> actual = captor.getValue().getList();
        assertThat(actual).isNotNull();
        assertThat(actual).containsExactly(PROCESS_EVENT);
    }
}
