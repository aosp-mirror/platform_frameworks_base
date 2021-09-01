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

package android.view.translation;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.annotation.WorkerThread;
import android.app.PendingIntent;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SynchronousResultReceiver;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.SyncResultReceiver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The {@link TranslationManager} class provides ways for apps to integrate and use the
 * translation framework.
 *
 * <p>The TranslationManager manages {@link Translator}s and help bridge client calls to
 * the server {@link android.service.translation.TranslationService} </p>
 */
@SystemService(Context.TRANSLATION_MANAGER_SERVICE)
public final class TranslationManager {

    private static final String TAG = "TranslationManager";

    /**
     * Timeout for calls to system_server, default 1 minute.
     */
    static final int SYNC_CALLS_TIMEOUT_MS = 60_000;
    /**
     * The result code from result receiver success.
     * @hide
     */
    public static final int STATUS_SYNC_CALL_SUCCESS = 1;
    /**
     * The result code from result receiver fail.
     * @hide
     */
    public static final int STATUS_SYNC_CALL_FAIL = 2;

    /**
     * Name of the extra used to pass the translation capabilities.
     * @hide
     */
    public static final String EXTRA_CAPABILITIES = "translation_capabilities";

    @GuardedBy("mLock")
    private final ArrayMap<Pair<Integer, Integer>, ArrayList<PendingIntent>>
            mTranslationCapabilityUpdateListeners = new ArrayMap<>();

    @GuardedBy("mLock")
    private final Map<Consumer<TranslationCapability>, IRemoteCallback> mCapabilityCallbacks =
            new ArrayMap<>();

    private static final Random ID_GENERATOR = new Random();
    private final Object mLock = new Object();

    @NonNull
    private final Context mContext;

    private final ITranslationManager mService;

    @NonNull
    @GuardedBy("mLock")
    private final SparseArray<Translator> mTranslators = new SparseArray<>();

    @NonNull
    @GuardedBy("mLock")
    private final ArrayMap<TranslationContext, Integer> mTranslatorIds =
            new ArrayMap<>();

    @NonNull
    private final Handler mHandler;

    private static final AtomicInteger sAvailableRequestId = new AtomicInteger(1);

    /**
     * @hide
     */
    public TranslationManager(@NonNull Context context, ITranslationManager service) {
        mContext = Objects.requireNonNull(context, "context cannot be null");
        mService = service;

        mHandler = Handler.createAsync(Looper.getMainLooper());
    }

