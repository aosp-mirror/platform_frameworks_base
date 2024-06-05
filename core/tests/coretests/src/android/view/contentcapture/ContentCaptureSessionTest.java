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

package android.view.contentcapture;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Map;

/**
 * Unit tests for {@link ContentCaptureSession}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:android.view.contentcapture.ContentCaptureSessionTest}
 */
@RunWith(MockitoJUnitRunner.class)
public class ContentCaptureSessionTest {
    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private ContentCaptureSession mSession1 = new MyContentCaptureSession(111);

    private ContentCaptureSession mSession2 = new MyContentCaptureSession(2222);

    @Mock
    private View mMockView;

    @DisableCompatChanges({ContentCaptureSession.NOTIFY_NODES_DISAPPEAR_NOW_SENDS_TREE_EVENTS})
    @Test
    public void testNewAutofillId_invalid() {
        assertThrows(NullPointerException.class, () -> mSession1.newAutofillId(null, 42L));
        assertThrows(IllegalArgumentException.class,
                () -> mSession1.newAutofillId(new AutofillId(42, 42), 42L));
    }

    @Test
    public void testNewAutofillId_valid() {
        final AutofillId parentId = new AutofillId(42);
        final AutofillId childId = mSession1.newAutofillId(parentId, 108L);
        assertThat(childId.getViewId()).isEqualTo(42);
        assertThat(childId.getVirtualChildLongId()).isEqualTo(108L);
        assertThat(childId.getVirtualChildIntId()).isEqualTo(View.NO_ID);
        assertThat(childId.getSessionId()).isEqualTo(mSession1.getId());
    }

    @Test
    public void testNewAutofillId_differentSessions() {
        assertThat(mSession1.getId()).isNotEqualTo(mSession2.getId()); //validity check
        final AutofillId parentId = new AutofillId(42);
        final AutofillId childId1 = mSession1.newAutofillId(parentId, 108L);
        final AutofillId childId2 = mSession2.newAutofillId(parentId, 108L);
        assertThat(childId1).isNotEqualTo(childId2);
        assertThat(childId2).isNotEqualTo(childId1);
    }

    @Test
    public void testNotifyXXX_null() {
        assertThrows(NullPointerException.class, () -> mSession1.notifyViewAppeared(null));
        assertThrows(NullPointerException.class, () -> mSession1.notifyViewDisappeared(null));
        assertThrows(NullPointerException.class,
                () -> mSession1.notifyViewsAppeared(null));
        assertThrows(NullPointerException.class,
                () -> mSession1.notifyViewTextChanged(null, "whatever"));
    }

    @Test
    public void testNewViewStructure() {
        assertThat(mMockView.getAutofillId()).isNotNull(); // validity check
        final ViewStructure structure = mSession1.newViewStructure(mMockView);
        assertThat(structure).isNotNull();
        assertThat(structure.getAutofillId()).isEqualTo(mMockView.getAutofillId());
    }

    @Test
    public void testNewVirtualViewStructure() {
        final AutofillId parentId = new AutofillId(42);
        final ViewStructure structure = mSession1.newVirtualViewStructure(parentId, 108L);
        assertThat(structure).isNotNull();
        final AutofillId childId = mSession1.newAutofillId(parentId, 108L);
        assertThat(structure.getAutofillId()).isEqualTo(childId);
    }

    @Test
    public void testNotifyViewsDisappeared_invalid() {
        // Null parent
        assertThrows(NullPointerException.class,
                () -> mSession1.notifyViewsDisappeared(null, new long[] {42}));
        // Null child
        assertThrows(IllegalArgumentException.class,
                () -> mSession1.notifyViewsDisappeared(new AutofillId(42), null));
        // Empty child
        assertThrows(IllegalArgumentException.class,
                () -> mSession1.notifyViewsDisappeared(new AutofillId(42), new long[] {}));
        // Virtual parent
        assertThrows(IllegalArgumentException.class,
                () -> mSession1.notifyViewsDisappeared(new AutofillId(42, 108), new long[] {666}));
    }

    @Ignore("b/286134492")
    @Test
    public void testNotifyViewsDisappeared_noSendTreeEventBeforeU() {
        MyContentCaptureSession session = new MyContentCaptureSession(121);
        session.notifyViewsDisappeared(new AutofillId(42), new long[] {42});

        assertThat(session.mInternalNotifyViewTreeEventStartedCount).isEqualTo(0);
        assertThat(session.mInternalNotifyViewTreeEventFinishedCount).isEqualTo(0);
    }

