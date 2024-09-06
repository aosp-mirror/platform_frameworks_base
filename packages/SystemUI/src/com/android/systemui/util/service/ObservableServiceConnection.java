/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.util.service;

import android.annotation.IntDef;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.IndentingPrintWriter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.DumpUtilsKt;
import com.android.systemui.util.annotations.WeaklyReferencedCallback;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * {@link ObservableServiceConnection} is a concrete implementation of {@link ServiceConnection}
 * that enables monitoring the status of a binder connection. It also aides in automatically
 * converting a proxy into an internal wrapper  type.
 * @param <T> The type of the wrapper over the resulting service.
 */
public class ObservableServiceConnection<T> implements ServiceConnection {
    private static final String TAG = "ObservableSvcConn";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    /**
     * An interface for converting the service proxy into a given internal wrapper type.
     * @param <T> The type of the wrapper over the resulting service.
     */
    public interface ServiceTransformer<T> {
        /**
         * Called to convert the service proxy to the wrapper type.
         * @param service The service proxy to create the wrapper type from.
         * @return The wrapper type.
         */
        T convert(IBinder service);
    }

    /**
     * An interface for listening to the connection status.
     * @param <T> The wrapper type.
     */
    @WeaklyReferencedCallback
    public interface Callback<T> {
        /**
         * Invoked when the service has been successfully connected to.
         * @param connection The {@link ObservableServiceConnection} instance that is now connected
         * @param proxy The service proxy converted into the typed wrapper.
         */
        void onConnected(ObservableServiceConnection<T> connection, T proxy);

        /**
         * Invoked when the service has been disconnected.
         * @param connection The {@link ObservableServiceConnection} that is now disconnected.
         * @param reason The reason for the disconnection.
         */
        void onDisconnected(ObservableServiceConnection<T> connection,
                @DisconnectReason int reason);
    }

