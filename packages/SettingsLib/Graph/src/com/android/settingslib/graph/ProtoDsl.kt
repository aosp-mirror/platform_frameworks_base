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

package com.android.settingslib.graph

import com.android.settingslib.graph.proto.BundleProto
import com.android.settingslib.graph.proto.BundleProto.BundleValue
import com.android.settingslib.graph.proto.IntentProto
import com.android.settingslib.graph.proto.PreferenceGroupProto
import com.android.settingslib.graph.proto.PreferenceOrGroupProto
import com.android.settingslib.graph.proto.PreferenceProto
import com.android.settingslib.graph.proto.PreferenceProto.ActionTarget
import com.android.settingslib.graph.proto.PreferenceScreenProto
import com.android.settingslib.graph.proto.PreferenceValueProto
import com.android.settingslib.graph.proto.TextProto

/** Returns root or null. */
val PreferenceScreenProto.rootOrNull
    get() = if (hasRoot()) root else null

/** Kotlin DSL-style builder for [PreferenceScreenProto]. */
@JvmSynthetic
inline fun preferenceScreenProto(init: PreferenceScreenProto.Builder.() -> Unit) =
    PreferenceScreenProto.newBuilder().also(init).build()

/** Returns preference or null. */
val PreferenceOrGroupProto.preferenceOrNull
    get() = if (hasPreference()) preference else null

/** Returns group or null. */
val PreferenceOrGroupProto.groupOrNull
    get() = if (hasGroup()) group else null

/** Kotlin DSL-style builder for [PreferenceOrGroupProto]. */
@JvmSynthetic
inline fun preferenceOrGroupProto(init: PreferenceOrGroupProto.Builder.() -> Unit) =
    PreferenceOrGroupProto.newBuilder().also(init).build()

/** Returns preference or null. */
val PreferenceGroupProto.preferenceOrNull
    get() = if (hasPreference()) preference else null

/** Kotlin DSL-style builder for [PreferenceGroupProto]. */
@JvmSynthetic
inline fun preferenceGroupProto(init: PreferenceGroupProto.Builder.() -> Unit) =
    PreferenceGroupProto.newBuilder().also(init).build()

/** Returns title or null. */
val PreferenceProto.titleOrNull
    get() = if (hasTitle()) title else null

/** Returns summary or null. */
val PreferenceProto.summaryOrNull
    get() = if (hasSummary()) summary else null

/** Returns actionTarget or null. */
val PreferenceProto.actionTargetOrNull
    get() = if (hasActionTarget()) actionTarget else null

/** Kotlin DSL-style builder for [PreferenceProto]. */
@JvmSynthetic
inline fun preferenceProto(init: PreferenceProto.Builder.() -> Unit) =
    PreferenceProto.newBuilder().also(init).build()

/** Returns intent or null. */
val ActionTarget.intentOrNull
    get() = if (hasIntent()) intent else null

/** Kotlin DSL-style builder for [ActionTarget]. */
@JvmSynthetic
inline fun actionTargetProto(init: ActionTarget.Builder.() -> Unit) =
    ActionTarget.newBuilder().also(init).build()

/** Kotlin DSL-style builder for [PreferenceValueProto]. */
@JvmSynthetic
inline fun preferenceValueProto(init: PreferenceValueProto.Builder.() -> Unit) =
    PreferenceValueProto.newBuilder().also(init).build()

/** Kotlin DSL-style builder for [TextProto]. */
@JvmSynthetic
inline fun textProto(init: TextProto.Builder.() -> Unit) = TextProto.newBuilder().also(init).build()

/** Kotlin DSL-style builder for [IntentProto]. */
@JvmSynthetic
inline fun intentProto(init: IntentProto.Builder.() -> Unit) =
    IntentProto.newBuilder().also(init).build()

/** Kotlin DSL-style builder for [BundleProto]. */
@JvmSynthetic
inline fun bundleProto(init: BundleProto.Builder.() -> Unit) =
    BundleProto.newBuilder().also(init).build()

/** Kotlin DSL-style builder for [BundleValue]. */
@JvmSynthetic
inline fun bundleValueProto(init: BundleValue.Builder.() -> Unit) =
    BundleValue.newBuilder().also(init).build()
