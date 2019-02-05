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

import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for {@link ContentCaptureSession}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:android.view.contentcapture.ContentCaptureSessionTest}
 */
@RunWith(MockitoJUnitRunner.class)
public class ContentCaptureSessionTest {

    private ContentCaptureSession mSession1 = new MyContentCaptureSession("111");

    private ContentCaptureSession mSession2 = new MyContentCaptureSession("2222");

    @Mock
    private View mMockView;

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
        assertThat(childId.getSessionId()).isEqualTo(mSession1.getIdAsInt());
    }

    @Test
    public void testNewAutofillId_differentSessions() {
        assertThat(mSession1.getIdAsInt()).isNotSameAs(mSession2.getIdAsInt()); //sanity check
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
                () -> mSession1.notifyViewTextChanged(null, "whatever"));
    }

    @Test
    public void testNewViewStructure() {
        assertThat(mMockView.getAutofillId()).isNotNull(); // sanity check
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

    // Cannot use @Spy because we need to pass the session id on constructor
    private class MyContentCaptureSession extends ContentCaptureSession {

        private MyContentCaptureSession(String id) {
            super(id);
        }

        @Override
        MainContentCaptureSession getMainCaptureSession() {
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
        void internalNotifyViewAppeared(ViewStructureImpl node) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        void internalNotifyViewDisappeared(AutofillId id) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        void internalNotifyViewTextChanged(AutofillId id, CharSequence text) {
            throw new UnsupportedOperationException("should not have been called");
        }

        @Override
        public void internalNotifyViewHierarchyEvent(boolean started) {
            throw new UnsupportedOperationException("should not have been called");
        }
    }
}
