/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardQuickAffordanceConfigTest : SysuiTestCase() {

    @Test
    fun appStoreIntent() {
        overrideResource(R.string.config_appStorePackageName, "app.store.package.name")
        overrideResource(R.string.config_appStoreAppLinkTemplate, "prefix?id=\$packageName")
        val packageName = "com.app.package.name"

        val intent = KeyguardQuickAffordanceConfig.appStoreIntent(context, packageName)

        assertThat(intent).isNotNull()
        assertThat(intent?.`package`).isEqualTo("app.store.package.name")
        assertThat(intent?.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(intent?.data).isEqualTo(Uri.parse("prefix?id=$packageName"))
    }

    @Test
    fun appStoreIntent_packageNameNotConfigured_returnNull() {
        overrideResource(R.string.config_appStorePackageName, "")
        overrideResource(R.string.config_appStoreAppLinkTemplate, "prefix?id=\$packageName")
        val packageName = "com.app.package.name"

        val intent = KeyguardQuickAffordanceConfig.appStoreIntent(context, packageName)

        assertThat(intent).isNull()
    }

    @Test(expected = IllegalStateException::class)
    fun appStoreIntent_packageNameMisconfigured_throwsIllegalStateException() {
        overrideResource(R.string.config_appStorePackageName, "app.store.package.name")
        overrideResource(
            R.string.config_appStoreAppLinkTemplate,
            "prefix?id=\$misconfiguredPackageName"
        )
        val packageName = "com.app.package.name"

        KeyguardQuickAffordanceConfig.appStoreIntent(context, packageName)
    }

    @Test
    fun appStoreIntent_linkTemplateNotConfigured_returnNull() {
        overrideResource(R.string.config_appStorePackageName, "app.store.package.name")
        overrideResource(R.string.config_appStoreAppLinkTemplate, "")
        val packageName = "com.app.package.name"

        val intent = KeyguardQuickAffordanceConfig.appStoreIntent(context, packageName)

        assertThat(intent).isNull()
    }

    @Test
    fun appStoreIntent_appPackageNameNull_returnNull() {
        overrideResource(R.string.config_appStorePackageName, "app.store.package.name")
        overrideResource(R.string.config_appStoreAppLinkTemplate, "prefix?id=\$packageName")

        val intent = KeyguardQuickAffordanceConfig.appStoreIntent(context, null)

        assertThat(intent).isNull()
    }
}
