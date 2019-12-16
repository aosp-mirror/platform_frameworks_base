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

package com.android.ims.internal.uce.common;

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/** Class for capability discovery information.
 *  @hide */
public class CapInfo implements Parcelable {

    /** IM session support. */
    private boolean mImSupported = false;
    /** File transfer support. */
    private boolean mFtSupported = false;
    /** File transfer Thumbnail support. */
    private boolean mFtThumbSupported = false;
    /** File transfer Store and forward support. */
    private boolean mFtSnFSupported = false;
    /** File transfer HTTP support. */
    private boolean mFtHttpSupported = false;
    /** Image sharing support. */
    private boolean mIsSupported = false;
    /** Video sharing during a CS call support -- IR-74. */
    private boolean mVsDuringCSSupported = false;
    /** Video sharing outside of voice call support -- IR-84. */
    private boolean mVsSupported = false;
    /** Social presence support. */
    private boolean mSpSupported = false;
    /** Presence discovery support. */
    private boolean mCdViaPresenceSupported = false;
    /** IP voice call support (IR-92/IR-58). */
    private boolean mIpVoiceSupported = false;
    /** IP video call support (IR-92/IR-58). */
    private boolean mIpVideoSupported = false;
    /** IP Geo location Pull using File Transfer support. */
    private boolean mGeoPullFtSupported = false;
    /** IP Geo location Pull support. */
    private boolean mGeoPullSupported = false;
    /** IP Geo location Push support. */
    private boolean mGeoPushSupported = false;
    /** Standalone messaging support. */
    private boolean mSmSupported = false;
    /** Full Store and Forward Group Chat information. */
    private boolean mFullSnFGroupChatSupported = false;
    /** RCS IP Voice call support .  */
    private boolean mRcsIpVoiceCallSupported = false;
    /** RCS IP Video call support .  */
    private boolean mRcsIpVideoCallSupported = false;
    /** RCS IP Video call support .  */
    private boolean mRcsIpVideoOnlyCallSupported = false;
    /** List of supported extensions. */
    private String[] mExts = new String[10];
    /** Time used to compute when to query again. */
    private long mCapTimestamp = 0;


    /**
     * Constructor for the CapInfo class.
     */
    @UnsupportedAppUsage
    public CapInfo() {
    };


    /**
     * Checks whether IM is supported.
     */
    @UnsupportedAppUsage
    public boolean isImSupported() {
        return mImSupported;
    }

    /**
     * Sets IM as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setImSupported(boolean imSupported) {
        this.mImSupported = imSupported;
    }

    /**
     * Checks whether FT Thumbnail is supported.
     */
    @UnsupportedAppUsage
    public boolean isFtThumbSupported() {
        return mFtThumbSupported;
    }

    /**
     * Sets FT thumbnail as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setFtThumbSupported(boolean ftThumbSupported) {
        this.mFtThumbSupported = ftThumbSupported;
    }



    /**
     * Checks whether FT Store and Forward is supported
     */
    @UnsupportedAppUsage
    public boolean isFtSnFSupported() {
        return  mFtSnFSupported;
    }

    /**
     * Sets FT Store and Forward as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setFtSnFSupported(boolean  ftSnFSupported) {
        this.mFtSnFSupported =  ftSnFSupported;
    }

   /**
    * Checks whether File transfer HTTP is supported.
    */
   @UnsupportedAppUsage
   public boolean isFtHttpSupported() {
       return  mFtHttpSupported;
   }

   /**
    * Sets File transfer HTTP as supported or not supported.
    */
   @UnsupportedAppUsage
   public void setFtHttpSupported(boolean  ftHttpSupported) {
       this.mFtHttpSupported =  ftHttpSupported;
   }

    /**
     * Checks whether FT is supported.
     */
    @UnsupportedAppUsage
    public boolean isFtSupported() {
        return mFtSupported;
    }

    /**
     * Sets FT as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setFtSupported(boolean ftSupported) {
        this.mFtSupported = ftSupported;
    }

    /**
     * Checks whether IS is supported.
     */
    @UnsupportedAppUsage
    public boolean isIsSupported() {
        return mIsSupported;
    }

    /**
     * Sets IS as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setIsSupported(boolean isSupported) {
        this.mIsSupported = isSupported;
    }

    /**
     * Checks whether video sharing is supported during a CS call.
     */
    @UnsupportedAppUsage
    public boolean isVsDuringCSSupported() {
        return mVsDuringCSSupported;
    }

    /**
     *  Sets video sharing as supported or not supported during a CS
     *  call.
     */
    @UnsupportedAppUsage
    public void setVsDuringCSSupported(boolean vsDuringCSSupported) {
        this.mVsDuringCSSupported = vsDuringCSSupported;
    }

