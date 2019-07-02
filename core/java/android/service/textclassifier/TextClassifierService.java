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

package android.service.textclassifier;

import android.Manifest;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Slog;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.SelectionEvent;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassificationSessionId;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextClassifierEvent;
import android.view.textclassifier.TextLanguage;
import android.view.textclassifier.TextLinks;
import android.view.textclassifier.TextSelection;

import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Abstract base class for the TextClassifier service.
 *
 * <p>A TextClassifier service provides text classification related features for the system.
 * The system's default TextClassifierService is configured in
 * {@code config_defaultTextClassifierService}. If this config has no value, a
 * {@link android.view.textclassifier.TextClassifierImpl} is loaded in the calling app's process.
 *
 * <p>See: {@link TextClassifier}.
 * See: {@link TextClassificationManager}.
 *
 * <p>Include the following in the manifest:
 *
 * <pre>
 * {@literal
 * <service android:name=".YourTextClassifierService"
 *          android:permission="android.permission.BIND_TEXTCLASSIFIER_SERVICE">
 *     <intent-filter>
 *         <action android:name="android.service.textclassifier.TextClassifierService" />
 *     </intent-filter>
 * </service>}</pre>
 *
 * <p>From {@link android.os.Build.VERSION_CODES#Q} onward, all callbacks are called on the main
 * thread. Prior to Q, there is no guarantee on what thread the callback will happen. You should
 * make sure the callbacks are executed in your desired thread by using a executor, a handler or
 * something else along the line.
 *
 * @see TextClassifier
 * @hide
 */
@SystemApi
public abstract class TextClassifierService extends Service {

    private static final String LOG_TAG = "TextClassifierService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_TEXTCLASSIFIER_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.textclassifier.TextClassifierService";

    /** @hide **/
    private static final String KEY_RESULT = "key_result";

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper(), null, true);
    private final ExecutorService mSingleThreadExecutor = Executors.newSingleThreadExecutor();

    private final ITextClassifierService.Stub mBinder = new ITextClassifierService.Stub() {

        // TODO(b/72533911): Implement cancellation signal
        @NonNull private final CancellationSignal mCancellationSignal = new CancellationSignal();

        @Override
        public void onSuggestSelection(
                TextClassificationSessionId sessionId,
                TextSelection.Request request, ITextClassifierCallback callback) {
            Preconditions.checkNotNull(request);
            Preconditions.checkNotNull(callback);
            mMainThreadHandler.post(() -> TextClassifierService.this.onSuggestSelection(
                    sessionId, request, mCancellationSignal, new ProxyCallback<>(callback)));

        }

        @Override
        public void onClassifyText(
                TextClassificationSessionId sessionId,
                TextClassification.Request request, ITextClassifierCallback callback) {
            Preconditions.checkNotNull(request);
            Preconditions.checkNotNull(callback);
            mMainThreadHandler.post(() -> TextClassifierService.this.onClassifyText(
                    sessionId, request, mCancellationSignal, new ProxyCallback<>(callback)));
        }

        @Override
        public void onGenerateLinks(
                TextClassificationSessionId sessionId,
                TextLinks.Request request, ITextClassifierCallback callback) {
            Preconditions.checkNotNull(request);
            Preconditions.checkNotNull(callback);
            mMainThreadHandler.post(() -> TextClassifierService.this.onGenerateLinks(
                    sessionId, request, mCancellationSignal, new ProxyCallback<>(callback)));
        }

        @Override
        public void onSelectionEvent(
                TextClassificationSessionId sessionId,
                SelectionEvent event) {
            Preconditions.checkNotNull(event);
            mMainThreadHandler.post(
                    () -> TextClassifierService.this.onSelectionEvent(sessionId, event));
        }

        @Override
        public void onTextClassifierEvent(
                TextClassificationSessionId sessionId,
                TextClassifierEvent event) {
            Preconditions.checkNotNull(event);
            mMainThreadHandler.post(
                    () -> TextClassifierService.this.onTextClassifierEvent(sessionId, event));
        }

        @Override
        public void onDetectLanguage(
                TextClassificationSessionId sessionId,
                TextLanguage.Request request,
                ITextClassifierCallback callback) {
            Preconditions.checkNotNull(request);
            Preconditions.checkNotNull(callback);
            mMainThreadHandler.post(() -> TextClassifierService.this.onDetectLanguage(
                    sessionId, request, mCancellationSignal, new ProxyCallback<>(callback)));
        }

        @Override
        public void onSuggestConversationActions(
                TextClassificationSessionId sessionId,
                ConversationActions.Request request,
                ITextClassifierCallback callback) {
            Preconditions.checkNotNull(request);
            Preconditions.checkNotNull(callback);
            mMainThreadHandler.post(() -> TextClassifierService.this.onSuggestConversationActions(
                    sessionId, request, mCancellationSignal, new ProxyCallback<>(callback)));
        }

        @Override
        public void onCreateTextClassificationSession(
                TextClassificationContext context, TextClassificationSessionId sessionId) {
            Preconditions.checkNotNull(context);
            Preconditions.checkNotNull(sessionId);
            mMainThreadHandler.post(
                    () -> TextClassifierService.this.onCreateTextClassificationSession(
                            context, sessionId));
        }

        @Override
        public void onDestroyTextClassificationSession(TextClassificationSessionId sessionId) {
            mMainThreadHandler.post(
                    () -> TextClassifierService.this.onDestroyTextClassificationSession(sessionId));
        }
    };

    @Nullable
    @Override
    public final IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mBinder;
        }
        return null;
    }

    /**
     * Returns suggested text selection start and end indices, recognized entity types, and their
     * associated confidence scores. The entity types are ordered from highest to lowest scoring.
     *
     * @param sessionId the session id
     * @param request the text selection request
     * @param cancellationSignal object to watch for canceling the current operation
     * @param callback the callback to return the result to
     */
    @MainThread
    public abstract void onSuggestSelection(
            @Nullable TextClassificationSessionId sessionId,
            @NonNull TextSelection.Request request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Callback<TextSelection> callback);

    /**
     * Classifies the specified text and returns a {@link TextClassification} object that can be
     * used to generate a widget for handling the classified text.
     *
     * @param sessionId the session id
     * @param request the text classification request
     * @param cancellationSignal object to watch for canceling the current operation
     * @param callback the callback to return the result to
     */
    @MainThread
    public abstract void onClassifyText(
            @Nullable TextClassificationSessionId sessionId,
            @NonNull TextClassification.Request request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Callback<TextClassification> callback);

    /**
     * Generates and returns a {@link TextLinks} that may be applied to the text to annotate it with
     * links information.
     *
     * @param sessionId the session id
     * @param request the text classification request
     * @param cancellationSignal object to watch for canceling the current operation
     * @param callback the callback to return the result to
     */
    @MainThread
    public abstract void onGenerateLinks(
            @Nullable TextClassificationSessionId sessionId,
            @NonNull TextLinks.Request request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Callback<TextLinks> callback);

    /**
     * Detects and returns the language of the give text.
     *
     * @param sessionId the session id
     * @param request the language detection request
     * @param cancellationSignal object to watch for canceling the current operation
     * @param callback the callback to return the result to
     */
    @MainThread
    public void onDetectLanguage(
            @Nullable TextClassificationSessionId sessionId,
            @NonNull TextLanguage.Request request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Callback<TextLanguage> callback) {
        mSingleThreadExecutor.submit(() ->
                callback.onSuccess(getLocalTextClassifier().detectLanguage(request)));
    }

    /**
     * Suggests and returns a list of actions according to the given conversation.
     *
     * @param sessionId the session id
     * @param request the conversation actions request
     * @param cancellationSignal object to watch for canceling the current operation
     * @param callback the callback to return the result to
     */
    @MainThread
    public void onSuggestConversationActions(
            @Nullable TextClassificationSessionId sessionId,
            @NonNull ConversationActions.Request request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Callback<ConversationActions> callback) {
        mSingleThreadExecutor.submit(() ->
                callback.onSuccess(getLocalTextClassifier().suggestConversationActions(request)));
    }

    /**
     * Writes the selection event.
     * This is called when a selection event occurs. e.g. user changed selection; or smart selection
     * happened.
     *
     * <p>The default implementation ignores the event.
     *
     * @param sessionId the session id
     * @param event the selection event
     * @deprecated
     *      Use {@link #onTextClassifierEvent(TextClassificationSessionId, TextClassifierEvent)}
     *      instead
     */
    @Deprecated
    @MainThread
    public void onSelectionEvent(
            @Nullable TextClassificationSessionId sessionId, @NonNull SelectionEvent event) {}

    /**
     * Writes the TextClassifier event.
     * This is called when a TextClassifier event occurs. e.g. user changed selection,
     * smart selection happened, or a link was clicked.
     *
     * <p>The default implementation ignores the event.
     *
     * @param sessionId the session id
     * @param event the TextClassifier event
     */
    @MainThread
    public void onTextClassifierEvent(
            @Nullable TextClassificationSessionId sessionId, @NonNull TextClassifierEvent event) {}

    /**
     * Creates a new text classification session for the specified context.
     *
     * @param context the text classification context
     * @param sessionId the session's Id
     */
    @MainThread
    public void onCreateTextClassificationSession(
            @NonNull TextClassificationContext context,
            @NonNull TextClassificationSessionId sessionId) {}

    /**
     * Destroys the text classification session identified by the specified sessionId.
     *
     * @param sessionId the id of the session to destroy
     */
    @MainThread
    public void onDestroyTextClassificationSession(
            @NonNull TextClassificationSessionId sessionId) {}

    /**
     * Returns a TextClassifier that runs in this service's process.
     * If the local TextClassifier is disabled, this returns {@link TextClassifier#NO_OP}.
     *
     * @deprecated Use {@link #getDefaultTextClassifierImplementation(Context)} instead.
     */
    @Deprecated
    public final TextClassifier getLocalTextClassifier() {
        // Deprecated: In the future, we may not guarantee that this runs in the service's process.
        return getDefaultTextClassifierImplementation(this);
    }

    /**
     * Returns the platform's default TextClassifier implementation.
     */
    @NonNull
    public static TextClassifier getDefaultTextClassifierImplementation(@NonNull Context context) {
        final TextClassificationManager tcm =
                context.getSystemService(TextClassificationManager.class);
        if (tcm != null) {
            return tcm.getTextClassifier(TextClassifier.LOCAL);
        }
        return TextClassifier.NO_OP;
    }

    /** @hide **/
    public static <T extends Parcelable> T getResponse(Bundle bundle) {
        return bundle.getParcelable(KEY_RESULT);
    }

    /**
     * Callbacks for TextClassifierService results.
     *
     * @param <T> the type of the result
     */
    public interface Callback<T> {
        /**
         * Returns the result.
         */
        void onSuccess(T result);

        /**
         * Signals a failure.
         */
        void onFailure(CharSequence error);
    }

    /**
     * Returns the component name of the system default textclassifier service if it can be found
     * on the system. Otherwise, returns null.
     * @hide
     */
    @Nullable
    public static ComponentName getServiceComponentName(Context context) {
        final String packageName = context.getPackageManager().getSystemTextClassifierPackageName();
        if (TextUtils.isEmpty(packageName)) {
            Slog.d(LOG_TAG, "No configured system TextClassifierService");
            return null;
        }

        final Intent intent = new Intent(SERVICE_INTERFACE).setPackage(packageName);

        final ResolveInfo ri = context.getPackageManager().resolveService(intent,
                PackageManager.MATCH_SYSTEM_ONLY);

        if ((ri == null) || (ri.serviceInfo == null)) {
            Slog.w(LOG_TAG, String.format("Package or service not found in package %s for user %d",
                    packageName, context.getUserId()));
            return null;
        }
        final ServiceInfo si = ri.serviceInfo;

        final String permission = si.permission;
        if (Manifest.permission.BIND_TEXTCLASSIFIER_SERVICE.equals(permission)) {
            return si.getComponentName();
        }
        Slog.w(LOG_TAG, String.format(
                "Service %s should require %s permission. Found %s permission",
                si.getComponentName(),
                Manifest.permission.BIND_TEXTCLASSIFIER_SERVICE,
                si.permission));
        return null;
    }

    /**
     * Forwards the callback result to a wrapped binder callback.
     */
    private static final class ProxyCallback<T extends Parcelable> implements Callback<T> {
        private WeakReference<ITextClassifierCallback> mTextClassifierCallback;

        private ProxyCallback(ITextClassifierCallback textClassifierCallback) {
            mTextClassifierCallback =
                    new WeakReference<>(Preconditions.checkNotNull(textClassifierCallback));
        }

        @Override
        public void onSuccess(T result) {
            ITextClassifierCallback callback = mTextClassifierCallback.get();
            if (callback == null) {
                return;
            }
            try {
                Bundle bundle = new Bundle(1);
                bundle.putParcelable(KEY_RESULT, result);
                callback.onSuccess(bundle);
            } catch (RemoteException e) {
                Slog.d(LOG_TAG, "Error calling callback");
            }
        }

        @Override
        public void onFailure(CharSequence error) {
            ITextClassifierCallback callback = mTextClassifierCallback.get();
            if (callback == null) {
                return;
            }
            try {
                callback.onFailure();
            } catch (RemoteException e) {
                Slog.d(LOG_TAG, "Error calling callback");
            }
        }
    }
}
