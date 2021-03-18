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

import android.content.pm.verify.domain.DomainVerificationRequest
import android.content.pm.verify.domain.DomainVerificationInfo
import android.content.pm.verify.domain.DomainVerificationUserState
import com.android.server.pm.verify.domain.DomainVerificationPersistence

operator fun <F> android.util.Pair<F, *>.component1() = first
operator fun <S> android.util.Pair<*, S>.component2() = second

operator fun DomainVerificationRequest.component1() = packageNames

operator fun DomainVerificationInfo.component1() = identifier
operator fun DomainVerificationInfo.component2() = packageName
operator fun DomainVerificationInfo.component3() = hostToStateMap

operator fun DomainVerificationUserState.component1() = identifier
operator fun DomainVerificationUserState.component2() = packageName
operator fun DomainVerificationUserState.component3() = user
operator fun DomainVerificationUserState.component4() = isLinkHandlingAllowed
operator fun DomainVerificationUserState.component5() = hostToStateMap

operator fun DomainVerificationPersistence.ReadResult.component1() = active
operator fun DomainVerificationPersistence.ReadResult.component2() = restored
