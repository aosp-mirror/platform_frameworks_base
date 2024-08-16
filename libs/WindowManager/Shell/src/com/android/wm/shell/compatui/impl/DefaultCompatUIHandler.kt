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

package com.android.wm.shell.compatui.impl

import com.android.wm.shell.compatui.api.CompatUIComponent
import com.android.wm.shell.compatui.api.CompatUIComponentIdGenerator
import com.android.wm.shell.compatui.api.CompatUIEvent
import com.android.wm.shell.compatui.api.CompatUIHandler
import com.android.wm.shell.compatui.api.CompatUIInfo
import com.android.wm.shell.compatui.api.CompatUIRepository
import com.android.wm.shell.compatui.api.CompatUIState
import java.util.function.Consumer
import java.util.function.IntSupplier

/**
 * Default implementation of {@link CompatUIHandler} to handle CompatUI components
 */
class DefaultCompatUIHandler(
    private val compatUIRepository: CompatUIRepository,
    private val compatUIState: CompatUIState,
    private val componentIdGenerator: CompatUIComponentIdGenerator
) : CompatUIHandler {

    private var compatUIEventSender: Consumer<CompatUIEvent>? = null

    override fun onCompatInfoChanged(compatUIInfo: CompatUIInfo) {
        compatUIRepository.iterateOn { spec ->
            // We get the identifier for the component depending on the task and spec
            val componentId = componentIdGenerator.generateId(compatUIInfo, spec)
            // We check in the state if the component already exists
            var comp = compatUIState.getUIComponent(componentId)
            if (comp == null) {
                // We evaluate the predicate
                if (spec.lifecycle.creationPredicate(compatUIInfo, compatUIState.sharedState)) {
                    // We create the component and store in the
                    // global state
                    comp = CompatUIComponent(spec, componentId)
                    // We initialize the state for the component
                    val compState = spec.lifecycle.stateBuilder(
                        compatUIInfo,
                        compatUIState.sharedState
                    )
                    compatUIState.registerUIComponent(componentId, comp, compState)
                    // Now we can invoke the update passing the shared state and
                    // the state specific to the component
                    comp.update(compatUIInfo, compatUIState)
                }
            } else {
                // The component is present. We check if we need to remove it
                if (spec.lifecycle.removalPredicate(
                        compatUIInfo,
                        compatUIState.sharedState,
                        compatUIState.stateForComponent(componentId)
                    )) {
                    // We clean the component
                    comp.release()
                    // We remove the component
                    compatUIState.unregisterUIComponent(componentId)
                } else {
                    // The component exists so we need to invoke the update methods
                    comp.update(compatUIInfo, compatUIState)
                }
            }
        }
        // Empty at the moment
    }

    override fun setCallback(compatUIEventSender: Consumer<CompatUIEvent>?) {
        this.compatUIEventSender = compatUIEventSender
    }
}
