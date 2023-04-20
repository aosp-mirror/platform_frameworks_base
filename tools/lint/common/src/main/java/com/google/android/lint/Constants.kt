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

package com.google.android.lint

import com.google.android.lint.model.Method

const val CLASS_STUB = "Stub"
const val CLASS_CONTEXT = "android.content.Context"
const val CLASS_ACTIVITY_MANAGER_SERVICE = "com.android.server.am.ActivityManagerService"
const val CLASS_ACTIVITY_MANAGER_INTERNAL = "android.app.ActivityManagerInternal"

// Enforce permission APIs
val ENFORCE_PERMISSION_METHODS = listOf(
        Method(CLASS_CONTEXT, "checkPermission"),
        Method(CLASS_CONTEXT, "checkCallingPermission"),
        Method(CLASS_CONTEXT, "checkCallingOrSelfPermission"),
        Method(CLASS_CONTEXT, "enforcePermission"),
        Method(CLASS_CONTEXT, "enforceCallingPermission"),
        Method(CLASS_CONTEXT, "enforceCallingOrSelfPermission"),
        Method(CLASS_ACTIVITY_MANAGER_SERVICE, "checkPermission"),
        Method(CLASS_ACTIVITY_MANAGER_INTERNAL, "enforceCallingPermission")
)

const val ANNOTATION_PERMISSION_METHOD = "android.annotation.PermissionMethod"
const val ANNOTATION_PERMISSION_NAME = "android.annotation.PermissionName"
const val ANNOTATION_PERMISSION_RESULT = "android.content.pm.PackageManager.PermissionResult"
