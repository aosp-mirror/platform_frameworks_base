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

import android.view.autofill.AutofillId;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit test for {@link ContentCaptureSessionTest}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:android.view.contentcapture.ContentCaptureSessionTest}
 */
@RunWith(MockitoJUnitRunner.class)
public class ContentCaptureSessionTest {

    /**
     * Uses a spy as ContentCaptureSession is abstract but (so far) we're testing its concrete
     * methods.
     */
    @Spy
    private ContentCaptureSession mMockSession;

    @Test
    public void testNewAutofillId_invalid() {
        assertThrows(NullPointerException.class, () -> mMockSession.newAutofillId(null, 42));
        assertThrows(IllegalArgumentException.class,
                () -> mMockSession.newAutofillId(new AutofillId(42, 42), 42));
    }

    @Test
    public void testNewAutofillId_valid() {
        final AutofillId parentId = new AutofillId(42);
        final AutofillId childId = mMockSession.newAutofillId(parentId, 108);
        assertThat(childId.getViewId()).isEqualTo(42);
        assertThat(childId.getVirtualChildId()).isEqualTo(108);
        // TODO(b/121197119): assert session id
    }

    @Test
    public void testNotifyXXX_null() {
        assertThrows(NullPointerException.class, () -> mMockSession.notifyViewAppeared(null));
        assertThrows(NullPointerException.class, () -> mMockSession.notifyViewDisappeared(null));
        assertThrows(NullPointerException.class,
                () -> mMockSession.notifyViewTextChanged(null, "whatever", 0));
    }
}
