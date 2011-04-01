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
 * A class representing an endpoint on a {@link UsbInterface}.
 * Endpoints are the channels for sending and receiving data over USB.
 * Typically bulk endpoints are used for sending non-trivial amounts of data.
 * Interrupt endpoints are used for sending small amounts of data, typically events,
 * separately from the main data streams.
 * The endpoint zero is a special endpoint for control messages sent from the host
 * to device.
 * Isochronous endpoints are currently unsupported.
 */
public class UsbEndpoint implements Parcelable {

    private final int mAddress;
    private final int mAttributes;
    private final int mMaxPacketSize;
    private final int mInterval;

    /**
     * UsbEndpoint should only be instantiated by UsbService implementation
     * @hide
     */
    public UsbEndpoint(int address, int attributes, int maxPacketSize, int interval) {
        mAddress = address;
        mAttributes = attributes;
        mMaxPacketSize = maxPacketSize;
        mInterval = interval;
    }

    /**
     * Returns the endpoint's address field.
     * The address is a bitfield containing both the endpoint number
     * as well as the data direction of the endpoint.
     * the endpoint number and direction can also be accessed via
     * {@link #getEndpointNumber} and {@link #getDirection}.
     *
     * @return the endpoint's address
     */
    public int getAddress() {
        return mAddress;
    }

    /**
     * Extracts the endpoint's endpoint number from its address
     *
     * @return the endpoint's endpoint number
     */
    public int getEndpointNumber() {
        return mAddress & UsbConstants.USB_ENDPOINT_NUMBER_MASK;
    }

    /**
     * Returns the endpoint's direction.
     * Returns {@link UsbConstants#USB_DIR_OUT}
     * if the direction is host to device, and
     * {@link UsbConstants#USB_DIR_IN} if the
     * direction is device to host.
     * @see {@link UsbConstants#USB_DIR_IN}
     * @see {@link UsbConstants#USB_DIR_OUT}
     *
     * @return the endpoint's direction
     */
    public int getDirection() {
        return mAddress & UsbConstants.USB_ENDPOINT_DIR_MASK;
    }

    /**
     * Returns the endpoint's attributes field.
     *
     * @return the endpoint's attributes
     */
    public int getAttributes() {
        return mAttributes;
    }

    /**
     * Returns the endpoint's type.
     * Possible results are:
     * <ul>
     * <li>{@link UsbConstants#USB_ENDPOINT_XFER_CONTROL} (endpoint zero)
     * <li>{@link UsbConstants#USB_ENDPOINT_XFER_ISOC} (isochronous endpoint)
     * <li>{@link UsbConstants#USB_ENDPOINT_XFER_BULK} (bulk endpoint)
     * <li>{@link UsbConstants#USB_ENDPOINT_XFER_INT} (interrupt endpoint)
     * </ul>
     *
     * @return the endpoint's type
     */
    public int getType() {
        return mAttributes & UsbConstants.USB_ENDPOINT_XFERTYPE_MASK;
    }

    /**
     * Returns the endpoint's maximum packet size.
     *
     * @return the endpoint's maximum packet size
     */
    public int getMaxPacketSize() {
        return mMaxPacketSize;
    }

    /**
     * Returns the endpoint's interval field.
     *
     * @return the endpoint's interval
     */
    public int getInterval() {
        return mInterval;
    }

    @Override
    public String toString() {
        return "UsbEndpoint[mAddress=" + mAddress + ",mAttributes=" + mAttributes +
                ",mMaxPacketSize=" + mMaxPacketSize + ",mInterval=" + mInterval +"]";
    }

    public static final Parcelable.Creator<UsbEndpoint> CREATOR =
        new Parcelable.Creator<UsbEndpoint>() {
        public UsbEndpoint createFromParcel(Parcel in) {
            int address = in.readInt();
            int attributes = in.readInt();
            int maxPacketSize = in.readInt();
            int interval = in.readInt();
            return new UsbEndpoint(address, attributes, maxPacketSize, interval);
        }

        public UsbEndpoint[] newArray(int size) {
            return new UsbEndpoint[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mAddress);
        parcel.writeInt(mAttributes);
        parcel.writeInt(mMaxPacketSize);
        parcel.writeInt(mInterval);
   }
}
