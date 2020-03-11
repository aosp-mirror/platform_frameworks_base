/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.view.WindowInsets.Type.InsetsType;
import android.view.inputmethod.EditorInfo;

/**
 * Interface that informs the client about {@link WindowInsetsAnimationController} state changes.
 */
public interface WindowInsetsAnimationControlListener {

    /**
     * @hide
     */
    default void onPrepare(int types) {
    }

    /**
     * Called when the animation is ready to be controlled. This may be delayed when the IME needs
     * to redraw because of an {@link EditorInfo} change, or when the window is starting up.
     *
     * @param controller The controller to control the inset animation.
     * @param types The {@link InsetsType}s it was able to gain control over. Note that this may be
     *              different than the types passed into
     *              {@link WindowInsetsController#controlWindowInsetsAnimation} in case the window
     *              wasn't able to gain the controls because it wasn't the IME target or not
     *              currently the window that's controlling the system bars.
     */
    void onReady(@NonNull WindowInsetsAnimationController controller, @InsetsType int types);

    /**
     * Called when the window no longer has control over the requested types. If it loses control
     * over one type, the whole control will be cancelled. If none of the requested types were
     * available when requesting the control, the animation control will be cancelled immediately
     * without {@link #onReady} being called.
     */
    void onCancelled();
}
