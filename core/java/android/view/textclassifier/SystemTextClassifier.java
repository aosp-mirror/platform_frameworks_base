/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view.textclassifier;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.content.Context;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.textclassifier.ITextClassificationCallback;
import android.service.textclassifier.ITextClassifierService;
import android.service.textclassifier.ITextLinksCallback;
import android.service.textclassifier.ITextSelectionCallback;

import com.android.internal.util.Preconditions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Proxy to the system's default TextClassifier.
 */
final class SystemTextClassifier implements TextClassifier {

    private static final String LOG_TAG = "SystemTextClassifier";

    private final ITextClassifierService mManagerService;
    private final TextClassificationConstants mSettings;
    private final TextClassifier mFallback;
    private final String mPackageName;

    SystemTextClassifier(Context context, TextClassificationConstants settings)
                throws ServiceManager.ServiceNotFoundException {
        mManagerService = ITextClassifierService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TEXT_CLASSIFICATION_SERVICE));
        mSettings = Preconditions.checkNotNull(settings);
        mFallback = new TextClassifierImpl(context, settings);
        mPackageName = context.getPackageName();
    }

    /**
     * @inheritDoc
     */
    @WorkerThread
    public TextSelection suggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex,
            @Nullable TextSelection.Options options) {
        Utils.validate(text, selectionStartIndex, selectionEndIndex, false /* allowInMainThread */);
        try {
            final TextSelectionCallback callback = new TextSelectionCallback();
            mManagerService.onSuggestSelection(
                    text, selectionStartIndex, selectionEndIndex, options, callback);
            final TextSelection selection = callback.mReceiver.get();
            if (selection != null) {
                return selection;
            }
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        } catch (InterruptedException e) {
            Log.d(LOG_TAG, e.getMessage());
        }
        return mFallback.suggestSelection(text, selectionStartIndex, selectionEndIndex, options);
    }

    /**
     * @inheritDoc
     */
    @WorkerThread
    public TextClassification classifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex,
            @Nullable TextClassification.Options options) {
        Utils.validate(text, startIndex, endIndex, false /* allowInMainThread */);
        try {
            final TextClassificationCallback callback = new TextClassificationCallback();
            mManagerService.onClassifyText(text, startIndex, endIndex, options, callback);
            final TextClassification classification = callback.mReceiver.get();
            if (classification != null) {
                return classification;
            }
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        } catch (InterruptedException e) {
            Log.d(LOG_TAG, e.getMessage());
        }
        return mFallback.classifyText(text, startIndex, endIndex, options);
    }

    /**
     * @inheritDoc
     */
    @WorkerThread
    public TextLinks generateLinks(
            @NonNull CharSequence text, @Nullable TextLinks.Options options) {
        Utils.validate(text, false /* allowInMainThread */);

        if (!mSettings.isSmartLinkifyEnabled()) {
            return TextClassifier.NO_OP.generateLinks(text, options);
        }

        try {
            if (options == null) {
                options = new TextLinks.Options().setCallingPackageName(mPackageName);
            } else if (!mPackageName.equals(options.getCallingPackageName())) {
                options.setCallingPackageName(mPackageName);
            }
            final TextLinksCallback callback = new TextLinksCallback();
            mManagerService.onGenerateLinks(text, options, callback);
            final TextLinks links = callback.mReceiver.get();
            if (links != null) {
                return links;
            }
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        } catch (InterruptedException e) {
            Log.d(LOG_TAG, e.getMessage());
        }
        return mFallback.generateLinks(text, options);
    }

    /**
     * @inheritDoc
     */
    @Override
    public int getMaxGenerateLinksTextLength() {
        // TODO: retrieve this from the bound service.
        return mFallback.getMaxGenerateLinksTextLength();
    }

    private static final class TextSelectionCallback extends ITextSelectionCallback.Stub {

        final ResponseReceiver<TextSelection> mReceiver = new ResponseReceiver<>();

        @Override
        public void onSuccess(TextSelection selection) {
            mReceiver.onSuccess(selection);
        }

        @Override
        public void onFailure() {
            mReceiver.onFailure();
        }
    }

    private static final class TextClassificationCallback extends ITextClassificationCallback.Stub {

        final ResponseReceiver<TextClassification> mReceiver = new ResponseReceiver<>();

        @Override
        public void onSuccess(TextClassification classification) {
            mReceiver.onSuccess(classification);
        }

        @Override
        public void onFailure() {
            mReceiver.onFailure();
        }
    }

    private static final class TextLinksCallback extends ITextLinksCallback.Stub {

        final ResponseReceiver<TextLinks> mReceiver = new ResponseReceiver<>();

        @Override
        public void onSuccess(TextLinks links) {
            mReceiver.onSuccess(links);
        }

        @Override
        public void onFailure() {
            mReceiver.onFailure();
        }
    }

    private static final class ResponseReceiver<T> {

        private final CountDownLatch mLatch = new CountDownLatch(1);

        private T mResponse;

        public void onSuccess(T response) {
            mResponse = response;
            mLatch.countDown();
        }

        public void onFailure() {
            Log.e(LOG_TAG, "Request failed.", null);
            mLatch.countDown();
        }

        @Nullable
        public T get() throws InterruptedException {
            // If this is running on the main thread, do not block for a response.
            // The response will unfortunately be null and the TextClassifier should depend on its
            // fallback.
            // NOTE that TextClassifier calls should preferably always be called on a worker thread.
            if (Looper.myLooper() != Looper.getMainLooper()) {
                mLatch.await(2, TimeUnit.SECONDS);
            }
            return mResponse;
        }
    }
}
