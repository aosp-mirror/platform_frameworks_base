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

package com.android.settingslib.service

const val PREFERENCE_SERVICE_ACTION = "com.android.settingslib.PREFERENCE_SERVICE"

/** API id for retrieving preference graph. */
internal const val API_GET_PREFERENCE_GRAPH = 1

/** API id for preference value setter. */
internal const val API_PREFERENCE_SETTER = 2

/** API id for preference getter. */
internal const val API_PREFERENCE_GETTER = 3

/**
 * The max API id reserved for internal preference service usages. Custom API id should start with
 * **1000** to avoid conflict.
 */
internal const val API_MAX_RESERVED = 999
