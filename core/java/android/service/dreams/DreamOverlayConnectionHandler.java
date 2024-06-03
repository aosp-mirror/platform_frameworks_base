/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.dreams;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ObservableServiceConnection;
import com.android.internal.util.PersistentServiceConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Handles the service connection to {@link IDreamOverlay}
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class DreamOverlayConnectionHandler {
    private static final String TAG = "DreamOverlayConnection";

    private static final int MSG_ADD_CONSUMER = 1;
    private static final int MSG_REMOVE_CONSUMER = 2;
    private static final int MSG_OVERLAY_CLIENT_READY = 3;

    private final Handler mHandler;
    private final PersistentServiceConnection<IDreamOverlay> mConnection;
    // Retrieved Client
    private IDreamOverlayClient mClient;
    // A list of pending requests to execute on the overlay.
    private final List<Consumer<IDreamOverlayClient>> mConsumers = new ArrayList<>();
    private final OverlayConnectionCallback mCallback;

    DreamOverlayConnectionHandler(
            Context context,
            Looper looper,
            Intent serviceIntent,
            int minConnectionDurationMs,
            int maxReconnectAttempts,
            int baseReconnectDelayMs) {
        this(context, looper, serviceIntent, minConnectionDurationMs, maxReconnectAttempts,
                baseReconnectDelayMs, new Injector());
    }

    @VisibleForTesting
    public DreamOverlayConnectionHandler(
            Context context,
            Looper looper,
            Intent serviceIntent,
            int minConnectionDurationMs,
            int maxReconnectAttempts,
            int baseReconnectDelayMs,
            Injector injector) {
        mCallback = new OverlayConnectionCallback();
        mHandler = new Handler(looper, new OverlayHandlerCallback());
        mConnection = injector.buildConnection(
                context,
                mHandler,
                serviceIntent,
                minConnectionDurationMs,
                maxReconnectAttempts,
                baseReconnectDelayMs
        );
    }

    /**
     * Bind to the overlay service. If binding fails, we automatically call unbind to clean
     * up resources.
     *
     * @return true if binding was successful, false otherwise.
     */
    public boolean bind() {
        mConnection.addCallback(mCallback);
        final boolean success = mConnection.bind();
        if (!success) {
            unbind();
        }
        return success;
    }

    /**
     * Unbind from the overlay service, clearing any pending callbacks.
     */
    public void unbind() {
        mConnection.removeCallback(mCallback);
        // Remove any pending messages.
        mHandler.removeCallbacksAndMessages(null);
        mClient = null;
        mConsumers.clear();
        mConnection.unbind();
    }

    /**
     * Adds a consumer to run once the overlay service has connected. If the overlay service
     * disconnects (eg binding dies) and then reconnects, this consumer will be re-run unless
     * removed.
     *
     * @param consumer The consumer to run. This consumer is always executed asynchronously.
     */
    public void addConsumer(Consumer<IDreamOverlayClient> consumer) {
        final Message msg = mHandler.obtainMessage(MSG_ADD_CONSUMER, consumer);
        mHandler.sendMessage(msg);
    }

    /**
     * Removes the consumer, preventing this consumer from being called again.
     *
     * @param consumer The consumer to remove.
     */
    public void removeConsumer(Consumer<IDreamOverlayClient> consumer) {
        final Message msg = mHandler.obtainMessage(MSG_REMOVE_CONSUMER, consumer);
        mHandler.sendMessage(msg);
        // Clear any pending messages to add this consumer
        mHandler.removeMessages(MSG_ADD_CONSUMER, consumer);
    }

    private final class OverlayHandlerCallback implements Handler.Callback {
        @Override
        public boolean handleMessage(@NonNull Message msg) {
            switch (msg.what) {
                case MSG_OVERLAY_CLIENT_READY:
                    onOverlayClientReady((IDreamOverlayClient) msg.obj);
                    break;
                case MSG_ADD_CONSUMER:
                    onAddConsumer((Consumer<IDreamOverlayClient>) msg.obj);
                    break;
                case MSG_REMOVE_CONSUMER:
                    onRemoveConsumer((Consumer<IDreamOverlayClient>) msg.obj);
                    break;
            }
            return true;
        }
    }

    private void onOverlayClientReady(IDreamOverlayClient client) {
        mClient = client;
        for (Consumer<IDreamOverlayClient> consumer : mConsumers) {
            consumer.accept(mClient);
        }
    }

    private void onAddConsumer(Consumer<IDreamOverlayClient> consumer) {
        if (mClient != null) {
            consumer.accept(mClient);
        }
        mConsumers.add(consumer);
    }

    private void onRemoveConsumer(Consumer<IDreamOverlayClient> consumer) {
        mConsumers.remove(consumer);
    }

    private final class OverlayConnectionCallback implements
            ObservableServiceConnection.Callback<IDreamOverlay> {

        private final IDreamOverlayClientCallback mClientCallback =
                new IDreamOverlayClientCallback.Stub() {
                    @Override
                    public void onDreamOverlayClient(IDreamOverlayClient client) {
                        final Message msg =
                                mHandler.obtainMessage(MSG_OVERLAY_CLIENT_READY, client);
                        mHandler.sendMessage(msg);
                    }
                };

        @Override
        public void onConnected(
                ObservableServiceConnection<IDreamOverlay> connection,
                IDreamOverlay service) {
            try {
                service.getClient(mClientCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "could not get DreamOverlayClient", e);
            }
        }

        @Override
        public void onDisconnected(ObservableServiceConnection<IDreamOverlay> connection,
                int reason) {
            mClient = null;
            // Cancel any pending messages about the overlay being ready, since it is no
            // longer ready.
            mHandler.removeMessages(MSG_OVERLAY_CLIENT_READY);
        }
    }

    /**
     * Injector for testing
     */
    @VisibleForTesting
    public static class Injector {
        /**
         * Returns milliseconds since boot, not counting time spent in deep sleep. Can be overridden
         * in tests with a fake clock.
         */
        public PersistentServiceConnection<IDreamOverlay> buildConnection(
                Context context,
                Handler handler,
                Intent serviceIntent,
                int minConnectionDurationMs,
                int maxReconnectAttempts,
                int baseReconnectDelayMs) {
            final Executor executor = handler::post;
            final int flags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE;
            return new PersistentServiceConnection<>(
                    context,
                    executor,
                    handler,
                    IDreamOverlay.Stub::asInterface,
                    serviceIntent,
                    flags,
                    minConnectionDurationMs,
                    maxReconnectAttempts,
                    baseReconnectDelayMs
            );
        }
    }
}
