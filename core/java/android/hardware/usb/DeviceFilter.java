/*
 * Copyright 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.service.usb.UsbDeviceFilterProto;
import android.util.Slog;

import com.android.internal.util.dump.DualDumpOutputStream;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Objects;

/**
 * This class is used to describe a USB device.
 * When used in HashMaps all values must be specified,
 * but wildcards can be used for any of the fields in
 * the package meta-data.
 *
 * @hide
 */
public class DeviceFilter {
    private static final String TAG = DeviceFilter.class.getSimpleName();

    // USB Vendor ID (or -1 for unspecified)
    public final int mVendorId;
    // USB Product ID (or -1 for unspecified)
    public final int mProductId;
    // USB device or interface class (or -1 for unspecified)
    public final int mClass;
    // USB device subclass (or -1 for unspecified)
    public final int mSubclass;
    // USB device protocol (or -1 for unspecified)
    public final int mProtocol;
    // USB device manufacturer name string (or null for unspecified)
    public final String mManufacturerName;
    // USB device product name string (or null for unspecified)
    public final String mProductName;
    // USB device serial number string (or null for unspecified)
    public final String mSerialNumber;

    public DeviceFilter(int vid, int pid, int clasz, int subclass, int protocol,
            String manufacturer, String product, String serialnum) {
        mVendorId = vid;
        mProductId = pid;
        mClass = clasz;
        mSubclass = subclass;
        mProtocol = protocol;
        mManufacturerName = manufacturer;
        mProductName = product;
        mSerialNumber = serialnum;
    }

    public DeviceFilter(UsbDevice device) {
        mVendorId = device.getVendorId();
        mProductId = device.getProductId();
        mClass = device.getDeviceClass();
        mSubclass = device.getDeviceSubclass();
        mProtocol = device.getDeviceProtocol();
        mManufacturerName = device.getManufacturerName();
        mProductName = device.getProductName();
        mSerialNumber = device.getSerialNumber();
    }

