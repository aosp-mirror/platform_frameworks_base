/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.view.IWindowManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link WindowContextController}
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:WindowContextControllerTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WindowContextControllerTest {
    private WindowContextController mController;
    private IWindowManager mMockWms;

    @Before
    public void setUp() throws Exception {
        mMockWms = mock(IWindowManager.class);
        mController = new WindowContextController(new Binder(), mMockWms);

        doReturn(true).when(mMockWms).registerWindowContextListener(
                any(), anyInt(), anyInt(), any());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testRegisterListenerTwiceThrowException() {
        mController.registerListener(TYPE_APPLICATION_OVERLAY, DEFAULT_DISPLAY,
                null /* options */);
        mController.registerListener(TYPE_APPLICATION_OVERLAY, DEFAULT_DISPLAY,
                null /* options */);
    }

    @Test
    public void testUnregisterListenerIfNeeded_NotRegisteredYet_DoNothing() throws Exception {
        mController.unregisterListenerIfNeeded();

        verify(mMockWms, never()).registerWindowContextListener(any(), anyInt(), anyInt(), any());
    }

    @Test
    public void testRegisterAndUnRegisterListener() {
        mController.registerListener(TYPE_APPLICATION_OVERLAY, DEFAULT_DISPLAY,
                null /* options */);

        assertThat(mController.mListenerRegistered).isTrue();

        mController.unregisterListenerIfNeeded();

        assertThat(mController.mListenerRegistered).isFalse();
    }
}
