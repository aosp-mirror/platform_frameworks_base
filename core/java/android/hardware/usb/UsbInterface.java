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

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import com.android.internal.util.Preconditions;

/**
 * A class representing an interface on a {@link UsbDevice}.
 * USB devices can have one or more interfaces, each one providing a different
 * piece of functionality, separate from the other interfaces.
 * An interface will have one or more {@link UsbEndpoint}s, which are the
 * channels by which the host transfers data with the device.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about communicating with USB hardware, read the
 * <a href="{@docRoot}guide/topics/usb/index.html">USB</a> developer guide.</p>
 * </div>
 */
public class UsbInterface implements Parcelable {

    private final int mId;
    private final int mAlternateSetting;
    private @Nullable final String mName;
    private final int mClass;
    private final int mSubclass;
    private final int mProtocol;

    /** All endpoints of this interface, only null during creation */
    private Parcelable[] mEndpoints;

    /**
     * UsbInterface should only be instantiated by UsbService implementation
     * @hide
     */
    public UsbInterface(int id, int alternateSetting, @Nullable String name,
            int Class, int subClass, int protocol) {
        mId = id;
        mAlternateSetting = alternateSetting;
        mName = name;
        mClass = Class;
        mSubclass = subClass;
        mProtocol = protocol;
    }

    /**
     * Returns the interface's bInterfaceNumber field.
     * This is an integer that along with the alternate setting uniquely identifies
     * the interface on the device.
     *
     * @return the interface's ID
     */
    public int getId() {
        return mId;
    }

    /**
     * Returns the interface's bAlternateSetting field.
     * This is an integer that along with the ID uniquely identifies
     * the interface on the device.
     * {@link UsbDeviceConnection#setInterface} can be used to switch between
     * two interfaces with the same ID but different alternate setting.
     *
     * @return the interface's alternate setting
     */
    public int getAlternateSetting() {
        return mAlternateSetting;
    }

    /**
     * Returns the interface's name.
     *
     * @return the interface's name, or {@code null} if the property could not be read
     */
    public @Nullable String getName() {
        return mName;
    }

    /**
     * Returns the interface's class field.
     * Some useful constants for USB classes can be found in {@link UsbConstants}
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
     * Only used by UsbService implementation
     * @hide
     */
    public void setEndpoints(Parcelable[] endpoints) {
        mEndpoints = Preconditions.checkArrayElementsNotNull(endpoints, "endpoints");
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder("UsbInterface[mId=" + mId +
                ",mAlternateSetting=" + mAlternateSetting +
                ",mName=" + mName + ",mClass=" + mClass +
                ",mSubclass=" + mSubclass + ",mProtocol=" + mProtocol +
                ",mEndpoints=[");
        for (int i = 0; i < mEndpoints.length; i++) {
            builder.append("\n");
            builder.append(mEndpoints[i].toString());
        }
        builder.append("]");
        return builder.toString();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<UsbInterface> CREATOR =
        new Parcelable.Creator<UsbInterface>() {
        public UsbInterface createFromParcel(Parcel in) {
            int id = in.readInt();
            int alternateSetting = in.readInt();
            String name = in.readString();
            int Class = in.readInt();
            int subClass = in.readInt();
            int protocol = in.readInt();
            Parcelable[] endpoints = in.readParcelableArray(UsbEndpoint.class.getClassLoader());
            UsbInterface intf = new UsbInterface(id, alternateSetting, name, Class, subClass, protocol);
            intf.setEndpoints(endpoints);
            return intf;
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
        parcel.writeInt(mAlternateSetting);
        parcel.writeString(mName);
        parcel.writeInt(mClass);
        parcel.writeInt(mSubclass);
        parcel.writeInt(mProtocol);
        parcel.writeParcelableArray(mEndpoints, 0);
   }
}
