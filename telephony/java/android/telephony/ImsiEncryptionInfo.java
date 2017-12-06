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
package android.telephony;

import android.os.Parcel;
import android.os.Parcelable;
import java.util.Date;
import android.util.Log;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

/**
 * Class to represent information sent by the carrier, which will be used to encrypt
 * the IMSI + IMPI. The ecryption is being done by WLAN, and the modem.
 *
 * @hide
 */
public final class ImsiEncryptionInfo implements Parcelable {

    private static final String LOG_TAG = "ImsiEncryptionInfo";


    private final String mcc;
    private final String mnc;
    private final PublicKey publicKey;
    private final String keyIdentifier;
    private final int keyType;
    //Date-Time in UTC when the key will expire.
    private final Date expirationTime;

    public ImsiEncryptionInfo(String mcc, String mnc, int keyType, String keyIdentifier,
                              byte[] key, Date expirationTime) {
        this(mcc, mnc, keyType, keyIdentifier, makeKeyObject(key), expirationTime);
    }

    public ImsiEncryptionInfo(String mcc, String mnc, int keyType, String keyIdentifier,
                              PublicKey publicKey, Date expirationTime) {
        // todo need to validate that ImsiEncryptionInfo is being created with the correct params.
        //      Including validating that the public key is in "X.509" format. This will be done in
        //      a subsequent CL.
        this.mcc = mcc;
        this.mnc = mnc;
        this.keyType = keyType;
        this.publicKey = publicKey;
        this.keyIdentifier = keyIdentifier;
        this.expirationTime = expirationTime;
    }

    public ImsiEncryptionInfo(Parcel in) {
        int length = in.readInt();
        byte b[] = new byte[length];
        in.readByteArray(b);
        publicKey = makeKeyObject(b);
        mcc = in.readString();
        mnc = in.readString();
        keyIdentifier = in.readString();
        keyType = in.readInt();
        expirationTime = new Date(in.readLong());
    }

    public String getMnc() {
        return this.mnc;
    }

    public String getMcc() {
        return this.mcc;
    }

    public String getKeyIdentifier() {
        return this.keyIdentifier;
    }

    public int getKeyType() {
        return this.keyType;
    }

    public PublicKey getPublicKey() {
        return this.publicKey;
    }

    public Date getExpirationTime() {
        return this.expirationTime;
    }

    private static PublicKey makeKeyObject(byte[] publicKeyBytes) {
        try {
            X509EncodedKeySpec pubKeySpec = new X509EncodedKeySpec(publicKeyBytes);
            return KeyFactory.getInstance("RSA").generatePublic(pubKeySpec);
        } catch (InvalidKeySpecException | NoSuchAlgorithmException ex) {
            Log.e(LOG_TAG, "Error makeKeyObject: unable to convert into PublicKey", ex);
        }
        throw new IllegalArgumentException();
    }

    /** Implement the Parcelable interface */
    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<ImsiEncryptionInfo> CREATOR =
            new Parcelable.Creator<ImsiEncryptionInfo>() {
                @Override
                public ImsiEncryptionInfo createFromParcel(Parcel in) {
                    return new ImsiEncryptionInfo(in);
                }

                @Override
                public ImsiEncryptionInfo[] newArray(int size) {
                    return new ImsiEncryptionInfo[size];
                }
            };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        byte[] b = publicKey.getEncoded();
        dest.writeInt(b.length);
        dest.writeByteArray(b);
        dest.writeString(mcc);
        dest.writeString(mnc);
        dest.writeString(keyIdentifier);
        dest.writeInt(keyType);
        dest.writeLong(expirationTime.getTime());
    }

    @Override
    public String toString(){
        return "[ImsiEncryptionInfo "
                + "mcc=" + mcc
                + "mnc=" + mnc
                + "publicKey=" + publicKey
                + ", keyIdentifier=" + keyIdentifier
                + ", keyType=" + keyType
                + ", expirationTime=" + expirationTime
                + "]";
    }
}
