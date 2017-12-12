/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import android.graphics.Rect;

/**
 * Interface used by the creator of {@link PinnedStackWindowController} to listen to changes with
 * the stack container.
 */
public interface PinnedStackWindowListener extends StackWindowListener {

    /**
     * Called when the stack container pinned stack animation will change the picture-in-picture
     * mode. This is a direct call into ActivityManager.
     */
    default void updatePictureInPictureModeForPinnedStackAnimation(Rect targetStackBounds,
            boolean forceUpdate) {}
}
