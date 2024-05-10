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

package com.android.server.wm;

/**
 * Interface for listening to changes in a {@link WindowContainer}. A usage of this listener is
 * to receive the changes and propagate them to the client side.
 */
interface WindowContainerListener extends ConfigurationContainerListener {

    /** @see WindowContainer#onDisplayChanged(DisplayContent) */
    default void onDisplayChanged(DisplayContent dc) {}

    /** Called when {@link WindowContainer#removeImmediately()} is invoked. */
    default void onRemoved() {}

    /**
     * Only invoked if the child successfully requested a visibility change.
     *
     * @param isVisibleRequested The current {@link WindowContainer#isVisibleRequested()} of this
     *                           {@link WindowContainer} (not of the child).
     * @see WindowContainer#onChildVisibleRequestedChanged(WindowContainer)
     */
    default void onVisibleRequestedChanged(boolean isVisibleRequested) { }
}
