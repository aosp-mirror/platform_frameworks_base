/*
 * Copyright 2021 The Android Open Source Project
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
import java.util.LinkedList;

class CredstoreCredentialDataResult extends CredentialDataResult {

    ResultData mDeviceSignedResult;
    ResultData mIssuerSignedResult;
    CredstoreEntries mDeviceSignedEntries;
    CredstoreEntries mIssuerSignedEntries;

    CredstoreCredentialDataResult(ResultData deviceSignedResult, ResultData issuerSignedResult) {
        mDeviceSignedResult = deviceSignedResult;
        mIssuerSignedResult = issuerSignedResult;
        mDeviceSignedEntries = new CredstoreEntries(deviceSignedResult);
        mIssuerSignedEntries = new CredstoreEntries(issuerSignedResult);
    }

    @Override
    public @NonNull byte[] getDeviceNameSpaces() {
        return mDeviceSignedResult.getAuthenticatedData();
    }

    @Override
    public @Nullable byte[] getDeviceMac() {
        return mDeviceSignedResult.getMessageAuthenticationCode();
    }

    @Override
    public @NonNull byte[] getStaticAuthenticationData() {
        return mDeviceSignedResult.getStaticAuthenticationData();
    }

    @Override
    public @NonNull CredentialDataResult.Entries getDeviceSignedEntries() {
        return mDeviceSignedEntries;
    }

    @Override
    public @NonNull CredentialDataResult.Entries getIssuerSignedEntries() {
        return mIssuerSignedEntries;
    }

    static class CredstoreEntries implements CredentialDataResult.Entries {
        ResultData mResultData;

        CredstoreEntries(ResultData resultData) {
            mResultData = resultData;
        }

        @Override
        public @NonNull Collection<String> getNamespaces() {
            return mResultData.getNamespaces();
        }

        @Override
        public @NonNull Collection<String> getEntryNames(@NonNull String namespaceName) {
            Collection<String> ret = mResultData.getEntryNames(namespaceName);
            if (ret == null) {
                ret = new LinkedList<String>();
            }
            return ret;
        }

        @Override
        public @NonNull Collection<String> getRetrievedEntryNames(@NonNull String namespaceName) {
            Collection<String> ret = mResultData.getRetrievedEntryNames(namespaceName);
            if (ret == null) {
                ret = new LinkedList<String>();
            }
            return ret;
        }

        @Override
        @Status
        public int getStatus(@NonNull String namespaceName, @NonNull String name) {
            return mResultData.getStatus(namespaceName, name);
        }

        @Override
        public @Nullable byte[] getEntry(@NonNull String namespaceName, @NonNull String name) {
            return mResultData.getEntry(namespaceName, name);
        }
    }

}
