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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.SystemService;
import android.annotation.WorkerThread;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
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
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The {@link TranslationManager} class provides ways for apps to integrate and use the
 * translation framework.
 *
 * <p>The TranslationManager manages {@link Translator}s and help bridge client calls to
 * the server {@link android.service.translation.TranslationService} </p>
 */
@SystemService(Context.TRANSLATION_MANAGER_SERVICE)
@RequiresFeature(PackageManager.FEATURE_TRANSLATION)
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

    // TODO: implement update listeners and propagate updates.
    @GuardedBy("mLock")
    private final ArrayMap<Pair<Integer, Integer>, ArrayList<PendingIntent>>
            mTranslationCapabilityUpdateListeners = new ArrayMap<>();

    private static final Random ID_GENERATOR = new Random();
    private final Object mLock = new Object();

    @NonNull
    private final Context mContext;

    private final ITranslationManager mService;

    @Nullable
    @GuardedBy("mLock")
    private ITranslationDirectManager mDirectServiceBinder;

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
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * @param translationContext {@link TranslationContext} containing the specs for creating the
     *                                                     Translator.
     */
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

    /** @deprecated Use {@link #createOnDeviceTranslator(TranslationContext)} */
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
     * The context object can then be used by {@link #createTranslator(TranslationContext)} to
     * obtain a {@link Translator} for translations.</p>
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
            return new ArraySet<>(
                    (TranslationCapability[]) result.bundle.getParcelableArray(EXTRA_CAPABILITIES));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (TimeoutException e) {
            Log.e(TAG, "Timed out getting supported translation capabilities: " + e);
            return Collections.emptySet();
        }
    }

    /** @deprecated Use {@link #getOnDeviceTranslationCapabilities(int, int)} */
    @Deprecated
    @NonNull
    @WorkerThread
    public Set<TranslationCapability> getTranslationCapabilities(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat) {
        return getOnDeviceTranslationCapabilities(sourceFormat, targetFormat);
    }

    /**
     * Registers a {@link PendingIntent} to listen for updates on states of on-device
     * {@link TranslationCapability}s.
     *
     * <p>IMPORTANT: the pending intent must be called to start a service, or a broadcast if it is
     * an explicit intent.</p>
     *
     * @param sourceFormat data format for the input data to be translated.
     * @param targetFormat data format for the expected translated output data.
     * @param pendingIntent the pending intent to invoke when updates are received.
     */
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
     * @deprecated Use {@link #addOnDeviceTranslationCapabilityUpdateListener(int, int,
     *  PendingIntent)}
     */
    @Deprecated
    public void addTranslationCapabilityUpdateListener(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat,
            @NonNull PendingIntent pendingIntent) {
        addOnDeviceTranslationCapabilityUpdateListener(sourceFormat, targetFormat, pendingIntent);
    }

    /**
     * Unregisters a {@link PendingIntent} to listen for updates on states of on-device
     * {@link TranslationCapability}s.
     *
     * @param sourceFormat data format for the input data to be translated.
     * @param targetFormat data format for the expected translated output data.
     * @param pendingIntent the pending intent to unregister
     */
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
     * @deprecated Use {@link #removeOnDeviceTranslationCapabilityUpdateListener(int, int,
     *  PendingIntent)}
     */
    @Deprecated
    public void removeTranslationCapabilityUpdateListener(
            @TranslationSpec.DataFormat int sourceFormat,
            @TranslationSpec.DataFormat int targetFormat,
            @NonNull PendingIntent pendingIntent) {
        removeOnDeviceTranslationCapabilityUpdateListener(
                sourceFormat, targetFormat, pendingIntent);
    }

    //TODO: Add method to propagate updates to mTCapabilityUpdateListeners

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

    /** @deprecated Use {@link #getOnDeviceTranslationSettingsActivityIntent()} */
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
}
