/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.contextualsearch;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.contextualsearch.flags.Flags;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.Executor;

/**
 * Used to share a single use token with the contextual search handling activity via the launch
 * extras bundle.
 * The caller can then use this token to get {@link ContextualSearchState} by calling
 * {@link #getContextualSearchState}.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_SERVICE)
@SystemApi
public final class CallbackToken implements Parcelable {
    private static final boolean DEBUG = true;
    private static final String TAG = CallbackToken.class.getSimpleName();
    private final IBinder mToken;

    private final Object mLock = new Object();
    private boolean mTokenUsed = false;

    public CallbackToken() {
        mToken = new Binder();
    }

    private CallbackToken(Parcel in) {
        mToken = in.readStrongBinder();
    }

    /**
     * Returns the {@link ContextualSearchState} to the handler via the provided callback. The
     * method can only be invoked to provide the {@link OutcomeReceiver} once and all subsequent
     * invocations of this method will result in {@link OutcomeReceiver#onError} being called with
     * an {@link IllegalAccessException}.
     *
     * Note that the callback could be invoked multiple times, e.g. in the case of split screen.
     *
     * @param executor The executor which will be used to invoke the callback.
     * @param callback The callback which will be used to return {@link ContextualSearchState}
     *                 if/when it is available via {@link OutcomeReceiver#onResult}. It will also be
     *                 used to return errors via {@link OutcomeReceiver#onError}.
     */
    public void getContextualSearchState(@NonNull @CallbackExecutor Executor executor,
            @NonNull OutcomeReceiver<ContextualSearchState, Throwable> callback) {
        if (DEBUG) Log.d(TAG, "getContextualSearchState for token:" + mToken);
        boolean tokenUsed;
        synchronized (mLock) {
            tokenUsed = markUsedLocked();
        }
        if (tokenUsed) {
            callback.onError(new IllegalAccessException("Token already used."));
            return;
        }
        try {
            // Get the service from the system server.
            IBinder b = ServiceManager.getService(Context.CONTEXTUAL_SEARCH_SERVICE);
            IContextualSearchManager service = IContextualSearchManager.Stub.asInterface(b);
            final CallbackWrapper wrapper = new CallbackWrapper(executor, callback);
            // If the service is not null, hand over the call to the service.
            if (service != null) {
                service.getContextualSearchState(mToken, wrapper);
            } else {
                Log.w(TAG, "Failed to getContextualSearchState. Service null.");
            }
        } catch (RemoteException e) {
            if (DEBUG) Log.d(TAG, "Failed to call getContextualSearchState", e);
            e.rethrowFromSystemServer();
        }
    }

    private boolean markUsedLocked() {
        boolean oldValue = mTokenUsed;
        mTokenUsed = true;
        return oldValue;
    }

    /**
     * Return the token necessary for validating the caller of {@link #getContextualSearchState}.
     *
     * @hide
     */
    @TestApi
    @NonNull
    public IBinder getToken() {
        return mToken;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mToken);
    }

    @NonNull
    public static final Creator<CallbackToken> CREATOR = new Creator<>() {
        @Override
        public CallbackToken createFromParcel(Parcel in) {
            return new CallbackToken(in);
        }

        @Override
        public CallbackToken[] newArray(int size) {
            return new CallbackToken[size];
        }
    };

    private static class CallbackWrapper extends IContextualSearchCallback.Stub {
        private final OutcomeReceiver<ContextualSearchState, Throwable> mCallback;
        private final Executor mExecutor;

        CallbackWrapper(@NonNull Executor callbackExecutor,
                @NonNull OutcomeReceiver<ContextualSearchState, Throwable> callback) {
            mCallback = callback;
            mExecutor = callbackExecutor;
        }

        @Override
        public void onResult(ContextualSearchState state) {
            Binder.withCleanCallingIdentity(() -> {
                if (DEBUG) Log.d(TAG, "onResult state:" + state);
                mExecutor.execute(() -> mCallback.onResult(state));
            });
        }

        @Override
        public void onError(ParcelableException error) {
            Binder.withCleanCallingIdentity(() -> {
                if (DEBUG) Log.w(TAG, "onError", error);
                mExecutor.execute(() -> mCallback.onError(error));
            });
        }
    }
}
