/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.service.translation;

import static android.view.translation.TranslationManager.STATUS_SYNC_CALL_FAIL;
import static android.view.translation.TranslationManager.STATUS_SYNC_CALL_SUCCESS;
import static android.view.translation.Translator.EXTRA_SERVICE_BINDER;
import static android.view.translation.Translator.EXTRA_SESSION_ID;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.translation.ITranslationDirectManager;
import android.view.translation.ITranslationServiceCallback;
import android.view.translation.TranslationCapability;
import android.view.translation.TranslationContext;
import android.view.translation.TranslationManager;
import android.view.translation.TranslationRequest;
import android.view.translation.TranslationResponse;
import android.view.translation.TranslationSpec;
import android.view.translation.Translator;

import com.android.internal.os.IResultReceiver;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Service for translating text.
 * @hide
 */
@SystemApi
public abstract class TranslationService extends Service {
    private static final String TAG = "TranslationService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_TRANSLATION_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.translation.TranslationService";

    /**
     * Name under which a TranslationService component publishes information about itself.
     *
     * <p>This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#TranslationService translation-service}&gt;</code> tag.
     *
     * <p>Here's an example of how to use it on {@code AndroidManifest.xml}:
     * <pre> &lt;translation-service
     *     android:settingsActivity="foo.bar.SettingsActivity"
     *     . . .
     * /&gt;</pre>
     */
    public static final String SERVICE_META_DATA = "android.translation_service";

    private Handler mHandler;
    private ITranslationServiceCallback mCallback;


    /**
     * Binder to receive calls from system server.
     */
    private final ITranslationService mInterface = new ITranslationService.Stub() {
        @Override
        public void onConnected(IBinder callback) {
            mHandler.sendMessage(obtainMessage(TranslationService::handleOnConnected,
                    TranslationService.this, callback));
        }

        @Override
        public void onDisconnected() {
            mHandler.sendMessage(obtainMessage(TranslationService::onDisconnected,
                    TranslationService.this));
        }

        @Override
        public void onCreateTranslationSession(TranslationContext translationContext,
                int sessionId, IResultReceiver receiver) throws RemoteException {
            mHandler.sendMessage(obtainMessage(TranslationService::handleOnCreateTranslationSession,
                    TranslationService.this, translationContext, sessionId, receiver));
        }

        @Override
        public void onTranslationCapabilitiesRequest(@TranslationSpec.DataFormat int sourceFormat,
                @TranslationSpec.DataFormat int targetFormat,
                @NonNull ResultReceiver resultReceiver) throws RemoteException {
            mHandler.sendMessage(
                    obtainMessage(TranslationService::handleOnTranslationCapabilitiesRequest,
                            TranslationService.this, sourceFormat, targetFormat,
                            resultReceiver));
        }
    };

    /**
     * Interface definition for a callback to be invoked when the translation is compleled.
     * @removed use a {@link Consumer} instead.
     */
    @Deprecated
    public interface OnTranslationResultCallback {
        /**
         * Notifies the Android System that a translation request
         * {@link TranslationService#onTranslationRequest(TranslationRequest, int,
         * CancellationSignal, OnTranslationResultCallback)} was successfully fulfilled by the
         * service.
         *
         * <p>This method should always be called, even if the service cannot fulfill the request
         * (in which case it should be called with a TranslationResponse with
         * {@link android.view.translation.TranslationResponse#TRANSLATION_STATUS_UNKNOWN_ERROR},
         * or {@link android.view.translation.TranslationResponse
         * #TRANSLATION_STATUS_LANGUAGE_UNAVAILABLE}).
         *
         * @param response translation response for the provided request infos.
         *
         * @throws IllegalStateException if this method was already called.
         */
        void onTranslationSuccess(@NonNull TranslationResponse response);

        /**
         * @removed use {@link #onTranslationSuccess} with an error response instead.
         */
        @Deprecated
        void onError();
    }

