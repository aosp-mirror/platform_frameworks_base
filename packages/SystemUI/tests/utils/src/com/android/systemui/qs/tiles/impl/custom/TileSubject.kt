/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.custom

import android.service.quicksettings.Tile
import com.android.systemui.qs.tiles.impl.custom.TileSubject.Companion.assertThat
import com.android.systemui.qs.tiles.impl.custom.TileSubject.Companion.tiles
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Subject.Factory
import com.google.common.truth.Truth

/**
 * [Tile]-specific extension for [Truth]. Use [assertThat] or [tiles] to get an instance of this
 * subject.
 */
class TileSubject private constructor(failureMetadata: FailureMetadata, subject: Tile?) :
    Subject(failureMetadata, subject) {

    private val actual: Tile? = subject

    /** Asserts if the [Tile] fields are the same. */
    fun isEqualTo(other: Tile?) {
        if (actual == null) {
            check("other").that(other).isNull()
            return
        } else {
            check("other").that(other).isNotNull()
            other ?: return
        }

        check("icon").that(actual.icon).isEqualTo(other.icon)
        check("label").that(actual.label).isEqualTo(other.label)
        check("subtitle").that(actual.subtitle).isEqualTo(other.subtitle)
        check("contentDescription")
            .that(actual.contentDescription)
            .isEqualTo(other.contentDescription)
        check("stateDescription").that(actual.stateDescription).isEqualTo(other.stateDescription)
        check("activityLaunchForClick")
            .that(actual.activityLaunchForClick)
            .isEqualTo(other.activityLaunchForClick)
        check("state").that(actual.state).isEqualTo(other.state)
    }

    companion object {

        /** Returns a factory to be used with [Truth.assertAbout]. */
        fun tiles(): Factory<TileSubject, Tile?> {
            return Factory { failureMetadata: FailureMetadata, subject: Tile? ->
                TileSubject(failureMetadata, subject)
            }
        }

        /** Shortcut for `Truth.assertAbout(tiles()).that(tile)`. */
        fun assertThat(tile: Tile?): TileSubject = Truth.assertAbout(tiles()).that(tile)
    }
}
