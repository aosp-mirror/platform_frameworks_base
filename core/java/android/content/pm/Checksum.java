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

package android.content.pm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.DataClass;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A typed checksum.
 *
 * @see ApkChecksum
 * @see PackageManager#requestChecksums
 */
@DataClass(genConstDefs = false)
public final class Checksum implements Parcelable {
    /**
     * Root SHA256 hash of a 4K Merkle tree computed over all file bytes.
     * <a href="https://source.android.com/security/apksigning/v4">See APK Signature Scheme V4</a>.
     * <a href="https://git.kernel.org/pub/scm/fs/fscrypt/fscrypt.git/tree/Documentation/filesystems/fsverity.rst">See fs-verity</a>.
     *
     * Recommended for all new applications.
     * Can be used by kernel to enforce authenticity and integrity of the APK.
     * <a href="https://git.kernel.org/pub/scm/fs/fscrypt/fscrypt.git/tree/Documentation/filesystems/fsverity.rst#">See fs-verity for details</a>
     *
     * @see PackageManager#requestChecksums
     */
    public static final int TYPE_WHOLE_MERKLE_ROOT_4K_SHA256 = 0x00000001;

    /**
     * MD5 hash computed over all file bytes.
     *
     * @see PackageManager#requestChecksums
     * @deprecated Not platform enforced. Cryptographically broken and unsuitable for further use.
     *             Use platform enforced digests e.g. {@link #TYPE_WHOLE_MERKLE_ROOT_4K_SHA256}.
     *             Provided for completeness' sake and to support legacy usecases.
     */
    @Deprecated
    public static final int TYPE_WHOLE_MD5 = 0x00000002;

    /**
     * SHA1 hash computed over all file bytes.
     *
     * @see PackageManager#requestChecksums
     * @deprecated Not platform enforced. Broken and should not be used.
     *             Use platform enforced digests e.g. {@link #TYPE_WHOLE_MERKLE_ROOT_4K_SHA256}.
     *             Provided for completeness' sake and to support legacy usecases.
     */
    @Deprecated
    public static final int TYPE_WHOLE_SHA1 = 0x00000004;

    /**
     * SHA256 hash computed over all file bytes.
     * @deprecated Not platform enforced.
     *             Use platform enforced digests e.g. {@link #TYPE_WHOLE_MERKLE_ROOT_4K_SHA256}.
     *             Provided for completeness' sake and to support legacy usecases.
     *
     * @see PackageManager#requestChecksums
     */
    @Deprecated
    public static final int TYPE_WHOLE_SHA256 = 0x00000008;

    /**
     * SHA512 hash computed over all file bytes.
     * @deprecated Not platform enforced.
     *             Use platform enforced digests e.g. {@link #TYPE_WHOLE_MERKLE_ROOT_4K_SHA256}.
     *             Provided for completeness' sake and to support legacy usecases.
     *
     * @see PackageManager#requestChecksums
     */
    @Deprecated
    public static final int TYPE_WHOLE_SHA512 = 0x00000010;

    /**
     * Root SHA256 hash of a 1M Merkle tree computed over protected content.
     * Excludes signing block.
     * <a href="https://source.android.com/security/apksigning/v2">See APK Signature Scheme V2</a>.
     *
     * @see PackageManager#requestChecksums
     */
    public static final int TYPE_PARTIAL_MERKLE_ROOT_1M_SHA256 = 0x00000020;

    /**
     * Root SHA512 hash of a 1M Merkle tree computed over protected content.
     * Excludes signing block.
     * <a href="https://source.android.com/security/apksigning/v2">See APK Signature Scheme V2</a>.
     *
     * @see PackageManager#requestChecksums
     */
    public static final int TYPE_PARTIAL_MERKLE_ROOT_1M_SHA512 = 0x00000040;

