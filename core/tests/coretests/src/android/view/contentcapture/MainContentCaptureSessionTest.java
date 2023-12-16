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

package android.view.contentcapture;

import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_STARTED;
import static android.view.contentcapture.ContentCaptureSession.FLUSH_REASON_VIEW_TREE_APPEARED;
import static android.view.contentcapture.ContentCaptureSession.FLUSH_REASON_VIEW_TREE_APPEARING;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.graphics.Insets;
import android.os.Handler;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.SparseArray;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.contentprotection.ContentProtectionEventProcessor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Test for {@link MainContentCaptureSession}.
 *
 * <p>Run with: {@code atest
 * FrameworksCoreTests:android.view.contentcapture.MainContentCaptureSessionTest}
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
@TestableLooper.RunWithLooper
public class MainContentCaptureSessionTest {

    private static final int BUFFER_SIZE = 100;

    private static final int REASON = 123;

    private static final ContentCaptureEvent EVENT =
            new ContentCaptureEvent(/* sessionId= */ 0, TYPE_SESSION_STARTED);

    private static final ComponentName COMPONENT_NAME =
            new ComponentName("com.test.package", "TestClass");

    private static final Context sContext = ApplicationProvider.getApplicationContext();

    private static final ContentCaptureManager.StrippedContext sStrippedContext =
            new ContentCaptureManager.StrippedContext(sContext);

    private TestableLooper mTestableLooper;

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private IContentCaptureManager mMockSystemServerInterface;

    @Mock private ContentProtectionEventProcessor mMockContentProtectionEventProcessor;

    @Mock private IContentCaptureDirectManager mMockContentCaptureDirectManager;

    @Before
    public void setup() {
        mTestableLooper = TestableLooper.get(this);
    }

    @Test
    public void onSessionStarted_contentProtectionEnabled_processorCreated() {
        MainContentCaptureSession session = createSession();
        assertThat(session.mContentProtectionEventProcessor).isNull();

        session.onSessionStarted(/* resultCode= */ 0, /* binder= */ null);
        mTestableLooper.processAllMessages();

        assertThat(session.mContentProtectionEventProcessor).isNotNull();
    }

    @Test
    public void onSessionStarted_contentProtectionDisabled_processorNotCreated() {
        MainContentCaptureSession session =
                createSession(
                        /* enableContentCaptureReceiver= */ true,
                        /* enableContentProtectionReceiver= */ false);
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.onSessionStarted(/* resultCode= */ 0, /* binder= */ null);
        mTestableLooper.processAllMessages();

        assertThat(session.mContentProtectionEventProcessor).isNull();
        verifyZeroInteractions(mMockContentProtectionEventProcessor);
    }

    @Test
    public void onSessionStarted_contentProtectionNoBuffer_processorNotCreated() {
        ContentCaptureOptions options =
                createOptions(
                        /* enableContentCaptureReceiver= */ true,
                        new ContentCaptureOptions.ContentProtectionOptions(
                                /* enableReceiver= */ true,
                                -BUFFER_SIZE,
                                /* requiredGroups= */ List.of(List.of("a")),
                                /* optionalGroups= */ Collections.emptyList(),
                                /* optionalGroupsThreshold= */ 0));
        MainContentCaptureSession session = createSession(options);
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.onSessionStarted(/* resultCode= */ 0, /* binder= */ null);
        mTestableLooper.processAllMessages();

        assertThat(session.mContentProtectionEventProcessor).isNull();
        verifyZeroInteractions(mMockContentProtectionEventProcessor);
    }

    @Test
    public void onSessionStarted_contentProtectionNoGroups_processorNotCreated() {
        ContentCaptureOptions options =
                createOptions(
                        /* enableContentCaptureReceiver= */ true,
                        new ContentCaptureOptions.ContentProtectionOptions(
                                /* enableReceiver= */ true,
                                BUFFER_SIZE,
                                /* requiredGroups= */ Collections.emptyList(),
                                /* optionalGroups= */ Collections.emptyList(),
                                /* optionalGroupsThreshold= */ 0));
        MainContentCaptureSession session = createSession(options);
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.onSessionStarted(/* resultCode= */ 0, /* binder= */ null);
        mTestableLooper.processAllMessages();

        assertThat(session.mContentProtectionEventProcessor).isNull();
        verifyZeroInteractions(mMockContentProtectionEventProcessor);
    }

