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

/**
 * {@link CommunalSource} defines an interface for working with a source for communal data. Clients
 * may request a communal surface that can be shown within a {@link android.view.SurfaceView}.
 * Callbacks may also be registered to listen to state changes.
 */
public interface CommunalSource {
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

    /**
     * Adds a {@link Callback} to receive future status updates regarding this
     * {@link CommunalSource}.
     *
     * @param callback The {@link Callback} to be added.
     */
    void addCallback(Callback callback);

    /**
     * Removes a {@link Callback} from receiving future updates.
     *
     * @param callback The {@link Callback} to be removed.
     */
    void removeCallback(Callback callback);

    /**
     * An interface for receiving updates on the state of the {@link CommunalSource}.
     */
    interface Callback {
        /**
         * Invoked when the {@link CommunalSource} is no longer available for use.
         */
        void onDisconnected();
    }
}