    /**
     * Binder that receives calls from the app.
     */
    private final ITranslationDirectManager mClientInterface =
            new ITranslationDirectManager.Stub() {
                @Override
                public void onTranslationRequest(TranslationRequest request, int sessionId,
                        ICancellationSignal transport, ITranslationCallback callback)
                        throws RemoteException {
                    final Consumer<TranslationResponse> consumer =
                            new OnTranslationResultCallbackWrapper(callback);
                    mHandler.sendMessage(obtainMessage(TranslationService::onTranslationRequest,
                            TranslationService.this, request, sessionId,
                            CancellationSignal.fromTransport(transport),
                            consumer));
                }

                @Override
                public void onFinishTranslationSession(int sessionId) throws RemoteException {
                    mHandler.sendMessage(obtainMessage(
                            TranslationService::onFinishTranslationSession,
                            TranslationService.this, sessionId));
                }
            };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
        BaseBundle.setShouldDefuse(true);
    }

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    /**
     * Called when the Android system connects to service.
     *
     * <p>You should generally do initialization here rather than in {@link #onCreate}.
     */
    public void onConnected() {
    }

    /**
     * Called when the Android system disconnects from the service.
     *
     * <p> At this point this service may no longer be an active {@link TranslationService}.
     * It should not make calls on {@link TranslationManager} that requires the caller to be
     * the current service.
     */
    public void onDisconnected() {
    }

    /**
     * Called to notify the service that a session was created
     * (see {@link android.view.translation.Translator}).
     *
     * <p>The service must call {@code callback.accept()} to acknowledge whether the session is
     * supported and created successfully. If the translation context is not supported, the service
     * should call back with {@code false}.</p>
     *
     * @param translationContext the {@link TranslationContext} of the session being created.
     * @param sessionId the id of the session.
     * @param callback {@link Consumer} to notify whether the session was successfully created.
     */
    // TODO(b/176464808): the session id won't be unique cross client/server process. Need to find
    // solution to make it's safe.
    public abstract void onCreateTranslationSession(@NonNull TranslationContext translationContext,
            int sessionId, @NonNull Consumer<Boolean> callback);

    /**
     * @removed use {@link #onCreateTranslationSession(TranslationContext, int, Consumer)}
     * instead.
     */
    @Deprecated
    public void onCreateTranslationSession(@NonNull TranslationContext translationContext,
            int sessionId) {
        // no-op
    }

    /**
     * Called when a translation session is finished.
     *
     * <p>The translation session is finished when the client calls {@link Translator#destroy()} on
     * the corresponding translator.
     *
     * @param sessionId id of the session that finished.
     */
    public abstract void onFinishTranslationSession(int sessionId);

    /**
     * @removed use
     * {@link #onTranslationRequest(TranslationRequest, int, CancellationSignal, Consumer)} instead.
     */
    @Deprecated
    public void onTranslationRequest(@NonNull TranslationRequest request, int sessionId,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull OnTranslationResultCallback callback) {
        // no-op
    }

    /**
     * Called to the service with a {@link TranslationRequest} to be translated.
     *
     * <p>The service must call {@code callback.accept()} with the {@link TranslationResponse}. If
     * {@link TranslationRequest#FLAG_PARTIAL_RESPONSES} was set, the service may call
     * {@code callback.accept()} multiple times with partial responses.</p>
     *
     * @param request The translation request containing the data to be translated.
     * @param sessionId id of the session that sent the translation request.
     * @param cancellationSignal A {@link CancellationSignal} that notifies when a client has
     *                           cancelled the operation in progress.
     * @param callback {@link Consumer} to pass back the translation response.
     */
    public abstract void onTranslationRequest(@NonNull TranslationRequest request, int sessionId,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull Consumer<TranslationResponse> callback);

    /**
     * Called to request a set of {@link TranslationCapability}s that are supported by the service.
     *
     * <p>The set of translation capabilities are limited to those supporting the source and target
     * {@link TranslationSpec.DataFormat}. e.g. Calling this with
     * {@link TranslationSpec#DATA_FORMAT_TEXT} as source and target returns only capabilities that
     * translates text to text.</p>
     *
     * <p>Must call {@code callback.accept} to pass back the set of translation capabilities.</p>
     *
     * @param sourceFormat data format restriction of the translation source spec.
     * @param targetFormat data format restriction of the translation target spec.
     * @param callback {@link Consumer} to pass back the set of translation capabilities.
     */
    public abstract void onTranslationCapabilitiesRequest(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat,
            @NonNull Consumer<Set<TranslationCapability>> callback);

    /**
     * Called by the service to notify an update in existing {@link TranslationCapability}s.
     *
     * @param capability the updated {@link TranslationCapability} with its new states and flags.
     */
    public final void updateTranslationCapability(@NonNull TranslationCapability capability) {
        Objects.requireNonNull(capability, "translation capability should not be null");

        final ITranslationServiceCallback callback = mCallback;
        if (callback == null) {
            Log.w(TAG, "updateTranslationCapability(): no server callback");
            return;
        }

        try {
            callback.updateTranslationCapability(capability);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    private void handleOnConnected(@NonNull IBinder callback) {
        mCallback = ITranslationServiceCallback.Stub.asInterface(callback);
        onConnected();
    }

    // TODO(b/176464808): Need to handle client dying case

    private void handleOnCreateTranslationSession(@NonNull TranslationContext translationContext,
            int sessionId, IResultReceiver resultReceiver) {
        onCreateTranslationSession(translationContext, sessionId,
                new Consumer<Boolean>() {
                    @Override
                    public void accept(Boolean created) {
                        try {
                            if (!created) {
                                Log.w(TAG, "handleOnCreateTranslationSession(): context="
                                        + translationContext + " not supported by service.");
                                resultReceiver.send(STATUS_SYNC_CALL_FAIL, null);
                                return;
                            }

                            final Bundle extras = new Bundle();
                            extras.putBinder(EXTRA_SERVICE_BINDER, mClientInterface.asBinder());
                            extras.putInt(EXTRA_SESSION_ID, sessionId);
                            resultReceiver.send(STATUS_SYNC_CALL_SUCCESS, extras);
                        } catch (RemoteException e) {
                            Log.w(TAG, "RemoteException sending client interface: " + e);
                        }
                    }
                });

    }

    private void handleOnTranslationCapabilitiesRequest(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat,
            @NonNull ResultReceiver resultReceiver) {
        onTranslationCapabilitiesRequest(sourceFormat, targetFormat,
                new Consumer<Set<TranslationCapability>>() {
                    @Override
                    public void accept(Set<TranslationCapability> values) {
                        if (!isValidCapabilities(sourceFormat, targetFormat, values)) {
                            throw new IllegalStateException("Invalid capabilities and "
                                    + "format compatibility");
                        }

                        final Bundle bundle = new Bundle();
                        final ParceledListSlice<TranslationCapability> listSlice =
                                new ParceledListSlice<>(Arrays.asList(
                                        values.toArray(new TranslationCapability[0])));
                        bundle.putParcelable(TranslationManager.EXTRA_CAPABILITIES, listSlice);
                        resultReceiver.send(STATUS_SYNC_CALL_SUCCESS, bundle);
                    }
                });
    }

    /**
     * Helper method to validate capabilities and format compatibility.
     */
    private boolean isValidCapabilities(@TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat, Set<TranslationCapability> capabilities) {
        if (sourceFormat != TranslationSpec.DATA_FORMAT_TEXT
                && targetFormat != TranslationSpec.DATA_FORMAT_TEXT) {
            return true;
        }

        for (TranslationCapability capability : capabilities) {
            if (capability.getState() == TranslationCapability.STATE_REMOVED_AND_AVAILABLE) {
                return false;
            }
        }

        return true;
    }
}
