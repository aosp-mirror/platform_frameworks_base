/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.plugins;

import android.app.PendingIntent;
import android.view.View;

import com.android.systemui.plugins.annotations.DependsOn;
import com.android.systemui.plugins.annotations.ProvidesInterface;

/**
 * Plugin which provides a "Panel" {@link View} to be rendered inside of the GlobalActions menu.
 *
 * Implementations should construct a new {@link PanelViewController} with the given
 * {@link Callbacks} instance inside of {@link #onPanelShown(Callbacks)}, and should not hold onto
 * a reference, instead allowing Global Actions to manage the lifetime of the object.
 *
 * Under this assumption, {@link PanelViewController} represents the lifetime of a single invocation
 * of the Global Actions menu. The {@link View} for the Panel is generated when the
 * {@link PanelViewController} is constructed, and {@link PanelViewController#getPanelContent()}
 * serves as a simple getter. When Global Actions is dismissed,
 * {@link PanelViewController#onDismissed()} can be used to cleanup any resources allocated when
 * constructed. Global Actions will then release the reference, and the {@link PanelViewController}
 * will be garbage-collected.
 */
@ProvidesInterface(
        action = GlobalActionsPanelPlugin.ACTION, version = GlobalActionsPanelPlugin.VERSION)
@DependsOn(target = GlobalActionsPanelPlugin.Callbacks.class)
@DependsOn(target = GlobalActionsPanelPlugin.PanelViewController.class)
public interface GlobalActionsPanelPlugin extends Plugin {
    String ACTION = "com.android.systemui.action.PLUGIN_GLOBAL_ACTIONS_PANEL";
    int VERSION = 0;

    /**
     * Invoked when the GlobalActions menu is shown.
     *
     * @param callbacks {@link Callbacks} instance that can be used by the Panel to interact with
     *                  the Global Actions menu.
     * @return A {@link PanelViewController} instance used to receive Global Actions events.
     */
    PanelViewController onPanelShown(Callbacks callbacks);

    /**
     * Provides methods to interact with the Global Actions menu.
     */
    @ProvidesInterface(version = Callbacks.VERSION)
    interface Callbacks {
        int VERSION = 0;

        /** Dismisses the Global Actions menu. */
        void dismissGlobalActionsMenu();

        /** Starts a PendingIntent, dismissing the keyguard if necessary. */
        default void startPendingIntentDismissingKeyguard(PendingIntent pendingIntent) {
            try {
                pendingIntent.send();
            } catch (PendingIntent.CanceledException e) {
                // no-op
            }
        }
    }

    /**
     * Receives Global Actions events, and provides the Panel {@link View}.
     */
    @ProvidesInterface(version = PanelViewController.VERSION)
    interface PanelViewController {
        int VERSION = 0;

        /**
         * Returns the {@link View} for the Panel to be rendered in Global Actions. This View can be
         * any size, and will be rendered above the Global Actions menu when z-ordered.
         */
        View getPanelContent();

        /**
         * Invoked when the Global Actions menu (containing the View returned from
         * {@link #getPanelContent()}) is dismissed.
         */
        void onDismissed();
    }
}
