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

package com.android.credentialmanager.createflow

import android.graphics.drawable.Drawable

data class ProviderInfo(
  val icon: Drawable,
  val name: String,
  val appDomainName: String,
  val credentialTypeIcon: Drawable,
  val createOptions: List<CreateOptionInfo>,
)

data class CreateOptionInfo(
  val icon: Drawable,
  val title: String,
  val subtitle: String,
  val id: Int,
  val usageData: String
)

/** The name of the current screen. */
enum class CreateScreenState {
  PASSKEY_INTRO,
  PROVIDER_SELECTION,
  CREATION_OPTION_SELECTION,
  MORE_OPTIONS_SELECTION,
  MORE_OPTIONS_ROW_INTRO,
}
