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

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertThrows;

import android.content.ContentCaptureOptions;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit test for {@link ContentCaptureManager}.
 *
 * <p>To run it:
 * {@code atest FrameworksCoreTests:android.view.contentcapture.ContentCaptureManagerTest}
 */
@RunWith(MockitoJUnitRunner.class)
public class ContentCaptureManagerTest {

    @Mock
    private Context mMockContext;

    @Test
    public void testConstructor_invalidParametersThrowsException() {
        assertThrows(NullPointerException.class,
                () -> new ContentCaptureManager(mMockContext, /* service= */ null, /* options= */
                        null));
    }

    @Test
    public void testRemoveData_invalidParametersThrowsException() {
        final IContentCaptureManager mockService = mock(IContentCaptureManager.class);
        final ContentCaptureOptions options = new ContentCaptureOptions(null);
        final ContentCaptureManager manager =
                new ContentCaptureManager(mMockContext, mockService, options);

        assertThrows(NullPointerException.class, () -> manager.removeData(null));
    }
}
