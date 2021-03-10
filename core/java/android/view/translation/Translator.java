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

import static android.view.translation.TranslationManager.STATUS_SYNC_CALL_FAIL;
import static android.view.translation.TranslationManager.SYNC_CALLS_TIMEOUT_MS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.WorkerThread;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.translation.ITranslationCallback;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.SyncResultReceiver;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The {@link Translator} for translation, defined by a source and a dest {@link TranslationSpec}.
 */
@SuppressLint("NotCloseable")
public class Translator {

    private static final String TAG = "Translator";

    // TODO: make this configurable and cross the Translation component
    private static boolean sDEBUG = false;

    private final Object mLock = new Object();

    private int mId;

    @NonNull
    private final Context mContext;

    @NonNull
    private final TranslationSpec mSourceSpec;

    @NonNull
    private final TranslationSpec mDestSpec;

    @NonNull
    private final TranslationManager mManager;

    @NonNull
    private final Handler mHandler;

    /**
     * Interface to the system_server binder object.
     */
    private ITranslationManager mSystemServerBinder;

    /**
     * Direct interface to the TranslationService binder object.
     */
    @Nullable
    private ITranslationDirectManager mDirectServiceBinder;

    @NonNull
    private final ServiceBinderReceiver mServiceBinderReceiver;

    @GuardedBy("mLock")
    private boolean mDestroyed;

    /**
     * Name of the {@link IResultReceiver} extra used to pass the binder interface to Translator.
     * @hide
     */
    public static final String EXTRA_SERVICE_BINDER = "binder";
    /**
     * Name of the extra used to pass the session id to Translator.
     * @hide
     */
    public static final String EXTRA_SESSION_ID = "sessionId";

    static class ServiceBinderReceiver extends IResultReceiver.Stub {
        private final WeakReference<Translator> mTranslator;
        private final CountDownLatch mLatch = new CountDownLatch(1);
        private int mSessionId;

        ServiceBinderReceiver(Translator translator) {
            mTranslator = new WeakReference<>(translator);
        }

        int getSessionStateResult() throws TimeoutException {
            try {
                if (!mLatch.await(SYNC_CALLS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw new TimeoutException(
                            "Session not created in " + SYNC_CALLS_TIMEOUT_MS + "ms");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TimeoutException("Session not created because interrupted");
            }
            return mSessionId;
        }

        @Override
        public void send(int resultCode, Bundle resultData) {
            if (resultCode == STATUS_SYNC_CALL_FAIL) {
                mLatch.countDown();
                return;
            }
            mSessionId = resultData.getInt(EXTRA_SESSION_ID);
            final Translator translator = mTranslator.get();
            if (translator == null) {
                Log.w(TAG, "received result after session is finished");
                return;
            }
            final IBinder binder;
            if (resultData != null) {
                binder = resultData.getBinder(EXTRA_SERVICE_BINDER);
                if (binder == null) {
                    Log.wtf(TAG, "No " + EXTRA_SERVICE_BINDER + " extra result");
                    return;
                }
            } else {
                binder = null;
            }
            translator.setServiceBinder(binder);
            mLatch.countDown();
        }

        // TODO(b/176464808): maybe make SyncResultReceiver.TimeoutException constructor public
        //  and use it.
        static final class TimeoutException extends Exception {
            private TimeoutException(String msg) {
                super(msg);
            }
        }
    }

    /**
     * Create the Translator.
     *
     * @hide
     */
    public Translator(@NonNull Context context,
            @NonNull TranslationSpec sourceSpec,
            @NonNull TranslationSpec destSpec, int sessionId,
            @NonNull TranslationManager translationManager, @NonNull Handler handler,
            @Nullable ITranslationManager systemServerBinder) {
        mContext = context;
        mSourceSpec = sourceSpec;
        mDestSpec = destSpec;
        mId = sessionId;
        mManager = translationManager;
        mHandler = handler;
        mSystemServerBinder = systemServerBinder;
        mServiceBinderReceiver = new ServiceBinderReceiver(this);
    }

    /**
     * Starts this Translator session.
     */
    void start() {
        try {
            mSystemServerBinder.onSessionCreated(mSourceSpec, mDestSpec, mId,
                    mServiceBinderReceiver, mContext.getUserId());
        } catch (RemoteException e) {
            Log.w(TAG, "RemoteException calling startSession(): " + e);
        }
    }

    /**
     * Wait this Translator session created.
     *
     * @return {@code true} if the session is created successfully.
     */
    boolean isSessionCreated() throws ServiceBinderReceiver.TimeoutException {
        int receivedId = mServiceBinderReceiver.getSessionStateResult();
        return receivedId > 0;
    }

    private int getNextRequestId() {
        // Get from manager to keep the request id unique to different Translators
        return mManager.getAvailableRequestId().getAndIncrement();
    }

    private void setServiceBinder(@Nullable IBinder binder) {
        synchronized (mLock) {
            if (mDirectServiceBinder != null) {
                return;
            }
            if (binder != null) {
                mDirectServiceBinder = ITranslationDirectManager.Stub.asInterface(binder);
            }
        }
    }

    /** @hide */
    public int getTranslatorId() {
        return mId;
    }

    /** @hide */
    public void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        pw.print(prefix); pw.print("sourceSpec: "); pw.println(mSourceSpec);
        pw.print(prefix); pw.print("destSpec: "); pw.println(mDestSpec);
    }

