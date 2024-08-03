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

package com.android.wm.shell.compatui.api

/**
 * Defines the predicates to invoke for understanding if a component can be created or destroyed.
 */
class CompatUILifecyclePredicates(
    // Predicate evaluating to true if the component needs to be created
    val creationPredicate: (CompatUIInfo, CompatUISharedState) -> Boolean,
    // Predicate evaluating to true if the component needs to be destroyed
    val removalPredicate: (
        CompatUIInfo,
        CompatUISharedState,
        CompatUIComponentState?
    ) -> Boolean,
    // Builder for the initial state of the component
    val stateBuilder: (
        CompatUIInfo,
        CompatUISharedState
    ) -> CompatUIComponentState? = { _, _ -> null }
)

/**
 * Describes each compat ui component to the framework.
 */
class CompatUISpec(
    // Unique name for the component. It's used for debug and for generating the
    // unique component identifier in the system.
    val name: String,
    // The lifecycle definition
    val lifecycle: CompatUILifecyclePredicates
)