    /**
     * Creates an on-device Translator for natural language translation.
     *
     * @param translationContext {@link TranslationContext} containing the specs for creating the
     *                                                     Translator.
     * @param executor Executor to run callback operations
     * @param callback {@link Consumer} to receive the translator. A {@code null} value is returned
     *                                 if the service could not create the translator.
     */
    public void createOnDeviceTranslator(@NonNull TranslationContext translationContext,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Translator> callback) {
        Objects.requireNonNull(translationContext, "translationContext cannot be null");
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");

        synchronized (mLock) {
            // TODO(b/176464808): Disallow multiple Translator now, it will throw
            //  IllegalStateException. Need to discuss if we can allow multiple Translators.
            if (mTranslatorIds.containsKey(translationContext)) {
                executor.execute(() -> callback.accept(
                        mTranslators.get(mTranslatorIds.get(translationContext))));
                return;
            }

            int translatorId;
            do {
                translatorId = Math.abs(ID_GENERATOR.nextInt());
            } while (translatorId == 0 || mTranslators.indexOfKey(translatorId) >= 0);
            final int tId = translatorId;

            new Translator(mContext, translationContext, translatorId, this, mHandler, mService,
                    new Consumer<Translator>() {
                        @Override
                        public void accept(Translator translator) {
                            if (translator == null) {
                                final long token = Binder.clearCallingIdentity();
                                try {
                                    executor.execute(() -> callback.accept(null));
                                } finally {
                                    Binder.restoreCallingIdentity(token);
                                }
                                return;
                            }

                            synchronized (mLock) {
                                mTranslators.put(tId, translator);
                                mTranslatorIds.put(translationContext, tId);
                            }
                            final long token = Binder.clearCallingIdentity();
                            try {
                                executor.execute(() -> callback.accept(translator));
                            } finally {
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
        }
    }

    /**
     * Creates an on-device Translator for natural language translation.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * @removed use {@link #createOnDeviceTranslator(TranslationContext, Executor, Consumer)}
     * instead.
     *
     * @param translationContext {@link TranslationContext} containing the specs for creating the
     *                                                     Translator.
     */
    @Deprecated
    @Nullable
    @WorkerThread
    public Translator createOnDeviceTranslator(@NonNull TranslationContext translationContext) {
        Objects.requireNonNull(translationContext, "translationContext cannot be null");

        synchronized (mLock) {
            // TODO(b/176464808): Disallow multiple Translator now, it will throw
            //  IllegalStateException. Need to discuss if we can allow multiple Translators.
            if (mTranslatorIds.containsKey(translationContext)) {
                return mTranslators.get(mTranslatorIds.get(translationContext));
            }

            int translatorId;
            do {
                translatorId = Math.abs(ID_GENERATOR.nextInt());
            } while (translatorId == 0 || mTranslators.indexOfKey(translatorId) >= 0);

            final Translator newTranslator = new Translator(mContext, translationContext,
                    translatorId, this, mHandler, mService);
            // Start the Translator session and wait for the result
            newTranslator.start();
            try {
                if (!newTranslator.isSessionCreated()) {
                    return null;
                }
                mTranslators.put(translatorId, newTranslator);
                mTranslatorIds.put(translationContext, translatorId);
                return newTranslator;
            } catch (Translator.ServiceBinderReceiver.TimeoutException e) {
                // TODO(b/176464808): maybe make SyncResultReceiver.TimeoutException constructor
                //  public and use it.
                Log.e(TAG, "Timed out getting create session: " + e);
                return null;
            }
        }
    }

    /** @removed Use {@link #createOnDeviceTranslator(TranslationContext)} */
    @Deprecated
    @Nullable
    @WorkerThread
    public Translator createTranslator(@NonNull TranslationContext translationContext) {
        return createOnDeviceTranslator(translationContext);
    }

    /**
     * Returns a set of {@link TranslationCapability}s describing the capabilities for on-device
     * {@link Translator}s.
     *
     * <p>These translation capabilities contains a source and target {@link TranslationSpec}
     * representing the data expected for both ends of translation process. The capabilities
     * provides the information and limitations for generating a {@link TranslationContext}.
     * The context object can then be used by
     * {@link #createOnDeviceTranslator(TranslationContext, Executor, Consumer)} to obtain a
     * {@link Translator} for translations.</p>
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * @param sourceFormat data format for the input data to be translated.
     * @param targetFormat data format for the expected translated output data.
     * @return A set of {@link TranslationCapability}s.
     */
    @NonNull
    @WorkerThread
    public Set<TranslationCapability> getOnDeviceTranslationCapabilities(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat) {
        try {
            final SynchronousResultReceiver receiver = new SynchronousResultReceiver();
            mService.onTranslationCapabilitiesRequest(sourceFormat, targetFormat, receiver,
                    mContext.getUserId());
            final SynchronousResultReceiver.Result result =
                    receiver.awaitResult(SYNC_CALLS_TIMEOUT_MS);
            if (result.resultCode != STATUS_SYNC_CALL_SUCCESS) {
                return Collections.emptySet();
            }
            Parcelable[] parcelables = result.bundle.getParcelableArray(EXTRA_CAPABILITIES);
            ArraySet<TranslationCapability> capabilities = new ArraySet();
            for (Parcelable obj : parcelables) {
                if (obj instanceof TranslationCapability) {
                    capabilities.add((TranslationCapability) obj);
                }
            }
            return capabilities;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (TimeoutException e) {
            Log.e(TAG, "Timed out getting supported translation capabilities: " + e);
            return Collections.emptySet();
        }
    }

    /** @removed Use {@link #getOnDeviceTranslationCapabilities(int, int)} */
    @Deprecated
    @NonNull
    @WorkerThread
    public Set<TranslationCapability> getTranslationCapabilities(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat) {
        return getOnDeviceTranslationCapabilities(sourceFormat, targetFormat);
    }

    /**
     * Adds a {@link TranslationCapability} Consumer to listen for updates on states of on-device
     * {@link TranslationCapability}s.
     *
     * @param capabilityListener a {@link TranslationCapability} Consumer to receive the updated
     * {@link TranslationCapability} from the on-device translation service.
     */
    public void addOnDeviceTranslationCapabilityUpdateListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<TranslationCapability> capabilityListener) {
        Objects.requireNonNull(executor, "executor should not be null");
        Objects.requireNonNull(capabilityListener, "capability listener should not be null");

        synchronized (mLock) {
            if (mCapabilityCallbacks.containsKey(capabilityListener)) {
                Log.w(TAG, "addOnDeviceTranslationCapabilityUpdateListener: the listener for "
                        + capabilityListener + " already registered; ignoring.");
                return;
            }
            final IRemoteCallback remoteCallback = new TranslationCapabilityRemoteCallback(executor,
                    capabilityListener);
            try {
                mService.registerTranslationCapabilityCallback(remoteCallback,
                        mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mCapabilityCallbacks.put(capabilityListener, remoteCallback);
        }
    }


    /**
     * @removed Use {@link TranslationManager#addOnDeviceTranslationCapabilityUpdateListener(
     * java.util.concurrent.Executor, java.util.function.Consumer)}
     */
    @Deprecated
    public void addOnDeviceTranslationCapabilityUpdateListener(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat,
            @NonNull PendingIntent pendingIntent) {
        Objects.requireNonNull(pendingIntent, "pending intent should not be null");

        synchronized (mLock) {
            final Pair<Integer, Integer> formatPair = new Pair<>(sourceFormat, targetFormat);
            mTranslationCapabilityUpdateListeners.computeIfAbsent(formatPair,
                    (formats) -> new ArrayList<>()).add(pendingIntent);
        }
    }

    /**
     * @removed Use {@link TranslationManager#addOnDeviceTranslationCapabilityUpdateListener(
     * java.util.concurrent.Executor, java.util.function.Consumer)}
     */
    @Deprecated
    public void addTranslationCapabilityUpdateListener(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat,
            @NonNull PendingIntent pendingIntent) {
        addOnDeviceTranslationCapabilityUpdateListener(sourceFormat, targetFormat, pendingIntent);
    }

    /**
     * Removes a {@link TranslationCapability} Consumer to listen for updates on states of
     * on-device {@link TranslationCapability}s.
     *
     * @param capabilityListener the {@link TranslationCapability} Consumer to unregister
     */
    public void removeOnDeviceTranslationCapabilityUpdateListener(
            @NonNull Consumer<TranslationCapability> capabilityListener) {
        Objects.requireNonNull(capabilityListener, "capability callback should not be null");

        synchronized (mLock) {
            final IRemoteCallback remoteCallback = mCapabilityCallbacks.get(capabilityListener);
            if (remoteCallback == null) {
                Log.w(TAG, "removeOnDeviceTranslationCapabilityUpdateListener: the capability "
                        + "listener not found; ignoring.");
                return;
            }
            try {
                mService.unregisterTranslationCapabilityCallback(remoteCallback,
                        mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mCapabilityCallbacks.remove(capabilityListener);
        }
    }

    /**
     * @removed Use {@link #removeOnDeviceTranslationCapabilityUpdateListener(
     * java.util.function.Consumer)}.
     */
    @Deprecated
    public void removeOnDeviceTranslationCapabilityUpdateListener(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat,
            @NonNull PendingIntent pendingIntent) {
        Objects.requireNonNull(pendingIntent, "pending intent should not be null");

        synchronized (mLock) {
            final Pair<Integer, Integer> formatPair = new Pair<>(sourceFormat, targetFormat);
            if (mTranslationCapabilityUpdateListeners.containsKey(formatPair)) {
                final ArrayList<PendingIntent> intents =
                        mTranslationCapabilityUpdateListeners.get(formatPair);
                if (intents.contains(pendingIntent)) {
                    intents.remove(pendingIntent);
                } else {
                    Log.w(TAG, "pending intent=" + pendingIntent + " does not exist in "
                            + "mTranslationCapabilityUpdateListeners");
                }
            } else {
                Log.w(TAG, "format pair=" + formatPair + " does not exist in "
                        + "mTranslationCapabilityUpdateListeners");
            }
        }
    }

    /**
     * @removed Use {@link #removeOnDeviceTranslationCapabilityUpdateListener(
     * java.util.function.Consumer)}.
     */
    @Deprecated
    public void removeTranslationCapabilityUpdateListener(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat,
            @NonNull PendingIntent pendingIntent) {
        removeOnDeviceTranslationCapabilityUpdateListener(
                sourceFormat, targetFormat, pendingIntent);
    }

    /**
     * Returns an immutable PendingIntent which can be used to launch an activity to view/edit
     * on-device translation settings.
     *
     * @return An immutable PendingIntent or {@code null} if one of reason met:
     * <ul>
     *     <li>Device manufacturer (OEM) does not provide TranslationService.</li>
     *     <li>The TranslationService doesn't provide the Settings.</li>
     * </ul>
     **/
    @Nullable
    public PendingIntent getOnDeviceTranslationSettingsActivityIntent() {
        final SyncResultReceiver resultReceiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        try {
            mService.getServiceSettingsActivity(resultReceiver, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        try {
            return resultReceiver.getParcelableResult();
        } catch (SyncResultReceiver.TimeoutException e) {
            Log.e(TAG, "Fail to get translation service settings activity.");
            return null;
        }
    }

    /** @removed Use {@link #getOnDeviceTranslationSettingsActivityIntent()} */
    @Deprecated
    @Nullable
    public PendingIntent getTranslationSettingsActivityIntent() {
        return getOnDeviceTranslationSettingsActivityIntent();
    }

    void removeTranslator(int id) {
        synchronized (mLock) {
            mTranslators.remove(id);
            for (int i = 0; i < mTranslatorIds.size(); i++) {
                if (mTranslatorIds.valueAt(i) == id) {
                    mTranslatorIds.removeAt(i);
                    break;
                }
            }
        }
    }

    AtomicInteger getAvailableRequestId() {
        synchronized (mLock) {
            return sAvailableRequestId;
        }
    }

    private static class TranslationCapabilityRemoteCallback extends
            IRemoteCallback.Stub {
        private final Executor mExecutor;
        private final Consumer<TranslationCapability> mListener;

        TranslationCapabilityRemoteCallback(Executor executor,
                Consumer<TranslationCapability> listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void sendResult(Bundle bundle) {
            Binder.withCleanCallingIdentity(
                    () -> mExecutor.execute(() -> onTranslationCapabilityUpdate(bundle)));
        }

        private void onTranslationCapabilityUpdate(Bundle bundle) {
            TranslationCapability capability =
                    (TranslationCapability) bundle.getParcelable(EXTRA_CAPABILITIES);
            mListener.accept(capability);
        }
    }
}
