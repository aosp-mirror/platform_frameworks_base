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

package com.android.systemui.activity

import android.app.Activity
import android.content.Intent
import androidx.test.runner.intercepting.SingleActivityFactory

/**
 * Builds a new [SingleActivityFactory] which delegating any call of [SingleActivityFactory.create]
 * to the [instantiate] parameter.
 *
 * For more details, see [SingleActivityFactory].
 */
inline fun <reified T : Activity> SingleActivityFactory(
    crossinline instantiate: (intent: Intent?) -> T,
): SingleActivityFactory<T> {
    return object : SingleActivityFactory<T>(T::class.java) {
        override fun create(intent: Intent?): T = instantiate(intent)
    }
}
