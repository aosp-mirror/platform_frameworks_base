/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.verify.domain;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.verify.domain.DomainOwner;
import android.content.pm.verify.domain.DomainSet;
import android.content.pm.verify.domain.DomainVerificationInfo;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.content.pm.verify.domain.IDomainVerificationManager;
import android.os.Bundle;
import android.os.ServiceSpecificException;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class DomainVerificationManagerStub extends IDomainVerificationManager.Stub {

    @NonNull
    private final DomainVerificationService mService;

    public DomainVerificationManagerStub(DomainVerificationService service) {
        mService = service;
    }

    @Override
    public void setUriRelativeFilterGroups(@NonNull String packageName,
            @NonNull Bundle domainToGroupsBundle) {
        try {
            mService.setUriRelativeFilterGroups(packageName, domainToGroupsBundle);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @NonNull
    @Override
    public Bundle getUriRelativeFilterGroups(
            @NonNull String packageName, @NonNull List<String> domains) {
        try {
            return mService.getUriRelativeFilterGroups(packageName, domains);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @NonNull
    @Override
    public List<String> queryValidVerificationPackageNames() {
        try {
            return mService.queryValidVerificationPackageNames();
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @Nullable
    @Override
    public DomainVerificationInfo getDomainVerificationInfo(String packageName) {
        try {
            return mService.getDomainVerificationInfo(packageName);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @DomainVerificationManager.Error
    @Override
    public int setDomainVerificationStatus(String domainSetId, @NonNull DomainSet domainSet,
            int state) {
        try {
            return mService.setDomainVerificationStatus(UUID.fromString(domainSetId),
                    domainSet.getDomains(), state);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @Override
    public void setDomainVerificationLinkHandlingAllowed(String packageName, boolean allowed,
            @UserIdInt int userId) {
        try {
            mService.setDomainVerificationLinkHandlingAllowed(packageName, allowed, userId);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @DomainVerificationManager.Error
    @Override
    public int setDomainVerificationUserSelection(String domainSetId, @NonNull DomainSet domainSet,
            boolean enabled, @UserIdInt int userId) {
        try {
            return mService.setDomainVerificationUserSelection(UUID.fromString(domainSetId),
                    domainSet.getDomains(), enabled, userId);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @Nullable
    @Override
    public DomainVerificationUserState getDomainVerificationUserState(
            String packageName, @UserIdInt int userId) {
        try {
            return mService.getDomainVerificationUserState(packageName, userId);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @Nullable
    @Override
    public List<DomainOwner> getOwnersForDomain(@NonNull String domain,
            @UserIdInt int userId) {
        try {
            Objects.requireNonNull(domain);
            return mService.getOwnersForDomain(domain, userId);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    private RuntimeException rethrow(Exception exception) throws RuntimeException {
        if (exception instanceof NameNotFoundException) {
            return new ServiceSpecificException(
                    DomainVerificationManager.INTERNAL_ERROR_NAME_NOT_FOUND);
        } else if (exception instanceof RuntimeException) {
            return (RuntimeException) exception;
        } else {
            return new RuntimeException(exception);
        }
    }
}
