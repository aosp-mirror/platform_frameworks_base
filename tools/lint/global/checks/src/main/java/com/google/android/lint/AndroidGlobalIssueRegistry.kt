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

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.google.android.lint.aidl.EnforcePermissionDetector
import com.google.android.lint.aidl.SimpleManualPermissionEnforcementDetector
import com.google.auto.service.AutoService

@AutoService(IssueRegistry::class)
@Suppress("UnstableApiUsage")
class AndroidGlobalIssueRegistry : IssueRegistry() {
    override val issues = listOf(
            EnforcePermissionDetector.ISSUE_MISSING_ENFORCE_PERMISSION,
            EnforcePermissionDetector.ISSUE_MISMATCHING_ENFORCE_PERMISSION,
            EnforcePermissionDetector.ISSUE_ENFORCE_PERMISSION_HELPER,
            EnforcePermissionDetector.ISSUE_MISUSING_ENFORCE_PERMISSION,
            SimpleManualPermissionEnforcementDetector.ISSUE_SIMPLE_MANUAL_PERMISSION_ENFORCEMENT,
    )

    override val api: Int
        get() = CURRENT_API

    override val minApi: Int
        get() = 8

    override val vendor: Vendor = Vendor(
            vendorName = "Android",
            feedbackUrl = "http://b/issues/new?component=315013",
            contact = "repsonsible-apis@google.com"
    )
}
