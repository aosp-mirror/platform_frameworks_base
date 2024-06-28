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

package com.google.android.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.google.android.lint.parcel.SaferParcelChecker
import com.google.android.lint.aidl.PermissionAnnotationDetector
import com.google.auto.service.AutoService

@AutoService(IssueRegistry::class)
@Suppress("UnstableApiUsage")
class AndroidFrameworkIssueRegistry : IssueRegistry() {
    override val issues = listOf(
        CallingIdentityTokenDetector.ISSUE_UNUSED_TOKEN,
        CallingIdentityTokenDetector.ISSUE_NON_FINAL_TOKEN,
        CallingIdentityTokenDetector.ISSUE_NESTED_CLEAR_IDENTITY_CALLS,
        CallingIdentityTokenDetector.ISSUE_RESTORE_IDENTITY_CALL_NOT_IN_FINALLY_BLOCK,
        CallingIdentityTokenDetector.ISSUE_USE_OF_CALLER_AWARE_METHODS_WITH_CLEARED_IDENTITY,
        CallingIdentityTokenDetector.ISSUE_CLEAR_IDENTITY_CALL_NOT_FOLLOWED_BY_TRY_FINALLY,
        CallingIdentityTokenDetector.ISSUE_RESULT_OF_CLEAR_IDENTITY_CALL_NOT_STORED_IN_VARIABLE,
        CallingSettingsNonUserGetterMethodsDetector.ISSUE_NON_USER_GETTER_CALLED,
        SaferParcelChecker.ISSUE_UNSAFE_API_USAGE,
        // TODO: Currently crashes due to OOM issue
        // PackageVisibilityDetector.ISSUE_PACKAGE_NAME_NO_PACKAGE_VISIBILITY_FILTERS,
        PermissionAnnotationDetector.ISSUE_MISSING_PERMISSION_ANNOTATION,
        PermissionMethodDetector.ISSUE_PERMISSION_METHOD_USAGE,
        PermissionMethodDetector.ISSUE_CAN_BE_PERMISSION_METHOD,
        FeatureAutomotiveDetector.ISSUE,
    )

    override val api: Int
        get() = CURRENT_API

    override val minApi: Int
        get() = 8

    override val vendor: Vendor = Vendor(
        vendorName = "Android",
        feedbackUrl = "http://b/issues/new?component=315013",
        contact = "brufino@google.com"
    )
}
