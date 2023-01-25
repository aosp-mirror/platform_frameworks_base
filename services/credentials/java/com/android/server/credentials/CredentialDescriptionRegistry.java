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
import android.util.SparseArray;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Contains information on what CredentialProvider has what provisioned Credential. */
public class CredentialDescriptionRegistry {

    private static final int MAX_ALLOWED_CREDENTIAL_DESCRIPTIONS = 128;
    private static final int MAX_ALLOWED_ENTRIES_PER_PROVIDER = 16;
    private static SparseArray<CredentialDescriptionRegistry> sCredentialDescriptionSessionPerUser;

    static {
        sCredentialDescriptionSessionPerUser = new SparseArray<>();
    }

    // TODO(b/265992655): add a way to update CredentialRegistry when a user is removed.
    /** Get and/or create a {@link  CredentialDescription} for the given user id. */
    public static CredentialDescriptionRegistry forUser(int userId) {
        CredentialDescriptionRegistry session =
                sCredentialDescriptionSessionPerUser.get(userId, null);

        if (session == null) {
            session = new CredentialDescriptionRegistry();
            sCredentialDescriptionSessionPerUser.put(userId, session);
        }
        return session;
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
            mTotalDescriptionCount += size - mCredentialDescriptions.get(callingPackageName).size();
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

    /** Returns package names of CredentialProviders that can satisfy a given
     * {@link CredentialDescription}. */
    public Set<String> filterCredentials(String flatRequestString) {

        Set<String> result = new HashSet<>();

        for (String componentName: mCredentialDescriptions.keySet()) {
            Set<CredentialDescription> currentSet = mCredentialDescriptions.get(componentName);
            for (CredentialDescription containedDescription: currentSet) {
                if (flatRequestString.equals(containedDescription.getFlattenedRequestString())) {
                    result.add(componentName);
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

}
