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

package com.android.wm.shell.pip;

import android.content.res.Configuration;
import android.graphics.Rect;

import com.android.wm.shell.common.annotations.ExternalThread;

import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Interface to engage picture in picture feature.
 */
@ExternalThread
public interface Pip {

    /**
     * Returns a binder that can be passed to an external process to manipulate PIP.
     */
    default IPip createExternalInterface() {
        return null;
    }

    /**
     * Expand PIP, it's possible that specific request to activate the window via Alt-tab.
     */
    default void expandPip() {
    }

    /**
     * Called when configuration is changed.
     */
    default void onConfigurationChanged(Configuration newConfig) {
    }

    /**
     * Called when display size or font size of settings changed
     */
    default void onDensityOrFontScaleChanged() {
    }

    /**
     * Called when overlay package change invoked.
     */
    default void onOverlayChanged() {
    }

    /**
     * Called when SysUI state changed.
     *
     * @param isSysUiStateValid Is SysUI state valid or not.
     * @param flag Current SysUI state.
     */
    default void onSystemUiStateChanged(boolean isSysUiStateValid, int flag) {
    }

    /**
     * Registers the session listener for the current user.
     */
    default void registerSessionListenerForCurrentUser() {
    }

    /**
     * Sets both shelf visibility and its height.
     *
     * @param visible visibility of shelf.
     * @param height  to specify the height for shelf.
     */
    default void setShelfHeight(boolean visible, int height) {
    }

    /**
     * Set the callback when {@link PipTaskOrganizer#isInPip()} state is changed.
     *
     * @param callback The callback accepts the result of {@link PipTaskOrganizer#isInPip()}
     *                 when it's changed.
     */
    default void setOnIsInPipStateChangedListener(Consumer<Boolean> callback) {}

    /**
     * Set the pinned stack with {@link PipAnimationController.AnimationType}
     *
     * @param animationType The pre-defined {@link PipAnimationController.AnimationType}
     */
    default void setPinnedStackAnimationType(int animationType) {
    }

    /**
     * Called when showing Pip menu.
     */
    default void showPictureInPictureMenu() {}

    /**
     * Called by NavigationBar and TaskbarDelegate in order to listen in for PiP bounds change. This
     * is mostly used for times where the PiP bounds could conflict with SystemUI elements, such as
     * a stashed PiP and the Back-from-Edge gesture.
     */
    default void addPipExclusionBoundsChangeListener(Consumer<Rect> listener) { }

    /**
     * Remove a callback added previously. This is used when NavigationBar is removed from the
     * view hierarchy or destroyed.
     */
    default void removePipExclusionBoundsChangeListener(Consumer<Rect> listener) { }

    /**
     * Called when the visibility of keyguard is changed.
     * @param showing {@code true} if keyguard is now showing, {@code false} otherwise.
     * @param animating {@code true} if system is animating between keyguard and surface behind,
     *                              this only makes sense when showing is {@code false}.
     */
    default void onKeyguardVisibilityChanged(boolean showing, boolean animating) { }

    /**
     * Called when the dismissing animation keyguard and surfaces behind is finished.
     * See also {@link #onKeyguardVisibilityChanged(boolean, boolean)}.
     *
     * TODO(b/206741900) deprecate this path once we're able to animate the PiP window as part of
     * keyguard dismiss animation.
     */
    default void onKeyguardDismissAnimationFinished() { }

    /**
     * Dump the current state and information if need.
     *
     * @param pw The stream to dump information to.
     */
    default void dump(PrintWriter pw) {
    }
}
