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

package com.android.server.pm.domain.verify;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.domain.verify.DomainVerificationSet;
import android.content.pm.domain.verify.DomainVerificationUserSelection;
import android.content.pm.domain.verify.IDomainVerificationManager;
import android.util.Singleton;

import com.android.server.SystemService;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public class DomainVerificationService extends SystemService
        implements DomainVerificationManagerInternal {

    private static final String TAG = "DomainVerificationService";

    @NonNull
    private final Singleton<Connection> mConnection;

    @NonNull
    private final IDomainVerificationManager.Stub mStub = new DomainVerificationManagerStub(this);

    public DomainVerificationService(@NonNull Context context,
            @NonNull Singleton<Connection> connection) {
        super(context);
        mConnection = connection;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.DOMAIN_VERIFICATION_SERVICE, mStub);
    }

    @NonNull
    @Override
    public List<String> getValidVerificationPackageNames() {
        return null;
    }

    @Nullable
    @Override
    public DomainVerificationSet getDomainVerificationSet(@NonNull String packageName)
            throws NameNotFoundException {
        return null;
    }

    @Override
    public void setDomainVerificationStatus(@NonNull UUID domainSetId, @NonNull Set<String> domains,
            int state) throws InvalidDomainSetException, NameNotFoundException {

    }

    @Override
    public void setDomainVerificationLinkHandlingAllowed(@NonNull String packageName,
            boolean allowed) throws NameNotFoundException {

    }

    public void setDomainVerificationLinkHandlingAllowed(@NonNull String packageName,
            boolean allowed, @UserIdInt int userId) throws NameNotFoundException {

    }

    @Override
    public void setDomainVerificationUserSelection(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean enabled)
            throws InvalidDomainSetException, NameNotFoundException {

    }

    public void setDomainVerificationUserSelection(@NonNull UUID domainSetId,
            @NonNull Set<String> domains, boolean enabled, @UserIdInt int userId)
            throws InvalidDomainSetException, NameNotFoundException {

    }

    @Nullable
    @Override
    public DomainVerificationUserSelection getDomainVerificationUserSelection(
            @NonNull String packageName) throws NameNotFoundException {
        return null;
    }

    @Nullable
    public DomainVerificationUserSelection getDomainVerificationUserSelection(
            @NonNull String packageName, @UserIdInt int userId) throws NameNotFoundException {
        return null;
    }

    public interface Connection {

    }
}
