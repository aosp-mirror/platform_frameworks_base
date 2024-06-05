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

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.UserHandle;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.screenshot.ImageExporter;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** A {@link ViewModel} to help with the App Clips screenshot flow. */
final class AppClipsViewModel extends ViewModel {

    private final AppClipsCrossProcessHelper mAppClipsCrossProcessHelper;
    private final ImageExporter mImageExporter;
    @Main
    private final Executor mMainExecutor;
    @Background
    private final Executor mBgExecutor;

    private final MutableLiveData<Bitmap> mScreenshotLiveData;
    private final MutableLiveData<Uri> mResultLiveData;
    private final MutableLiveData<Integer> mErrorLiveData;

    AppClipsViewModel(AppClipsCrossProcessHelper appClipsCrossProcessHelper,
            ImageExporter imageExporter, @Main Executor mainExecutor,
            @Background Executor bgExecutor) {
        mAppClipsCrossProcessHelper = appClipsCrossProcessHelper;
        mImageExporter = imageExporter;
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;

        mScreenshotLiveData = new MutableLiveData<>();
        mResultLiveData = new MutableLiveData<>();
        mErrorLiveData = new MutableLiveData<>();
    }

    /** Grabs a screenshot and updates the {@link Bitmap} set in screenshot {@link LiveData}. */
    void performScreenshot() {
        mBgExecutor.execute(() -> {
            Bitmap screenshot = mAppClipsCrossProcessHelper.takeScreenshot();
            mMainExecutor.execute(() -> {
                if (screenshot == null) {
                    mErrorLiveData.setValue(CAPTURE_CONTENT_FOR_NOTE_FAILED);
                } else {
                    mScreenshotLiveData.setValue(screenshot);
                }
            });
        });
    }

    /** Returns a {@link LiveData} that holds the captured screenshot. */
    LiveData<Bitmap> getScreenshot() {
        return mScreenshotLiveData;
    }

    /** Returns a {@link LiveData} that holds the {@link Uri} where screenshot is saved. */
    LiveData<Uri> getResultLiveData() {
        return mResultLiveData;
    }

    /**
     * Returns a {@link LiveData} that holds the error codes for
     * {@link Intent#EXTRA_CAPTURE_CONTENT_FOR_NOTE_STATUS_CODE}.
     */
    LiveData<Integer> getErrorLiveData() {
        return mErrorLiveData;
    }

    /**
     * Saves the provided {@link Drawable} to storage then informs the result {@link Uri} to
     * {@link LiveData}.
     */
    void saveScreenshotThenFinish(Drawable screenshotDrawable, Rect bounds, UserHandle user) {
        mBgExecutor.execute(() -> {
            // Render the screenshot bitmap in background.
            Bitmap screenshotBitmap = renderBitmap(screenshotDrawable, bounds);

            // Export and save the screenshot in background.
            ListenableFuture<ImageExporter.Result> exportFuture = mImageExporter.export(mBgExecutor,
                    UUID.randomUUID(), screenshotBitmap, user, Display.DEFAULT_DISPLAY);

            // Get the result and update state on main thread.
            exportFuture.addListener(() -> {
                try {
                    ImageExporter.Result result = exportFuture.get();
                    if (result.uri == null) {
                        mErrorLiveData.setValue(CAPTURE_CONTENT_FOR_NOTE_FAILED);
                        return;
                    }

                    mResultLiveData.setValue(result.uri);
                } catch (CancellationException | InterruptedException | ExecutionException e) {
                    mErrorLiveData.setValue(CAPTURE_CONTENT_FOR_NOTE_FAILED);
                }
            }, mMainExecutor);
        });
    }

    private static Bitmap renderBitmap(Drawable drawable, Rect bounds) {
        final RenderNode output = new RenderNode("Screenshot save");
        output.setPosition(0, 0, bounds.width(), bounds.height());
        RecordingCanvas canvas = output.beginRecording();
        canvas.translate(-bounds.left, -bounds.top);
        canvas.clipRect(bounds);
        drawable.draw(canvas);
        output.endRecording();
        return HardwareRenderer.createHardwareBitmap(output, bounds.width(), bounds.height());
    }

    /** Helper factory to help with injecting {@link AppClipsViewModel}. */
    static final class Factory implements ViewModelProvider.Factory {

        private final AppClipsCrossProcessHelper mAppClipsCrossProcessHelper;
        private final ImageExporter mImageExporter;
        @Main
        private final Executor mMainExecutor;
        @Background
        private final Executor mBgExecutor;

        @Inject
        Factory(AppClipsCrossProcessHelper appClipsCrossProcessHelper,  ImageExporter imageExporter,
                @Main Executor mainExecutor, @Background Executor bgExecutor) {
            mAppClipsCrossProcessHelper = appClipsCrossProcessHelper;
            mImageExporter = imageExporter;
            mMainExecutor = mainExecutor;
            mBgExecutor = bgExecutor;
        }

        @NonNull
        @Override
        public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            if (modelClass != AppClipsViewModel.class) {
                throw new IllegalArgumentException();
            }

            //noinspection unchecked
            return (T) new AppClipsViewModel(mAppClipsCrossProcessHelper, mImageExporter,
                    mMainExecutor, mBgExecutor);
        }
    }
}
