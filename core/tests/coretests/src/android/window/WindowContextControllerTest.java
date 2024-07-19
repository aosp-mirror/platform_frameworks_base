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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.os.Binder;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

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

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    private WindowContextController mController;
    @Mock
    private WindowTokenClientController mWindowTokenClientController;
    @Mock
    private WindowTokenClient mMockToken;

    @Before
    public void setUp() throws Exception {
        mController = spy(new WindowContextController(mMockToken));
        doReturn(mWindowTokenClientController).when(mController).getWindowTokenClientController();
        doNothing().when(mMockToken).onConfigurationChanged(any(), anyInt(), anyBoolean());
        doReturn(true).when(mWindowTokenClientController).attachToDisplayArea(
                eq(mMockToken), anyInt(), anyInt(), any());
    }

    @Test(expected = IllegalStateException.class)
    public void testAttachToDisplayAreaTwiceThrowException() {
        mController.attachToDisplayArea(TYPE_APPLICATION_OVERLAY, DEFAULT_DISPLAY,
                null /* options */);
        mController.attachToDisplayArea(TYPE_APPLICATION_OVERLAY, DEFAULT_DISPLAY,
                null /* options */);
    }

    @Test
    public void testDetachIfNeeded_NotAttachedYet_DoNothing() {
        mController.detachIfNeeded();

        verify(mWindowTokenClientController, never()).detachIfNeeded(any());
    }

    @Test
    public void testAttachAndDetachDisplayArea() {
        mController.attachToDisplayArea(TYPE_APPLICATION_OVERLAY, DEFAULT_DISPLAY,
                null /* options */);

        assertThat(mController.mAttachedToDisplayArea).isEqualTo(
                WindowContextController.AttachStatus.STATUS_ATTACHED);

        mController.detachIfNeeded();

        assertThat(mController.mAttachedToDisplayArea).isEqualTo(
                WindowContextController.AttachStatus.STATUS_DETACHED);
    }

    @Test(expected = IllegalStateException.class)
    public void testAttachToWindowTokenBeforeAttachingToDAThrowException() {
        mController.attachToWindowToken(new Binder());
    }
}
