/*
 * Copyright (c) 2016 The Android Open Source Project
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

package com.android.ims.internal.uce.presence;

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/** @hide  */
public class PresRlmiInfo implements Parcelable {

    /**
     * uri corresponding to the list.  Typically, this is the URI to
     * which the SUBSCRIBE request was sent.
     */
    private String mUri = "";
    /** list version number from 0 to 2^32-1 */
    private int mVersion;
    /**
     * Indicate whether the NOTIFY message contains information for
     * every resource in the list
     */
    private boolean mFullState;
    /** list name */
    private String mListName = "";
    /**
     * unique request ID used to match NOTIFY with original list
     * SUBSCRIBE
     */
    private int mRequestId;
    /** subscription state*/
    private PresSubscriptionState mPresSubscriptionState;
    /** active subscription expires time in second */
    private int mSubscriptionExpireTime;
    /** list subscrption terminated reason */
    private String mSubscriptionTerminatedReason;

    /**
     * Gets the URI.
     * @hide
     */
    public String getUri() {
        return mUri;
    }

    /**
     * Sets the URI.
     * @hide
     */
    @UnsupportedAppUsage
    public void setUri(String uri) {
        this.mUri = uri;
    }

    /**
     * Gets the version.
     * @hide
     */
    public int getVersion() {
        return mVersion;
    }

    /**
     * Sets the version.
     * @hide
     */
    @UnsupportedAppUsage
    public void setVersion(int version) {
        this.mVersion = version;
    }

    /**
     * Gets the RLMI state.
     * @hide
     */
    public boolean isFullState() {
        return mFullState;
    }

    /**
     * Sets the RLMI state.
     * @hide
     */
    @UnsupportedAppUsage
    public void setFullState(boolean fullState) {
        this.mFullState = fullState;
    }

    /**
     * Gets the RLMI list name.
     * @hide
     */
    public String getListName() {
        return mListName;
    }

    /**
     * Sets the RLMI list name.
     * @hide
     */
    @UnsupportedAppUsage
    public void setListName(String listName) {
        this.mListName = listName;
    }

    /**
     *  Gets the subscription request ID.
     *  @hide
     */
    public int getRequestId() {
        return mRequestId;
    }

    /**
     * Sets the subscription request ID.
     * @hide
     */
    @UnsupportedAppUsage
    public void setRequestId(int requestId) {
        this.mRequestId = requestId;
    }

    /**
     * Gets the presence subscription state.
     * @hide
     */
    public PresSubscriptionState getPresSubscriptionState() {
        return mPresSubscriptionState;
    }

    /**
     * Sets the presence subscription state.
     * @hide
     */
    @UnsupportedAppUsage
    public void setPresSubscriptionState(PresSubscriptionState presSubscriptionState) {
        this.mPresSubscriptionState = presSubscriptionState;
    }

    /**
     * Gets the presence subscription expiration time.
     * @hide
     */
    public int getSubscriptionExpireTime() {
        return mSubscriptionExpireTime;
    }

    /**
     * Sets the presence subscription expiration time.
     * @hide
     */
    @UnsupportedAppUsage
    public void setSubscriptionExpireTime(int subscriptionExpireTime) {
        this.mSubscriptionExpireTime = subscriptionExpireTime;
    }

    /**
     * Gets the presence subscription terminated reason.
     * @hide
     */
    public String getSubscriptionTerminatedReason() {
        return mSubscriptionTerminatedReason;
    }

    /**
     * Sets the presence subscription terminated reason.
     * @hide
     */
    @UnsupportedAppUsage
    public void setSubscriptionTerminatedReason(String subscriptionTerminatedReason) {
        this.mSubscriptionTerminatedReason = subscriptionTerminatedReason;
    }

    /**
     * Constructor for the PresTupleInfo class.
     * @hide
     */
    @UnsupportedAppUsage
    public PresRlmiInfo(){};

    /** @hide */
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mUri);
        dest.writeInt(mVersion);
        dest.writeInt(mFullState ? 1 : 0);
        dest.writeString(mListName);
        dest.writeInt(mRequestId);
        dest.writeParcelable(mPresSubscriptionState, flags);
        dest.writeInt(mSubscriptionExpireTime);
        dest.writeString(mSubscriptionTerminatedReason);
    }

    /** @hide */
    public static final Parcelable.Creator<PresRlmiInfo> CREATOR =
                                new Parcelable.Creator<PresRlmiInfo>() {

        public PresRlmiInfo createFromParcel(Parcel source) {
            return new PresRlmiInfo(source);
        }

        public PresRlmiInfo[] newArray(int size) {
            return new PresRlmiInfo[size];
        }
    };

    /** @hide */
    private PresRlmiInfo(Parcel source) {
        readFromParcel(source);
    }

    /** @hide */
    public void readFromParcel(Parcel source) {
        mUri = source.readString();
        mVersion = source.readInt();
        mFullState = (source.readInt() == 0) ? false : true;
        mListName = source.readString();
        mRequestId = source.readInt();
        mPresSubscriptionState = source.readParcelable(
                                  PresSubscriptionState.class.getClassLoader());
        mSubscriptionExpireTime = source.readInt();
        mSubscriptionTerminatedReason = source.readString();
    }
}