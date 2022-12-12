/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.permission.access.permission

object PermissionFlags {
    const val INSTALL_GRANTED = 1 shl 0
    const val INSTALL_REVOKED = 1 shl 1
    const val PROTECTION_GRANTED = 1 shl 2
    const val ROLE_GRANTED = 1 shl 3
    // For permissions that are granted in other ways,
    // ex: via an API or implicit permissions that inherit from granted install permissions
    const val OTHER_GRANTED = 1 shl 4
    // For the permissions that are implicit for the package
    const val IMPLICIT = 1 shl 5

    const val MASK_ALL = 0.inv()
    const val MASK_GRANTED = INSTALL_GRANTED or PROTECTION_GRANTED or OTHER_GRANTED or ROLE_GRANTED
    const val MASK_RUNTIME = OTHER_GRANTED or IMPLICIT
}
