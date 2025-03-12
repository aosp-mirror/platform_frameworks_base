/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.layout;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.RemoteContext;

/** Interface to represent operations that can handle click events */
public interface ClickHandler {

    /**
     * callback for a click event
     *
     * @param context the current context
     * @param document the current document
     * @param component the component on which the click has been received
     * @param x the x position of the click in document coordinates
     * @param y the y position of the click in document coordinates
     */
    void onClick(
            RemoteContext context, CoreDocument document, Component component, float x, float y);
}
