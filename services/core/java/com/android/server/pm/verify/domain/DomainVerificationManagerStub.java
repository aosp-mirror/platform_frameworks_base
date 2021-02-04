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
import android.content.pm.verify.domain.DomainVerificationManager.InvalidDomainSetException;
import android.content.pm.verify.domain.DomainVerificationManagerImpl;
import android.content.pm.verify.domain.DomainVerificationInfo;
import android.content.pm.verify.domain.DomainVerificationUserSelection;
import android.content.pm.verify.domain.IDomainVerificationManager;
import android.os.ServiceSpecificException;
import android.util.ArraySet;

import java.util.List;
import java.util.UUID;

class DomainVerificationManagerStub extends IDomainVerificationManager.Stub {

    @NonNull
    private DomainVerificationService mService;

    DomainVerificationManagerStub(DomainVerificationService service) {
        mService = service;
    }

    @NonNull
    @Override
    public List<String> getValidVerificationPackageNames() {
        try {
            return mService.getValidVerificationPackageNames();
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

    @Override
    public void setDomainVerificationStatus(String domainSetId, List<String> domains,
            int state) {
        try {
            mService.setDomainVerificationStatus(UUID.fromString(domainSetId),
                            new ArraySet<>(domains), state);
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

    @Override
    public void setDomainVerificationUserSelection(String domainSetId, List<String> domains,
            boolean enabled, @UserIdInt int userId) {
        try {
            mService.setDomainVerificationUserSelection(UUID.fromString(domainSetId),
                            new ArraySet<>(domains), enabled, userId);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    @Nullable
    @Override
    public DomainVerificationUserSelection getDomainVerificationUserSelection(
            String packageName, @UserIdInt int userId) {
        try {
            return mService.getDomainVerificationUserSelection(packageName, userId);
        } catch (Exception e) {
            throw rethrow(e);
        }
    }

    private RuntimeException rethrow(Exception exception) throws RuntimeException {
        if (exception instanceof InvalidDomainSetException) {
            int packedErrorCode = DomainVerificationManagerImpl.ERROR_INVALID_DOMAIN_SET;
            packedErrorCode |= ((InvalidDomainSetException) exception).getReason() << 16;
            return new ServiceSpecificException(packedErrorCode,
                    ((InvalidDomainSetException) exception).getPackageName());
        } else if (exception instanceof NameNotFoundException) {
            return new ServiceSpecificException(
                    DomainVerificationManagerImpl.ERROR_NAME_NOT_FOUND);
        } else if (exception instanceof RuntimeException) {
            return (RuntimeException) exception;
        } else {
            return new RuntimeException(exception);
        }
    }
}
