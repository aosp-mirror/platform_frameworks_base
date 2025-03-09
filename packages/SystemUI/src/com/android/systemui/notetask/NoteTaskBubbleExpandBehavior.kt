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

package com.android.systemui.notetask

import android.os.Parcel
import android.os.Parcelable

enum class NoteTaskBubbleExpandBehavior : Parcelable {
    /**
     * The default bubble expand behavior for note task bubble: The bubble will collapse if there is
     * already an expanded bubble, The bubble will expand if there is a collapsed bubble.
     */
    DEFAULT,
    /**
     * The special bubble expand behavior for note task bubble: The bubble will stay expanded, not
     * collapse, if there is already an expanded bubble, The bubble will expand if there is a
     * collapsed bubble.
     */
    KEEP_IF_EXPANDED;

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(name)
    }

    companion object CREATOR : Parcelable.Creator<NoteTaskBubbleExpandBehavior> {
        override fun createFromParcel(parcel: Parcel?): NoteTaskBubbleExpandBehavior {
            return parcel?.readString()?.let { valueOf(it) } ?: DEFAULT
        }

        override fun newArray(size: Int) = arrayOfNulls<NoteTaskBubbleExpandBehavior>(size)
    }
}
