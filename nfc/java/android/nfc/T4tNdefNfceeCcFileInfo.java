/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.nfc;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class is used to represence T4T (Type-4 Tag) NDEF (NFC Data Exchange Format)
 * NFCEE (NFC Execution Environment) CC (Capability Container) File data.
 * The CC file stores metadata about the T4T tag being emulated.
 *
 * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.4" for more details.
 * @hide
 */
@FlaggedApi(Flags.FLAG_NFC_OEM_EXTENSION)
@SystemApi
public final class T4tNdefNfceeCcFileInfo implements Parcelable {
    /**
     * Indicates the size of this capability container (called “CC File”)<p>
     */
    private int mCcLength;
    /**
     * Indicates the mapping specification version<p>
     */
    private int mVersion;
    /**
     * Indicates the max data size by a single ReadBinary<p>
     */
    private int mMaxReadLength;
    /**
     * Indicates the max data size by a single UpdateBinary<p>
     */
    private int mMaxWriteLength;
    /**
     * Indicates the NDEF File Identifier<p>
     */
    private int mFileId;
    /**
     * Indicates the maximum Max NDEF file size<p>
     */
    private int mMaxSize;
    /**
     * Indicates the read access condition<p>
     */
    private int mReadAccess;
    /**
     * Indicates the write access condition<p>
     */
    private int mWriteAccess;

    /**
     * Constructor to be used by NFC service and internal classes.
     * @hide
     */
    public T4tNdefNfceeCcFileInfo(int cclen, int version, int maxLe, int maxLc,
                      int ndefFileId, int ndefMaxSize,
                      int ndefReadAccess, int ndefWriteAccess) {
        mCcLength = cclen;
        mVersion = version;
        mMaxWriteLength = maxLc;
        mMaxReadLength = maxLe;
        mFileId = ndefFileId;
        mMaxSize = ndefMaxSize;
        mReadAccess = ndefReadAccess;
        mWriteAccess = ndefWriteAccess;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {

        dest.writeInt(mCcLength);
        dest.writeInt(mVersion);
        dest.writeInt(mMaxWriteLength);
        dest.writeInt(mMaxReadLength);
        dest.writeInt(mFileId);
        dest.writeInt(mMaxSize);
        dest.writeInt(mReadAccess);
        dest.writeInt(mWriteAccess);
    }

    /**
     * Indicates the size of this capability container (called “CC File”).
     *
     * @return length of the CC file.
     */
    @IntRange(from = 0xf, to = 0x7fff)
    public int getCcFileLength() {
        return mCcLength;
    }

    /**
     * T4T tag mapping version 2.0.
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.4" for more details.
     */
    public static final int VERSION_2_0 = 0x20;
    /**
     * T4T tag mapping version 2.0.
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.4" for more details.
     */
    public static final int VERSION_3_0 = 0x30;

    /**
     * Possible return values for {@link #getVersion()}.
     * @hide
     */
    @IntDef(prefix = { "VERSION_" }, value = {
            VERSION_2_0,
            VERSION_3_0,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Version{}

    /**
     * Indicates the mapping version of the T4T tag supported.
     *
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.5" for more details.
     *
     * @return version of the specification
     */
    @Version
    public int getVersion() {
        return mVersion;
    }

    /**
     * Indicates the max data size that can be read by a single invocation of
     * {@link T4tNdefNfcee#readData(int)}.
     *
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.4" MLe.
     * @return max size of read (in bytes).
     */
    @IntRange(from = 0xf, to = 0xffff)
    public int getMaxReadLength() {
        return mMaxReadLength;
    }

    /**
     * Indicates the max data size that can be written by a single invocation of
     * {@link T4tNdefNfcee#writeData(int, byte[])}
     *
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.4" MLc.
     * @return max size of write (in bytes).
     */
    @IntRange(from = 0xd, to = 0xffff)
    public int getMaxWriteLength() {
        return mMaxWriteLength;
    }

    /**
     * Indicates the NDEF File Identifier. This is the identifier used in the last invocation of
     * {@link T4tNdefNfcee#writeData(int, byte[])}
     *
     * @return FileId of the data stored or -1 if no data is present.
     */
    @IntRange(from = -1, to = 65535)
    public int getFileId() {
        return mFileId;
    }

    /**
     * Indicates the maximum size of T4T NDEF data that can be written to the NFCEE.
     *
     * @return max size of the contents.
     */
    @IntRange(from = 0x5, to = 0x7fff)
    public int getMaxSize() {
        return mMaxSize;
    }

    /**
     * T4T tag read access granted without any security.
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.2" for more details.
     */
    public static final int READ_ACCESS_GRANTED_UNRESTRICTED = 0x0;
    /**
     * T4T tag read access granted with limited proprietary access only.
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.2" for more details.
     */
    public static final int READ_ACCESS_GRANTED_RESTRICTED = 0x80;

    /**
     * Possible return values for {@link #getVersion()}.
     * @hide
     */
    @IntDef(prefix = { "READ_ACCESS_GRANTED_" }, value = {
            READ_ACCESS_GRANTED_RESTRICTED,
            READ_ACCESS_GRANTED_UNRESTRICTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ReadAccess {}

    /**
     * Indicates the read access condition.
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.2" for more details.
     * @return read access restriction
     */
    @ReadAccess
    public int getReadAccess() {
        return mReadAccess;
    }

    /**
     * T4T tag write access granted without any security.
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.2" for more details.
     */
    public static final int WRITE_ACCESS_GRANTED_UNRESTRICTED = 0x0;
    /**
     * T4T tag write access granted with limited proprietary access only.
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.2" for more details.
     */
    public static final int WRITE_ACCESS_GRANTED_RESTRICTED = 0x80;
    /**
     * T4T tag write access not granted.
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.2" for more details.
     */
    public static final int WRITE_ACCESS_NOT_GRANTED = 0xFF;

    /**
     * Possible return values for {@link #getVersion()}.
     * @hide
     */
    @IntDef(prefix = { "READ_ACCESS_GRANTED_" }, value = {
            WRITE_ACCESS_GRANTED_RESTRICTED,
            WRITE_ACCESS_GRANTED_UNRESTRICTED,
            WRITE_ACCESS_NOT_GRANTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WriteAccess {}

    /**
     * Indicates the write access condition.
     * Refer to the NFC forum specification "NFCForum-TS-T4T-1.1 section 4.2" for more details.
     * @return write access restriction
     */
    @WriteAccess
    public int getWriteAccess() {
        return mWriteAccess;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Parcelable.Creator<T4tNdefNfceeCcFileInfo> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public T4tNdefNfceeCcFileInfo createFromParcel(Parcel in) {

                    // NdefNfceeCcFileInfo fields
                    int cclen = in.readInt();
                    int version = in.readInt();
                    int maxLe = in.readInt();
                    int maxLc = in.readInt();
                    int ndefFileId = in.readInt();
                    int ndefMaxSize = in.readInt();
                    int ndefReadAccess = in.readInt();
                    int ndefWriteAccess = in.readInt();

                    return new T4tNdefNfceeCcFileInfo(cclen, version, maxLe, maxLc,
                            ndefFileId, ndefMaxSize,
                            ndefReadAccess, ndefWriteAccess);
                }

                @Override
                public T4tNdefNfceeCcFileInfo[] newArray(int size) {
                    return new T4tNdefNfceeCcFileInfo[size];
                }
            };
}
