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
    private boolean mOnScrollCaptureEndCalled;
    private CancellationSignal mLastCancellationSignal;

    @Override
    public void onScrollCaptureSearch(@NonNull CancellationSignal signal,
            @NonNull Consumer<Rect> onReady) {
        mLastCancellationSignal = signal;
        mSearchConsumer = onReady;
        mModCount++;
    }

    @Override
    public void onScrollCaptureStart(@NonNull ScrollCaptureSession session,
            @NonNull CancellationSignal signal, @NonNull Runnable onReady) {
        mLastCancellationSignal = signal;
        mStartOnReady = onReady;
        mModCount++;
    }

    @Override
    public void onScrollCaptureImageRequest(@NonNull ScrollCaptureSession session,
            @NonNull CancellationSignal signal, @NonNull Rect captureArea,
            @NonNull Consumer<Rect> onComplete) {
        mLastCancellationSignal = signal;
        mImageOnComplete = onComplete;
        mModCount++;
    }

    @Override
    public void onScrollCaptureEnd(@NonNull Runnable onReady) {
        mOnEndReady = onReady;
        mOnScrollCaptureEndCalled = true;
    }

    public boolean onScrollCaptureEndCalled() {
        return mOnScrollCaptureEndCalled;
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
        if (mLastCancellationSignal != null && !mLastCancellationSignal.isCanceled()) {
            mStartOnReady.run();
        }
    }

    void completeImageRequest(Rect captured) {
        assertNotNull("Did not receive image request", mImageOnComplete);
        if (mLastCancellationSignal != null && !mLastCancellationSignal.isCanceled()) {
            mImageOnComplete.accept(captured);
        }
    }

    void completeEndRequest() {
        assertNotNull("Did not receive end request", mOnEndReady);
        if (mLastCancellationSignal != null && !mLastCancellationSignal.isCanceled()) {
            mOnEndReady.run();
        }
    }

    public CancellationSignal getLastCancellationSignal() {
        return mLastCancellationSignal;
    }
}
