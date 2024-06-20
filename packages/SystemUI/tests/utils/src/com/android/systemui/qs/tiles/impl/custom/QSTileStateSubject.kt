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

import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject.Companion.assertThat
import com.android.systemui.qs.tiles.impl.custom.QSTileStateSubject.Companion.states
import com.android.systemui.qs.tiles.impl.custom.TileSubject.Companion.assertThat
import com.android.systemui.qs.tiles.viewmodel.QSTileState
import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Subject.Factory
import com.google.common.truth.Truth

/**
 * [QSTileState]-specific extension for [Truth]. Use [assertThat] or [states] to get an instance of
 * this subject.
 */
class QSTileStateSubject
private constructor(failureMetadata: FailureMetadata, subject: QSTileState?) :
    Subject(failureMetadata, subject) {

    private val actual: QSTileState? = subject

    /** Asserts if the [QSTileState] fields are the same. */
    fun isEqualTo(other: QSTileState?) {
        if (actual == null) {
            check("other").that(other).isNull()
            return
        } else {
            check("other").that(other).isNotNull()
            other ?: return
        }
        check("icon").that(actual.icon()).isEqualTo(other.icon())
        check("iconRes").that(actual.iconRes).isEqualTo(other.iconRes)
        check("label").that(actual.label).isEqualTo(other.label)
        check("activationState").that(actual.activationState).isEqualTo(other.activationState)
        check("secondaryLabel").that(actual.secondaryLabel).isEqualTo(other.secondaryLabel)
        check("label").that(actual.supportedActions).isEqualTo(other.supportedActions)
        check("contentDescription")
            .that(actual.contentDescription)
            .isEqualTo(other.contentDescription)
        check("stateDescription").that(actual.stateDescription).isEqualTo(other.stateDescription)
        check("sideViewIcon").that(actual.sideViewIcon).isEqualTo(other.sideViewIcon)
        check("enabledState").that(actual.enabledState).isEqualTo(other.enabledState)
        check("expandedAccessibilityClassName")
            .that(actual.expandedAccessibilityClassName)
            .isEqualTo(other.expandedAccessibilityClassName)
    }

    companion object {

        /** Returns a factory to be used with [Truth.assertAbout]. */
        fun states(): Factory<QSTileStateSubject, QSTileState?> {
            return Factory { failureMetadata: FailureMetadata, subject: QSTileState? ->
                QSTileStateSubject(failureMetadata, subject)
            }
        }

        /** Shortcut for `Truth.assertAbout(states()).that(state)`. */
        fun assertThat(actual: QSTileState?): QSTileStateSubject =
            Truth.assertAbout(states()).that(actual)
    }
}
