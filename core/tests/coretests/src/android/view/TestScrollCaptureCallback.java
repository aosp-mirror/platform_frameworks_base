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

package android.view;

import static org.junit.Assert.*;

import android.graphics.Rect;
import android.os.CancellationSignal;

import androidx.annotation.NonNull;

import java.util.function.Consumer;

class TestScrollCaptureCallback implements ScrollCaptureCallback {
    private Consumer<Rect> mSearchConsumer;
    private Runnable mStartOnReady;
    private Consumer<Rect> mImageOnComplete;
    private Runnable mOnEndReady;
    private volatile int mModCount;

    @Override
    public void onScrollCaptureSearch(@NonNull CancellationSignal signal,
            @NonNull Consumer<Rect> onReady) {
        mSearchConsumer = onReady;
        mModCount++;
    }

    @Override
    public void onScrollCaptureStart(@NonNull ScrollCaptureSession session,
            @NonNull CancellationSignal signal, @NonNull Runnable onReady) {
        mStartOnReady = onReady;
        mModCount++;
    }

    @Override
    public void onScrollCaptureImageRequest(@NonNull ScrollCaptureSession session,
            @NonNull CancellationSignal signal, @NonNull Rect captureArea,
            @NonNull Consumer<Rect> onComplete) {
        mImageOnComplete = onComplete;
        mModCount++;
    }

    @Override
    public void onScrollCaptureEnd(@NonNull Runnable onReady) {
        mOnEndReady = onReady;
    }

    void completeSearchRequest(Rect scrollBounds) {
        assertNotNull("Did not receive search request", mSearchConsumer);
        mSearchConsumer.accept(scrollBounds);
        mModCount++;
    }

    void verifyZeroInteractions() {
        assertEquals("Expected zero interactions", 0, mModCount);
    }

    void completeStartRequest() {
        assertNotNull("Did not receive start request", mStartOnReady);
        mStartOnReady.run();
    }

    void completeImageRequest(Rect captured) {
        assertNotNull("Did not receive image request", mImageOnComplete);
        mImageOnComplete.accept(captured);
    }

    void completeEndRequest() {
        assertNotNull("Did not receive end request", mOnEndReady);
        mOnEndReady.run();
    }
}