    /**
     *  Checks whether video sharing outside a voice call is
     *   supported.
     */
    @UnsupportedAppUsage
    public boolean isVsSupported() {
        return mVsSupported;
    }

    /**
     * Sets video sharing as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setVsSupported(boolean vsSupported) {
        this.mVsSupported = vsSupported;
    }

    /**
     * Checks whether social presence is supported.
     */
    @UnsupportedAppUsage
    public boolean isSpSupported() {
        return mSpSupported;
    }

    /**
     * Sets social presence as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setSpSupported(boolean spSupported) {
        this.mSpSupported = spSupported;
    }

    /**
     * Checks whether capability discovery via presence is
     * supported.
     */
    @UnsupportedAppUsage
    public boolean isCdViaPresenceSupported() {
        return mCdViaPresenceSupported;
    }

    /**
     * Sets capability discovery via presence as supported or not
     * supported.
     */
    @UnsupportedAppUsage
    public void setCdViaPresenceSupported(boolean cdViaPresenceSupported) {
        this.mCdViaPresenceSupported = cdViaPresenceSupported;
    }

    /**
     * Checks whether IP voice call is supported.
     */
    @UnsupportedAppUsage
    public boolean isIpVoiceSupported() {
        return mIpVoiceSupported;
    }

    /**
     * Sets IP voice call as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setIpVoiceSupported(boolean ipVoiceSupported) {
        this.mIpVoiceSupported = ipVoiceSupported;
    }

    /**
     * Checks whether IP video call is supported.
     */
    @UnsupportedAppUsage
    public boolean isIpVideoSupported() {
        return mIpVideoSupported;
    }

    /**
     * Sets IP video call as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setIpVideoSupported(boolean ipVideoSupported) {
        this.mIpVideoSupported = ipVideoSupported;
    }

   /**
    * Checks whether Geo location Pull using File Transfer is
    * supported.
    */
   @UnsupportedAppUsage
   public boolean isGeoPullFtSupported() {
       return mGeoPullFtSupported;
   }

   /**
    * Sets Geo location Pull using File Transfer as supported or
    * not supported.
    */
   @UnsupportedAppUsage
   public void setGeoPullFtSupported(boolean geoPullFtSupported) {
       this.mGeoPullFtSupported = geoPullFtSupported;
   }

    /**
     * Checks whether Geo Pull is supported.
     */
    @UnsupportedAppUsage
    public boolean isGeoPullSupported() {
        return mGeoPullSupported;
    }

    /**
     * Sets Geo Pull as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setGeoPullSupported(boolean geoPullSupported) {
        this.mGeoPullSupported = geoPullSupported;
    }

    /**
     * Checks whether Geo Push is supported.
     */
    @UnsupportedAppUsage
    public boolean isGeoPushSupported() {
        return mGeoPushSupported;
    }

    /**
     * Sets Geo Push as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setGeoPushSupported(boolean geoPushSupported) {
        this.mGeoPushSupported = geoPushSupported;
    }

    /**
     * Checks whether short messaging is supported.
     */
    @UnsupportedAppUsage
    public boolean isSmSupported() {
        return mSmSupported;
    }

    /**
     * Sets short messaging as supported or not supported.
     */
    @UnsupportedAppUsage
    public void setSmSupported(boolean smSupported) {
        this.mSmSupported = smSupported;
    }

    /**
     * Checks whether store/forward and group chat are supported.
     */
    @UnsupportedAppUsage
    public boolean isFullSnFGroupChatSupported() {
        return mFullSnFGroupChatSupported;
    }

    @UnsupportedAppUsage
    public boolean isRcsIpVoiceCallSupported() {
        return mRcsIpVoiceCallSupported;
    }

    @UnsupportedAppUsage
    public boolean isRcsIpVideoCallSupported() {
        return mRcsIpVideoCallSupported;
    }

    @UnsupportedAppUsage
    public boolean isRcsIpVideoOnlyCallSupported() {
        return mRcsIpVideoOnlyCallSupported;
    }

    /**
     * Sets store/forward and group chat supported or not supported.
     */
    @UnsupportedAppUsage
    public void setFullSnFGroupChatSupported(boolean fullSnFGroupChatSupported) {
        this.mFullSnFGroupChatSupported = fullSnFGroupChatSupported;
    }

