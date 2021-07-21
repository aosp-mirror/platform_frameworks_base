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

package com.android.server.pm.test.verify.domain;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;
import android.content.pm.verify.domain.DomainOwner;
import android.content.pm.verify.domain.DomainVerificationManager;

import com.android.server.pm.verify.domain.DomainVerificationService;

import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

/**
 * Proxies Kotlin calls to the Java layer such that null values can be passed for {@link NonNull}
 * marked parameters, as Kotlin disallows this at the compiler leveling, preventing the null error
 * codes from being tested.
 */
class DomainVerificationJavaUtil {

    static int setStatusForceNullable(@NonNull DomainVerificationService service,
            @Nullable UUID domainSetId, @Nullable Set<String> domains, int state)
            throws PackageManager.NameNotFoundException {
        return service.setDomainVerificationStatus(domainSetId, domains, state);
    }

    static int setUserSelectionForceNullable(@NonNull DomainVerificationService service,
            @Nullable UUID domainSetId, @Nullable Set<String> domains, boolean enabled,
            @UserIdInt int userId)
            throws PackageManager.NameNotFoundException {
        return service.setDomainVerificationUserSelection(domainSetId, domains, enabled, userId);
    }

    static int setStatusForceNullable(@NonNull DomainVerificationManager manager,
            @Nullable UUID domainSetId, @Nullable Set<String> domains, int state)
            throws PackageManager.NameNotFoundException {
        return manager.setDomainVerificationStatus(domainSetId, domains, state);
    }

    static int setUserSelectionForceNullable(@NonNull DomainVerificationManager manager,
            @Nullable UUID domainSetId, @Nullable Set<String> domains, boolean enabled)
            throws PackageManager.NameNotFoundException {
        return manager.setDomainVerificationUserSelection(domainSetId, domains, enabled);
    }

    static SortedSet<DomainOwner> getOwnersForDomain(@NonNull DomainVerificationManager manager,
            @Nullable String domain) {
        return manager.getOwnersForDomain(domain);
    }

    static List<DomainOwner> getOwnersForDomain(@NonNull DomainVerificationService service,
            @Nullable String domain, @UserIdInt int userId) {
        return service.getOwnersForDomain(domain, userId);
    }
}
