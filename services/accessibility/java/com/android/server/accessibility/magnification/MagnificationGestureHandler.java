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

package com.android.server.accessibility.magnification;

import com.android.server.accessibility.BaseEventStreamTransformation;

/**
 * A base class that detects gestures and defines common methods for magnification.
 */
public abstract class MagnificationGestureHandler extends BaseEventStreamTransformation {

    /**
     * Called when the shortcut target is magnification.
     */
    public abstract void notifyShortcutTriggered();
}
