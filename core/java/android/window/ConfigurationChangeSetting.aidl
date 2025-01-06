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

package android.window;

/**
 * Represents a setting request that can be applied as part of a batch to avoid multiple
 * configuration updates.
 *
 * @see IWindowManager#setConfigurationChangeSettingsForUser
 * @hide
 */
parcelable ConfigurationChangeSetting;

/**
 * Represents a request to change the display density.
 * @hide
 */
parcelable DensitySetting;

/**
 * Represents a request to change the font scale.
 * @hide
 */
parcelable FontScaleSetting;
