/*
 * Copyright 2020 The Android Open Source Project
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

package android.security.identity;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * An object that contains the result of retrieving data from a credential. This is used to return
 * data requested from a {@link IdentityCredential}.
 */
class CredstoreResultData extends ResultData {

    byte[] mStaticAuthenticationData = null;
    byte[] mAuthenticatedData = null;
    byte[] mMessageAuthenticationCode = null;

    private Map<String, Map<String, EntryData>> mData = new LinkedHashMap<>();

    private static class EntryData {
        @Status
        int mStatus;
        byte[] mValue;

        EntryData(byte[] value, @Status int status) {
            this.mValue = value;
            this.mStatus = status;
        }
    }

    CredstoreResultData() {}

    @Override
    public @NonNull byte[] getAuthenticatedData() {
        return mAuthenticatedData;
    }

    @Override
    public @Nullable byte[] getMessageAuthenticationCode() {
        return mMessageAuthenticationCode;
    }

    @Override
    public @NonNull byte[] getStaticAuthenticationData() {
        return mStaticAuthenticationData;
    }

    @Override
    public @NonNull Collection<String> getNamespaces() {
        return Collections.unmodifiableCollection(mData.keySet());
    }

    @Override
    public @Nullable Collection<String> getEntryNames(@NonNull String namespaceName) {
        Map<String, EntryData> innerMap = mData.get(namespaceName);
        if (innerMap == null) {
            return null;
        }
        return Collections.unmodifiableCollection(innerMap.keySet());
    }

    @Override
    public @Nullable Collection<String> getRetrievedEntryNames(@NonNull String namespaceName) {
        Map<String, EntryData> innerMap = mData.get(namespaceName);
        if (innerMap == null) {
            return null;
        }
        LinkedList<String> result = new LinkedList<String>();
        for (Map.Entry<String, EntryData> entry : innerMap.entrySet()) {
            if (entry.getValue().mStatus == STATUS_OK) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private EntryData getEntryData(@NonNull String namespaceName, @NonNull String name) {
        Map<String, EntryData> innerMap = mData.get(namespaceName);
        if (innerMap == null) {
            return null;
        }
        return innerMap.get(name);
    }

    @Override
    @Status
    public int getStatus(@NonNull String namespaceName, @NonNull String name) {
        EntryData value = getEntryData(namespaceName, name);
        if (value == null) {
            return STATUS_NOT_REQUESTED;
        }
        return value.mStatus;
    }

    @Override
    public @Nullable byte[] getEntry(@NonNull String namespaceName, @NonNull String name) {
        EntryData value = getEntryData(namespaceName, name);
        if (value == null) {
            return null;
        }
        return value.mValue;
    }

    static class Builder {
        private CredstoreResultData mResultData;

        Builder(byte[] staticAuthenticationData,
                byte[] authenticatedData,
                byte[] messageAuthenticationCode) {
            this.mResultData = new CredstoreResultData();
            this.mResultData.mStaticAuthenticationData = staticAuthenticationData;
            this.mResultData.mAuthenticatedData = authenticatedData;
            this.mResultData.mMessageAuthenticationCode = messageAuthenticationCode;
        }

        private Map<String, EntryData> getOrCreateInnerMap(String namespaceName) {
            Map<String, EntryData> innerMap = mResultData.mData.get(namespaceName);
            if (innerMap == null) {
                innerMap = new LinkedHashMap<>();
                mResultData.mData.put(namespaceName, innerMap);
            }
            return innerMap;
        }

        Builder addEntry(String namespaceName, String name, byte[] value) {
            Map<String, EntryData> innerMap = getOrCreateInnerMap(namespaceName);
            innerMap.put(name, new EntryData(value, STATUS_OK));
            return this;
        }

        Builder addErrorStatus(String namespaceName, String name, @Status int status) {
            Map<String, EntryData> innerMap = getOrCreateInnerMap(namespaceName);
            innerMap.put(name, new EntryData(null, status));
            return this;
        }

        CredstoreResultData build() {
            return mResultData;
        }
    }

}
