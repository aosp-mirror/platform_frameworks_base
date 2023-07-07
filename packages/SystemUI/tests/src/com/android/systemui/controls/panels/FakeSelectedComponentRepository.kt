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

package com.android.systemui.controls.panels

class FakeSelectedComponentRepository : SelectedComponentRepository {

    private var selectedComponent: SelectedComponentRepository.SelectedComponent? = null
    private var shouldAddDefaultPanel: Boolean = true

    override fun getSelectedComponent(): SelectedComponentRepository.SelectedComponent? =
        selectedComponent

    override fun setSelectedComponent(
        selectedComponent: SelectedComponentRepository.SelectedComponent
    ) {
        this.selectedComponent = selectedComponent
    }

    override fun removeSelectedComponent() {
        selectedComponent = null
    }

    override fun shouldAddDefaultComponent(): Boolean = shouldAddDefaultPanel

    override fun setShouldAddDefaultComponent(shouldAdd: Boolean) {
        shouldAddDefaultPanel = shouldAdd
    }
}
