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

package com.android.systemui.volume.panel.component.anc

import androidx.slice.Slice
import androidx.slice.SliceItem
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever

object FakeSliceFactory {

    fun createSlice(hasError: Boolean, hasSliceItem: Boolean): Slice {
        return mock {
            val sliceItem: SliceItem = mock {
                whenever(format).thenReturn(android.app.slice.SliceItem.FORMAT_SLICE)
            }

            whenever(items)
                .thenReturn(
                    buildList {
                        if (hasSliceItem) {
                            add(sliceItem)
                        }
                    }
                )

            whenever(hints)
                .thenReturn(
                    buildList {
                        if (hasError) {
                            add(android.app.slice.Slice.HINT_ERROR)
                        }
                    }
                )
        }
    }
}
