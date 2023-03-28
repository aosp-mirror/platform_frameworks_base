/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.credentials;

import android.credentials.CredentialDescription;
import android.credentials.RegisterCredentialDescriptionRequest;
import android.credentials.UnregisterCredentialDescriptionRequest;
import android.service.credentials.CredentialEntry;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Contains information on what CredentialProvider has what provisioned Credential. */
public class CredentialDescriptionRegistry {

    private static final int MAX_ALLOWED_CREDENTIAL_DESCRIPTIONS = 128;
    private static final int MAX_ALLOWED_ENTRIES_PER_PROVIDER = 16;
    @GuardedBy("sLock")
    private static final SparseArray<CredentialDescriptionRegistry>
            sCredentialDescriptionSessionPerUser;
    private static final ReentrantLock sLock;

    static {
        sCredentialDescriptionSessionPerUser = new SparseArray<>();
        sLock = new ReentrantLock();
    }

    /** Represents the results of a given query into the registry. */
    public static final class FilterResult {
        final String mPackageName;
        final Set<String> mElementKeys;
        final List<CredentialEntry> mCredentialEntries;

        @VisibleForTesting
        FilterResult(String packageName,
                Set<String> elementKeys,
                List<CredentialEntry> credentialEntries) {
            mPackageName = packageName;
            mElementKeys = elementKeys;
            mCredentialEntries = credentialEntries;
        }
    }

    /** Get and/or create a {@link  CredentialDescription} for the given user id. */
    @GuardedBy("sLock")
    public static CredentialDescriptionRegistry forUser(int userId) {
        sLock.lock();
        try {
            CredentialDescriptionRegistry session =
                    sCredentialDescriptionSessionPerUser.get(userId, null);

            if (session == null) {
                session = new CredentialDescriptionRegistry();
                sCredentialDescriptionSessionPerUser.put(userId, session);
            }
            return session;
        } finally {
            sLock.unlock();
        }
    }

    /** Clears an existing session for a given user identifier. */
    @GuardedBy("sLock")
    public static void clearUserSession(int userId) {
        sLock.lock();
        try {
            sCredentialDescriptionSessionPerUser.remove(userId);
        } finally {
            sLock.unlock();
        }
    }

    /** Clears an existing session for a given user identifier. Used when testing only. */
    @GuardedBy("sLock")
    @VisibleForTesting
    static void clearAllSessions() {
        sLock.lock();
        try {
            sCredentialDescriptionSessionPerUser.clear();
        } finally {
            sLock.unlock();
        }
    }

    /** Sets an existing session for a given user identifier. Used when testing only. */
    @GuardedBy("sLock")
    @VisibleForTesting
    static void setSession(int userId, CredentialDescriptionRegistry
            credentialDescriptionRegistry) {
        sLock.lock();
        try {
            sCredentialDescriptionSessionPerUser.put(userId, credentialDescriptionRegistry);
        } finally {
            sLock.unlock();
        }
    }

    private Map<String, Set<CredentialDescription>> mCredentialDescriptions;
    private int mTotalDescriptionCount;

    private CredentialDescriptionRegistry() {
        this.mCredentialDescriptions = new HashMap<>();
        this.mTotalDescriptionCount = 0;
    }

    /** Handle the given {@link RegisterCredentialDescriptionRequest} by creating
     * the appropriate package name mapping. */
    public void executeRegisterRequest(RegisterCredentialDescriptionRequest request,
            String callingPackageName) {

        if (!mCredentialDescriptions.containsKey(callingPackageName)) {
            mCredentialDescriptions.put(callingPackageName, new HashSet<>());
        }

        if (mTotalDescriptionCount <= MAX_ALLOWED_CREDENTIAL_DESCRIPTIONS
                && mCredentialDescriptions.get(callingPackageName).size()
                <= MAX_ALLOWED_ENTRIES_PER_PROVIDER) {
            Set<CredentialDescription> descriptions = request.getCredentialDescriptions();
            int size = mCredentialDescriptions.get(callingPackageName).size();
            mCredentialDescriptions.get(callingPackageName)
                    .addAll(descriptions);
            mTotalDescriptionCount += mCredentialDescriptions.get(callingPackageName).size() - size;
        }

    }

    /** Handle the given {@link UnregisterCredentialDescriptionRequest} by creating
     * the appropriate package name mapping. */
    public void executeUnregisterRequest(
            UnregisterCredentialDescriptionRequest request,
            String callingPackageName) {

        if (mCredentialDescriptions.containsKey(callingPackageName)) {
            int size = mCredentialDescriptions.get(callingPackageName).size();
            mCredentialDescriptions.get(callingPackageName)
                    .removeAll(request.getCredentialDescriptions());
            mTotalDescriptionCount -= size - mCredentialDescriptions.get(callingPackageName).size();
        }
    }

    /** Returns package names and entries of a CredentialProviders that can satisfy a given
     * {@link CredentialDescription}. */
    public Set<FilterResult> getFilteredResultForProvider(String packageName,
            Set<String> requestedKeyElements) {
        Set<FilterResult> result = new HashSet<>();
        if (!mCredentialDescriptions.containsKey(packageName)) {
            return result;
        }
        Set<CredentialDescription> currentSet = mCredentialDescriptions.get(packageName);
        for (CredentialDescription containedDescription: currentSet) {
            if (checkForMatch(containedDescription.getSupportedElementKeys(),
                    requestedKeyElements)) {
                result.add(new FilterResult(packageName,
                        containedDescription.getSupportedElementKeys(), containedDescription
                        .getCredentialEntries()));
            }
        }
        return result;
    }

    /** Returns package names of CredentialProviders that can satisfy a given
     * {@link CredentialDescription}. */
    public Set<FilterResult> getMatchingProviders(Set<Set<String>> supportedElementKeys) {
        Set<FilterResult> result = new HashSet<>();
        for (String packageName: mCredentialDescriptions.keySet()) {
            Set<CredentialDescription> currentSet = mCredentialDescriptions.get(packageName);
            for (CredentialDescription containedDescription : currentSet) {
                if (canProviderSatisfyAny(containedDescription.getSupportedElementKeys(),
                        supportedElementKeys)) {
                    result.add(new FilterResult(packageName,
                            containedDescription.getSupportedElementKeys(), containedDescription
                            .getCredentialEntries()));
                }
            }
        }
        return result;
    }

    void evictProviderWithPackageName(String packageName) {
        if (mCredentialDescriptions.containsKey(packageName)) {
            mCredentialDescriptions.remove(packageName);
        }
    }

    private static boolean canProviderSatisfyAny(Set<String> registeredElementKeys,
            Set<Set<String>> requestedElementKeys) {
        for (Set<String> requestedUnflattenedString : requestedElementKeys) {
            if (registeredElementKeys.containsAll(requestedUnflattenedString)) {
                return true;
            }
        }
        return false;
    }

    static boolean checkForMatch(Set<String> registeredElementKeys,
            Set<String> requestedElementKeys) {
        return registeredElementKeys.containsAll(requestedElementKeys);
    }

}
