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
package android.service.search;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.app.search.ISearchCallback;
import android.app.search.Query;
import android.app.search.SearchContext;
import android.app.search.SearchSessionId;
import android.app.search.SearchTarget;
import android.app.search.SearchTargetEvent;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.service.search.ISearchUiService.Stub;
import android.util.ArrayMap;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A service used to share the lifecycle of search UI (open, close, interaction)
 * and also to return search result on a query.
 *
 * To understand the lifecycle of search session and how a query get issued,
 * {@see SearchSession}
 *
 * @hide
 */
@SystemApi
public abstract class SearchUiService extends Service {

    private static final boolean DEBUG = false;
    private static final String TAG = "SearchUiService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>The service must also require the {@link android.permission#MANAGE_SEARCH_UI}
     * permission.
     *
     * @hide
     */
    public static final String SERVICE_INTERFACE =
            "android.service.search.SearchUiService";

    private final ArrayMap<SearchSessionId, ArrayList<CallbackWrapper>>
            mSessionEmptyQueryResultCallbacks = new ArrayMap<>();

    private Handler mHandler;

    private final android.service.search.ISearchUiService mInterface = new Stub() {

        @Override
        public void onCreateSearchSession(SearchContext context, SearchSessionId sessionId) {
            mHandler.sendMessage(
                    obtainMessage(SearchUiService::onSearchSessionCreated,
                            SearchUiService.this, context, sessionId));
            // to be removed
            mHandler.sendMessage(
                    obtainMessage(SearchUiService::onCreateSearchSession,
                            SearchUiService.this, context, sessionId));
        }

        @Override
        public void onQuery(SearchSessionId sessionId, Query input,
                ISearchCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(SearchUiService::onQuery,
                            SearchUiService.this, sessionId, input,
                            new CallbackWrapper(callback, null)));
        }

        @Override
        public void onNotifyEvent(SearchSessionId sessionId, Query query, SearchTargetEvent event) {
            mHandler.sendMessage(
                    obtainMessage(SearchUiService::onNotifyEvent,
                            SearchUiService.this, sessionId, query, event));
        }

