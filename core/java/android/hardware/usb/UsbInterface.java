/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.hardware.usb;

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class representing an interface on a {@link android.hardware.usb.UsbDevice}.
 */
public class UsbInterface implements Parcelable {

    private int mId;
    private int mClass;
    private int mSubclass;
    private int mProtocol;
    private UsbDevice mDevice;
    private Parcelable[] mEndpoints;

    private UsbInterface() {
    }

    /**
     * UsbInterface should only be instantiated by UsbService implementation
     * @hide
     */
    public UsbInterface(int id, int Class, int subClass, int protocol,
            Parcelable[] endpoints) {
        mId = id;
        mClass = Class;
        mSubclass = subClass;
        mProtocol = protocol;
        mEndpoints = endpoints;
    }

    /**
     * Returns the interface's ID field.
     *
     * @return the interface's ID
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the interface's class field.
     * Some useful constants for USB classes can be found in
     * {@link android.hardware.usb.UsbConstants}
     *
     * @return the interface's class
     */
    public int getInterfaceClass() {
        return mClass;
    }

    /**
     * Returns the interface's subclass field.
     *
     * @return the interface's subclass
     */
    public int getInterfaceSubclass() {
        return mSubclass;
    }

    /**
     * Returns the interface's protocol field.
     *
     * @return the interface's protocol
     */
    public int getInterfaceProtocol() {
        return mProtocol;
    }

    /**
     * Returns the number of {@link android.hardware.usb.UsbEndpoint}s this interface contains.
     *
     * @return the number of endpoints
     */
    public int getEndpointCount() {
        return mEndpoints.length;
    }

    /**
     * Returns the {@link android.hardware.usb.UsbEndpoint} at the given index.
     *
     * @return the endpoint
     */
    public UsbEndpoint getEndpoint(int index) {
        return (UsbEndpoint)mEndpoints[index];
    }

    /**
     * Returns the {@link android.hardware.usb.UsbDevice} this interface belongs to.
     *
     * @return the interface's device
     */
    public UsbDevice getDevice() {
        return mDevice;
    }

    // only used for parcelling
    /* package */ void setDevice(UsbDevice device) {
        mDevice = device;
    }

    @Override
    public String toString() {
        return "UsbInterface[mId=" + mId + ",mClass=" + mClass +
                ",mSubclass=" + mSubclass + ",mProtocol=" + mProtocol +
                ",mEndpoints=" + mEndpoints + "]";
    }

    public static final Parcelable.Creator<UsbInterface> CREATOR =
        new Parcelable.Creator<UsbInterface>() {
        public UsbInterface createFromParcel(Parcel in) {
            int id = in.readInt();
            int Class = in.readInt();
            int subClass = in.readInt();
            int protocol = in.readInt();
            Parcelable[] endpoints = in.readParcelableArray(UsbEndpoint.class.getClassLoader());
            UsbInterface result = new UsbInterface(id, Class, subClass, protocol, endpoints);
            for (int i = 0; i < endpoints.length; i++) {
                ((UsbEndpoint)endpoints[i]).setInterface(result);
            }
            return result;
        }

        public UsbInterface[] newArray(int size) {
            return new UsbInterface[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mId);
        parcel.writeInt(mClass);
        parcel.writeInt(mSubclass);
        parcel.writeInt(mProtocol);
        parcel.writeParcelableArray(mEndpoints, 0);
   }
}