    @UnsupportedAppUsage
    public void setRcsIpVoiceCallSupported(boolean rcsIpVoiceCallSupported) {
        this.mRcsIpVoiceCallSupported = rcsIpVoiceCallSupported;
    }
    @UnsupportedAppUsage
    public void setRcsIpVideoCallSupported(boolean rcsIpVideoCallSupported) {
        this.mRcsIpVideoCallSupported = rcsIpVideoCallSupported;
    }
    @UnsupportedAppUsage
    public void setRcsIpVideoOnlyCallSupported(boolean rcsIpVideoOnlyCallSupported) {
        this.mRcsIpVideoOnlyCallSupported = rcsIpVideoOnlyCallSupported;
    }

    /** Gets the list of supported extensions. */
    public String[] getExts() {
        return mExts;
    }

    /** Sets the list of supported extensions. */
    @UnsupportedAppUsage
    public void setExts(String[] exts) {
        this.mExts = exts;
    }


    /** Gets the time stamp for when to query again. */
    @UnsupportedAppUsage
    public long getCapTimestamp() {
        return mCapTimestamp;
    }

    /** Sets the time stamp for when to query again. */
    @UnsupportedAppUsage
    public void setCapTimestamp(long capTimestamp) {
        this.mCapTimestamp = capTimestamp;
    }

    public int describeContents() {
        // TODO Auto-generated method stub
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {

        dest.writeInt(mImSupported ? 1 : 0);
        dest.writeInt(mFtSupported ? 1 : 0);
        dest.writeInt(mFtThumbSupported ? 1 : 0);
        dest.writeInt(mFtSnFSupported ? 1 : 0);
        dest.writeInt(mFtHttpSupported ? 1 : 0);
        dest.writeInt(mIsSupported ? 1 : 0);
        dest.writeInt(mVsDuringCSSupported ? 1 : 0);
        dest.writeInt(mVsSupported ? 1 : 0);
        dest.writeInt(mSpSupported ? 1 : 0);
        dest.writeInt(mCdViaPresenceSupported ? 1 : 0);
        dest.writeInt(mIpVoiceSupported ? 1 : 0);
        dest.writeInt(mIpVideoSupported ? 1 : 0);
        dest.writeInt(mGeoPullFtSupported ? 1 : 0);
        dest.writeInt(mGeoPullSupported ? 1 : 0);
        dest.writeInt(mGeoPushSupported ? 1 : 0);
        dest.writeInt(mSmSupported ? 1 : 0);
        dest.writeInt(mFullSnFGroupChatSupported ? 1 : 0);

        dest.writeInt(mRcsIpVoiceCallSupported ? 1 : 0);
        dest.writeInt(mRcsIpVideoCallSupported ? 1 : 0);
        dest.writeInt(mRcsIpVideoOnlyCallSupported ? 1 : 0);
        dest.writeStringArray(mExts);
        dest.writeLong(mCapTimestamp);
    }

    public static final Parcelable.Creator<CapInfo> CREATOR = new Parcelable.Creator<CapInfo>() {

        public CapInfo createFromParcel(Parcel source) {
            return new CapInfo(source);
        }

        public CapInfo[] newArray(int size) {
            return new CapInfo[size];
        }
    };

    private CapInfo(Parcel source) {
        readFromParcel(source);
    }

    public void readFromParcel(Parcel source) {

        mImSupported = (source.readInt() == 0) ? false : true;
        mFtSupported = (source.readInt() == 0) ? false : true;
        mFtThumbSupported = (source.readInt() == 0) ? false : true;
        mFtSnFSupported = (source.readInt() == 0) ? false : true;
        mFtHttpSupported = (source.readInt() == 0) ? false : true;
        mIsSupported = (source.readInt() == 0) ? false : true;
        mVsDuringCSSupported = (source.readInt() == 0) ? false : true;
        mVsSupported = (source.readInt() == 0) ? false : true;
        mSpSupported = (source.readInt() == 0) ? false : true;
        mCdViaPresenceSupported = (source.readInt() == 0) ? false : true;
        mIpVoiceSupported = (source.readInt() == 0) ? false : true;
        mIpVideoSupported = (source.readInt() == 0) ? false : true;
        mGeoPullFtSupported = (source.readInt() == 0) ? false : true;
        mGeoPullSupported = (source.readInt() == 0) ? false : true;
        mGeoPushSupported = (source.readInt() == 0) ? false : true;
        mSmSupported = (source.readInt() == 0) ? false : true;
        mFullSnFGroupChatSupported = (source.readInt() == 0) ? false : true;

        mRcsIpVoiceCallSupported = (source.readInt() == 0) ? false : true;
        mRcsIpVideoCallSupported = (source.readInt() == 0) ? false : true;
        mRcsIpVideoOnlyCallSupported = (source.readInt() == 0) ? false : true;

        mExts = source.createStringArray();
        mCapTimestamp = source.readLong();
    }
}
