/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.compose.test.subjects

import androidx.compose.ui.test.assertIsEqualTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Subject.Factory
import com.google.common.truth.Truth.assertAbout

/** Assert on a [DpOffset]. */
fun assertThat(dpOffset: DpOffset): DpOffsetSubject {
    return assertAbout(DpOffsetSubject.dpOffsets()).that(dpOffset)
}

/** A Truth subject to assert on [DpOffset] with some tolerance. Inspired by FloatSubject. */
class DpOffsetSubject(metadata: FailureMetadata, private val actual: DpOffset) :
    Subject(metadata, actual) {
    fun isWithin(tolerance: Dp): TolerantDpOffsetComparison {
        return object : TolerantDpOffsetComparison {
            override fun of(expected: DpOffset) {
                actual.x.assertIsEqualTo(expected.x, "offset.x", tolerance)
                actual.y.assertIsEqualTo(expected.y, "offset.y", tolerance)
            }
        }
    }

    interface TolerantDpOffsetComparison {
        fun of(expected: DpOffset)
    }

    companion object {
        val DefaultTolerance = Dp(.5f)

        fun dpOffsets() =
            Factory<DpOffsetSubject, DpOffset> { metadata, actual ->
                DpOffsetSubject(metadata, actual)
            }
    }
}