        @Override
        public void onRegisterEmptyQueryResultUpdateCallback(SearchSessionId sessionId,
                ISearchCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(SearchUiService::doRegisterEmptyQueryResultUpdateCallback,
                            SearchUiService.this, sessionId, callback));
        }

        @Override
        public void onUnregisterEmptyQueryResultUpdateCallback(SearchSessionId sessionId,
                ISearchCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(SearchUiService::doUnregisterEmptyQueryResultUpdateCallback,
                            SearchUiService.this, sessionId, callback));
        }

        @Override
        public void onDestroy(SearchSessionId sessionId) {
            mHandler.sendMessage(
                    obtainMessage(SearchUiService::doDestroy,
                            SearchUiService.this, sessionId));
        }
    };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
    }

    @Override
    @NonNull
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Slog.w(TAG, "Tried to bind to wrong intent (should be "
                + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    /**
     * Creates a new search session.
     *
     * @removed
     * @deprecated this is method will be removed as soon as
     * {@link #onSearchSessionCreated(SearchContext, SearchSessionId)}
     * is adopted by the service.
     */
    @Deprecated
    public void onCreateSearchSession(@NonNull SearchContext context,
            @NonNull SearchSessionId sessionId) {
    }

    /**
     * A new search session is created.
     */
    public void onSearchSessionCreated(@NonNull SearchContext context,
            @NonNull SearchSessionId sessionId) {
        mSessionEmptyQueryResultCallbacks.put(sessionId, new ArrayList<>());
    }

    /**
     * Called by the client to request search results using a query string.
     */
    @MainThread
    public abstract void onQuery(@NonNull SearchSessionId sessionId,
            @NonNull Query query,
            @NonNull Consumer<List<SearchTarget>> callback);

    /**
     * Called by a client to indicate an interaction (tap, long press, drag, etc) on target(s)
     * and lifecycle event on the search surface (e.g., visibility change).
     *
     * {@see SearchTargetEvent}
     */
    @MainThread
    public abstract void onNotifyEvent(@NonNull SearchSessionId sessionId,
            @NonNull Query query,
            @NonNull SearchTargetEvent event);

    private void doRegisterEmptyQueryResultUpdateCallback(@NonNull SearchSessionId sessionId,
            @NonNull ISearchCallback callback) {
        final ArrayList<CallbackWrapper> callbacks = mSessionEmptyQueryResultCallbacks.get(
                sessionId);
        if (callbacks == null) {
            Slog.e(TAG, "Failed to register for updates for unknown session: " + sessionId);
            return;
        }

        final CallbackWrapper wrapper = findCallbackWrapper(callbacks, callback);
        if (wrapper == null) {
            callbacks.add(new CallbackWrapper(callback,
                    callbackWrapper ->
                            mHandler.post(() ->
                                    removeCallbackWrapper(callbacks, callbackWrapper))));
            if (callbacks.size() == 1) {
                onStartUpdateEmptyQueryResult();
            }
        }
    }

    /**
     * Called when the first empty query result callback is registered. Service provider may make
     * their own decision whether to generate data if no callback is registered to optimize for
     * system health.
     */
    @MainThread
    public void onStartUpdateEmptyQueryResult() {}

    private void doUnregisterEmptyQueryResultUpdateCallback(@NonNull SearchSessionId sessionId,
            @NonNull ISearchCallback callback) {
        final ArrayList<CallbackWrapper> callbacks = mSessionEmptyQueryResultCallbacks.get(
                sessionId);
        if (callbacks == null) {
            Slog.e(TAG, "Failed to unregister for updates for unknown session: " + sessionId);
            return;
        }

        final CallbackWrapper wrapper = findCallbackWrapper(callbacks, callback);
        removeCallbackWrapper(callbacks, wrapper);
    }

    /**
     * Finds the callback wrapper for the given callback.
     */
    private CallbackWrapper findCallbackWrapper(ArrayList<CallbackWrapper> callbacks,
            ISearchCallback callback) {
        for (int i = callbacks.size() - 1; i >= 0; i--) {
            if (callbacks.get(i).isCallback(callback)) {
                return callbacks.get(i);
            }
        }
        return null;
    }

    private void removeCallbackWrapper(@Nullable ArrayList<CallbackWrapper> callbacks,
            @Nullable CallbackWrapper wrapper) {
        if (callbacks == null || wrapper == null) {
            return;
        }
        callbacks.remove(wrapper);
        wrapper.destroy();
        if (callbacks.isEmpty()) {
            onStopUpdateEmptyQueryResult();
        }
    }

    /**
     * Called when there are no longer any empty query result callbacks registered. Service
     * provider can choose to stop generating data to optimize for system health.
     */
    @MainThread
    public void onStopUpdateEmptyQueryResult() {}

    private void doDestroy(@NonNull SearchSessionId sessionId) {
        super.onDestroy();
        onDestroy(sessionId);
    }

    /**
     * Destroys a search session.
     */
    @MainThread
    public abstract void onDestroy(@NonNull SearchSessionId sessionId);

    /**
     * Used by the service provider to send back results the client app. The can be called
     * in response to {@link #onRequestEmptyQueryResultUpdate(SearchSessionId)} or proactively as
     * a result of changes in zero state data.
     */
    public final void updateEmptyQueryResult(@NonNull SearchSessionId sessionId,
            @NonNull List<SearchTarget> targets) {
        List<CallbackWrapper> callbacks = mSessionEmptyQueryResultCallbacks.get(sessionId);
        if (callbacks != null) {
            for (CallbackWrapper callback : callbacks) {
                callback.accept(targets);
            }
        }
    }

    private static final class CallbackWrapper implements Consumer<List<SearchTarget>>,
            IBinder.DeathRecipient {

        private ISearchCallback mCallback;
        private final Consumer<CallbackWrapper> mOnBinderDied;

        CallbackWrapper(ISearchCallback callback,
                @Nullable Consumer<CallbackWrapper> onBinderDied) {
            mCallback = callback;
            mOnBinderDied = onBinderDied;
            if (mOnBinderDied != null) {
                try {
                    mCallback.asBinder().linkToDeath(this, 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to link to death:" + e);
                }
            }
        }

        public boolean isCallback(@NonNull ISearchCallback callback) {
            if (mCallback == null) {
                Slog.e(TAG, "Callback is null, likely the binder has died.");
                return false;
            }
            return mCallback.asBinder().equals(callback.asBinder());
        }


        @Override
        public void accept(List<SearchTarget> searchTargets) {
            try {
                if (mCallback != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "CallbackWrapper.accept searchTargets=" + searchTargets);
                    }
                    mCallback.onResult(new ParceledListSlice(searchTargets));
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Error sending result:" + e);
            }
        }

        public void destroy() {
            if (mCallback != null && mOnBinderDied != null) {
                mCallback.asBinder().unlinkToDeath(this, 0);
            }
        }

        @Override
        public void binderDied() {
            destroy();
            mCallback = null;
            if (mOnBinderDied != null) {
                mOnBinderDied.accept(this);
            }
        }
    }
}
