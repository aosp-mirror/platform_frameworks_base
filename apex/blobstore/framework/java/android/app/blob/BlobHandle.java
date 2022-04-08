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

import static android.app.blob.XmlTags.ATTR_ALGO;
import static android.app.blob.XmlTags.ATTR_DIGEST;
import static android.app.blob.XmlTags.ATTR_EXPIRY_TIME;
import static android.app.blob.XmlTags.ATTR_LABEL;
import static android.app.blob.XmlTags.ATTR_TAG;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Base64;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * An identifier to represent a blob.
 */
// TODO: use datagen tool?
public final class BlobHandle implements Parcelable {
    /** @hide */
    public static final String ALGO_SHA_256 = "SHA-256";

    private static final String[] SUPPORTED_ALGOS = {
            ALGO_SHA_256
    };

    private static final int LIMIT_BLOB_TAG_LENGTH = 128; // characters
    private static final int LIMIT_BLOB_LABEL_LENGTH = 100; // characters

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
        final BlobHandle handle = new BlobHandle(algorithm, digest, label, expiryTimeMillis, tag);
        handle.assertIsValid();
        return handle;
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
     *              The length of the label cannot be more than 100 characters. It is recommended
     *              to keep this brief. This may be truncated and ellipsized if it is too long
     *              to be displayed to the user.
     * @param expiryTimeMillis the time in secs after which the blob should be invalidated and not
     *                         allowed to be accessed by any other app,
     *                         in {@link System#currentTimeMillis()} timebase or {@code 0} to
     *                         indicate that there is no expiry time associated with this blob.
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
                && this.label.toString().equals(other.label.toString())
                && this.expiryTimeMillis == other.expiryTimeMillis
                && this.tag.equals(other.tag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(algorithm, Arrays.hashCode(digest), label, expiryTimeMillis, tag);
    }

    /** @hide */
    public void dump(IndentingPrintWriter fout, boolean dumpFull) {
        if (dumpFull) {
            fout.println("algo: " + algorithm);
            fout.println("digest: " + (dumpFull ? encodeDigest(digest) : safeDigest(digest)));
            fout.println("label: " + label);
            fout.println("expiryMs: " + expiryTimeMillis);
            fout.println("tag: " + tag);
        } else {
            fout.println(toString());
        }
    }

    /** @hide */
    public void assertIsValid() {
        Preconditions.checkArgumentIsSupported(SUPPORTED_ALGOS, algorithm);
        Preconditions.checkByteArrayNotEmpty(digest, "digest");
        Preconditions.checkStringNotEmpty(label, "label must not be null");
        Preconditions.checkArgument(label.length() <= LIMIT_BLOB_LABEL_LENGTH, "label too long");
        Preconditions.checkArgumentNonnegative(expiryTimeMillis,
                "expiryTimeMillis must not be negative");
        Preconditions.checkStringNotEmpty(tag, "tag must not be null");
        Preconditions.checkArgument(tag.length() <= LIMIT_BLOB_TAG_LENGTH, "tag too long");
    }

    @Override
    public String toString() {
        return "BlobHandle {"
                + "algo:" + algorithm + ","
                + "digest:" + safeDigest(digest) + ","
                + "label:" + label + ","
                + "expiryMs:" + expiryTimeMillis + ","
                + "tag:" + tag
                + "}";
    }

    /** @hide */
    public static String safeDigest(@NonNull byte[] digest) {
        final String digestStr = encodeDigest(digest);
        return digestStr.substring(0, 2) + ".." + digestStr.substring(digestStr.length() - 2);
    }

    private static String encodeDigest(@NonNull byte[] digest) {
        return Base64.encodeToString(digest, Base64.NO_WRAP);
    }

    /** @hide */
    public boolean isExpired() {
        return expiryTimeMillis != 0 && expiryTimeMillis < System.currentTimeMillis();
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

    /** @hide */
    public void writeToXml(@NonNull XmlSerializer out) throws IOException {
        XmlUtils.writeStringAttribute(out, ATTR_ALGO, algorithm);
        XmlUtils.writeByteArrayAttribute(out, ATTR_DIGEST, digest);
        XmlUtils.writeStringAttribute(out, ATTR_LABEL, label);
        XmlUtils.writeLongAttribute(out, ATTR_EXPIRY_TIME, expiryTimeMillis);
        XmlUtils.writeStringAttribute(out, ATTR_TAG, tag);
    }

    /** @hide */
    @NonNull
    public static BlobHandle createFromXml(@NonNull XmlPullParser in) throws IOException {
        final String algo = XmlUtils.readStringAttribute(in, ATTR_ALGO);
        final byte[] digest = XmlUtils.readByteArrayAttribute(in, ATTR_DIGEST);
        final CharSequence label = XmlUtils.readStringAttribute(in, ATTR_LABEL);
        final long expiryTimeMs = XmlUtils.readLongAttribute(in, ATTR_EXPIRY_TIME);
        final String tag = XmlUtils.readStringAttribute(in, ATTR_TAG);

        return BlobHandle.create(algo, digest, label, expiryTimeMs, tag);
    }
}
