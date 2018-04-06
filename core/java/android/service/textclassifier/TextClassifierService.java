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
import android.annotation.IntRange;
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
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Slog;
import android.view.textclassifier.SelectionEvent;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassificationSessionId;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;
import android.view.textclassifier.TextSelection;

import com.android.internal.util.Preconditions;

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
    @SystemApi
    public static final String SERVICE_INTERFACE =
            "android.service.textclassifier.TextClassifierService";

    private final ITextClassifierService.Stub mBinder = new ITextClassifierService.Stub() {

        // TODO(b/72533911): Implement cancellation signal
        @NonNull private final CancellationSignal mCancellationSignal = new CancellationSignal();

        /** {@inheritDoc} */
        @Override
        public void onSuggestSelection(
                TextClassificationSessionId sessionId,
                TextSelection.Request request, ITextSelectionCallback callback)
                throws RemoteException {
            Preconditions.checkNotNull(request);
            Preconditions.checkNotNull(callback);
            TextClassifierService.this.onSuggestSelection(
                    request.getText(), request.getStartIndex(), request.getEndIndex(),
                    TextSelection.Options.from(sessionId, request), mCancellationSignal,
                    new Callback<TextSelection>() {
                        @Override
                        public void onSuccess(TextSelection result) {
                            try {
                                callback.onSuccess(result);
                            } catch (RemoteException e) {
                                Slog.d(LOG_TAG, "Error calling callback");
                            }
                        }

                        @Override
                        public void onFailure(CharSequence error) {
                            try {
                                if (callback.asBinder().isBinderAlive()) {
                                    callback.onFailure();
                                }
                            } catch (RemoteException e) {
                                Slog.d(LOG_TAG, "Error calling callback");
                            }
                        }
                    });
        }

        /** {@inheritDoc} */
        @Override
        public void onClassifyText(
                TextClassificationSessionId sessionId,
                TextClassification.Request request, ITextClassificationCallback callback)
                throws RemoteException {
            Preconditions.checkNotNull(request);
            Preconditions.checkNotNull(callback);
            TextClassifierService.this.onClassifyText(
                    request.getText(), request.getStartIndex(), request.getEndIndex(),
                    TextClassification.Options.from(sessionId, request), mCancellationSignal,
                    new Callback<TextClassification>() {
                        @Override
                        public void onSuccess(TextClassification result) {
                            try {
                                callback.onSuccess(result);
                            } catch (RemoteException e) {
                                Slog.d(LOG_TAG, "Error calling callback");
                            }
                        }

                        @Override
                        public void onFailure(CharSequence error) {
                            try {
                                callback.onFailure();
                            } catch (RemoteException e) {
                                Slog.d(LOG_TAG, "Error calling callback");
                            }
                        }
                    });
        }

        /** {@inheritDoc} */
        @Override
        public void onGenerateLinks(
                TextClassificationSessionId sessionId,
                TextLinks.Request request, ITextLinksCallback callback)
                throws RemoteException {
            Preconditions.checkNotNull(request);
            Preconditions.checkNotNull(callback);
            TextClassifierService.this.onGenerateLinks(
                    request.getText(), TextLinks.Options.from(sessionId, request),
                    mCancellationSignal,
                    new Callback<TextLinks>() {
                        @Override
                        public void onSuccess(TextLinks result) {
                            try {
                                callback.onSuccess(result);
                            } catch (RemoteException e) {
                                Slog.d(LOG_TAG, "Error calling callback");
                            }
                        }

                        @Override
                        public void onFailure(CharSequence error) {
                            try {
                                callback.onFailure();
                            } catch (RemoteException e) {
                                Slog.d(LOG_TAG, "Error calling callback");
                            }
                        }
                    });
        }

        /** {@inheritDoc} */
        @Override
        public void onSelectionEvent(
                TextClassificationSessionId sessionId,
                SelectionEvent event) throws RemoteException {
            Preconditions.checkNotNull(event);
            TextClassifierService.this.onSelectionEvent(sessionId, event);
        }

        /** {@inheritDoc} */
        @Override
        public void onCreateTextClassificationSession(
                TextClassificationContext context, TextClassificationSessionId sessionId)
                throws RemoteException {
            Preconditions.checkNotNull(context);
            Preconditions.checkNotNull(sessionId);
            TextClassifierService.this.onCreateTextClassificationSession(context, sessionId);
        }

        /** {@inheritDoc} */
        @Override
        public void onDestroyTextClassificationSession(TextClassificationSessionId sessionId)
                throws RemoteException {
            TextClassifierService.this.onDestroyTextClassificationSession(sessionId);
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
    public abstract void onSuggestSelection(
            @Nullable TextClassificationSessionId sessionId,
            @NonNull TextSelection.Request request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Callback<TextSelection> callback);

    // TODO: Remove once apps can build against the latest sdk.
    /** @hide */
    public void onSuggestSelection(
            @NonNull CharSequence text,
            @IntRange(from = 0) int selectionStartIndex,
            @IntRange(from = 0) int selectionEndIndex,
            @Nullable TextSelection.Options options,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Callback<TextSelection> callback) {
        final TextClassificationSessionId sessionId = options.getSessionId();
        final TextSelection.Request request = options.getRequest() != null
                ? options.getRequest()
                : new TextSelection.Request.Builder(
                        text, selectionStartIndex, selectionEndIndex)
                        .setDefaultLocales(options.getDefaultLocales())
                        .build();
        onSuggestSelection(sessionId, request, cancellationSignal, callback);
    }

    /**
     * Classifies the specified text and returns a {@link TextClassification} object that can be
     * used to generate a widget for handling the classified text.
     *
     * @param sessionId the session id
     * @param request the text classification request
     * @param cancellationSignal object to watch for canceling the current operation
     * @param callback the callback to return the result to
     */
    public abstract void onClassifyText(
            @Nullable TextClassificationSessionId sessionId,
            @NonNull TextClassification.Request request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Callback<TextClassification> callback);

    // TODO: Remove once apps can build against the latest sdk.
    /** @hide */
    public void onClassifyText(
            @NonNull CharSequence text,
            @IntRange(from = 0) int startIndex,
            @IntRange(from = 0) int endIndex,
            @Nullable TextClassification.Options options,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Callback<TextClassification> callback) {
        final TextClassificationSessionId sessionId = options.getSessionId();
        final TextClassification.Request request = options.getRequest() != null
                ? options.getRequest()
                : new TextClassification.Request.Builder(
                        text, startIndex, endIndex)
                        .setDefaultLocales(options.getDefaultLocales())
                        .setReferenceTime(options.getReferenceTime())
                        .build();
        onClassifyText(sessionId, request, cancellationSignal, callback);
    }

    /**
     * Generates and returns a {@link TextLinks} that may be applied to the text to annotate it with
     * links information.
     *
     * @param sessionId the session id
     * @param request the text classification request
     * @param cancellationSignal object to watch for canceling the current operation
     * @param callback the callback to return the result to
     */
    public abstract void onGenerateLinks(
            @Nullable TextClassificationSessionId sessionId,
            @NonNull TextLinks.Request request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Callback<TextLinks> callback);

    // TODO: Remove once apps can build against the latest sdk.
    /** @hide */
    public void onGenerateLinks(
            @NonNull CharSequence text,
            @Nullable TextLinks.Options options,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull Callback<TextLinks> callback) {
        final TextClassificationSessionId sessionId = options.getSessionId();
        final TextLinks.Request request = options.getRequest() != null
                ? options.getRequest()
                : new TextLinks.Request.Builder(text)
                        .setDefaultLocales(options.getDefaultLocales())
                        .setEntityConfig(options.getEntityConfig())
                        .build();
        onGenerateLinks(sessionId, request, cancellationSignal, callback);
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
     */
    public void onSelectionEvent(
            @Nullable TextClassificationSessionId sessionId, @NonNull SelectionEvent event) {}

    /**
     * Creates a new text classification session for the specified context.
     *
     * @param context the text classification context
     * @param sessionId the session's Id
     */
    public void onCreateTextClassificationSession(
            @NonNull TextClassificationContext context,
            @NonNull TextClassificationSessionId sessionId) {}

    /**
     * Destroys the text classification session identified by the specified sessionId.
     *
     * @param sessionId the id of the session to destroy
     */
    public void onDestroyTextClassificationSession(
            @NonNull TextClassificationSessionId sessionId) {}

    /**
     * Returns a TextClassifier that runs in this service's process.
     * If the local TextClassifier is disabled, this returns {@link TextClassifier#NO_OP}.
     */
    public final TextClassifier getLocalTextClassifier() {
        final TextClassificationManager tcm = getSystemService(TextClassificationManager.class);
        if (tcm != null) {
            return tcm.getTextClassifier(TextClassifier.LOCAL);
        }
        return TextClassifier.NO_OP;
    }

    /**
     * Callbacks for TextClassifierService results.
     *
     * @param <T> the type of the result
     * @hide
     */
    @SystemApi
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
            Slog.w(LOG_TAG, String.format("Package or service not found in package %s",
                    packageName));
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
}
