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
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.textclassifier.ITextClassifierCallback;
import android.service.textclassifier.ITextClassifierService;
import android.service.textclassifier.TextClassifierService;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.internal.util.IndentingPrintWriter;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * proxy to the request to TextClassifierService via the TextClassificationManagerService.
 *
 * @hide
 */
@VisibleForTesting(visibility = Visibility.PACKAGE)
public final class SystemTextClassifier implements TextClassifier {

    private static final String LOG_TAG = TextClassifier.LOG_TAG;

    private final ITextClassifierService mManagerService;
    private final TextClassificationConstants mSettings;
    private final TextClassifier mFallback;
    private TextClassificationSessionId mSessionId;
    // NOTE: Always set this before sending a request to the manager service otherwise the
    // manager service will throw a remote exception.
    @NonNull
    private final SystemTextClassifierMetadata mSystemTcMetadata;

    /**
     * Constructor of {@link SystemTextClassifier}
     *
     * @param context the context of the request.
     * @param settings TextClassifier specific settings.
     * @param useDefault whether to use the default text classifier to handle this request
     */
    public SystemTextClassifier(
            Context context,
            TextClassificationConstants settings,
            boolean useDefault) throws ServiceManager.ServiceNotFoundException {
        mManagerService = ITextClassifierService.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.TEXT_CLASSIFICATION_SERVICE));
        mSettings = Objects.requireNonNull(settings);
        mFallback = TextClassifier.NO_OP;
        // NOTE: Always set this before sending a request to the manager service otherwise the
        // manager service will throw a remote exception.
        mSystemTcMetadata = new SystemTextClassifierMetadata(
                Objects.requireNonNull(context.getOpPackageName()), context.getUserId(),
                useDefault);
    }

    /**
     * @inheritDoc
     */
    @Override
    @WorkerThread
    public TextSelection suggestSelection(TextSelection.Request request) {
        Objects.requireNonNull(request);
        Utils.checkMainThread();
        try {
            request.setSystemTextClassifierMetadata(mSystemTcMetadata);
            final BlockingCallback<TextSelection> callback =
                    new BlockingCallback<>("textselection", mSettings);
            mManagerService.onSuggestSelection(mSessionId, request, callback);
            final TextSelection selection = callback.get();
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
        Objects.requireNonNull(request);
        Utils.checkMainThread();
        try {
            request.setSystemTextClassifierMetadata(mSystemTcMetadata);
            final BlockingCallback<TextClassification> callback =
                    new BlockingCallback<>("textclassification", mSettings);
            mManagerService.onClassifyText(mSessionId, request, callback);
            final TextClassification classification = callback.get();
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
        Objects.requireNonNull(request);
        Utils.checkMainThread();
        if (!Utils.checkTextLength(request.getText(), getMaxGenerateLinksTextLength())) {
            return mFallback.generateLinks(request);
        }
        if (!mSettings.isSmartLinkifyEnabled() && request.isLegacyFallback()) {
            return Utils.generateLegacyLinks(request);
        }

        try {
            request.setSystemTextClassifierMetadata(mSystemTcMetadata);
            final BlockingCallback<TextLinks> callback =
                    new BlockingCallback<>("textlinks", mSettings);
            mManagerService.onGenerateLinks(mSessionId, request, callback);
            final TextLinks links = callback.get();
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
        Objects.requireNonNull(event);
        Utils.checkMainThread();

        try {
            event.setSystemTextClassifierMetadata(mSystemTcMetadata);
            mManagerService.onSelectionEvent(mSessionId, event);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error reporting selection event.", e);
        }
    }

    @Override
    public void onTextClassifierEvent(@NonNull TextClassifierEvent event) {
        Objects.requireNonNull(event);
        Utils.checkMainThread();

        try {
            final TextClassificationContext tcContext =
                    event.getEventContext() == null ? new TextClassificationContext.Builder(
                            mSystemTcMetadata.getCallingPackageName(), WIDGET_TYPE_UNKNOWN).build()
                            : event.getEventContext();
            tcContext.setSystemTextClassifierMetadata(mSystemTcMetadata);
            event.setEventContext(tcContext);
            mManagerService.onTextClassifierEvent(mSessionId, event);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error reporting textclassifier event.", e);
        }
    }

    @Override
    public TextLanguage detectLanguage(TextLanguage.Request request) {
        Objects.requireNonNull(request);
        Utils.checkMainThread();

        try {
            request.setSystemTextClassifierMetadata(mSystemTcMetadata);
            final BlockingCallback<TextLanguage> callback =
                    new BlockingCallback<>("textlanguage", mSettings);
            mManagerService.onDetectLanguage(mSessionId, request, callback);
            final TextLanguage textLanguage = callback.get();
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
        Objects.requireNonNull(request);
        Utils.checkMainThread();

        try {
            request.setSystemTextClassifierMetadata(mSystemTcMetadata);
            final BlockingCallback<ConversationActions> callback =
                    new BlockingCallback<>("conversation-actions", mSettings);
            mManagerService.onSuggestConversationActions(mSessionId, request, callback);
            final ConversationActions conversationActions = callback.get();
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
        return mSettings.getGenerateLinksMaxTextLength();
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
        printWriter.printPair("mSessionId", mSessionId);
        printWriter.printPair("mSystemTcMetadata",  mSystemTcMetadata);
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
        mSessionId = Objects.requireNonNull(sessionId);
        try {
            classificationContext.setSystemTextClassifierMetadata(mSystemTcMetadata);
            mManagerService.onCreateTextClassificationSession(classificationContext, mSessionId);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error starting a new classification session.", e);
        }
    }

    private static final class BlockingCallback<T extends Parcelable>
            extends ITextClassifierCallback.Stub {
        private final ResponseReceiver<T> mReceiver;

        BlockingCallback(String name, TextClassificationConstants settings) {
            mReceiver = new ResponseReceiver<>(name, settings);
        }

        @Override
        public void onSuccess(Bundle result) {
            mReceiver.onSuccess(TextClassifierService.getResponse(result));
        }

        @Override
        public void onFailure() {
            mReceiver.onFailure();
        }

        public T get() {
            return mReceiver.get();
        }

    }

    private static final class ResponseReceiver<T> {

        private final CountDownLatch mLatch = new CountDownLatch(1);
        private final String mName;
        private final TextClassificationConstants mSettings;
        private T mResponse;

        private ResponseReceiver(String name, TextClassificationConstants settings) {
            mName = name;
            mSettings = settings;
        }

        public void onSuccess(T response) {
            mResponse = response;
            mLatch.countDown();
        }

        public void onFailure() {
            Log.e(LOG_TAG, "Request failed at " + mName, null);
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
                    boolean success = mLatch.await(
                            mSettings.getSystemTextClassifierApiTimeoutInSecond(),
                            TimeUnit.SECONDS);
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
