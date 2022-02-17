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

package com.android.systemui.communal;

import android.content.Context;
import android.view.View;

import com.android.systemui.util.ViewController;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Optional;

/**
 * {@link CommunalSource} defines an interface for working with a source for communal data. Clients
 * may request a communal surface that can be shown within a {@link android.view.SurfaceView}.
 * Callbacks may also be registered to listen to state changes.
 */
public interface CommunalSource {
    /**
     * {@link Connector} defines an interface for {@link CommunalSource} instances to be generated.
     */
    interface Connector {
        Connection connect(Connection.Callback callback);
    }

    /**
     * {@link Connection} defines an interface for an entity which holds the necessary components
     * for establishing and maintaining a connection to the communal source.
     */
    interface Connection {
        /**
         * {@link Callback} defines an interface for clients to be notified when a source is ready
         */
        interface Callback {
            void onSourceEstablished(Optional<CommunalSource> source);
            void onDisconnected();
        }

        void disconnect();
    }

    /**
     * The {@link Observer} interface specifies an entity which {@link CommunalSource} listeners
     * can be informed of changes to the source, which will require updating. Note that this deals
     * with changes to the source itself, not content which will be updated through the
     * {@link CommunalSource} interface.
     */
    interface Observer {
        interface Callback {
            void onSourceChanged();
        }

        void addCallback(Callback callback);
        void removeCallback(Callback callback);
    }

    /**
     * {@link CommunalViewResult} is handed back from {@link #requestCommunalView(Context)} and
     * contains the view to be displayed and its associated controller.
     */
    class CommunalViewResult {
        /**
         * The resulting communal view.
         */
        public final View view;
        /**
         * The controller for the communal view.
         */
        public final ViewController<? extends View> viewController;

        /**
         * The default constructor for {@link CommunalViewResult}.
         * @param view The communal view.
         * @param viewController The communal view's controller.
         */
        public CommunalViewResult(View view, ViewController<? extends View> viewController) {
            this.view = view;
            this.viewController = viewController;
        }
    }

    /**
     * Requests a communal surface that can be displayed inside {@link CommunalHostView}.
     *
     * @param context The {@link View} {@link Context} to build the resulting view from
     * @return A future that can be listened upon for the resulting {@link CommunalViewResult}. The
     * value will be {@code null} in case of a failure.
     */
    ListenableFuture<CommunalViewResult> requestCommunalView(Context context);
}
