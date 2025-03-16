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
package com.android.internal.widget.remotecompose.core;

/** Interface used by objects to register for touch events */
public interface TouchListener {
    /**
     * Called when touch down happens
     *
     * @param context The players context
     * @param x the x location of the down touch
     * @param y the y location of the down touch
     */
    void touchDown(RemoteContext context, float x, float y);

    /**
     * called on touch up
     *
     * @param context the players context
     * @param x the x location
     * @param y the y location
     * @param dx the x velocity when the touch up happened
     * @param dy the y valocity when the touch up happened
     */
    void touchUp(RemoteContext context, float x, float y, float dx, float dy);

    /**
     * Drag event (occur between down and up)
     *
     * @param context the players context
     * @param x the x coord of the drag
     * @param y the y coord of the drag
     */
    void touchDrag(RemoteContext context, float x, float y);
}
