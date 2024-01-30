/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.speech;

import android.content.ComponentName;
import android.content.Intent;
import android.util.CloseGuard;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.Reference;
import java.util.concurrent.Executor;

/**
 * @hide
 */
class SpeechRecognizerProxy extends SpeechRecognizer {

    private final CloseGuard mCloseGuard = new CloseGuard();

    private final SpeechRecognizer mDelegate;

    SpeechRecognizerProxy(final SpeechRecognizer delegate) {
        mDelegate = delegate;
        mCloseGuard.open("SpeechRecognizer#destroy()");
    }

    @Override
    public void setRecognitionListener(RecognitionListener listener) {
        mDelegate.setRecognitionListener(listener);
    }

    @Override
    public void startListening(Intent recognizerIntent) {
        mDelegate.startListening(recognizerIntent);
    }

    @Override
    public void stopListening() {
        mDelegate.stopListening();
    }

    @Override
    public void cancel() {
        mDelegate.cancel();
    }

    @Override
    public void destroy() {
        try {
            mCloseGuard.close();
            mDelegate.destroy();
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    @Override
    public void checkRecognitionSupport(
            @NonNull Intent recognizerIntent,
            @NonNull Executor executor,
            @NonNull RecognitionSupportCallback supportListener) {
        mDelegate.checkRecognitionSupport(recognizerIntent, executor, supportListener);
    }

    @Override
    public void triggerModelDownload(@NonNull Intent recognizerIntent) {
        mDelegate.triggerModelDownload(recognizerIntent);
    }

    @Override
    public void triggerModelDownload(
            @NonNull Intent recognizerIntent,
            @NonNull Executor executor,
            @NonNull ModelDownloadListener listener) {
        mDelegate.triggerModelDownload(recognizerIntent, executor, listener);
    }

    @Override
    public void setTemporaryOnDeviceRecognizer(@Nullable ComponentName componentName) {
        mDelegate.setTemporaryOnDeviceRecognizer(componentName);
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            mCloseGuard.warnIfOpen();
            destroy();
        } finally {
            super.finalize();
        }
    }
}
