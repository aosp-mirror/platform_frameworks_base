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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.os.Looper;
import android.view.contentprotection.ContentProtectionEventProcessor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test for {@link MainContentCaptureSession}.
 *
 * <p>Run with: {@code atest
 * FrameworksCoreTests:android.view.contentcapture.MainContentCaptureSessionTest}
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
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

    @Rule public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private IContentCaptureManager mMockSystemServerInterface;

    @Mock private ContentProtectionEventProcessor mMockContentProtectionEventProcessor;

    @Mock private IContentCaptureDirectManager mMockContentCaptureDirectManager;

    @Test
    public void onSessionStarted_contentProtectionEnabled_processorCreated() {
        MainContentCaptureSession session = createSession();
        assertThat(session.mContentProtectionEventProcessor).isNull();

        session.onSessionStarted(/* resultCode= */ 0, /* binder= */ null);

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

        assertThat(session.mContentProtectionEventProcessor).isNull();
        verifyZeroInteractions(mMockContentProtectionEventProcessor);
    }

    @Test
    public void onSessionStarted_contentProtectionNoBuffer_processorNotCreated() {
        ContentCaptureOptions options =
                createOptions(
                        /* enableContentCaptureReceiver= */ true,
                        new ContentCaptureOptions.ContentProtectionOptions(
                                /* enableReceiver= */ true, -BUFFER_SIZE));
        MainContentCaptureSession session = createSession(options);
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.onSessionStarted(/* resultCode= */ 0, /* binder= */ null);

        assertThat(session.mContentProtectionEventProcessor).isNull();
        verifyZeroInteractions(mMockContentProtectionEventProcessor);
    }

    @Test
    public void onSessionStarted_noComponentName_processorNotCreated() {
        MainContentCaptureSession session = createSession();
        session.mComponentName = null;

        session.onSessionStarted(/* resultCode= */ 0, /* binder= */ null);

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

        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mEvents).isNotNull();
        assertThat(session.mEvents).containsExactly(EVENT);
    }

    @Test
    public void sendEvent_contentCaptureEnabled_contentProtectionEnabled() {
        MainContentCaptureSession session = createSession();
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.sendEvent(EVENT);

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

        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mEvents).isEmpty();
        assertEventFlushedContentCapture(options);
    }

    @Test
    public void destroySession() throws Exception {
        MainContentCaptureSession session = createSession();
        session.mContentProtectionEventProcessor = mMockContentProtectionEventProcessor;

        session.destroySession();

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

        verifyZeroInteractions(mMockSystemServerInterface);
        verifyZeroInteractions(mMockContentProtectionEventProcessor);
        assertThat(session.mDirectServiceInterface).isNull();
        assertThat(session.mContentProtectionEventProcessor).isNull();
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
                        enableContentProtectionReceiver, BUFFER_SIZE));
    }

    private ContentCaptureManager createManager(ContentCaptureOptions options) {
        return new ContentCaptureManager(sContext, mMockSystemServerInterface, options);
    }

    private MainContentCaptureSession createSession(ContentCaptureManager manager) {
        MainContentCaptureSession session =
                new MainContentCaptureSession(
                        sStrippedContext,
                        manager,
                        new Handler(Looper.getMainLooper()),
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