    public static DeviceFilter read(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int vendorId = -1;
        int productId = -1;
        int deviceClass = -1;
        int deviceSubclass = -1;
        int deviceProtocol = -1;
        String manufacturerName = null;
        String productName = null;
        String serialNumber = null;

        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            String name = parser.getAttributeName(i);
            String value = parser.getAttributeValue(i);
            // Attribute values are ints or strings
            if ("manufacturer-name".equals(name)) {
                manufacturerName = value;
            } else if ("product-name".equals(name)) {
                productName = value;
            } else if ("serial-number".equals(name)) {
                serialNumber = value;
            } else {
                int intValue;
                int radix = 10;
                if (value != null && value.length() > 2 && value.charAt(0) == '0' &&
                        (value.charAt(1) == 'x' || value.charAt(1) == 'X')) {
                    // allow hex values starting with 0x or 0X
                    radix = 16;
                    value = value.substring(2);
                }
                try {
                    intValue = Integer.parseInt(value, radix);
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "invalid number for field " + name, e);
                    continue;
                }
                if ("vendor-id".equals(name)) {
                    vendorId = intValue;
                } else if ("product-id".equals(name)) {
                    productId = intValue;
                } else if ("class".equals(name)) {
                    deviceClass = intValue;
                } else if ("subclass".equals(name)) {
                    deviceSubclass = intValue;
                } else if ("protocol".equals(name)) {
                    deviceProtocol = intValue;
                }
            }
        }
        return new DeviceFilter(vendorId, productId,
                deviceClass, deviceSubclass, deviceProtocol,
                manufacturerName, productName, serialNumber);
    }

    public void write(XmlSerializer serializer) throws IOException {
        serializer.startTag(null, "usb-device");
        if (mVendorId != -1) {
            serializer.attribute(null, "vendor-id", Integer.toString(mVendorId));
        }
        if (mProductId != -1) {
            serializer.attribute(null, "product-id", Integer.toString(mProductId));
        }
        if (mClass != -1) {
            serializer.attribute(null, "class", Integer.toString(mClass));
        }
        if (mSubclass != -1) {
            serializer.attribute(null, "subclass", Integer.toString(mSubclass));
        }
        if (mProtocol != -1) {
            serializer.attribute(null, "protocol", Integer.toString(mProtocol));
        }
        if (mManufacturerName != null) {
            serializer.attribute(null, "manufacturer-name", mManufacturerName);
        }
        if (mProductName != null) {
            serializer.attribute(null, "product-name", mProductName);
        }
        if (mSerialNumber != null) {
            serializer.attribute(null, "serial-number", mSerialNumber);
        }
        serializer.endTag(null, "usb-device");
    }

    private boolean matches(int clasz, int subclass, int protocol) {
        return ((mClass == -1 || clasz == mClass) &&
                (mSubclass == -1 || subclass == mSubclass) &&
                (mProtocol == -1 || protocol == mProtocol));
    }

    public boolean matches(UsbDevice device) {
        if (mVendorId != -1 && device.getVendorId() != mVendorId) return false;
        if (mProductId != -1 && device.getProductId() != mProductId) return false;
        if (mManufacturerName != null && device.getManufacturerName() == null) return false;
        if (mProductName != null && device.getProductName() == null) return false;
        if (mSerialNumber != null && device.getSerialNumber() == null) return false;
        if (mManufacturerName != null && device.getManufacturerName() != null &&
                !mManufacturerName.equals(device.getManufacturerName())) return false;
        if (mProductName != null && device.getProductName() != null &&
                !mProductName.equals(device.getProductName())) return false;
        if (mSerialNumber != null && device.getSerialNumber() != null &&
                !mSerialNumber.equals(device.getSerialNumber())) return false;

        // check device class/subclass/protocol
        if (matches(device.getDeviceClass(), device.getDeviceSubclass(),
                device.getDeviceProtocol())) return true;

        // if device doesn't match, check the interfaces
        int count = device.getInterfaceCount();
        for (int i = 0; i < count; i++) {
            UsbInterface intf = device.getInterface(i);
            if (matches(intf.getInterfaceClass(), intf.getInterfaceSubclass(),
                    intf.getInterfaceProtocol())) return true;
        }

        return false;
    }

    /**
     * If the device described by {@code device} covered by this filter?
     *
     * @param device The device
     *
     * @return {@code true} iff this filter covers the {@code device}
     */
    public boolean contains(DeviceFilter device) {
        // -1 and null means "match anything"

        if (mVendorId != -1 && device.mVendorId != mVendorId) return false;
        if (mProductId != -1 && device.mProductId != mProductId) return false;
        if (mManufacturerName != null && !Objects.equals(mManufacturerName,
                device.mManufacturerName)) {
            return false;
        }
        if (mProductName != null && !Objects.equals(mProductName, device.mProductName)) {
            return false;
        }
        if (mSerialNumber != null
                && !Objects.equals(mSerialNumber, device.mSerialNumber)) {
            return false;
        }

        // check device class/subclass/protocol
        return matches(device.mClass, device.mSubclass, device.mProtocol);
    }

    @Override
    public boolean equals(Object obj) {
        // can't compare if we have wildcard strings
        if (mVendorId == -1 || mProductId == -1 ||
                mClass == -1 || mSubclass == -1 || mProtocol == -1) {
            return false;
        }
        if (obj instanceof DeviceFilter) {
            DeviceFilter filter = (DeviceFilter)obj;

            if (filter.mVendorId != mVendorId ||
                    filter.mProductId != mProductId ||
                    filter.mClass != mClass ||
                    filter.mSubclass != mSubclass ||
                    filter.mProtocol != mProtocol) {
                return(false);
            }
            if ((filter.mManufacturerName != null &&
                    mManufacturerName == null) ||
                    (filter.mManufacturerName == null &&
                            mManufacturerName != null) ||
                    (filter.mProductName != null &&
                            mProductName == null)  ||
                    (filter.mProductName == null &&
                            mProductName != null) ||
                    (filter.mSerialNumber != null &&
                            mSerialNumber == null)  ||
                    (filter.mSerialNumber == null &&
                            mSerialNumber != null)) {
                return(false);
            }
            if  ((filter.mManufacturerName != null &&
                    mManufacturerName != null &&
                    !mManufacturerName.equals(filter.mManufacturerName)) ||
                    (filter.mProductName != null &&
                            mProductName != null &&
                            !mProductName.equals(filter.mProductName)) ||
                    (filter.mSerialNumber != null &&
                            mSerialNumber != null &&
                            !mSerialNumber.equals(filter.mSerialNumber))) {
                return false;
            }
            return true;
        }
        if (obj instanceof UsbDevice) {
            UsbDevice device = (UsbDevice)obj;
            if (device.getVendorId() != mVendorId ||
                    device.getProductId() != mProductId ||
                    device.getDeviceClass() != mClass ||
                    device.getDeviceSubclass() != mSubclass ||
                    device.getDeviceProtocol() != mProtocol) {
                return(false);
            }
            if ((mManufacturerName != null && device.getManufacturerName() == null) ||
                    (mManufacturerName == null && device.getManufacturerName() != null) ||
                    (mProductName != null && device.getProductName() == null) ||
                    (mProductName == null && device.getProductName() != null) ||
                    (mSerialNumber != null && device.getSerialNumber() == null) ||
                    (mSerialNumber == null && device.getSerialNumber() != null)) {
                return(false);
            }
            if ((device.getManufacturerName() != null &&
                    !mManufacturerName.equals(device.getManufacturerName())) ||
                    (device.getProductName() != null &&
                            !mProductName.equals(device.getProductName())) ||
                    (device.getSerialNumber() != null &&
                            !mSerialNumber.equals(device.getSerialNumber()))) {
                return false;
            }
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return (((mVendorId << 16) | mProductId) ^
                ((mClass << 16) | (mSubclass << 8) | mProtocol));
    }

    @Override
    public String toString() {
        return "DeviceFilter[mVendorId=" + mVendorId + ",mProductId=" + mProductId +
                ",mClass=" + mClass + ",mSubclass=" + mSubclass +
                ",mProtocol=" + mProtocol + ",mManufacturerName=" + mManufacturerName +
                ",mProductName=" + mProductName + ",mSerialNumber=" + mSerialNumber +
                "]";
    }

    /**
     * Write a description of the filter to a dump stream.
     */
    public void dump(@NonNull DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);

        dump.write("vendor_id", UsbDeviceFilterProto.VENDOR_ID, mVendorId);
        dump.write("product_id", UsbDeviceFilterProto.PRODUCT_ID, mProductId);
        dump.write("class", UsbDeviceFilterProto.CLASS, mClass);
        dump.write("subclass", UsbDeviceFilterProto.SUBCLASS, mSubclass);
        dump.write("protocol", UsbDeviceFilterProto.PROTOCOL, mProtocol);
        dump.write("manufacturer_name", UsbDeviceFilterProto.MANUFACTURER_NAME, mManufacturerName);
        dump.write("product_name", UsbDeviceFilterProto.PRODUCT_NAME, mProductName);
        dump.write("serial_number", UsbDeviceFilterProto.SERIAL_NUMBER, mSerialNumber);

        dump.end(token);
    }
}