    /**
     * Requests a translation for the provided {@link TranslationRequest} using the Translator's
     * source spec and destination spec.
     *
     * <p><strong>NOTE: </strong>Call on a worker thread.
     *
     * @param request {@link TranslationRequest} request to be translate.
     *
     * @return {@link TranslationRequest} containing translated request,
     *         or null if translation could not be done.
     * @throws IllegalStateException if this TextClassification session was destroyed when calls
     */
    @Nullable
    @WorkerThread
    public TranslationResponse translate(@NonNull TranslationRequest request) {
        Objects.requireNonNull(request, "Translation request cannot be null");
        if (isDestroyed()) {
            // TODO(b/176464808): Disallow multiple Translator now, it will throw
            //  IllegalStateException. Need to discuss if we can allow multiple Translators.
            throw new IllegalStateException(
                    "This translator has been destroyed");
        }

        TranslationResponse response = null;
        try {
            final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
            mDirectServiceBinder.onTranslationRequest(request, mId, null, receiver);

            response = receiver.getParcelableResult();
        } catch (RemoteException e) {
            Log.w(TAG, "RemoteException calling requestTranslate(): " + e);
        }  catch (SyncResultReceiver.TimeoutException e) {
            Log.e(TAG, "Timed out calling requestTranslate: " + e);
        }
        if (sDEBUG) {
            Log.v(TAG, "Receive translation response: " + response);
        }
        return response;
    }

    /**
     * Destroy this Translator.
     */
    public void destroy() {
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }
            mDestroyed = true;
            try {
                mDirectServiceBinder.onFinishTranslationSession(mId);
            } catch (RemoteException e) {
                Log.w(TAG, "RemoteException calling onSessionFinished");
            }
            mDirectServiceBinder = null;
            mManager.removeTranslator(mId);
        }
    }

    /**
     * Returns whether or not this Translator has been destroyed.
     *
     * @see #destroy()
     */
    public boolean isDestroyed() {
        synchronized (mLock) {
            return mDestroyed;
        }
    }

    // TODO: add methods for UI-toolkit case.
    /** @hide */
    public void requestUiTranslate(@NonNull TranslationRequest request,
            @NonNull Consumer<TranslationResponse> responseCallback) {
        if (mDirectServiceBinder == null) {
            Log.wtf(TAG, "Translator created without proper initialization.");
            return;
        }
        final ITranslationCallback callback =
                new TranslationResponseCallbackImpl(responseCallback);
        try {
            mDirectServiceBinder.onTranslationRequest(request, mId, callback, null);
        } catch (RemoteException e) {
            Log.w(TAG, "RemoteException calling flushRequest");
        }
    }

    private static class TranslationResponseCallbackImpl extends ITranslationCallback.Stub {

        private final WeakReference<Consumer<TranslationResponse>> mResponseCallback;

        TranslationResponseCallbackImpl(Consumer<TranslationResponse> responseCallback) {
            mResponseCallback = new WeakReference<>(responseCallback);
        }

        @Override
        public void onTranslationComplete(TranslationResponse response) throws RemoteException {
            provideTranslationResponse(response);
        }

        @Override
        public void onError() throws RemoteException {
            provideTranslationResponse(null);
        }

        private void provideTranslationResponse(TranslationResponse response) {
            final Consumer<TranslationResponse> responseCallback = mResponseCallback.get();
            if (responseCallback != null) {
                responseCallback.accept(response);
            }
        }
    }
}
