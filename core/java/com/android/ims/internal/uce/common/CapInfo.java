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

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Bundle;

import java.util.Map;
import java.util.HashMap;

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
    /** IP Geo location Push using SMS. */
    private boolean mGeoSmsSupported = false;
    /** RCS call composer support. */
    private boolean mCallComposerSupported = false;
    /** RCS post-call support. */
    private boolean mPostCallSupported = false;
    /** Shared map support. */
    private boolean mSharedMapSupported = false;
    /** Shared Sketch supported. */
    private boolean mSharedSketchSupported = false;
    /** Chatbot communication support. */
    private boolean mChatbotSupported = false;
    /** Chatbot role support. */
    private boolean mChatbotRoleSupported = false;
    /** Standalone Chatbot communication support. */
    private boolean mSmChatbotSupported = false;
    /** MMtel based call composer support. */
    private boolean mMmtelCallComposerSupported = false;
    /** List of supported extensions. */
    private String[] mExts = new String[10];
    /** Time used to compute when to query again. */
    private long mCapTimestamp = 0;

    private Map<String, String> mCapInfoMap = new HashMap<String, String>();

    /** IM session feature tag key. */
    public static final String INSTANT_MSG =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcse.im\"";
    /** File transfer feature tag key. */
    public static final String FILE_TRANSFER =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcse.ft\"";
    /** File transfer Thumbnail feature tag key. */
    public static final String FILE_TRANSFER_THUMBNAIL =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.ftthumb\"";
    /** File transfer Store and forward feature tag key. */
    public static final String FILE_TRANSFER_SNF =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.ftstandfw\"";
    /** File transfer HTTP feature tag key. */
    public static final String FILE_TRANSFER_HTTP =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.fthttp\"";
    /** Image sharing feature tag key. */
    public static final String IMAGE_SHARE =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.gsma-is\"";
    /** Video sharing during a CS call feature tag key-- IR-74. */
    public static final String VIDEO_SHARE_DURING_CS = "+g.3gpp.cs-voice";
    /** Video sharing outside of voice call feature tag key-- IR-84. */
    public static final String VIDEO_SHARE =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.gsma-vs\"";
    /** Social presence feature tag key. */
    public static final String SOCIAL_PRESENCE =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcse.sp\"";
    /** Presence discovery feature tag key. */
    public static final String CAPDISC_VIA_PRESENCE =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcse.dp\"";
    /** IP voice call feature tag key (IR-92/IR-58). */
    public static final String IP_VOICE =
      "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\"";
    /** IP video call feature tag key (IR-92/IR-58). */
    public static final String IP_VIDEO =
      "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\";video";
    /** IP Geo location Pull using File Transfer feature tag key. */
    public static final String GEOPULL_FT =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.geopullft\"";
    /** IP Geo location Pull feature tag key. */
    public static final String GEOPULL =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.geopull\"";
    /** IP Geo location Push feature tag key. */
    public static final String GEOPUSH =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.geopush\"";
    /** Standalone messaging feature tag key. */
    public static final String STANDALONE_MSG =
      "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.msg;" +
      "urn%3Aurn-7%3A3gpp-service.ims.icsi.oma.cpm.largemsg\"";
    /** Full Store and Forward Group Chat information feature tag key. */
    public static final String FULL_SNF_GROUPCHAT =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.fullsfgroupchat\"";
    /** RCS IP Voice call feature tag key.  */
    public static final String RCS_IP_VOICE_CALL =
      "+g.gsma.rcs.ipcall";
    /** RCS IP Video call feature tag key.  */
    public static final String RCS_IP_VIDEO_CALL =
      "+g.gsma.rcs.ipvideocall";
    /** RCS IP Video only call feature tag key.  */
    public static final String RCS_IP_VIDEO_ONLY_CALL =
      "+g.gsma.rcs.ipvideoonlycall";
    /** IP Geo location Push using SMS feature tag key. */
    public static final String GEOSMS =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gppapplication.ims.iari.rcs.geosms\"";
    /** RCS call composer feature tag key. */
    public static final String CALLCOMPOSER =
      "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gppservice.ims.icsi.gsma.callcomposer\"";
    /** RCS post-call feature tag key. */
    public static final String POSTCALL =
      "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gppservice.ims.icsi.gsma.callunanswered\"";
    /** Shared map feature tag key. */
    public static final String SHAREDMAP =
      "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gppservice.ims.icsi.gsma.sharedmap\"";
    /** Shared Sketch feature tag key. */
    public static final String SHAREDSKETCH =
      "+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gppservice.ims.icsi.gsma.sharedsketch\"";
    /** Chatbot communication feature tag key. */
    public static final String CHATBOT =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gppapplication.ims.iari.rcs.chatbot\"";
    /** Chatbot role feature tag key. */
    public static final String CHATBOTROLE = "+g.gsma.rcs.isbot";
    /** Standalone Chatbot communication feature tag key. */
    public static final String STANDALONE_CHATBOT =
      "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcs.chatbot.sa\"";
    /** MMtel based call composer feature tag key. */
    public static final String MMTEL_CALLCOMPOSER = "+g.gsma.callcomposer";



    /**
     * Constructor for the CapInfo class.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public CapInfo() {
    };


    /**
     * Checks whether IM is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isImSupported() {
        return mImSupported;
    }

    /**
     * Sets IM as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setImSupported(boolean imSupported) {
        this.mImSupported = imSupported;
    }

    /**
     * Checks whether FT Thumbnail is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isFtThumbSupported() {
        return mFtThumbSupported;
    }

    /**
     * Sets FT thumbnail as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setFtThumbSupported(boolean ftThumbSupported) {
        this.mFtThumbSupported = ftThumbSupported;
    }

    /**
     * Checks whether FT Store and Forward is supported
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isFtSnFSupported() {
        return  mFtSnFSupported;
    }

    /**
     * Sets FT Store and Forward as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setFtSnFSupported(boolean  ftSnFSupported) {
        this.mFtSnFSupported =  ftSnFSupported;
    }

   /**
    * Checks whether File transfer HTTP is supported.
    * @deprecated Use {@link #isCapabilitySupported(String)} instead.
    */
   @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
   public boolean isFtHttpSupported() {
       return  mFtHttpSupported;
   }

   /**
    * Sets File transfer HTTP as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
    */
   @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
   public void setFtHttpSupported(boolean  ftHttpSupported) {
       this.mFtHttpSupported =  ftHttpSupported;
   }

    /**
     * Checks whether FT is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isFtSupported() {
        return mFtSupported;
    }

    /**
     * Sets FT as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setFtSupported(boolean ftSupported) {
        this.mFtSupported = ftSupported;
    }

    /**
     * Checks whether IS is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isIsSupported() {
        return mIsSupported;
    }

    /**
     * Sets IS as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setIsSupported(boolean isSupported) {
        this.mIsSupported = isSupported;
    }

    /**
     * Checks whether video sharing is supported during a CS call.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isVsDuringCSSupported() {
        return mVsDuringCSSupported;
    }

    /**
     * Sets video sharing as supported or not supported during a CS
     * call.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setVsDuringCSSupported(boolean vsDuringCSSupported) {
        this.mVsDuringCSSupported = vsDuringCSSupported;
    }

    /**
     * Checks whether video sharing outside a voice call is
     *  supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isVsSupported() {
        return mVsSupported;
    }

    /**
     * Sets video sharing as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setVsSupported(boolean vsSupported) {
        this.mVsSupported = vsSupported;
    }

    /**
     * Checks whether social presence is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isSpSupported() {
        return mSpSupported;
    }

    /**
     * Sets social presence as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setSpSupported(boolean spSupported) {
        this.mSpSupported = spSupported;
    }

    /**
     * Checks whether capability discovery via presence is
     * supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isCdViaPresenceSupported() {
        return mCdViaPresenceSupported;
    }

    /**
     * Sets capability discovery via presence as supported or not
     * supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setCdViaPresenceSupported(boolean cdViaPresenceSupported) {
        this.mCdViaPresenceSupported = cdViaPresenceSupported;
    }

    /**
     * Checks whether IP voice call is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isIpVoiceSupported() {
        return mIpVoiceSupported;
    }

    /**
     * Sets IP voice call as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setIpVoiceSupported(boolean ipVoiceSupported) {
        this.mIpVoiceSupported = ipVoiceSupported;
    }

    /**
     * Checks whether IP video call is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isIpVideoSupported() {
        return mIpVideoSupported;
    }

    /**
     * Sets IP video call as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setIpVideoSupported(boolean ipVideoSupported) {
        this.mIpVideoSupported = ipVideoSupported;
    }

   /**
    * Checks whether Geo location Pull using File Transfer is
    * supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
    */
   @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
   public boolean isGeoPullFtSupported() {
       return mGeoPullFtSupported;
   }

   /**
    * Sets Geo location Pull using File Transfer as supported or
    * not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
    */
   @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
   public void setGeoPullFtSupported(boolean geoPullFtSupported) {
       this.mGeoPullFtSupported = geoPullFtSupported;
   }

    /**
     * Checks whether Geo Pull is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isGeoPullSupported() {
        return mGeoPullSupported;
    }

    /**
     * Sets Geo Pull as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setGeoPullSupported(boolean geoPullSupported) {
        this.mGeoPullSupported = geoPullSupported;
    }

    /**
     * Checks whether Geo Push is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isGeoPushSupported() {
        return mGeoPushSupported;
    }

    /**
     * Sets Geo Push as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setGeoPushSupported(boolean geoPushSupported) {
        this.mGeoPushSupported = geoPushSupported;
    }

    /**
     * Checks whether short messaging is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isSmSupported() {
        return mSmSupported;
    }

    /**
     * Sets short messaging as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setSmSupported(boolean smSupported) {
        this.mSmSupported = smSupported;
    }

    /**
     * Checks whether store/forward and group chat are supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isFullSnFGroupChatSupported() {
        return mFullSnFGroupChatSupported;
    }

    /**
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isRcsIpVoiceCallSupported() {
        return mRcsIpVoiceCallSupported;
    }

    /**
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isRcsIpVideoCallSupported() {
        return mRcsIpVideoCallSupported;
    }

    /**
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isRcsIpVideoOnlyCallSupported() {
        return mRcsIpVideoOnlyCallSupported;
    }

    /**
     * Sets store/forward and group chat supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setFullSnFGroupChatSupported(boolean fullSnFGroupChatSupported) {
        this.mFullSnFGroupChatSupported = fullSnFGroupChatSupported;
    }

    /**
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setRcsIpVoiceCallSupported(boolean rcsIpVoiceCallSupported) {
        this.mRcsIpVoiceCallSupported = rcsIpVoiceCallSupported;
    }

    /**
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setRcsIpVideoCallSupported(boolean rcsIpVideoCallSupported) {
        this.mRcsIpVideoCallSupported = rcsIpVideoCallSupported;
    }

    /**
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setRcsIpVideoOnlyCallSupported(boolean rcsIpVideoOnlyCallSupported) {
        this.mRcsIpVideoOnlyCallSupported = rcsIpVideoOnlyCallSupported;
    }

    /**
     * Checks whether Geo Push via SMS is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    public boolean isGeoSmsSupported() {
        return mGeoSmsSupported;
    }

    /**
     * Sets Geolocation Push via SMS as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    public void setGeoSmsSupported(boolean geoSmsSupported) {
         this.mGeoSmsSupported = geoSmsSupported;
    }

    /**
     * Checks whether RCS call composer is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    public boolean isCallComposerSupported() {
        return mCallComposerSupported;
    }

    /**
     * Sets call composer as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    public void setCallComposerSupported(boolean callComposerSupported) {
        this.mCallComposerSupported = callComposerSupported;
    }

    /**
     * Checks whether post call is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    public boolean isPostCallSupported(){
        return mPostCallSupported;
    }

    /**
     * Sets post call as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    public void setPostCallSupported(boolean postCallSupported) {
        this.mPostCallSupported = postCallSupported;
    }

    /**
     * Checks whether shared map is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    public boolean isSharedMapSupported() {
        return mSharedMapSupported;
    }

    /**
     * Sets shared map as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    public void setSharedMapSupported(boolean sharedMapSupported) {
        this.mSharedMapSupported = sharedMapSupported;
    }

    /**
     * Checks whether shared sketch is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    public boolean isSharedSketchSupported() {
        return mSharedSketchSupported;
    }

    /**
     * Sets shared sketch as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    public void setSharedSketchSupported(boolean sharedSketchSupported) {
        this.mSharedSketchSupported = sharedSketchSupported;
    }

    /**
     * Checks whether chatbot communication is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    public boolean isChatbotSupported() {
        return mChatbotSupported;
    }

    /**
     * Sets chatbot communication as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    public void setChatbotSupported(boolean chatbotSupported) {
        this.mChatbotSupported = chatbotSupported;
    }

    /**
     * Checks whether chatbot role is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    public boolean isChatbotRoleSupported() {
        return mChatbotRoleSupported;
    }

    /**
     * Sets chatbot role as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    public void setChatbotRoleSupported(boolean chatbotRoleSupported) {
        this.mChatbotRoleSupported = chatbotRoleSupported;
    }

    /**
     * Checks whether standalone chatbot communication is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    public boolean isSmChatbotSupported() {
        return mSmChatbotSupported;
    }

    /**
     * Sets standalone chatbot communication as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    public void setSmChatbotSupported(boolean smChatbotSupported) {
        this.mSmChatbotSupported = smChatbotSupported;
    }

    /**
     * Checks whether Mmtel based call composer is supported.
     * @deprecated Use {@link #isCapabilitySupported(String)} instead.
     */
    public boolean isMmtelCallComposerSupported() {
        return mMmtelCallComposerSupported;
    }

    /**
     * Sets Mmtel based call composer as supported or not supported.
     * @deprecated Use {@link #addCapability(String, String)} instead.
     */
    public void setMmtelCallComposerSupported(boolean mmtelCallComposerSupported) {
        this.mMmtelCallComposerSupported = mmtelCallComposerSupported;
    }

    /** Gets the list of supported extensions. */
    public String[] getExts() {
        return mExts;
    }

    /** Sets the list of supported extensions. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setExts(String[] exts) {
        this.mExts = exts;
    }


    /** Gets the time stamp for when to query again. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public long getCapTimestamp() {
        return mCapTimestamp;
    }

    /** Sets the time stamp for when to query again. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setCapTimestamp(long capTimestamp) {
        this.mCapTimestamp = capTimestamp;
    }

    /**
     * Adds the feature tag string with supported versions to
     * the mCapInfoMap.
     * Map<String featureType, String versions>
     * Versions format:
     *    "+g.gsma.rcs.botversion=\"#=1"        -> Version 1 supported
     *    "+g.gsma.rcs.botversion=\"#=1,#=2\""  -> Versions 1 and 2 are supported
     *
     * Example #1: Add standard feature tag with one version support
     * addCapability(CapInfo.STANDALONE_CHATBOT, "+g.gsma.rcs.botversion=\"#=1");
     * The above example indicates standalone chatbot feature tag is supported
     * in version 1.
     *
     * Example #2: Add standard feature tag with multiple version support
     * addCapability(CapInfo.CHATBOT, "+g.gsma.rcs.botversion=\"#=1,#=2\"");
     * The above example indicates session based chatbot feature tag is supported
     * in versions 1 and 2.
     *
     * Example #3: Add standard feature tag with no version support
     * addCapability(CapInfo.INSTANT_MSG, "");
     * The above example indicates im feature tag does not have version support.
     *
     * Example #4: Add custom/extension feature tag with no version support
     * addCapability("+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcse.custom_im\"",
     *               "");
     * Call setNewFeatureTag(int presenceServiceHdl, String featureTag,
     *           in PresServiceInfo serviceInfo, int userData) API
     * in IPresenceService.aidl before calling addCapability() API
     */
    public void addCapability(String featureTagName, String versions) {
        this.mCapInfoMap.put(featureTagName, versions);
    }

    /**
     * Returns String of versions of the feature tag passed.
     * Returns "" if versioning support is not present for the feature tag passed.
     * Returns null if feature tag is not present.
     *
     * Example # 1:
     * getCapabilityVersions(CapInfo.STANDALONE_CHATBOT);
     * The above returns String in this format "+g.gsma.rcs.botversion=\"#=1,#=2\"",
     * indicating more than one versions are supported for standalone chatbot feature tag
     *
     * Example # 2:
     * getCapabilityVersions(CapInfo.INSTANT_MSG);
     * The above returns empty String in this format "",
     * indicating versions support is not present for im feature tag
     *
     * Example #3:
     * getCapabilityVersions(
     *   "+g.3gpp.iari-ref=\"urn%3Aurn-7%3A3gpp-application.ims.iari.rcse.custom_im\");
     * The above returns String "",
     * indicating version supported is not present for the custom feature tag passed.
     */
    public String getCapabilityVersions(String featureTagName) {
        return mCapInfoMap.get(featureTagName);
    }

    /** Removes the entry of the feature tag passed, from the Map. */
    public void removeCapability(String featureTagName) {
        this.mCapInfoMap.remove(featureTagName);
    }

    /** Sets Map of feature tag string and string of supported versions. */
    public void setCapInfoMap(Map<String, String> capInfoMap) {
        this.mCapInfoMap = capInfoMap;
    }

    /** Gets Map of feature tag string and string of supported versions. */
    public Map<String, String> getCapInfoMap() {
        return mCapInfoMap;
    }

    /** Checks whether the featureTag is supported or not. */
    public boolean isCapabilitySupported(String featureTag) {
       return mCapInfoMap.containsKey(featureTag);
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
        dest.writeInt(mGeoSmsSupported ? 1 : 0);
        dest.writeInt(mCallComposerSupported ? 1 : 0);
        dest.writeInt(mPostCallSupported ? 1 : 0);
        dest.writeInt(mSharedMapSupported ? 1 : 0);
        dest.writeInt(mSharedSketchSupported ? 1 : 0);
        dest.writeInt(mChatbotSupported ? 1 : 0);
        dest.writeInt(mChatbotRoleSupported ? 1 : 0);
        dest.writeInt(mSmChatbotSupported ? 1 : 0);
        dest.writeInt(mMmtelCallComposerSupported ? 1 : 0);

        dest.writeInt(mRcsIpVoiceCallSupported ? 1 : 0);
        dest.writeInt(mRcsIpVideoCallSupported ? 1 : 0);
        dest.writeInt(mRcsIpVideoOnlyCallSupported ? 1 : 0);
        dest.writeStringArray(mExts);
        dest.writeLong(mCapTimestamp);

        Bundle capInfoBundle = new Bundle();
        for (Map.Entry<String, String> entry : mCapInfoMap.entrySet()) {
          capInfoBundle.putString(entry.getKey(), entry.getValue());
        }
        dest.writeBundle(capInfoBundle);
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
        mGeoSmsSupported = (source.readInt() == 0) ? false : true;
        mCallComposerSupported = (source.readInt() == 0) ? false : true;
        mPostCallSupported = (source.readInt() == 0) ? false : true;
        mSharedMapSupported = (source.readInt() == 0) ? false : true;
        mSharedSketchSupported = (source.readInt() == 0) ? false : true;
        mChatbotSupported = (source.readInt() == 0) ? false : true;
        mChatbotRoleSupported = (source.readInt() == 0) ? false : true;
        mSmChatbotSupported = (source.readInt() == 0) ? false : true;
        mMmtelCallComposerSupported = (source.readInt() == 0) ? false : true;

        mRcsIpVoiceCallSupported = (source.readInt() == 0) ? false : true;
        mRcsIpVideoCallSupported = (source.readInt() == 0) ? false : true;
        mRcsIpVideoOnlyCallSupported = (source.readInt() == 0) ? false : true;

        mExts = source.createStringArray();
        mCapTimestamp = source.readLong();

        Bundle capInfoBundle = source.readBundle();
        for (String key: capInfoBundle.keySet()) {
          mCapInfoMap.put(key, capInfoBundle.getString(key));
        }
    }
}
