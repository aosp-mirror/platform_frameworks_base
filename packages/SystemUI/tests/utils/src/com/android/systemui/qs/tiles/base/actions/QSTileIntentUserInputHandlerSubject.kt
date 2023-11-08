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

package com.android.systemui.qs.tiles.base.actions

import com.google.common.truth.FailureMetadata
import com.google.common.truth.Subject
import com.google.common.truth.Truth

/** [Truth] [Subject] to assert inputs handled by [FakeQSTileIntentUserInputHandler] */
class QSTileIntentUserInputHandlerSubject
private constructor(
    failureMetadata: FailureMetadata,
    private val subject: FakeQSTileIntentUserInputHandler
) : Subject(failureMetadata, subject) {

    fun handledOneIntentInput(
        intentAssertions: (FakeQSTileIntentUserInputHandler.Input.Intent) -> Unit = {},
    ) {
        // check that there are no other inputs
        check("handledInputs").that(subject.handledInputs).hasSize(1)
        // check there is an intent input
        check("intentInputs").that(subject.intentInputs).hasSize(1)

        intentAssertions(subject.intentInputs.first())
    }

    fun handledOnePendingIntentInput(
        intentAssertions: (FakeQSTileIntentUserInputHandler.Input.PendingIntent) -> Unit = {},
    ) {
        // check that there are no other inputs
        check("handledInputs").that(subject.handledInputs).hasSize(1)
        // check there is a pending intent input
        check("intentInputs").that(subject.pendingIntentInputs).hasSize(1)

        intentAssertions(subject.pendingIntentInputs.first())
    }

    fun handledNoInputs() {
        check("handledInputs").that(subject.handledInputs).isEmpty()
    }

    companion object {

        /**
         * [Truth.assertThat]-like factory to initialize the assertion. Example:
         * ```
         *  assertThat(inputHandler).handledOneIntentInput {
         *      assertThat(it.intent.action).isEqualTo("action.Test")
         *  }
         * ```
         */
        fun assertThat(
            handler: FakeQSTileIntentUserInputHandler
        ): QSTileIntentUserInputHandlerSubject =
            Truth.assertAbout {
                    failureMetadata: FailureMetadata,
                    subject: FakeQSTileIntentUserInputHandler ->
                    QSTileIntentUserInputHandlerSubject(failureMetadata, subject)
                }
                .that(handler)
    }
}
