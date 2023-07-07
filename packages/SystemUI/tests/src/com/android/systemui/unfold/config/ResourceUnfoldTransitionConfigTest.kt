/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.unfold.config

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith


/**
 * A test that checks that we load correct resources in
 * ResourceUnfoldTransitionConfig as we use strings there instead of R constants.
 * Internal Android resource constants are not available in public APIs,
 * so we can't use them there directly.
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class ResourceUnfoldTransitionConfigTest : SysuiTestCase() {

    private val config = ResourceUnfoldTransitionConfig()

    @Test
    fun testIsEnabled() {
        assertThat(config.isEnabled).isEqualTo(mContext.resources
            .getBoolean(com.android.internal.R.bool.config_unfoldTransitionEnabled))
    }

    @Test
    fun testHingeAngleEnabled() {
        assertThat(config.isHingeAngleEnabled).isEqualTo(mContext.resources
            .getBoolean(com.android.internal.R.bool.config_unfoldTransitionHingeAngle))
    }

    @Test
    fun testHalfFoldedTimeout() {
        assertThat(config.halfFoldedTimeoutMillis).isEqualTo(mContext.resources
            .getInteger(com.android.internal.R.integer.config_unfoldTransitionHalfFoldedTimeout))
    }
}
