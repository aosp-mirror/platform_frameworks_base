/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.authentication.shared.model

import com.android.systemui.log.table.Diffable
import com.android.systemui.log.table.TableRowLogger

/** Enumerates all known authentication methods. */
sealed class AuthenticationMethodModel(
    /**
     * Whether the authentication method is considered to be "secure".
     *
     * "Secure" authentication methods require authentication to unlock the device. Non-secure auth
     * methods simply require user dismissal.
     */
    open val isSecure: Boolean
) : Diffable<AuthenticationMethodModel> {
    /**
     * Device doesn't use a secure authentication method. Either there is no lockscreen or the lock
     * screen can be swiped away when displayed.
     */
    data object None : AuthenticationMethodModel(isSecure = false)

    data object Pin : AuthenticationMethodModel(isSecure = true)

    data object Password : AuthenticationMethodModel(isSecure = true)

    data object Pattern : AuthenticationMethodModel(isSecure = true)

    data object Sim : AuthenticationMethodModel(isSecure = true)

    override fun logDiffs(prevVal: AuthenticationMethodModel, row: TableRowLogger) {
        row.logChange(columnName = "authenticationMethod", value = toString())
    }
}