    /** @hide */
    @IntDef(prefix = {"TYPE_"}, value = {
            TYPE_WHOLE_MERKLE_ROOT_4K_SHA256,
            TYPE_WHOLE_MD5,
            TYPE_WHOLE_SHA1,
            TYPE_WHOLE_SHA256,
            TYPE_WHOLE_SHA512,
            TYPE_PARTIAL_MERKLE_ROOT_1M_SHA256,
            TYPE_PARTIAL_MERKLE_ROOT_1M_SHA512,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /** @hide */
    @IntDef(flag = true, prefix = {"TYPE_"}, value = {
            TYPE_WHOLE_MERKLE_ROOT_4K_SHA256,
            TYPE_WHOLE_MD5,
            TYPE_WHOLE_SHA1,
            TYPE_WHOLE_SHA256,
            TYPE_WHOLE_SHA512,
            TYPE_PARTIAL_MERKLE_ROOT_1M_SHA256,
            TYPE_PARTIAL_MERKLE_ROOT_1M_SHA512,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TypeMask {}

    /**
     * Serialize checksum to the stream in binary format.
     * @hide
     */
    public static void writeToStream(@NonNull DataOutputStream dos, @NonNull Checksum checksum)
            throws IOException {
        dos.writeInt(checksum.getType());

        final byte[] valueBytes = checksum.getValue();
        dos.writeInt(valueBytes.length);
        dos.write(valueBytes);
    }

    /**
     * Deserialize checksum previously stored in
     * {@link #writeToStream(DataOutputStream, Checksum)}.
     * @hide
     */
    public static @NonNull Checksum readFromStream(@NonNull DataInputStream dis)
            throws IOException {
        final int type = dis.readInt();

        final byte[] valueBytes = new byte[dis.readInt()];
        dis.read(valueBytes);
        return new Checksum(type, valueBytes);
    }

    /**
     * Checksum type.
     */
    private final @Checksum.Type int mType;
    /**
     * Checksum value.
     */
    private final @NonNull byte[] mValue;



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/Checksum.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Creates a new Checksum.
     *
     * @param type
     *   Checksum type.
     * @param value
     *   Checksum value.
     */
    @DataClass.Generated.Member
    public Checksum(
            @Checksum.Type int type,
            @NonNull byte[] value) {
        this.mType = type;
        com.android.internal.util.AnnotationValidations.validate(
                Checksum.Type.class, null, mType);
        this.mValue = value;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mValue);

        // onConstructed(); // You can define this method to get a callback
    }

    /**
     * Checksum type.
     */
    @DataClass.Generated.Member
    public @Checksum.Type int getType() {
        return mType;
    }

    /**
     * Checksum value.
     */
    @DataClass.Generated.Member
    public @NonNull byte[] getValue() {
        return mValue;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeInt(mType);
        dest.writeByteArray(mValue);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ Checksum(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int type = in.readInt();
        byte[] value = in.createByteArray();

        this.mType = type;
        com.android.internal.util.AnnotationValidations.validate(
                Checksum.Type.class, null, mType);
        this.mValue = value;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mValue);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<Checksum> CREATOR
            = new Parcelable.Creator<Checksum>() {
        @Override
        public Checksum[] newArray(int size) {
            return new Checksum[size];
        }

        @Override
        public Checksum createFromParcel(@NonNull Parcel in) {
            return new Checksum(in);
        }
    };

    @DataClass.Generated(
            time = 1619810358402L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/content/pm/Checksum.java",
            inputSignatures = "public static final  int TYPE_WHOLE_MERKLE_ROOT_4K_SHA256\npublic static final @java.lang.Deprecated int TYPE_WHOLE_MD5\npublic static final @java.lang.Deprecated int TYPE_WHOLE_SHA1\npublic static final @java.lang.Deprecated int TYPE_WHOLE_SHA256\npublic static final @java.lang.Deprecated int TYPE_WHOLE_SHA512\npublic static final  int TYPE_PARTIAL_MERKLE_ROOT_1M_SHA256\npublic static final  int TYPE_PARTIAL_MERKLE_ROOT_1M_SHA512\nprivate final @android.content.pm.Checksum.Type int mType\nprivate final @android.annotation.NonNull byte[] mValue\npublic static  void writeToStream(java.io.DataOutputStream,android.content.pm.Checksum)\npublic static @android.annotation.NonNull android.content.pm.Checksum readFromStream(java.io.DataInputStream)\nclass Checksum extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genConstDefs=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
