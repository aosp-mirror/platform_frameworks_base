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
package android.app.search;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.search.ISearchCallback.Stub;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import dalvik.system.CloseGuard;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Client needs to create {@link SearchSession} object from in order to execute
 * {@link #query(Query, Executor, Consumer)} method and share client side signals
 * back to the service using {@link #notifyEvent(Query, SearchTargetEvent)}.
 *
 * <p>
 * Usage: <pre> {@code
 *
 * class MyActivity {
 *
 *    void onCreate() {
 *         mSearchSession.createSearchSession(searchContext)
 *    }
 *
 *    void afterTextChanged(...) {
 *        mSearchSession.query(...);
 *    }
 *
 *    void onTouch(...) OR
 *    void onStateTransitionStarted(...) OR
 *    void onResume(...) OR
 *    void onStop(...) {
 *        mSearchSession.notifyEvent(event);
 *    }
 *
 *    void onDestroy() {
 *        mSearchSession.close();
 *    }
 *
 * }</pre>
 *
 * @hide
 */
@SystemApi
public final class SearchSession implements AutoCloseable {

    private static final String TAG = SearchSession.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final android.app.search.ISearchUiManager mInterface;
    private final CloseGuard mCloseGuard = CloseGuard.get();
    private final AtomicBoolean mIsClosed = new AtomicBoolean(false);

    private final SearchSessionId mSessionId;
    private final IBinder mToken = new Binder();

    /**
     * Creates a new search ui client.
     * <p>
     * The caller should call {@link SearchSession#destroy()} to dispose the client once it
     * no longer used.
     *
     * @param context       the {@link Context} of the user of this {@link SearchSession}.
     * @param searchContext the search context.
     */
    // b/175668315 Create weak reference child objects to not leak context.
    SearchSession(@NonNull Context context, @NonNull SearchContext searchContext) {
        IBinder b = ServiceManager.getService(Context.SEARCH_UI_SERVICE);
        mInterface = android.app.search.ISearchUiManager.Stub.asInterface(b);
        mSessionId = new SearchSessionId(
                context.getPackageName() + ":" + UUID.randomUUID().toString(), context.getUserId());
        // b/175527717 whitelist possible clients of this API
        searchContext.setPackageName(context.getPackageName());
        try {
            mInterface.createSearchSession(searchContext, mSessionId, mToken);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to search session", e);
            e.rethrowFromSystemServer();
        }

        mCloseGuard.open("SearchSession.close");
    }

    /**
     * Notifies the search service of an search target event (e.g., user interaction
     * and lifecycle event of the search surface).
     *
     * {@see SearchTargetEvent}
     *
     * @param query input object associated with the event.
     * @param event The {@link SearchTargetEvent} that represents the search target event.
     */
    public void notifyEvent(@NonNull Query query, @NonNull SearchTargetEvent event) {
        if (mIsClosed.get()) {
            throw new IllegalStateException("This client has already been destroyed.");
        }

        try {
            mInterface.notifyEvent(mSessionId, query, event);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to notify event", e);
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Calls consumer with list of {@link SearchTarget}s based on the input query.
     *
     * @param input query object to be used for the request.
     * @param callbackExecutor The callback executor to use when calling the callback.
     * @param callback The callback to return the list of search targets.
     */
    @Nullable
    public void query(@NonNull Query input,
            @NonNull @CallbackExecutor Executor callbackExecutor,
            @NonNull Consumer<List<SearchTarget>> callback) {
        if (mIsClosed.get()) {
            throw new IllegalStateException("This client has already been destroyed.");
        }

        try {

            mInterface.query(mSessionId, input, new CallbackWrapper(callbackExecutor, callback));
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to sort targets", e);
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Destroys the client and unregisters the callback. Any method on this class after this call
     * will throw {@link IllegalStateException}.
     *
     * @deprecated
     * @removed
     */
    @Deprecated
    public void destroy() {
        if (!mIsClosed.getAndSet(true)) {
            mCloseGuard.close();

            // Do destroy;
            try {
                mInterface.destroySearchSession(mSessionId);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify search target event", e);
                e.rethrowFromSystemServer();
            }
        } else {
            throw new IllegalStateException("This client has already been destroyed.");
        }
    }

    @Override
    protected void finalize() {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            if (!mIsClosed.get()) {
                destroy();
            }
        } finally {
            try {
                super.finalize();
            } catch (Throwable throwable) {
                throwable.printStackTrace();
            }
        }
    }

    /**
     * Destroys the client and unregisters the callback. Any method on this class after this call
     * will throw {@link IllegalStateException}.
     *
     */
    @Override
    public void close() {
        try {
            finalize();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

    static class CallbackWrapper extends Stub {

        private final Consumer<List<SearchTarget>> mCallback;
        private final Executor mExecutor;

        CallbackWrapper(@NonNull Executor callbackExecutor,
                @NonNull Consumer<List<SearchTarget>> callback) {
            mCallback = callback;
            mExecutor = callbackExecutor;
        }

        @Override
        public void onResult(ParceledListSlice result) {
            final long identity = Binder.clearCallingIdentity();
            try {
                if (DEBUG) {
                    Log.d(TAG, "CallbackWrapper.onResult result=" + result.getList());
                }
                List<SearchTarget> list = result.getList();
                if (list.size() > 0) {
                    Bundle bundle = list.get(0).getExtras();
                    if (bundle != null) {
                        bundle.putLong("key_ipc_start", SystemClock.elapsedRealtime());
                    }
                }
                mExecutor.execute(() -> mCallback.accept(list));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
