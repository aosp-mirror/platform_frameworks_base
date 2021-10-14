/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os;

/**
 * @see ResourcesTypes.h ResTable_overlayable_policy_header::PolicyFlags
 * @hide
 */
@Backing(type="int")
enum OverlayablePolicy {
  NONE = 0x00000000,
  PUBLIC = 0x00000001,
  SYSTEM_PARTITION = 0x00000002,
  VENDOR_PARTITION = 0x00000004,
  PRODUCT_PARTITION = 0x00000008,
  SIGNATURE = 0x00000010,
  ODM_PARTITION = 0x00000020,
  OEM_PARTITION = 0x00000040,
  ACTOR_SIGNATURE = 0x00000080,
  CONFIG_SIGNATURE = 0x0000100,
}
