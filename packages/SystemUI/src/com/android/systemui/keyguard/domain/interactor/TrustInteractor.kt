/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.TrustRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Encapsulates any state relevant to trust agents and trust grants. */
@SysUISingleton
class TrustInteractor @Inject constructor(repository: TrustRepository) {
    /**
     * Whether the current user has a trust agent enabled. This is true if the user has at least one
     * trust agent enabled in settings.
     */
    val isEnrolledAndEnabled: StateFlow<Boolean> = repository.isCurrentUserTrustUsuallyManaged

    /**
     * Whether the current user's trust agent is currently allowed, this will be false if trust
     * agent is disabled for any reason (security timeout, disabled on lock screen by opening the
     * power menu, etc), it does not include temporary biometric lockouts.
     */
    val isTrustAgentCurrentlyAllowed: StateFlow<Boolean> = repository.isCurrentUserTrustManaged

    /** Whether the current user is trusted by any of the enabled trust agents. */
    val isTrusted: Flow<Boolean> = repository.isCurrentUserTrusted
}
