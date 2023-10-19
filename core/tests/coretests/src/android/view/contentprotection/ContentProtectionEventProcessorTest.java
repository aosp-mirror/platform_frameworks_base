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

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
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

    private static final String ANDROID_CLASS_NAME = "android.test.some.class.name";

    private static final String PASSWORD_TEXT = "ENTER PASSWORD HERE";

    private static final String SUSPICIOUS_TEXT = "PLEASE SIGN IN";

    private static final String SAFE_TEXT = "SAFE TEXT";

    private static final ContentCaptureEvent PROCESS_EVENT = createProcessEvent();

    private static final ContentCaptureEvent[] BUFFERED_EVENTS =
            new ContentCaptureEvent[] {PROCESS_EVENT};

    private static final Set<Integer> EVENT_TYPES_TO_STORE =
            ImmutableSet.of(TYPE_VIEW_APPEARED, TYPE_VIEW_DISAPPEARED, TYPE_VIEW_TEXT_CHANGED);

    private static final int RESET_LOGIN_TOTAL_EVENTS_TO_PROCESS = 150;

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private RingBuffer<ContentCaptureEvent> mMockEventBuffer;

    @Mock private IContentCaptureManager mMockContentCaptureManager;

    private final Context mContext = ApplicationProvider.getApplicationContext();

    private ContentProtectionEventProcessor mContentProtectionEventProcessor;

    @Before
    public void setup() {
        mContentProtectionEventProcessor =
                new ContentProtectionEventProcessor(
                        mMockEventBuffer,
                        new Handler(Looper.getMainLooper()),
                        mMockContentCaptureManager,
                        PACKAGE_NAME);
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
    public void processEvent_loginDetected_inspectsOnlyTypeViewAppeared() {
        mContentProtectionEventProcessor.mPasswordFieldDetected = true;
        mContentProtectionEventProcessor.mSuspiciousTextDetected = true;

        for (int type = -100; type <= 100; type++) {
            if (type == TYPE_VIEW_APPEARED) {
                continue;
            }

            mContentProtectionEventProcessor.processEvent(createEvent(type));

            assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isTrue();
            assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isTrue();
        }

        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void processEvent_loginDetected() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);
        mContentProtectionEventProcessor.mPasswordFieldDetected = true;
        mContentProtectionEventProcessor.mSuspiciousTextDetected = true;

        mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isFalse();
        verify(mMockEventBuffer).clear();
        verify(mMockEventBuffer).toArray();
        assertOnLoginDetected();
    }

    @Test
    public void processEvent_loginDetected_passwordFieldNotDetected() {
        mContentProtectionEventProcessor.mSuspiciousTextDetected = true;
        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();

        mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isTrue();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void processEvent_loginDetected_suspiciousTextNotDetected() {
        mContentProtectionEventProcessor.mPasswordFieldDetected = true;
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isFalse();

        mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isTrue();
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isFalse();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void processEvent_loginDetected_withoutViewNode() {
        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isFalse();

        mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isFalse();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void processEvent_loginDetected_belowResetLimit() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);
        mContentProtectionEventProcessor.mSuspiciousTextDetected = true;
        ContentCaptureEvent event =
                createAndroidPasswordFieldEvent(
                        ANDROID_CLASS_NAME, InputType.TYPE_TEXT_VARIATION_PASSWORD);

        for (int i = 0; i < RESET_LOGIN_TOTAL_EVENTS_TO_PROCESS; i++) {
            mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);
        }

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isTrue();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isFalse();
        verify(mMockEventBuffer).clear();
        verify(mMockEventBuffer).toArray();
        assertOnLoginDetected();
    }

    @Test
    public void processEvent_loginDetected_aboveResetLimit() throws Exception {
        mContentProtectionEventProcessor.mSuspiciousTextDetected = true;
        ContentCaptureEvent event =
                createAndroidPasswordFieldEvent(
                        ANDROID_CLASS_NAME, InputType.TYPE_TEXT_VARIATION_PASSWORD);

        for (int i = 0; i < RESET_LOGIN_TOTAL_EVENTS_TO_PROCESS + 1; i++) {
            mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);
        }

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isFalse();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isTrue();
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isFalse();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
    }

    @Test
    public void processEvent_multipleLoginsDetected_belowFlushThreshold() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);

        mContentProtectionEventProcessor.mPasswordFieldDetected = true;
        mContentProtectionEventProcessor.mSuspiciousTextDetected = true;
        mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);

        mContentProtectionEventProcessor.mPasswordFieldDetected = true;
        mContentProtectionEventProcessor.mSuspiciousTextDetected = true;
        mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isFalse();
        verify(mMockEventBuffer).clear();
        verify(mMockEventBuffer).toArray();
        assertOnLoginDetected();
    }

    @Test
    public void processEvent_multipleLoginsDetected_aboveFlushThreshold() throws Exception {
        when(mMockEventBuffer.toArray()).thenReturn(BUFFERED_EVENTS);

        mContentProtectionEventProcessor.mPasswordFieldDetected = true;
        mContentProtectionEventProcessor.mSuspiciousTextDetected = true;
        mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);

        mContentProtectionEventProcessor.mLastFlushTime = Instant.now().minusSeconds(5);

        mContentProtectionEventProcessor.mPasswordFieldDetected = true;
        mContentProtectionEventProcessor.mSuspiciousTextDetected = true;
        mContentProtectionEventProcessor.processEvent(PROCESS_EVENT);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isFalse();
        verify(mMockEventBuffer, times(2)).clear();
        verify(mMockEventBuffer, times(2)).toArray();
        assertOnLoginDetected(PROCESS_EVENT, /* times= */ 2);
    }

    @Test
    public void isPasswordField_android() {
        ContentCaptureEvent event =
                createAndroidPasswordFieldEvent(
                        ANDROID_CLASS_NAME, InputType.TYPE_TEXT_VARIATION_PASSWORD);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isTrue();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void isPasswordField_android_withoutClassName() {
        ContentCaptureEvent event =
                createAndroidPasswordFieldEvent(
                        /* className= */ null, InputType.TYPE_TEXT_VARIATION_PASSWORD);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void isPasswordField_android_wrongClassName() {
        ContentCaptureEvent event =
                createAndroidPasswordFieldEvent(
                        "wrong.prefix" + ANDROID_CLASS_NAME,
                        InputType.TYPE_TEXT_VARIATION_PASSWORD);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void isPasswordField_android_wrongInputType() {
        ContentCaptureEvent event =
                createAndroidPasswordFieldEvent(
                        ANDROID_CLASS_NAME, InputType.TYPE_TEXT_VARIATION_NORMAL);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void isPasswordField_webView() throws Exception {
        ContentCaptureEvent event =
                createWebViewPasswordFieldEvent(
                        /* className= */ null, /* eventText= */ null, PASSWORD_TEXT);
        when(mMockEventBuffer.toArray()).thenReturn(new ContentCaptureEvent[] {event});

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        verify(mMockEventBuffer).clear();
        verify(mMockEventBuffer).toArray();
        assertOnLoginDetected(event, /* times= */ 1);
    }

    @Test
    public void isPasswordField_webView_withClassName() {
        ContentCaptureEvent event =
                createWebViewPasswordFieldEvent(
                        /* className= */ "any.class.name", /* eventText= */ null, PASSWORD_TEXT);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void isPasswordField_webView_withSafeViewNodeText() {
        ContentCaptureEvent event =
                createWebViewPasswordFieldEvent(
                        /* className= */ null, /* eventText= */ null, SAFE_TEXT);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void isPasswordField_webView_withEventText() {
        ContentCaptureEvent event =
                createWebViewPasswordFieldEvent(/* className= */ null, PASSWORD_TEXT, SAFE_TEXT);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mPasswordFieldDetected).isFalse();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void isSuspiciousText_withSafeText() {
        ContentCaptureEvent event = createSuspiciousTextEvent(SAFE_TEXT, SAFE_TEXT);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isFalse();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void isSuspiciousText_eventText_suspiciousText() {
        ContentCaptureEvent event = createSuspiciousTextEvent(SUSPICIOUS_TEXT, SAFE_TEXT);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isTrue();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void isSuspiciousText_viewNodeText_suspiciousText() {
        ContentCaptureEvent event = createSuspiciousTextEvent(SAFE_TEXT, SUSPICIOUS_TEXT);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isTrue();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void isSuspiciousText_eventText_passwordText() {
        ContentCaptureEvent event = createSuspiciousTextEvent(PASSWORD_TEXT, SAFE_TEXT);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isTrue();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
    }

    @Test
    public void isSuspiciousText_viewNodeText_passwordText() {
        // Specify the class to differ from {@link isPasswordField_webView} test in this version
        ContentCaptureEvent event =
                createProcessEvent(
                        "test.class.not.a.web.view", /* inputType= */ 0, SAFE_TEXT, PASSWORD_TEXT);

        mContentProtectionEventProcessor.processEvent(event);

        assertThat(mContentProtectionEventProcessor.mSuspiciousTextDetected).isTrue();
        verify(mMockEventBuffer, never()).clear();
        verify(mMockEventBuffer, never()).toArray();
        verifyZeroInteractions(mMockContentCaptureManager);
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

    private ContentCaptureEvent createProcessEvent(
            @Nullable String className,
            int inputType,
            @Nullable String eventText,
            @Nullable String viewNodeText) {
        View view = new View(mContext);
        ViewStructureImpl viewStructure = new ViewStructureImpl(view);
        if (className != null) {
            viewStructure.setClassName(className);
        }
        if (viewNodeText != null) {
            viewStructure.setText(viewNodeText);
        }
        viewStructure.setInputType(inputType);

        ContentCaptureEvent event = createProcessEvent();
        event.setViewNode(viewStructure.getNode());
        if (eventText != null) {
            event.setText(eventText);
        }

        return event;
    }

    private ContentCaptureEvent createAndroidPasswordFieldEvent(
            @Nullable String className, int inputType) {
        return createProcessEvent(
                className, inputType, /* eventText= */ null, /* viewNodeText= */ null);
    }

    private ContentCaptureEvent createWebViewPasswordFieldEvent(
            @Nullable String className, @Nullable String eventText, @Nullable String viewNodeText) {
        return createProcessEvent(className, /* inputType= */ 0, eventText, viewNodeText);
    }

    private ContentCaptureEvent createSuspiciousTextEvent(
            @Nullable String eventText, @Nullable String viewNodeText) {
        return createProcessEvent(
                /* className= */ null, /* inputType= */ 0, eventText, viewNodeText);
    }

    private void assertOnLoginDetected() throws Exception {
        assertOnLoginDetected(PROCESS_EVENT, /* times= */ 1);
    }

    private void assertOnLoginDetected(ContentCaptureEvent event, int times) throws Exception {
        ArgumentCaptor<ParceledListSlice> captor = ArgumentCaptor.forClass(ParceledListSlice.class);
        verify(mMockContentCaptureManager, times(times)).onLoginDetected(captor.capture());

        assertThat(captor.getValue()).isNotNull();
        List<ContentCaptureEvent> actual = captor.getValue().getList();
        assertThat(actual).isNotNull();
        assertThat(actual).containsExactly(event);
    }
}