    @Ignore("b/286134492")
    @EnableCompatChanges({ContentCaptureSession.NOTIFY_NODES_DISAPPEAR_NOW_SENDS_TREE_EVENTS})
    @Test
    public void testNotifyViewsDisappeared_sendTreeEventSinceU() {
        MyContentCaptureSession session = new MyContentCaptureSession(122);
        session.notifyViewsDisappeared(new AutofillId(42), new long[] {42});

        assertThat(session.mInternalNotifyViewTreeEventStartedCount).isEqualTo(1);
        assertThat(session.mInternalNotifyViewTreeEventFinishedCount).isEqualTo(1);
    }

    @Test
    public void testGetFlushReasonAsString() {
        int invalidFlushReason = ContentCaptureSession.FLUSH_REASON_VIEW_TREE_APPEARED + 1;
        Map<Integer, String> expectedMap =
                new ImmutableMap.Builder<Integer, String>()
                        .put(ContentCaptureSession.FLUSH_REASON_FULL, "FULL")
                        .put(ContentCaptureSession.FLUSH_REASON_VIEW_ROOT_ENTERED, "VIEW_ROOT")
                        .put(ContentCaptureSession.FLUSH_REASON_SESSION_STARTED, "STARTED")
                        .put(ContentCaptureSession.FLUSH_REASON_SESSION_FINISHED, "FINISHED")
                        .put(ContentCaptureSession.FLUSH_REASON_IDLE_TIMEOUT, "IDLE")
                        .put(ContentCaptureSession.FLUSH_REASON_TEXT_CHANGE_TIMEOUT, "TEXT_CHANGE")
                        .put(ContentCaptureSession.FLUSH_REASON_SESSION_CONNECTED, "CONNECTED")
                        .put(ContentCaptureSession.FLUSH_REASON_FORCE_FLUSH, "FORCE_FLUSH")
                        .put(
                                ContentCaptureSession.FLUSH_REASON_VIEW_TREE_APPEARING,
                                "VIEW_TREE_APPEARING")
                        .put(
                                ContentCaptureSession.FLUSH_REASON_VIEW_TREE_APPEARED,
                                "VIEW_TREE_APPEARED")
                        .put(invalidFlushReason, "UNKNOWN-" + invalidFlushReason)
                        .build();

        expectedMap.forEach(
                (reason, expected) ->
                        assertThat(ContentCaptureSession.getFlushReasonAsString(reason))
                                .isEqualTo(expected));
    }

    // Cannot use @Spy because we need to pass the session id on constructor
    private class MyContentCaptureSession extends ContentCaptureSession {
        int mInternalNotifyViewTreeEventStartedCount = 0;
        int mInternalNotifyViewTreeEventFinishedCount = 0;

        private MyContentCaptureSession(int id) {
            super(id);
        }

        @Override
        MainContentCaptureSession getMainCaptureSession() {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        void start(@NonNull IBinder token, @NonNull IBinder shareableActivityToken,
                @NonNull ComponentName component, int flags) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        boolean isDisabled() {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        boolean setDisabled(boolean disabled) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        ContentCaptureSession newChild(ContentCaptureContext context) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        void flush(int reason) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        void onDestroy() {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        void internalNotifyViewAppeared(final int sessionId, ViewStructureImpl node) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        void internalNotifyViewDisappeared(final int sessionId, AutofillId id) {}

        @Override
        void internalNotifyViewTextChanged(final int sessionId, AutofillId id, CharSequence text) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        public void internalNotifyViewTreeEvent(final int sessionId, boolean started) {
            if (started) {
                mInternalNotifyViewTreeEventStartedCount += 1;
            } else {
                mInternalNotifyViewTreeEventFinishedCount += 1;
            }
        }

        @Override
        void internalNotifySessionResumed() {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        void internalNotifySessionPaused() {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        void internalNotifyChildSessionStarted(int parentSessionId, int childSessionId,
                @NonNull ContentCaptureContext clientContext) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        void internalNotifyChildSessionFinished(int parentSessionId, int childSessionId) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        void internalNotifyContextUpdated(int sessionId, @Nullable ContentCaptureContext context) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        public void notifyWindowBoundsChanged(int sessionId, @NonNull Rect bounds) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        public void notifyContentCaptureEvents(
                @NonNull SparseArray<ArrayList<Object>> contentCaptureEvents) {

        }

        @Override
        void internalNotifyViewInsetsChanged(final int sessionId, Insets viewInsets) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        public void updateContentCaptureContext(ContentCaptureContext context) {
            throw new UnsupportedOperationException("should not have been called");
        }
    }
}
