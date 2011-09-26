/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;

import java.security.PublicKey;

/**
 * Contains information about a package verifier as used by
 * {@code PackageManagerService} during package verification.
 *
 * @hide
 */
public class VerifierInfo implements Parcelable {
    /** Package name of the verifier. */
    public final String packageName;

    /** Signatures used to sign the package verifier's package. */
    public final PublicKey publicKey;

    /**
     * Creates an object that represents a verifier info object.
     *
     * @param packageName the package name in Java-style. Must not be {@code
     *            null} or empty.
     * @param publicKey the public key for the signer encoded in Base64. Must
     *            not be {@code null} or empty.
     * @throws IllegalArgumentException if either argument is null or empty.
     */
    public VerifierInfo(String packageName, PublicKey publicKey) {
        if (packageName == null || packageName.length() == 0) {
            throw new IllegalArgumentException("packageName must not be null or empty");
        } else if (publicKey == null) {
            throw new IllegalArgumentException("publicKey must not be null");
        }

        this.packageName = packageName;
        this.publicKey = publicKey;
    }

    private VerifierInfo(Parcel source) {
        packageName = source.readString();
        publicKey = (PublicKey) source.readSerializable();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(packageName);
        dest.writeSerializable(publicKey);
    }

    public static final Parcelable.Creator<VerifierInfo> CREATOR
            = new Parcelable.Creator<VerifierInfo>() {
        public VerifierInfo createFromParcel(Parcel source) {
            return new VerifierInfo(source);
        }

        public VerifierInfo[] newArray(int size) {
            return new VerifierInfo[size];
        }
    };
}