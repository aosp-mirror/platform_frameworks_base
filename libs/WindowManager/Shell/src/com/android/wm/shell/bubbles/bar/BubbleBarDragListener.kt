/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.bubbles.bar

import android.graphics.Rect
import com.android.wm.shell.shared.bubbles.BubbleBarLocation

/** Controller that takes care of the bubble bar drag events. */
interface BubbleBarDragListener {

    /** Called when the drag event is over the bubble bar drop zone. */
    fun onDragItemOverBubbleBarDragZone(location: BubbleBarLocation)

    /** Called when the drag event leaves the bubble bar drop zone. */
    fun onItemDraggedOutsideBubbleBarDropZone()

    /** Called when the drop event happens over the bubble bar drop zone. */
    fun onItemDroppedOverBubbleBarDragZone(location: BubbleBarLocation?)

    /**
     * Returns mapping of the bubble bar locations to the corresponding
     * [rect][android.graphics.Rect] zone.
     */
    fun getBubbleBarDropZones(l: Int, t: Int, r: Int, b: Int): Map<BubbleBarLocation, Rect>
}
