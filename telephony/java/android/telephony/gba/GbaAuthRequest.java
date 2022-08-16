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

package android.telephony.gba;

import android.annotation.NonNull;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.telephony.IBootstrapAuthenticationCallback;

import com.android.internal.telephony.uicc.IccUtils;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * GBA authentication request
 * {@hide}
 */
public final class GbaAuthRequest implements Parcelable {
    private int mToken;
    private int mSubId;
    private int mAppType;
    private Uri mNafUrl;
    private byte[] mSecurityProtocol;
    private boolean mForceBootStrapping;
    private IBootstrapAuthenticationCallback mCallback;

    private static AtomicInteger sUniqueToken = new AtomicInteger(0);

    public GbaAuthRequest(int subId, int appType, Uri nafUrl, byte[] securityProtocol,
            boolean forceBootStrapping, IBootstrapAuthenticationCallback callback) {
        this(nextUniqueToken(), subId, appType, nafUrl,
                securityProtocol, forceBootStrapping, callback);
    }

    public GbaAuthRequest(GbaAuthRequest request) {
        this(request.mToken, request.mSubId, request.mAppType, request.mNafUrl,
                request.mSecurityProtocol, request.mForceBootStrapping, request.mCallback);
    }

    public GbaAuthRequest(int token, int subId, int appType, Uri nafUrl, byte[] securityProtocol,
            boolean forceBootStrapping, IBootstrapAuthenticationCallback callback) {
        mToken = token;
        mSubId = subId;
        mAppType = appType;
        mNafUrl = nafUrl;
        mSecurityProtocol = securityProtocol;
        mCallback = callback;
        mForceBootStrapping = forceBootStrapping;
    }

    public int getToken() {
        return mToken;
    }

    public int getSubId() {
        return mSubId;
    }

    public int getAppType() {
        return mAppType;
    }

    public Uri getNafUrl() {
        return mNafUrl;
    }

    public byte[] getSecurityProtocol() {
        return mSecurityProtocol;
    }

    public boolean isForceBootStrapping() {
        return mForceBootStrapping;
    }

    public void setCallback(IBootstrapAuthenticationCallback cb) {
        mCallback = cb;
    }

    public IBootstrapAuthenticationCallback getCallback() {
        return mCallback;
    }

    /**
     * {@link Parcelable#writeToParcel}
     */
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mToken);
        out.writeInt(mSubId);
        out.writeInt(mAppType);
        out.writeParcelable(mNafUrl, 0);
        out.writeInt(mSecurityProtocol.length);
        out.writeByteArray(mSecurityProtocol);
        out.writeBoolean(mForceBootStrapping);
        out.writeStrongInterface(mCallback);
    }

    /**
     * {@link Parcelable.Creator}
     *
     */
    public static final @android.annotation.NonNull Parcelable.Creator<
            GbaAuthRequest> CREATOR = new Creator<GbaAuthRequest>() {
                @Override
                public GbaAuthRequest createFromParcel(Parcel in) {
                    int token = in.readInt();
                    int subId = in.readInt();
                    int appType = in.readInt();
                    Uri nafUrl = in.readParcelable(GbaAuthRequest.class.getClassLoader(), android.net.Uri.class);
                    int len = in.readInt();
                    byte[] protocol = new byte[len];
                    in.readByteArray(protocol);
                    boolean forceBootStrapping = in.readBoolean();
                    IBootstrapAuthenticationCallback callback =
                            IBootstrapAuthenticationCallback.Stub
                            .asInterface(in.readStrongBinder());
                    return new GbaAuthRequest(token, subId, appType, nafUrl, protocol,
                            forceBootStrapping, callback);
                }

                @Override
                public GbaAuthRequest[] newArray(int size) {
                    return new GbaAuthRequest[size];
                }
            };

    /**
     * {@link Parcelable#describeContents}
     */
    public int describeContents() {
        return 0;
    }

    private static int nextUniqueToken() {
        return sUniqueToken.getAndIncrement() << 16 | (0xFFFF & (int) System.currentTimeMillis());
    }

    @Override
    public String toString() {
        String str = "Token: " +  mToken + "SubId:" + mSubId + ", AppType:"
                + mAppType + ", NafUrl:" + mNafUrl + ", SecurityProtocol:"
                + IccUtils.bytesToHexString(mSecurityProtocol)
                + ", ForceBootStrapping:" + mForceBootStrapping
                + ", CallBack:" + mCallback;
        return str;
    }
}
