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

/** Interface to represent operations that can handle touch events */
public interface TouchHandler {

    /**
     * callback for a touch down event
     *
     * @param context the current context
     * @param document the current document
     * @param component the component on which the touch has been received
     * @param x the x position of the click in document coordinates
     * @param y the y position of the click in document coordinates
     */
    void onTouchDown(
            RemoteContext context, CoreDocument document, Component component, float x, float y);

    /**
     * callback for a touch up event
     *
     * @param context the current context
     * @param document the current document
     * @param component the component on which the touch has been received
     * @param x the x position of the click in document coordinates
     * @param y the y position of the click in document coordinates
     * @param dx
     * @param dy
     */
    void onTouchUp(
            RemoteContext context,
            CoreDocument document,
            Component component,
            float x,
            float y,
            float dx,
            float dy);

    /**
     * callback for a touch move event
     *
     * @param context the current context
     * @param document the current document
     * @param component the component on which the touch has been received
     * @param x the x position of the click in document coordinates
     * @param y the y position of the click in document coordinates
     */
    void onTouchDrag(
            RemoteContext context, CoreDocument document, Component component, float x, float y);

    /**
     * callback for a touch cancel event
     *
     * @param context the current context
     * @param document the current document
     * @param component the component on which the touch has been received
     * @param x the x position of the click in document coordinates
     * @param y the y position of the click in document coordinates
     */
    void onTouchCancel(
            RemoteContext context, CoreDocument document, Component component, float x, float y);
}
