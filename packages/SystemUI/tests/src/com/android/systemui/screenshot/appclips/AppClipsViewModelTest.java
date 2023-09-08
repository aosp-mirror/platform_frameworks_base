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

package com.android.systemui.screenshot.appclips;

import static android.content.Intent.CAPTURE_CONTENT_FOR_NOTE_FAILED;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.view.Display;

import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.screenshot.ImageExporter;

import com.google.common.util.concurrent.Futures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

@RunWith(AndroidJUnit4.class)
public final class AppClipsViewModelTest extends SysuiTestCase {

    private static final Bitmap FAKE_BITMAP = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
    private static final Drawable FAKE_DRAWABLE = new ShapeDrawable();
    private static final Rect FAKE_RECT = new Rect();
    private static final Uri FAKE_URI = Uri.parse("www.test-uri.com");
    private static final UserHandle USER_HANDLE = Process.myUserHandle();

    @Mock private AppClipsCrossProcessHelper mAppClipsCrossProcessHelper;
    @Mock private ImageExporter mImageExporter;
    private AppClipsViewModel mViewModel;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mViewModel = new AppClipsViewModel.Factory(mAppClipsCrossProcessHelper, mImageExporter,
                getContext().getMainExecutor(), directExecutor()).create(AppClipsViewModel.class);
    }

    @Test
    public void performScreenshot_fails_shouldUpdateErrorWithFailed() {
        when(mAppClipsCrossProcessHelper.takeScreenshot()).thenReturn(null);

        mViewModel.performScreenshot();
        waitForIdleSync();

        verify(mAppClipsCrossProcessHelper).takeScreenshot();
        assertThat(mViewModel.getErrorLiveData().getValue())
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_FAILED);
        assertThat(mViewModel.getResultLiveData().getValue()).isNull();
    }

    @Test
    public void performScreenshot_succeeds_shouldUpdateScreenshotWithBitmap() {
        when(mAppClipsCrossProcessHelper.takeScreenshot()).thenReturn(FAKE_BITMAP);

        mViewModel.performScreenshot();
        waitForIdleSync();

        verify(mAppClipsCrossProcessHelper).takeScreenshot();
        assertThat(mViewModel.getErrorLiveData().getValue()).isNull();
        assertThat(mViewModel.getScreenshot().getValue()).isEqualTo(FAKE_BITMAP);
    }

    @Test
    public void saveScreenshot_throwsError_shouldUpdateErrorWithFailed() {
        when(mImageExporter.export(any(Executor.class), any(UUID.class), eq(null), eq(USER_HANDLE),
                eq(Display.DEFAULT_DISPLAY))).thenReturn(
                Futures.immediateFailedFuture(new ExecutionException(new Throwable())));

        mViewModel.saveScreenshotThenFinish(FAKE_DRAWABLE, FAKE_RECT, USER_HANDLE);
        waitForIdleSync();

        assertThat(mViewModel.getErrorLiveData().getValue())
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_FAILED);
        assertThat(mViewModel.getResultLiveData().getValue()).isNull();
    }

    @Test
    public void saveScreenshot_failsSilently_shouldUpdateErrorWithFailed() {
        when(mImageExporter.export(any(Executor.class), any(UUID.class), eq(null), eq(USER_HANDLE),
                eq(Display.DEFAULT_DISPLAY))).thenReturn(
                Futures.immediateFuture(new ImageExporter.Result()));

        mViewModel.saveScreenshotThenFinish(FAKE_DRAWABLE, FAKE_RECT, USER_HANDLE);
        waitForIdleSync();

        assertThat(mViewModel.getErrorLiveData().getValue())
                .isEqualTo(CAPTURE_CONTENT_FOR_NOTE_FAILED);
        assertThat(mViewModel.getResultLiveData().getValue()).isNull();
    }

    @Test
    public void saveScreenshot_succeeds_shouldUpdateResultWithUri() {
        ImageExporter.Result result = new ImageExporter.Result();
        result.uri = FAKE_URI;
        when(mImageExporter.export(any(Executor.class), any(UUID.class), eq(null), eq(USER_HANDLE),
                eq(Display.DEFAULT_DISPLAY))).thenReturn(Futures.immediateFuture(result));

        mViewModel.saveScreenshotThenFinish(FAKE_DRAWABLE, FAKE_RECT, USER_HANDLE);
        waitForIdleSync();

        assertThat(mViewModel.getErrorLiveData().getValue()).isNull();
        assertThat(mViewModel.getResultLiveData().getValue()).isEqualTo(FAKE_URI);
    }
}
