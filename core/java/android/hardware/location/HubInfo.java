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
package android.hardware.location;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.chre.flags.Flags;
import android.os.BadParcelableException;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Union type for {@link ContextHubInfo} and {@link VendorHubInfo}
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_OFFLOAD_API)
public final class HubInfo implements Parcelable {

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {TYPE_CONTEXT_HUB, TYPE_VENDOR_HUB})
    private @interface HubType {}

    public static final int TYPE_CONTEXT_HUB = 0;
    public static final int TYPE_VENDOR_HUB = 1;

    private final long mId;
    @HubType private final int mType;
    @Nullable private final ContextHubInfo mContextHubInfo;
    @Nullable private final VendorHubInfo mVendorHubInfo;

    /** @hide */
    public HubInfo(long id, @NonNull ContextHubInfo contextHubInfo) {
        mId = id;
        mType = TYPE_CONTEXT_HUB;
        mContextHubInfo = contextHubInfo;
        mVendorHubInfo = null;
    }

    /** @hide */
    public HubInfo(long id, @NonNull VendorHubInfo vendorHubInfo) {
        mId = id;
        mType = TYPE_VENDOR_HUB;
        mContextHubInfo = null;
        mVendorHubInfo = vendorHubInfo;
    }

    private HubInfo(Parcel in) {
        mId = in.readLong();
        mType = in.readInt();

        switch (mType) {
            case TYPE_CONTEXT_HUB:
                mContextHubInfo = ContextHubInfo.CREATOR.createFromParcel(in);
                mVendorHubInfo = null;
                break;
            case TYPE_VENDOR_HUB:
                mVendorHubInfo = VendorHubInfo.CREATOR.createFromParcel(in);
                mContextHubInfo = null;
                break;
            default:
                throw new BadParcelableException("Parcelable has invalid type");
        }
    }

    /** Get the hub unique identifier */
    public long getId() {
        return mId;
    }

    /** Get the hub type. The type can be {@link TYPE_CONTEXT_HUB} or {@link TYPE_VENDOR_HUB} */
    public int getType() {
        return mType;
    }

    /** Get the {@link ContextHubInfo} object, null if type is not {@link TYPE_CONTEXT_HUB} */
    @Nullable
    public ContextHubInfo getContextHubInfo() {
        return mContextHubInfo;
    }

    /** Parcel implementation details */
    public int describeContents() {
        if (mType == TYPE_CONTEXT_HUB && mContextHubInfo != null) {
            return mContextHubInfo.describeContents();
        }
        if (mType == TYPE_VENDOR_HUB && mVendorHubInfo != null) {
            return mVendorHubInfo.describeContents();
        }
        return 0;
    }

    /** Parcel implementation details */
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeLong(mId);
        out.writeInt(mType);

        if (mType == TYPE_CONTEXT_HUB && mContextHubInfo != null) {
            mContextHubInfo.writeToParcel(out, flags);
        }

        if (mType == TYPE_VENDOR_HUB && mVendorHubInfo != null) {
            mVendorHubInfo.writeToParcel(out, flags);
        }
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        out.append("HubInfo ID: 0x");
        out.append(Long.toHexString(mId));
        out.append("\n");
        if (mType == TYPE_CONTEXT_HUB && mContextHubInfo != null) {
            out.append(" ContextHubDetails: ");
            out.append(mContextHubInfo);
        }
        if (mType == TYPE_VENDOR_HUB && mVendorHubInfo != null) {
            out.append(" VendorHubDetails: ");
            out.append(mVendorHubInfo);
        }
        return out.toString();
    }

    public static final @NonNull Creator<HubInfo> CREATOR =
            new Creator<>() {
                public HubInfo createFromParcel(Parcel in) {
                    return new HubInfo(in);
                }

                public HubInfo[] newArray(int size) {
                    return new HubInfo[size];
                }
            };
}
