/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.provider;

import android.annotation.NonNull;
import android.util.Base64;

import com.android.internal.util.Preconditions;

import java.util.Collections;
import java.util.List;

/**
 * Information about a font request that may be sent to a Font Provider.
 * @deprecated Use the {@link androidx.core.provider.FontRequest}
 */
@Deprecated
public final class FontRequest {
    private final String mProviderAuthority;
    private final String mProviderPackage;
    private final String mQuery;
    private final List<List<byte[]>> mCertificates;

    // Used for key of the cache.
    private final String mIdentifier;

    /**
     * @param providerAuthority The authority of the Font Provider to be used for the request. This
     *         should be a system installed app.
     * @param providerPackage The package for the Font Provider to be used for the request. This is
     *         used to verify the identity of the provider.
     * @param query The query to be sent over to the provider. Refer to your font provider's
     *         documentation on the format of this string.
     */
    public FontRequest(@NonNull String providerAuthority, @NonNull String providerPackage,
            @NonNull String query) {
        mProviderAuthority = Preconditions.checkNotNull(providerAuthority);
        mQuery = Preconditions.checkNotNull(query);
        mProviderPackage = Preconditions.checkNotNull(providerPackage);
        mCertificates = Collections.emptyList();
        mIdentifier = new StringBuilder(mProviderAuthority).append("-").append(mProviderPackage)
                .append("-").append(mQuery).toString();
    }

    /**
     * @param providerAuthority The authority of the Font Provider to be used for the request.
     * @param query The query to be sent over to the provider. Refer to your font provider's
     *         documentation on the format of this string.
     * @param providerPackage The package for the Font Provider to be used for the request. This is
     *         used to verify the identity of the provider.
     * @param certificates The list of sets of hashes for the certificates the provider should be
     *         signed with. This is used to verify the identity of the provider. Each set in the
     *         list represents one collection of signature hashes. Refer to your font provider's
     *         documentation for these values.
     */
    public FontRequest(@NonNull String providerAuthority, @NonNull String providerPackage,
            @NonNull String query, @NonNull List<List<byte[]>> certificates) {
        mProviderAuthority = Preconditions.checkNotNull(providerAuthority);
        mProviderPackage = Preconditions.checkNotNull(providerPackage);
        mQuery = Preconditions.checkNotNull(query);
        mCertificates = Preconditions.checkNotNull(certificates);
        mIdentifier = new StringBuilder(mProviderAuthority).append("-").append(mProviderPackage)
                .append("-").append(mQuery).toString();
    }

    /**
     * Returns the selected font provider's authority. This tells the system what font provider
     * it should request the font from.
     */
    public String getProviderAuthority() {
        return mProviderAuthority;
    }

    /**
     * Returns the selected font provider's package. This helps the system verify that the provider
     * identified by the given authority is the one requested.
     */
    public String getProviderPackage() {
        return mProviderPackage;
    }

    /**
     * Returns the query string. Refer to your font provider's documentation on the format of this
     * string.
     */
    public String getQuery() {
        return mQuery;
    }

    /**
     * Returns the list of certificate sets given for this provider. This helps the system verify
     * that the provider identified by the given authority is the one requested.
     */
    public List<List<byte[]>> getCertificates() {
        return mCertificates;
    }

    /** @hide */
    public String getIdentifier() {
        return mIdentifier;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("FontRequest {"
                + "mProviderAuthority: " + mProviderAuthority
                + ", mProviderPackage: " + mProviderPackage
                + ", mQuery: " + mQuery
                + ", mCertificates:");
        for (int i = 0; i < mCertificates.size(); i++) {
            builder.append(" [");
            List<byte[]> set = mCertificates.get(i);
            for (int j = 0; j < set.size(); j++) {
                builder.append(" \"");
                byte[] array = set.get(j);
                builder.append(Base64.encodeToString(array, Base64.DEFAULT));
                builder.append("\"");
            }
            builder.append(" ]");
        }
        builder.append("}");
        return builder.toString();
    }
}
