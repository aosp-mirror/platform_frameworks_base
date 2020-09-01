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
import android.service.usb.UsbAccessoryFilterProto;

import com.android.internal.util.dump.DualDumpOutputStream;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Objects;

/**
 * This class is used to describe a USB accessory.
 * When used in HashMaps all values must be specified,
 * but wildcards can be used for any of the fields in
 * the package meta-data.
 *
 * @hide
 */
public class AccessoryFilter {
    // USB accessory manufacturer (or null for unspecified)
    public final String mManufacturer;
    // USB accessory model (or null for unspecified)
    public final String mModel;
    // USB accessory version (or null for unspecified)
    public final String mVersion;

    public AccessoryFilter(String manufacturer, String model, String version) {
        mManufacturer = manufacturer;
        mModel = model;
        mVersion = version;
    }

    public AccessoryFilter(UsbAccessory accessory) {
        mManufacturer = accessory.getManufacturer();
        mModel = accessory.getModel();
        mVersion = accessory.getVersion();
    }

    public AccessoryFilter(@NonNull AccessoryFilter filter) {
        mManufacturer = filter.mManufacturer;
        mModel = filter.mModel;
        mVersion = filter.mVersion;
    }

    public static AccessoryFilter read(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String manufacturer = null;
        String model = null;
        String version = null;

        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            String name = parser.getAttributeName(i);
            String value = parser.getAttributeValue(i);

            if ("manufacturer".equals(name)) {
                manufacturer = value;
            } else if ("model".equals(name)) {
                model = value;
            } else if ("version".equals(name)) {
                version = value;
            }
        }
        return new AccessoryFilter(manufacturer, model, version);
    }

    public void write(XmlSerializer serializer)throws IOException {
        serializer.startTag(null, "usb-accessory");
        if (mManufacturer != null) {
            serializer.attribute(null, "manufacturer", mManufacturer);
        }
        if (mModel != null) {
            serializer.attribute(null, "model", mModel);
        }
        if (mVersion != null) {
            serializer.attribute(null, "version", mVersion);
        }
        serializer.endTag(null, "usb-accessory");
    }

    public boolean matches(UsbAccessory acc) {
        if (mManufacturer != null && !acc.getManufacturer().equals(mManufacturer)) return false;
        if (mModel != null && !acc.getModel().equals(mModel)) return false;
        return !(mVersion != null && !acc.getVersion().equals(mVersion));
    }

    /**
     * Is the accessories described {@code accessory} covered by this filter?
     *
     * @param accessory A filter describing the accessory
     *
     * @return {@code true} iff this the filter covers the accessory
     */
    public boolean contains(AccessoryFilter accessory) {
        if (mManufacturer != null && !Objects.equals(accessory.mManufacturer, mManufacturer)) {
            return false;
        }
        if (mModel != null && !Objects.equals(accessory.mModel, mModel)) return false;
        return !(mVersion != null && !Objects.equals(accessory.mVersion, mVersion));
    }

    @Override
    public boolean equals(Object obj) {
        // can't compare if we have wildcard strings
        if (mManufacturer == null || mModel == null || mVersion == null) {
            return false;
        }
        if (obj instanceof AccessoryFilter) {
            AccessoryFilter filter = (AccessoryFilter)obj;
            return (mManufacturer.equals(filter.mManufacturer) &&
                    mModel.equals(filter.mModel) &&
                    mVersion.equals(filter.mVersion));
        }
        if (obj instanceof UsbAccessory) {
            UsbAccessory accessory = (UsbAccessory)obj;
            return (mManufacturer.equals(accessory.getManufacturer()) &&
                    mModel.equals(accessory.getModel()) &&
                    mVersion.equals(accessory.getVersion()));
        }
        return false;
    }

    @Override
    public int hashCode() {
        return ((mManufacturer == null ? 0 : mManufacturer.hashCode()) ^
                (mModel == null ? 0 : mModel.hashCode()) ^
                (mVersion == null ? 0 : mVersion.hashCode()));
    }

    @Override
    public String toString() {
        return "AccessoryFilter[mManufacturer=\"" + mManufacturer +
                "\", mModel=\"" + mModel +
                "\", mVersion=\"" + mVersion + "\"]";
    }

    /**
     * Write a description of the filter to a dump stream.
     */
    public void dump(@NonNull DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);

        dump.write("manufacturer", UsbAccessoryFilterProto.MANUFACTURER, mManufacturer);
        dump.write("model", UsbAccessoryFilterProto.MODEL, mModel);
        dump.write("version", UsbAccessoryFilterProto.VERSION, mVersion);

        dump.end(token);
    }
}
