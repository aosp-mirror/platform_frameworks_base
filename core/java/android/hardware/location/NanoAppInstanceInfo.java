/*
 * Copyright (C) 2016 The Android Open Source Project
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


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

import libcore.util.EmptyArray;

/**
 * @hide
 */
@SystemApi
public class NanoAppInstanceInfo {
    private String mPublisher;
    private String mName;

    private long mAppId;
    private int mAppVersion;

    private int mNeededReadMemBytes;
    private int mNeededWriteMemBytes;
    private int mNeededExecMemBytes;

    private int[] mNeededSensors;
    private int[] mOutputEvents;

    private int mContexthubId;
    private int mHandle;

    public NanoAppInstanceInfo() {
        mNeededSensors = EmptyArray.INT;
        mOutputEvents = EmptyArray.INT;
    }

    /**
     * get the publisher of this app
     *
     * @return String - name of the publisher
     */
    public String getPublisher() {
        return mPublisher;
    }


    /**
     * set the publisher name for the app
     *
     * @param publisher - name of the publisher
     *
     * @hide
     */
    public void setPublisher(String publisher) {
        mPublisher = publisher;
    }

    /**
     * get the name of the app
     *
     * @return String - name of the app
     */
    public String getName() {
        return mName;
    }

    /**
     * set the name of the app
     *
     * @param name - name of the app
     *
     * @hide
     */
    public void setName(String name) {
        mName = name;
    }

    /**
     * Get the application identifier
     *
     * @return int - application identifier
     */
    public long getAppId() {
        return mAppId;
    }

    /**
     * Set the application identifier
     *
     * @param appId - application identifier
     *
     * @hide
     */
    public void setAppId(long appId) {
        mAppId = appId;
    }

    /**
     * Get the application version
     *
     * NOTE: There is a race condition where shortly after loading, this
     * may return -1 instead of the correct version.
     *
     * TODO(b/30970527): Fix this race condition.
     *
     * @return int - version of the app
     */
    public int getAppVersion() {
        return mAppVersion;
    }

    /**
     * Set the application version
     *
     * @param appVersion - version of the app
     *
     * @hide
     */
    public void setAppVersion(int appVersion) {
        mAppVersion = appVersion;
    }

    /**
     * Get the read memory needed by the app
     *
     * @return int - readable memory needed in bytes
     */
    public int getNeededReadMemBytes() {
        return mNeededReadMemBytes;
    }

    /**
     * Set the read memory needed by the app
     *
     * @param neededReadMemBytes - readable Memory needed in bytes
     *
     * @hide
     */
    public void setNeededReadMemBytes(int neededReadMemBytes) {
        mNeededReadMemBytes = neededReadMemBytes;
    }

    /**
     *  get writable memory needed by the app
     *
     * @return int - writable memory needed by the app
     */
    public int getNeededWriteMemBytes() {
        return mNeededWriteMemBytes;
    }

    /**
     * set writable memory needed by the app
     *
     * @param neededWriteMemBytes - writable memory needed by the
     *                            app
     *
     * @hide
     */
    public void setNeededWriteMemBytes(int neededWriteMemBytes) {
        mNeededWriteMemBytes = neededWriteMemBytes;
    }

    /**
     * get executable memory needed by the app
     *
     * @return int - executable memory needed by the app
     */
    public int getNeededExecMemBytes() {
        return mNeededExecMemBytes;
    }

    /**
     * set executable memory needed by the app
     *
     * @param neededExecMemBytes - executable memory needed by the
     *                           app
     *
     * @hide
     */
    public void setNeededExecMemBytes(int neededExecMemBytes) {
        mNeededExecMemBytes = neededExecMemBytes;
    }

    /**
     * Get the sensors needed by this app
     *
     * @return int[] all the required sensors needed by this app
     */
    @NonNull
    public int[] getNeededSensors() {
        return mNeededSensors;
    }

    /**
     * set the sensors needed by this app
     *
     * @param neededSensors - all the sensors needed by this app
     *
     * @hide
     */
    public void setNeededSensors(@Nullable int[] neededSensors) {
        mNeededSensors = neededSensors != null ? neededSensors : EmptyArray.INT;
    }

    /**
     * get the events generated by this app
     *
     * @return all the events that can be generated by this app
     */
    @NonNull
    public int[] getOutputEvents() {
        return mOutputEvents;
    }

    /**
     * set the output events that can be generated by this app
     *
     * @param outputEvents - the events that may be generated by
     *                     this app
     *
     * @hide
     */
    public void setOutputEvents(@Nullable int[] outputEvents) {
        mOutputEvents = outputEvents != null ? outputEvents : EmptyArray.INT;
    }

    /**
     * get the context hub identifier
     *
     * @return int - system unique hub identifier
     */
    public int getContexthubId() {
        return mContexthubId;
    }

    /**
     * set the context hub identifier
     *
     * @param contexthubId - system wide unique identifier
     *
     * @hide
     */
    public void setContexthubId(int contexthubId) {
        mContexthubId = contexthubId;
    }

    /**
     * get a handle to the nano app instance
     *
     * @return int - handle to this instance
     */
    public int getHandle() {
        return mHandle;
    }

    /**
     * set the handle for an app instance
     *
     * @param handle - handle to this instance
     *
     * @hide
     */
    public void setHandle(int handle) {
        mHandle = handle;
    }


    private NanoAppInstanceInfo(Parcel in) {
        mPublisher = in.readString();
        mName = in.readString();

        mAppId = in.readLong();
        mAppVersion = in.readInt();
        mNeededReadMemBytes = in.readInt();
        mNeededWriteMemBytes = in.readInt();
        mNeededExecMemBytes = in.readInt();

        int neededSensorsLength = in.readInt();
        mNeededSensors = new int[neededSensorsLength];
        in.readIntArray(mNeededSensors);

        int outputEventsLength = in.readInt();
        mOutputEvents = new int[outputEventsLength];
        in.readIntArray(mOutputEvents);
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mPublisher);
        out.writeString(mName);
        out.writeLong(mAppId);
        out.writeInt(mAppVersion);
        out.writeInt(mContexthubId);
        out.writeInt(mNeededReadMemBytes);
        out.writeInt(mNeededWriteMemBytes);
        out.writeInt(mNeededExecMemBytes);

        // arrays are never null
        out.writeInt(mNeededSensors.length);
        out.writeIntArray(mNeededSensors);
        out.writeInt(mOutputEvents.length);
        out.writeIntArray(mOutputEvents);
    }

    public static final Parcelable.Creator<NanoAppInstanceInfo> CREATOR
            = new Parcelable.Creator<NanoAppInstanceInfo>() {
        public NanoAppInstanceInfo createFromParcel(Parcel in) {
            return new NanoAppInstanceInfo(in);
        }

        public NanoAppInstanceInfo[] newArray(int size) {
            return new NanoAppInstanceInfo[size];
        }
    };

    @Override
    public String toString() {
        String retVal = "handle : " + mHandle;
        retVal += ", Id : 0x" + Long.toHexString(mAppId);
        retVal += ", Version : " + mAppVersion;
        retVal += ", Name : " + mName;
        retVal += ", Publisher : " + mPublisher;

        return retVal;
    }
}
