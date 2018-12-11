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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.WorkerThread;
import android.content.Context;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.textclassifier.IConversationActionsCallback;
import android.service.textclassifier.ITextClassificationCallback;
import android.service.textclassifier.ITextClassifierService;
import android.service.textclassifier.ITextLanguageCallback;
import android.service.textclassifier.ITextLinksCallback;
import android.service.textclassifier.ITextSelectionCallback;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Proxy to the system's default TextClassifier.
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PACKAGE)
public final class SystemTextClassifier implements TextClassifier {

    private static final String LOG_TAG = "SystemTextClassifier";

    private final ITextClassifierService mManagerService;
    private final TextClassificationConstants mSettings;
    private final TextClassifier mFallback;
    private final String mPackageName;
    private TextClassificationSessionId mSessionId;

    public SystemTextClassifier(Context context, TextClassificationConstants settings)
                throws ServiceManager.ServiceNotFoundException {
        mManagerService = ITextClassifierService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TEXT_CLASSIFICATION_SERVICE));
        mSettings = Preconditions.checkNotNull(settings);
        mFallback = context.getSystemService(TextClassificationManager.class)
                .getTextClassifier(TextClassifier.LOCAL);
        mPackageName = Preconditions.checkNotNull(context.getOpPackageName());
    }

    /**
     * @inheritDoc
     */
    @Override
    @WorkerThread
    public TextSelection suggestSelection(TextSelection.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();
        try {
            request.setCallingPackageName(mPackageName);
            final TextSelectionCallback callback = new TextSelectionCallback();
            mManagerService.onSuggestSelection(mSessionId, request, callback);
            final TextSelection selection = callback.mReceiver.get();
            if (selection != null) {
                return selection;
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error suggesting selection for text. Using fallback.", e);
        }
        return mFallback.suggestSelection(request);
    }

    /**
     * @inheritDoc
     */
    @Override
    @WorkerThread
    public TextClassification classifyText(TextClassification.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();
        try {
            request.setCallingPackageName(mPackageName);
            final TextClassificationCallback callback = new TextClassificationCallback();
            mManagerService.onClassifyText(mSessionId, request, callback);
            final TextClassification classification = callback.mReceiver.get();
            if (classification != null) {
                return classification;
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error classifying text. Using fallback.", e);
        }
        return mFallback.classifyText(request);
    }

    /**
     * @inheritDoc
     */
    @Override
    @WorkerThread
    public TextLinks generateLinks(@NonNull TextLinks.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();

        if (!mSettings.isSmartLinkifyEnabled() && request.isLegacyFallback()) {
            return Utils.generateLegacyLinks(request);
        }

        try {
            request.setCallingPackageName(mPackageName);
            final TextLinksCallback callback = new TextLinksCallback();
            mManagerService.onGenerateLinks(mSessionId, request, callback);
            final TextLinks links = callback.mReceiver.get();
            if (links != null) {
                return links;
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error generating links. Using fallback.", e);
        }
        return mFallback.generateLinks(request);
    }

    @Override
    public void onSelectionEvent(SelectionEvent event) {
        Preconditions.checkNotNull(event);
        Utils.checkMainThread();

        try {
            mManagerService.onSelectionEvent(mSessionId, event);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error reporting selection event.", e);
        }
    }

    @Override
    public void onTextClassifierEvent(@NonNull TextClassifierEvent event) {
        Preconditions.checkNotNull(event);
        Utils.checkMainThread();

        try {
            mManagerService.onTextClassifierEvent(mSessionId, event);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error reporting textclassifier event.", e);
        }
    }

    @Override
    public TextLanguage detectLanguage(TextLanguage.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();

        try {
            request.setCallingPackageName(mPackageName);
            final TextLanguageCallback callback = new TextLanguageCallback();
            mManagerService.onDetectLanguage(mSessionId, request, callback);
            final TextLanguage textLanguage = callback.mReceiver.get();
            if (textLanguage != null) {
                return textLanguage;
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error detecting language.", e);
        }
        return mFallback.detectLanguage(request);
    }

    @Override
    public ConversationActions suggestConversationActions(ConversationActions.Request request) {
        Preconditions.checkNotNull(request);
        Utils.checkMainThread();

        try {
            request.setCallingPackageName(mPackageName);
            final ConversationActionsCallback callback = new ConversationActionsCallback();
            mManagerService.onSuggestConversationActions(mSessionId, request, callback);
            final ConversationActions conversationActions = callback.mReceiver.get();
            if (conversationActions != null) {
                return conversationActions;
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error reporting selection event.", e);
        }
        return mFallback.suggestConversationActions(request);
    }

    /**
     * @inheritDoc
     */
    @Override
    @WorkerThread
    public int getMaxGenerateLinksTextLength() {
        // TODO: retrieve this from the bound service.
        return mFallback.getMaxGenerateLinksTextLength();
    }

    @Override
    public void destroy() {
        try {
            if (mSessionId != null) {
                mManagerService.onDestroyTextClassificationSession(mSessionId);
            }
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error destroying classification session.", e);
        }
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter printWriter) {
        printWriter.println("SystemTextClassifier:");
        printWriter.increaseIndent();
        printWriter.printPair("mFallback", mFallback);
        printWriter.printPair("mPackageName", mPackageName);
        printWriter.printPair("mSessionId", mSessionId);
        printWriter.decreaseIndent();
        printWriter.println();
    }

    /**
     * Attempts to initialize a new classification session.
     *
     * @param classificationContext the classification context
     * @param sessionId the session's id
     */
    void initializeRemoteSession(
            @NonNull TextClassificationContext classificationContext,
            @NonNull TextClassificationSessionId sessionId) {
        mSessionId = Preconditions.checkNotNull(sessionId);
        try {
            mManagerService.onCreateTextClassificationSession(classificationContext, mSessionId);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error starting a new classification session.", e);
        }
    }

    private static final class TextSelectionCallback extends ITextSelectionCallback.Stub {

        final ResponseReceiver<TextSelection> mReceiver = new ResponseReceiver<>("textselection");

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

        final ResponseReceiver<TextClassification> mReceiver =
                new ResponseReceiver<>("textclassification");

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

        final ResponseReceiver<TextLinks> mReceiver = new ResponseReceiver<>("textlinks");

        @Override
        public void onSuccess(TextLinks links) {
            mReceiver.onSuccess(links);
        }

        @Override
        public void onFailure() {
            mReceiver.onFailure();
        }
    }

    private static final class TextLanguageCallback extends ITextLanguageCallback.Stub {

        final ResponseReceiver<TextLanguage> mReceiver = new ResponseReceiver<>("textlanguage");

        @Override
        public void onSuccess(TextLanguage textLanguage) {
            mReceiver.onSuccess(textLanguage);
        }

        @Override
        public void onFailure() {
            mReceiver.onFailure();
        }
    }

    private static final class ConversationActionsCallback
            extends IConversationActionsCallback.Stub {

        final ResponseReceiver<ConversationActions> mReceiver =
                new ResponseReceiver<>("conversationaction");

        @Override
        public void onSuccess(ConversationActions conversationActions) {
            mReceiver.onSuccess(conversationActions);
        }

        @Override
        public void onFailure() {
            mReceiver.onFailure();
        }
    }

    private static final class ResponseReceiver<T> {

        private final CountDownLatch mLatch = new CountDownLatch(1);
        private final String mName;
        private T mResponse;

        private ResponseReceiver(String name) {
            mName = name;
        }

        public void onSuccess(T response) {
            mResponse = response;
            mLatch.countDown();
        }

        public void onFailure() {
            Log.e(LOG_TAG, "Request failed.", null);
            mLatch.countDown();
        }

        @Nullable
        public T get() {
            // If this is running on the main thread, do not block for a response.
            // The response will unfortunately be null and the TextClassifier should depend on its
            // fallback.
            // NOTE that TextClassifier calls should preferably always be called on a worker thread.
            if (Looper.myLooper() != Looper.getMainLooper()) {
                try {
                    boolean success = mLatch.await(2, TimeUnit.SECONDS);
                    if (!success) {
                        Log.w(LOG_TAG, "Timeout in ResponseReceiver.get(): " + mName);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.e(LOG_TAG, "Interrupted during ResponseReceiver.get(): " + mName, e);
                }
            }
            return mResponse;
        }
    }
}
