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

package com.android.server.pm.test.verify.domain

import com.android.internal.util.FunctionalUtils
import com.android.server.pm.PackageSetting
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal
import com.android.server.testutils.whenever
import org.mockito.ArgumentMatchers.any
import java.util.function.Consumer
import java.util.function.Function

internal object DomainVerificationTestUtils {

    @Suppress("UNCHECKED_CAST")
    fun DomainVerificationManagerInternal.Connection.mockPackageSettings(
        block: (String) -> PackageSetting?
    ) {
        whenever(withPackageSettings(any())) {
            (arguments[0] as Consumer<Function<String, PackageSetting?>>).accept { block(it) }
        }
        whenever(withPackageSettingsReturning<Any>(any())) {
            (arguments[0] as FunctionalUtils.ThrowingFunction<Function<String, PackageSetting?>, *>)
                .apply { block(it) }
        }
        whenever(withPackageSettingsThrowing<Exception>(any())) {
            (arguments[0] as DomainVerificationManagerInternal.Connection.ThrowingConsumer<
                    Function<String, PackageSetting?>, *>)
                .accept { block(it) }
        }
        whenever(withPackageSettingsReturningThrowing<Any, Exception>(any())) {
            (arguments[0] as DomainVerificationManagerInternal.Connection.ThrowingFunction<
                    Function<String, PackageSetting?>, *, *>)
                .apply { block(it) }
        }
    }
}
