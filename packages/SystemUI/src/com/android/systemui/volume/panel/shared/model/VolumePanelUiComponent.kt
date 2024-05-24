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

package com.android.systemui.volume.panel.shared.model

/**
 * An element of a Volume Panel. This can be a button bar, group of sliders or something else. The
 * only real implementation is Compose-based and located in `compose/features/`.
 *
 * Steps for adding an implementation in SystemUI:
 * 1) Implement `ComposeVolumePanelUiComponent` in `compose/features/`
 * 2) Add a module binding `ComposeVolumePanelUiComponent` into a map in compose/facade/enabled
 * 3) Add an interface with the same name as the 2-step module in compose/facade/disabled to stub it
 *    when the Compose is disabled
 * 4) Add the module to the VolumePanelComponent
 */
interface VolumePanelUiComponent