    /**
     * Disconnection was due to the resulting binding being {@code null}.
     */
    public static final int DISCONNECT_REASON_NULL_BINDING = 1;
    /**
     * Disconnection was due to the remote end disconnecting.
     */
    public static final int DISCONNECT_REASON_DISCONNECTED = 2;
    /**
     * Disconnection due to the binder dying.
     */
    public static final int DISCONNECT_REASON_BINDING_DIED = 3;
    /**
     * Disconnection from an explicit unbinding.
     */
    public static final int DISCONNECT_REASON_UNBIND = 4;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            DISCONNECT_REASON_NULL_BINDING,
            DISCONNECT_REASON_DISCONNECTED,
            DISCONNECT_REASON_BINDING_DIED,
            DISCONNECT_REASON_UNBIND
    })
    public @interface DisconnectReason {}

    private final Context mContext;
    private final Intent mServiceIntent;
    private final UserTracker mUserTracker;
    private final int mFlags;
    private final Executor mBgExecutor;
    private final ServiceTransformer<T> mTransformer;
    private final ArrayList<WeakReference<Callback<T>>> mCallbacks;
    private Optional<Integer> mLastDisconnectReason;
    private T mProxy;

    private boolean mBoundCalled;

    /**
     * Default constructor for {@link ObservableServiceConnection}.
     * @param context       The context from which the service will be bound with.
     * @param serviceIntent The intent to  bind service with.
     * @param bgExecutor    The executor for connection callbacks to be delivered on
     * @param transformer   A {@link ServiceTransformer} for transforming the resulting service
     *                      into a desired type.
     */
    @Inject
    public ObservableServiceConnection(Context context, Intent serviceIntent,
            UserTracker userTracker,
            @Background Executor bgExecutor,
            ServiceTransformer<T> transformer) {
        mContext = context;
        mServiceIntent = serviceIntent;
        mUserTracker = userTracker;
        mFlags = Context.BIND_AUTO_CREATE;
        mBgExecutor = bgExecutor;
        mTransformer = transformer;
        mCallbacks = new ArrayList<>();
        mLastDisconnectReason = Optional.empty();
    }

    /**
     * Initiate binding to the service in the background.
     */
    public void bind() {
        mBgExecutor.execute(this::bindInternal);
    }

    @WorkerThread
    private void bindInternal() {
        boolean bindResult = false;
        try {
            bindResult = mContext.bindServiceAsUser(mServiceIntent, this, mFlags,
                    mUserTracker.getUserHandle());
            mBoundCalled = true;
        } catch (SecurityException e) {
            Log.d(TAG, "Could not bind to service", e);
            mContext.unbindService(this);
        }
        if (DEBUG) {
            Log.d(TAG, "bind. bound:" + bindResult);
        }
    }

    /**
     * Disconnect from the service if bound.
     */
    public void unbind() {
        mBgExecutor.execute(() -> onDisconnected(DISCONNECT_REASON_UNBIND));
    }

    /**
     * Adds a callback for receiving connection updates. The callback is executed in the background.
     * @param callback The {@link Callback} to receive future updates.
     */
    public void addCallback(Callback<T> callback) {
        if (DEBUG) {
            Log.d(TAG, "addCallback:" + callback);
        }

        mBgExecutor.execute(() -> {
            final Iterator<WeakReference<Callback<T>>> iterator = mCallbacks.iterator();

            while (iterator.hasNext()) {
                if (iterator.next().get() == callback) {
                    return;
                }
            }

            mCallbacks.add(new WeakReference<>(callback));

            // If not connected anymore, immediately inform new callback of disconnection and
            // remove.
            if (mProxy != null) {
                callback.onConnected(this, mProxy);
            } else if (mLastDisconnectReason.isPresent()) {
                callback.onDisconnected(this, mLastDisconnectReason.get());
            }
        });
    }

    /**
     * Removes previously added callback from receiving future connection updates.
     * @param callback The {@link Callback} to be removed.
     */
    public void removeCallback(Callback<T> callback) {
        if (DEBUG) {
            Log.d(TAG, "removeCallback:" + callback);
        }

        mBgExecutor.execute(() -> mCallbacks.removeIf(el-> el.get() == callback));
    }

    @WorkerThread
    private void onDisconnected(@DisconnectReason int reason) {
        if (DEBUG) {
            Log.d(TAG, "onDisconnected:" + reason);
        }

        // If not bound or already unbound, do not proceed setting reason, unbinding, and
        // notifying
        if (!mBoundCalled) {
            return;
        }

        mBoundCalled = false;
        mLastDisconnectReason = Optional.of(reason);
        mContext.unbindService(this);
        mProxy = null;

        applyToCallbacksLocked(callback-> callback.onDisconnected(this,
                mLastDisconnectReason.get()));
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        mBgExecutor.execute(() -> {
            if (DEBUG) {
                Log.d(TAG, "onServiceConnected");
            }
            mProxy = mTransformer.convert(service);
            applyToCallbacksLocked(callback -> callback.onConnected(this, mProxy));
        });
    }

    void dump(@NonNull PrintWriter pw) {
        IndentingPrintWriter ipw = DumpUtilsKt.asIndenting(pw);
        ipw.println("ObservableServiceConnection state:");
        DumpUtilsKt.withIncreasedIndent(ipw, () -> {
            ipw.println("mServiceIntent: " + mServiceIntent);
            ipw.println("mLastDisconnectReason: " + mLastDisconnectReason.orElse(-1));
            ipw.println("Callbacks:");
            DumpUtilsKt.withIncreasedIndent(ipw, () -> {
                for (WeakReference<Callback<T>> cbRef : mCallbacks) {
                    ipw.println(cbRef.get());
                }
            });
        });
    }

    private void applyToCallbacksLocked(Consumer<Callback<T>> applicator) {
        final Iterator<WeakReference<Callback<T>>> iterator = mCallbacks.iterator();

        while (iterator.hasNext()) {
            final Callback<T> cb = iterator.next().get();
            if (cb != null) {
                applicator.accept(cb);
            } else {
                iterator.remove();
            }
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mBgExecutor.execute(() -> onDisconnected(DISCONNECT_REASON_DISCONNECTED));
    }

    @Override
    public void onBindingDied(ComponentName name) {
        mBgExecutor.execute(() -> onDisconnected(DISCONNECT_REASON_BINDING_DIED));
    }

    @Override
    public void onNullBinding(ComponentName name) {
        mBgExecutor.execute(() -> onDisconnected(DISCONNECT_REASON_NULL_BINDING));
    }
}