    @Test
    public void onSessionStarted_noComponentName_processorNotCreated() {
        MainContentCaptureSession session = createSession();
        session.mComponentName = null;

        session.onSessionStarted(/* resultCode= */ 0, /* binder= */ null);
        mTestableLooper.processAllMessages();

        assertThat(session.mContentProtectionEventProcessor).isNull();
    }

    @Test
    public void sendEvent_contentCaptureDisabled_contentProtectionDisabled() {
        MainContentCaptureSession session =
                createSession(
                        /* enableContentCaptureReceiver= */ false,
                        /* enableContentProtectionReceiver= */ false);
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.sendEvent(EVENT);
        mTestableLooper.processAllMessages();

        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mEvents).isNull();
    }

    @Test
    public void sendEvent_contentCaptureDisabled_contentProtectionEnabled() {
        MainContentCaptureSession session =
                createSession(
                        /* enableContentCaptureReceiver= */ false,
                        /* enableContentProtectionReceiver= */ true);
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.sendEvent(EVENT);
        mTestableLooper.processAllMessages();

        verify(mMockContentProtectionEventProcessor).processEvent(EVENT);
        assertThat(session.mEvents).isNull();
    }

    @Test
    public void sendEvent_contentCaptureEnabled_contentProtectionDisabled() {
        MainContentCaptureSession session =
                createSession(
                        /* enableContentCaptureReceiver= */ true,
                        /* enableContentProtectionReceiver= */ false);
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.sendEvent(EVENT);
        mTestableLooper.processAllMessages();

        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mEvents).isNotNull();
        assertThat(session.mEvents).containsExactly(EVENT);
    }

    @Test
    public void sendEvent_contentCaptureEnabled_contentProtectionEnabled() {
        MainContentCaptureSession session = createSession();
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.sendEvent(EVENT);
        mTestableLooper.processAllMessages();

        verify(mMockContentProtectionEventProcessor).processEvent(EVENT);
        assertThat(session.mEvents).isNotNull();
        assertThat(session.mEvents).containsExactly(EVENT);
    }

    @Test
    public void sendEvent_contentProtectionEnabled_processorNotCreated() {
        MainContentCaptureSession session =
                createSession(
                        /* enableContentCaptureReceiver= */ false,
                        /* enableContentProtectionReceiver= */ true);

        session.sendEvent(EVENT);
        mTestableLooper.processAllMessages();

        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mEvents).isNull();
    }

    @Test
    public void flush_contentCaptureDisabled_contentProtectionDisabled() throws Exception {
        ContentCaptureOptions options =
                createOptions(
                        /* enableContentCaptureReceiver= */ false,
                        /* enableContentProtectionReceiver= */ false);
        MainContentCaptureSession session = createSession(options);
        session.mEvents = new ArrayList<>(Arrays.asList(EVENT));
        session.mDirectServiceInterface = mMockContentCaptureDirectManager;

        session.flush(REASON);
        mTestableLooper.processAllMessages();

        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        verifyZeroInteractions(mMockContentCaptureDirectManager);
        assertThat(session.mEvents).containsExactly(EVENT);
    }

    @Test
    public void flush_contentCaptureDisabled_contentProtectionEnabled() {
        MainContentCaptureSession session =
                createSession(
                        /* enableContentCaptureReceiver= */ false,
                        /* enableContentProtectionReceiver= */ true);
        session.mEvents = new ArrayList<>(Arrays.asList(EVENT));
        session.mDirectServiceInterface = mMockContentCaptureDirectManager;

        session.flush(REASON);
        mTestableLooper.processAllMessages();

        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        verifyZeroInteractions(mMockContentCaptureDirectManager);
        assertThat(session.mEvents).containsExactly(EVENT);
    }

    @Test
    public void flush_contentCaptureEnabled_contentProtectionDisabled() throws Exception {
        ContentCaptureOptions options =
                createOptions(
                        /* enableContentCaptureReceiver= */ true,
                        /* enableContentProtectionReceiver= */ false);
        MainContentCaptureSession session = createSession(options);
        session.mEvents = new ArrayList<>(Arrays.asList(EVENT));
        session.mDirectServiceInterface = mMockContentCaptureDirectManager;

        session.flush(REASON);
        mTestableLooper.processAllMessages();

        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mEvents).isEmpty();
        assertEventFlushedContentCapture(options);
    }

    @Test
    public void flush_contentCaptureEnabled_contentProtectionEnabled() throws Exception {
        ContentCaptureOptions options =
                createOptions(
                        /* enableContentCaptureReceiver= */ true,
                        /* enableContentProtectionReceiver= */ true);
        MainContentCaptureSession session = createSession(options);
        session.mEvents = new ArrayList<>(Arrays.asList(EVENT));
        session.mDirectServiceInterface = mMockContentCaptureDirectManager;

        session.flush(REASON);
        mTestableLooper.processAllMessages();

        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mEvents).isEmpty();
        assertEventFlushedContentCapture(options);
    }

    @Test
    public void destroySession() throws Exception {
        MainContentCaptureSession session = createSession();
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.destroySession();
        mTestableLooper.processAllMessages();

        verify(mMockSystemServerInterface).finishSession(anyInt());
        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mDirectServiceInterface).isNull();
        assertThat(session.mContentProtectionEventProcessor).isNull();
    }

    @Test
    public void resetSession() {
        MainContentCaptureSession session = createSession();
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.resetSession(/* newState= */ 0);
        mTestableLooper.processAllMessages();

        verifyZeroInteractions(mMockSystemServerInterface);
        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mDirectServiceInterface).isNull();
        assertThat(session.mContentProtectionEventProcessor).isNull();
    }

    @Test
    @SuppressWarnings("GuardedBy")
    public void notifyContentCaptureEvents_notStarted_ContentCaptureDisabled_ProtectionDisabled() {
        ContentCaptureOptions options =
                createOptions(
                        /* enableContentCaptureReceiver= */ false,
                        /* enableContentProtectionReceiver= */ false);
        MainContentCaptureSession session = createSession(options);

        notifyContentCaptureEvents(session);
        mTestableLooper.processAllMessages();

        verifyZeroInteractions(mMockContentCaptureDirectManager);
        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mEvents).isNull();
    }

    @Test
    @SuppressWarnings("GuardedBy")
    public void notifyContentCaptureEvents_started_ContentCaptureDisabled_ProtectionDisabled() {
        ContentCaptureOptions options =
                createOptions(
                        /* enableContentCaptureReceiver= */ false,
                        /* enableContentProtectionReceiver= */ false);
        MainContentCaptureSession session = createSession(options);

        session.onSessionStarted(0x2, null);
        notifyContentCaptureEvents(session);
        mTestableLooper.processAllMessages();

        verifyZeroInteractions(mMockContentCaptureDirectManager);
        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mEvents).isNull();
    }

    @Test
    @SuppressWarnings("GuardedBy")
    public void notifyContentCaptureEvents_notStarted_ContentCaptureEnabled_ProtectionEnabled() {
        ContentCaptureOptions options =
                createOptions(
                        /* enableContentCaptureReceiver= */ true,
                        /* enableContentProtectionReceiver= */ true);
        MainContentCaptureSession session = createSession(options);
        session.mDirectServiceInterface = mMockContentCaptureDirectManager;
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        notifyContentCaptureEvents(session);
        mTestableLooper.processAllMessages();

        verifyZeroInteractions(mMockContentCaptureDirectManager);
        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mEvents).isNull();
    }

    @Test
    @SuppressWarnings("GuardedBy")
    public void notifyContentCaptureEvents_started_ContentCaptureEnabled_ProtectionEnabled()
            throws RemoteException {
        ContentCaptureOptions options =
                createOptions(
                        /* enableContentCaptureReceiver= */ true,
                        /* enableContentProtectionReceiver= */ true);
        MainContentCaptureSession session = createSession(options);
        session.mDirectServiceInterface = mMockContentCaptureDirectManager;

        session.onSessionStarted(0x2, null);
        // Override the processor for interaction verification.
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;
        notifyContentCaptureEvents(session);
        mTestableLooper.processAllMessages();

        // Force flush will happen twice.
        verify(mMockContentCaptureDirectManager, times(1))
                .sendEvents(any(), eq(FLUSH_REASON_VIEW_TREE_APPEARING), any());
        verify(mMockContentCaptureDirectManager, times(1))
                .sendEvents(any(), eq(FLUSH_REASON_VIEW_TREE_APPEARED), any());
        // Other than the five view events, there will be two additional tree appearing events.
        verify(mMockContentProtectionEventProcessor, times(7)).processEvent(any());
        assertThat(session.mEvents).isEmpty();
    }

    /** Simulates the regular content capture events sequence. */
    private void notifyContentCaptureEvents(final MainContentCaptureSession session) {
        final ArrayList<Object> events = new ArrayList<>(
                List.of(
                        prepareView(session),
                        prepareView(session),
                        new AutofillId(0),
                        prepareView(session),
                        Insets.of(0, 0, 0, 0)
                )
        );

        final SparseArray<ArrayList<Object>> contentCaptureEvents = new SparseArray<>();
        contentCaptureEvents.set(session.getId(), events);

        session.notifyContentCaptureEvents(contentCaptureEvents);
    }

    private View prepareView(final MainContentCaptureSession session) {
        final View view = new View(sContext);
        view.setContentCaptureSession(session);
        return view;
    }

    private static ContentCaptureOptions createOptions(
            boolean enableContentCaptureReceiver,
            ContentCaptureOptions.ContentProtectionOptions contentProtectionOptions) {
        return new ContentCaptureOptions(
                /* loggingLevel= */ 0,
                BUFFER_SIZE,
                /* idleFlushingFrequencyMs= */ 0,
                /* textChangeFlushingFrequencyMs= */ 0,
                /* logHistorySize= */ 0,
                /* disableFlushForViewTreeAppearing= */ false,
                enableContentCaptureReceiver,
                contentProtectionOptions,
                /* whitelistedComponents= */ null);
    }

    private static ContentCaptureOptions createOptions(
            boolean enableContentCaptureReceiver, boolean enableContentProtectionReceiver) {
        return createOptions(
                enableContentCaptureReceiver,
                new ContentCaptureOptions.ContentProtectionOptions(
                        enableContentProtectionReceiver,
                        BUFFER_SIZE,
                        /* requiredGroups= */ List.of(List.of("a")),
                        /* optionalGroups= */ Collections.emptyList(),
                        /* optionalGroupsThreshold= */ 0));
    }

    private ContentCaptureManager createManager(ContentCaptureOptions options) {
        return new ContentCaptureManager(sContext, mMockSystemServerInterface, options);
    }

    private MainContentCaptureSession createSession(ContentCaptureManager manager) {
        MainContentCaptureSession session =
                new MainContentCaptureSession(
                        sStrippedContext,
                        manager,
                        Handler.createAsync(mTestableLooper.getLooper()),
                        mMockSystemServerInterface);
        session.mComponentName = COMPONENT_NAME;
        return session;
    }

    private MainContentCaptureSession createSession(ContentCaptureOptions options) {
        return createSession(createManager(options));
    }

    private MainContentCaptureSession createSession(
            boolean enableContentCaptureReceiver, boolean enableContentProtectionReceiver) {
        return createSession(
                createOptions(enableContentCaptureReceiver, enableContentProtectionReceiver));
    }

    private MainContentCaptureSession createSession() {
        return createSession(
                /* enableContentCaptureReceiver= */ true,
                /* enableContentProtectionReceiver= */ true);
    }

    private void assertEventFlushedContentCapture(ContentCaptureOptions options) throws Exception {
        ArgumentCaptor<ParceledListSlice> captor = ArgumentCaptor.forClass(ParceledListSlice.class);
        verify(mMockContentCaptureDirectManager)
                .sendEvents(captor.capture(), eq(REASON), eq(options));

        assertThat(captor.getValue()).isNotNull();
        List<ContentCaptureEvent> actual = captor.getValue().getList();
        assertThat(actual).isNotNull();
        assertThat(actual).containsExactly(EVENT);
    }
}
