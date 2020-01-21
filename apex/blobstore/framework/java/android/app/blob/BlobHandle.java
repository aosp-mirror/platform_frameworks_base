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
package android.app.blob;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Objects;

/**
 * An identifier to represent a blob.
 */
// TODO: use datagen tool?
public final class BlobHandle implements Parcelable {
    private static final String ALGO_SHA_256 = "SHA-256";

    private static final int LIMIT_BLOB_TAG_LENGTH = 128; // characters

    /**
     * Cyrptographically secure hash algorithm used to generate hash of the blob this handle is
     * representing.
     *
     * @hide
     */
    @NonNull public final String algorithm;
    /**
     * Hash of the blob this handle is representing using {@link #algorithm}.
     *
     * @hide
     */
    @NonNull public final byte[] digest;
    /**
     * Label of the blob that can be surfaced to the user.
     * @hide
     */
    @NonNull public final CharSequence label;
    /**
     * Time in milliseconds after which the blob should be invalidated and not
     * allowed to be accessed by any other app, in {@link System#currentTimeMillis()} timebase.
     *
     * @hide
     */
    @CurrentTimeMillisLong public final long expiryTimeMillis;
    /**
     * An opaque {@link String} associated with the blob.
     *
     * @hide
     */
    @NonNull public final String tag;

    private BlobHandle(String algorithm, byte[] digest, CharSequence label, long expiryTimeMillis,
            String tag) {
        this.algorithm = algorithm;
        this.digest = digest;
        this.label = label;
        this.expiryTimeMillis = expiryTimeMillis;
        this.tag = tag;
    }

    private BlobHandle(Parcel in) {
        this.algorithm = in.readString();
        this.digest = in.createByteArray();
        this.label = in.readCharSequence();
        this.expiryTimeMillis = in.readLong();
        this.tag = in.readString();
    }

    /** @hide */
    public static @NonNull BlobHandle create(@NonNull String algorithm, @NonNull byte[] digest,
            @NonNull CharSequence label, @CurrentTimeMillisLong long expiryTimeMillis,
            @NonNull String tag) {
        Preconditions.checkNotNull(algorithm, "algorithm must not be null");
        Preconditions.checkNotNull(digest, "digest must not be null");
        Preconditions.checkNotNull(label, "label must not be null");
        Preconditions.checkArgumentNonnegative(expiryTimeMillis,
                "expiryTimeMillis must not be negative");
        Preconditions.checkNotNull(tag, "tag must not be null");
        Preconditions.checkArgument(tag.length() <= LIMIT_BLOB_TAG_LENGTH, "tag too long");
        return new BlobHandle(algorithm, digest, label, expiryTimeMillis, tag);
    }

    /**
     * Create a new blob identifier.
     *
     * <p> For two objects of {@link BlobHandle} to be considered equal, the following arguments
     * must be equal:
     * <ul>
     * <li> {@code digest}
     * <li> {@code label}
     * <li> {@code expiryTimeMillis}
     * <li> {@code tag}
     * </ul>
     *
     * @param digest the SHA-256 hash of the blob this is representing.
     * @param label a label indicating what the blob is, that can be surfaced to the user.
     * @param expiryTimeMillis the time in secs after which the blob should be invalidated and not
     *                         allowed to be accessed by any other app,
     *                         in {@link System#currentTimeMillis()} timebase.
     * @param tag an opaque {@link String} associated with the blob. The length of the tag
     *            cannot be more than 128 characters.
     *
     * @return a new instance of {@link BlobHandle} object.
     */
    public static @NonNull BlobHandle createWithSha256(@NonNull byte[] digest,
            @NonNull CharSequence label, @CurrentTimeMillisLong long expiryTimeMillis,
            @NonNull String tag) {
        return create(ALGO_SHA_256, digest, label, expiryTimeMillis, tag);
    }

    /**
     * Returns the SHA-256 hash of the blob that this object is representing.
     *
     * @see #createWithSha256(byte[], CharSequence, long, String)
     */
    public @NonNull byte[] getSha256Digest() {
        return digest;
    }

    /**
     * Returns the label associated with the blob that this object is representing.
     *
     * @see #createWithSha256(byte[], CharSequence, long, String)
     */
    public @NonNull CharSequence getLabel() {
        return label;
    }

    /**
     * Returns the expiry time in milliseconds of the blob that this object is representing, in
     *         {@link System#currentTimeMillis()} timebase.
     *
     * @see #createWithSha256(byte[], CharSequence, long, String)
     */
    public @CurrentTimeMillisLong long getExpiryTimeMillis() {
        return expiryTimeMillis;
    }

    /**
     * Returns the opaque {@link String} associated with the blob this object is representing.
     *
     * @see #createWithSha256(byte[], CharSequence, long, String)
     */
    public @NonNull String getTag() {
        return tag;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(algorithm);
        dest.writeByteArray(digest);
        dest.writeCharSequence(label);
        dest.writeLong(expiryTimeMillis);
        dest.writeString(tag);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || !(obj instanceof BlobHandle)) {
            return false;
        }
        final BlobHandle other = (BlobHandle) obj;
        return this.algorithm.equals(other.algorithm)
                && Arrays.equals(this.digest, other.digest)
                && this.label.equals(other.label)
                && this.expiryTimeMillis == other.expiryTimeMillis
                && this.tag.equals(tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(algorithm, Arrays.hashCode(digest), label, expiryTimeMillis, tag);
    }

    public static final @NonNull Creator<BlobHandle> CREATOR = new Creator<BlobHandle>() {
        @Override
        public @NonNull BlobHandle createFromParcel(@NonNull Parcel source) {
            return new BlobHandle(source);
        }

        @Override
        public @NonNull BlobHandle[] newArray(int size) {
            return new BlobHandle[size];
        }
    };
}
