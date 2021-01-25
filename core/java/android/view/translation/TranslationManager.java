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
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.service.translation.TranslationService;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.SyncResultReceiver;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
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
     * Timeout for calls to system_server.
     */
    static final int SYNC_CALLS_TIMEOUT_MS = 5000;
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
    private final ArrayMap<Pair<TranslationSpec, TranslationSpec>, Integer> mTranslatorIds =
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
     * Create a Translator for translation.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * @param sourceSpec {@link TranslationSpec} for the data to be translated.
     * @param destSpec {@link TranslationSpec} for the translated data.
     * @return a {@link Translator} to be used for calling translation APIs.
     */
    @Nullable
    @WorkerThread
    public Translator createTranslator(@NonNull TranslationSpec sourceSpec,
            @NonNull TranslationSpec destSpec) {
        Objects.requireNonNull(sourceSpec, "sourceSpec cannot be null");
        Objects.requireNonNull(sourceSpec, "destSpec cannot be null");

        synchronized (mLock) {
            // TODO(b/176464808): Disallow multiple Translator now, it will throw
            //  IllegalStateException. Need to discuss if we can allow multiple Translators.
            final Pair<TranslationSpec, TranslationSpec> specs = new Pair<>(sourceSpec, destSpec);
            if (mTranslatorIds.containsKey(specs)) {
                return mTranslators.get(mTranslatorIds.get(specs));
            }

            int translatorId;
            do {
                translatorId = Math.abs(ID_GENERATOR.nextInt());
            } while (translatorId == 0 || mTranslators.indexOfKey(translatorId) >= 0);

            final Translator newTranslator = new Translator(mContext, sourceSpec, destSpec,
                    translatorId, this, mHandler, mService);
            // Start the Translator session and wait for the result
            newTranslator.start();
            try {
                if (!newTranslator.isSessionCreated()) {
                    return null;
                }
                mTranslators.put(translatorId, newTranslator);
                mTranslatorIds.put(specs, translatorId);
                return newTranslator;
            } catch (Translator.ServiceBinderReceiver.TimeoutException e) {
                // TODO(b/176464808): maybe make SyncResultReceiver.TimeoutException constructor
                //  public and use it.
                Log.e(TAG, "Timed out getting create session: " + e);
                return null;
            }
        }
    }

    /**
     * Returns a list of locales supported by the {@link TranslationService}.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * TODO: Change to correct language/locale format
     */
    @NonNull
    @WorkerThread
    public List<String> getSupportedLocales() {
        try {
            // TODO: implement it
            final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
            mService.getSupportedLocales(receiver, mContext.getUserId());
            int resutCode = receiver.getIntResult();
            if (resutCode != STATUS_SYNC_CALL_SUCCESS) {
                return Collections.emptyList();
            }
            return receiver.getParcelableResult();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (SyncResultReceiver.TimeoutException e) {
            Log.e(TAG, "Timed out getting supported locales: " + e);
            return Collections.emptyList();
        }
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
