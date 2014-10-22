/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.usb;

import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Process;
import android.os.UserHandle;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.Xml;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import libcore.io.IoUtils;

class UsbSettingsManager {
    private static final String TAG = "UsbSettingsManager";
    private static final boolean DEBUG = false;

    /** Legacy settings file, before multi-user */
    private static final File sSingleUserSettingsFile = new File(
            "/data/system/usb_device_manager.xml");

    private final UserHandle mUser;
    private final AtomicFile mSettingsFile;
    private final boolean mDisablePermissionDialogs;

    private final Context mContext;
    private final Context mUserContext;
    private final PackageManager mPackageManager;

    // Temporary mapping USB device name to list of UIDs with permissions for the device
    private final HashMap<String, SparseBooleanArray> mDevicePermissionMap =
            new HashMap<String, SparseBooleanArray>();
    // Temporary mapping UsbAccessory to list of UIDs with permissions for the accessory
    private final HashMap<UsbAccessory, SparseBooleanArray> mAccessoryPermissionMap =
            new HashMap<UsbAccessory, SparseBooleanArray>();
    // Maps DeviceFilter to user preferred application package
    private final HashMap<DeviceFilter, String> mDevicePreferenceMap =
            new HashMap<DeviceFilter, String>();
    // Maps AccessoryFilter to user preferred application package
    private final HashMap<AccessoryFilter, String> mAccessoryPreferenceMap =
            new HashMap<AccessoryFilter, String>();

    private final Object mLock = new Object();

    // This class is used to describe a USB device.
    // When used in HashMaps all values must be specified,
    // but wildcards can be used for any of the fields in
    // the package meta-data.
    private static class DeviceFilter {
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
                    int intValue = -1;
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

        public boolean matches(DeviceFilter f) {
            if (mVendorId != -1 && f.mVendorId != mVendorId) return false;
            if (mProductId != -1 && f.mProductId != mProductId) return false;
            if (f.mManufacturerName != null && mManufacturerName == null) return false;
            if (f.mProductName != null && mProductName == null) return false;
            if (f.mSerialNumber != null && mSerialNumber == null) return false;
            if (mManufacturerName != null && f.mManufacturerName != null &&
                !mManufacturerName.equals(f.mManufacturerName)) return false;
            if (mProductName != null && f.mProductName != null &&
                !mProductName.equals(f.mProductName)) return false;
            if (mSerialNumber != null && f.mSerialNumber != null &&
                !mSerialNumber.equals(f.mSerialNumber)) return false;

            // check device class/subclass/protocol
            return matches(f.mClass, f.mSubclass, f.mProtocol);
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
                    return(false);
                }
                return(true);
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
                    return(false);
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
    }

    // This class is used to describe a USB accessory.
    // When used in HashMaps all values must be specified,
    // but wildcards can be used for any of the fields in
    // the package meta-data.
    private static class AccessoryFilter {
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
            if (mVersion != null && !acc.getVersion().equals(mVersion)) return false;
            return true;
        }

        public boolean matches(AccessoryFilter f) {
            if (mManufacturer != null && !f.mManufacturer.equals(mManufacturer)) return false;
            if (mModel != null && !f.mModel.equals(mModel)) return false;
            if (mVersion != null && !f.mVersion.equals(mVersion)) return false;
            return true;
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
    }

    private class MyPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageAdded(String packageName, int uid) {
            handlePackageUpdate(packageName);
        }

        @Override
        public boolean onPackageChanged(String packageName, int uid, String[] components) {
            handlePackageUpdate(packageName);
            return false;
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            clearDefaults(packageName);
        }
    }

    MyPackageMonitor mPackageMonitor = new MyPackageMonitor();

    public UsbSettingsManager(Context context, UserHandle user) {
        if (DEBUG) Slog.v(TAG, "Creating settings for " + user);

        try {
            mUserContext = context.createPackageContextAsUser("android", 0, user);
        } catch (NameNotFoundException e) {
            throw new RuntimeException("Missing android package");
        }

        mContext = context;
        mPackageManager = mUserContext.getPackageManager();

        mUser = user;
        mSettingsFile = new AtomicFile(new File(
                Environment.getUserSystemDirectory(user.getIdentifier()),
                "usb_device_manager.xml"));

        mDisablePermissionDialogs = context.getResources().getBoolean(
                com.android.internal.R.bool.config_disableUsbPermissionDialogs);

        synchronized (mLock) {
            if (UserHandle.OWNER.equals(user)) {
                upgradeSingleUserLocked();
            }
            readSettingsLocked();
        }

        mPackageMonitor.register(mUserContext, null, true);
    }

    private void readPreference(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String packageName = null;
        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if ("package".equals(parser.getAttributeName(i))) {
                packageName = parser.getAttributeValue(i);
                break;
            }
        }
        XmlUtils.nextElement(parser);
        if ("usb-device".equals(parser.getName())) {
            DeviceFilter filter = DeviceFilter.read(parser);
            mDevicePreferenceMap.put(filter, packageName);
        } else if ("usb-accessory".equals(parser.getName())) {
            AccessoryFilter filter = AccessoryFilter.read(parser);
            mAccessoryPreferenceMap.put(filter, packageName);
        }
        XmlUtils.nextElement(parser);
    }

    /**
     * Upgrade any single-user settings from {@link #sSingleUserSettingsFile}.
     * Should only by called by owner.
     */
    private void upgradeSingleUserLocked() {
        if (sSingleUserSettingsFile.exists()) {
            mDevicePreferenceMap.clear();
            mAccessoryPreferenceMap.clear();

            FileInputStream fis = null;
            try {
                fis = new FileInputStream(sSingleUserSettingsFile);
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(fis, null);

                XmlUtils.nextElement(parser);
                while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                    final String tagName = parser.getName();
                    if ("preference".equals(tagName)) {
                        readPreference(parser);
                    } else {
                        XmlUtils.nextElement(parser);
                    }
                }
            } catch (IOException e) {
                Log.wtf(TAG, "Failed to read single-user settings", e);
            } catch (XmlPullParserException e) {
                Log.wtf(TAG, "Failed to read single-user settings", e);
            } finally {
                IoUtils.closeQuietly(fis);
            }

            writeSettingsLocked();

            // Success or failure, we delete single-user file
            sSingleUserSettingsFile.delete();
        }
    }

    private void readSettingsLocked() {
        if (DEBUG) Slog.v(TAG, "readSettingsLocked()");

        mDevicePreferenceMap.clear();
        mAccessoryPreferenceMap.clear();

        FileInputStream stream = null;
        try {
            stream = mSettingsFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            XmlUtils.nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if ("preference".equals(tagName)) {
                    readPreference(parser);
                } else {
                    XmlUtils.nextElement(parser);
                }
            }
        } catch (FileNotFoundException e) {
            if (DEBUG) Slog.d(TAG, "settings file not found");
        } catch (Exception e) {
            Slog.e(TAG, "error reading settings file, deleting to start fresh", e);
            mSettingsFile.delete();
        } finally {
            IoUtils.closeQuietly(stream);
        }
    }

    private void writeSettingsLocked() {
        if (DEBUG) Slog.v(TAG, "writeSettingsLocked()");

        FileOutputStream fos = null;
        try {
            fos = mSettingsFile.startWrite();

            FastXmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(fos, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startTag(null, "settings");

            for (DeviceFilter filter : mDevicePreferenceMap.keySet()) {
                serializer.startTag(null, "preference");
                serializer.attribute(null, "package", mDevicePreferenceMap.get(filter));
                filter.write(serializer);
                serializer.endTag(null, "preference");
            }

            for (AccessoryFilter filter : mAccessoryPreferenceMap.keySet()) {
                serializer.startTag(null, "preference");
                serializer.attribute(null, "package", mAccessoryPreferenceMap.get(filter));
                filter.write(serializer);
                serializer.endTag(null, "preference");
            }

            serializer.endTag(null, "settings");
            serializer.endDocument();

            mSettingsFile.finishWrite(fos);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write settings", e);
            if (fos != null) {
                mSettingsFile.failWrite(fos);
            }
        }
    }

    // Checks to see if a package matches a device or accessory.
    // Only one of device and accessory should be non-null.
    private boolean packageMatchesLocked(ResolveInfo info, String metaDataName,
            UsbDevice device, UsbAccessory accessory) {
        ActivityInfo ai = info.activityInfo;

        XmlResourceParser parser = null;
        try {
            parser = ai.loadXmlMetaData(mPackageManager, metaDataName);
            if (parser == null) {
                Slog.w(TAG, "no meta-data for " + info);
                return false;
            }

            XmlUtils.nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if (device != null && "usb-device".equals(tagName)) {
                    DeviceFilter filter = DeviceFilter.read(parser);
                    if (filter.matches(device)) {
                        return true;
                    }
                }
                else if (accessory != null && "usb-accessory".equals(tagName)) {
                    AccessoryFilter filter = AccessoryFilter.read(parser);
                    if (filter.matches(accessory)) {
                        return true;
                    }
                }
                XmlUtils.nextElement(parser);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unable to load component info " + info.toString(), e);
        } finally {
            if (parser != null) parser.close();
        }
        return false;
    }

    private final ArrayList<ResolveInfo> getDeviceMatchesLocked(UsbDevice device, Intent intent) {
        ArrayList<ResolveInfo> matches = new ArrayList<ResolveInfo>();
        List<ResolveInfo> resolveInfos = mPackageManager.queryIntentActivities(intent,
                PackageManager.GET_META_DATA);
        int count = resolveInfos.size();
        for (int i = 0; i < count; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);
            if (packageMatchesLocked(resolveInfo, intent.getAction(), device, null)) {
                matches.add(resolveInfo);
            }
        }
        return matches;
    }

    private final ArrayList<ResolveInfo> getAccessoryMatchesLocked(
            UsbAccessory accessory, Intent intent) {
        ArrayList<ResolveInfo> matches = new ArrayList<ResolveInfo>();
        List<ResolveInfo> resolveInfos = mPackageManager.queryIntentActivities(intent,
                PackageManager.GET_META_DATA);
        int count = resolveInfos.size();
        for (int i = 0; i < count; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);
            if (packageMatchesLocked(resolveInfo, intent.getAction(), null, accessory)) {
                matches.add(resolveInfo);
            }
        }
        return matches;
    }

    public void deviceAttached(UsbDevice device) {
        Intent intent = new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        ArrayList<ResolveInfo> matches;
        String defaultPackage;
        synchronized (mLock) {
            matches = getDeviceMatchesLocked(device, intent);
            // Launch our default activity directly, if we have one.
            // Otherwise we will start the UsbResolverActivity to allow the user to choose.
            defaultPackage = mDevicePreferenceMap.get(new DeviceFilter(device));
        }

        // Send broadcast to running activity with registered intent
        mUserContext.sendBroadcast(intent);

        // Start activity with registered intent
        resolveActivity(intent, matches, defaultPackage, device, null);
    }

    public void deviceDetached(UsbDevice device) {
        // clear temporary permissions for the device
        mDevicePermissionMap.remove(device.getDeviceName());

        Intent intent = new Intent(UsbManager.ACTION_USB_DEVICE_DETACHED);
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);
        if (DEBUG) Slog.d(TAG, "usbDeviceRemoved, sending " + intent);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    public void accessoryAttached(UsbAccessory accessory) {
        Intent intent = new Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        ArrayList<ResolveInfo> matches;
        String defaultPackage;
        synchronized (mLock) {
            matches = getAccessoryMatchesLocked(accessory, intent);
            // Launch our default activity directly, if we have one.
            // Otherwise we will start the UsbResolverActivity to allow the user to choose.
            defaultPackage = mAccessoryPreferenceMap.get(new AccessoryFilter(accessory));
        }

        resolveActivity(intent, matches, defaultPackage, null, accessory);
    }

    public void accessoryDetached(UsbAccessory accessory) {
        // clear temporary permissions for the accessory
        mAccessoryPermissionMap.remove(accessory);

        Intent intent = new Intent(
                UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
    }

    private void resolveActivity(Intent intent, ArrayList<ResolveInfo> matches,
            String defaultPackage, UsbDevice device, UsbAccessory accessory) {
        int count = matches.size();

        // don't show the resolver activity if there are no choices available
        if (count == 0) {
            if (accessory != null) {
                String uri = accessory.getUri();
                if (uri != null && uri.length() > 0) {
                    // display URI to user
                    // start UsbResolverActivity so user can choose an activity
                    Intent dialogIntent = new Intent();
                    dialogIntent.setClassName("com.android.systemui",
                            "com.android.systemui.usb.UsbAccessoryUriActivity");
                    dialogIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    dialogIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
                    dialogIntent.putExtra("uri", uri);
                    try {
                        mUserContext.startActivityAsUser(dialogIntent, mUser);
                    } catch (ActivityNotFoundException e) {
                        Slog.e(TAG, "unable to start UsbAccessoryUriActivity");
                    }
                }
            }

            // do nothing
            return;
        }

        ResolveInfo defaultRI = null;
        if (count == 1 && defaultPackage == null) {
            // Check to see if our single choice is on the system partition.
            // If so, treat it as our default without calling UsbResolverActivity
            ResolveInfo rInfo = matches.get(0);
            if (rInfo.activityInfo != null &&
                    rInfo.activityInfo.applicationInfo != null &&
                    (rInfo.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                defaultRI = rInfo;
            }

            if (mDisablePermissionDialogs) {
                // bypass dialog and launch the only matching activity
                rInfo = matches.get(0);
                if (rInfo.activityInfo != null) {
                    defaultPackage = rInfo.activityInfo.packageName;
                }
            }
        }

        if (defaultRI == null && defaultPackage != null) {
            // look for default activity
            for (int i = 0; i < count; i++) {
                ResolveInfo rInfo = matches.get(i);
                if (rInfo.activityInfo != null &&
                        defaultPackage.equals(rInfo.activityInfo.packageName)) {
                    defaultRI = rInfo;
                    break;
                }
            }
        }

        if (defaultRI != null) {
            // grant permission for default activity
            if (device != null) {
                grantDevicePermission(device, defaultRI.activityInfo.applicationInfo.uid);
            } else if (accessory != null) {
                grantAccessoryPermission(accessory, defaultRI.activityInfo.applicationInfo.uid);
            }

            // start default activity directly
            try {
                intent.setComponent(
                        new ComponentName(defaultRI.activityInfo.packageName,
                                defaultRI.activityInfo.name));
                mUserContext.startActivityAsUser(intent, mUser);
            } catch (ActivityNotFoundException e) {
                Slog.e(TAG, "startActivity failed", e);
            }
        } else {
            Intent resolverIntent = new Intent();
            resolverIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (count == 1) {
                // start UsbConfirmActivity if there is only one choice
                resolverIntent.setClassName("com.android.systemui",
                        "com.android.systemui.usb.UsbConfirmActivity");
                resolverIntent.putExtra("rinfo", matches.get(0));

                if (device != null) {
                    resolverIntent.putExtra(UsbManager.EXTRA_DEVICE, device);
                } else {
                    resolverIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
                }
            } else {
                // start UsbResolverActivity so user can choose an activity
                resolverIntent.setClassName("com.android.systemui",
                        "com.android.systemui.usb.UsbResolverActivity");
                resolverIntent.putParcelableArrayListExtra("rlist", matches);
                resolverIntent.putExtra(Intent.EXTRA_INTENT, intent);
            }
            try {
                mUserContext.startActivityAsUser(resolverIntent, mUser);
            } catch (ActivityNotFoundException e) {
                Slog.e(TAG, "unable to start activity " + resolverIntent);
            }
        }
    }

    private boolean clearCompatibleMatchesLocked(String packageName, DeviceFilter filter) {
        boolean changed = false;
        for (DeviceFilter test : mDevicePreferenceMap.keySet()) {
            if (filter.matches(test)) {
                mDevicePreferenceMap.remove(test);
                changed = true;
            }
        }
        return changed;
    }

    private boolean clearCompatibleMatchesLocked(String packageName, AccessoryFilter filter) {
        boolean changed = false;
        for (AccessoryFilter test : mAccessoryPreferenceMap.keySet()) {
            if (filter.matches(test)) {
                mAccessoryPreferenceMap.remove(test);
                changed = true;
            }
        }
        return changed;
    }

    private boolean handlePackageUpdateLocked(String packageName, ActivityInfo aInfo,
            String metaDataName) {
        XmlResourceParser parser = null;
        boolean changed = false;

        try {
            parser = aInfo.loadXmlMetaData(mPackageManager, metaDataName);
            if (parser == null) return false;

            XmlUtils.nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if ("usb-device".equals(tagName)) {
                    DeviceFilter filter = DeviceFilter.read(parser);
                    if (clearCompatibleMatchesLocked(packageName, filter)) {
                        changed = true;
                    }
                }
                else if ("usb-accessory".equals(tagName)) {
                    AccessoryFilter filter = AccessoryFilter.read(parser);
                    if (clearCompatibleMatchesLocked(packageName, filter)) {
                        changed = true;
                    }
                }
                XmlUtils.nextElement(parser);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unable to load component info " + aInfo.toString(), e);
        } finally {
            if (parser != null) parser.close();
        }
        return changed;
    }

    // Check to see if the package supports any USB devices or accessories.
    // If so, clear any non-matching preferences for matching devices/accessories.
    private void handlePackageUpdate(String packageName) {
        synchronized (mLock) {
            PackageInfo info;
            boolean changed = false;

            try {
                info = mPackageManager.getPackageInfo(packageName,
                        PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
            } catch (NameNotFoundException e) {
                Slog.e(TAG, "handlePackageUpdate could not find package " + packageName, e);
                return;
            }

            ActivityInfo[] activities = info.activities;
            if (activities == null) return;
            for (int i = 0; i < activities.length; i++) {
                // check for meta-data, both for devices and accessories
                if (handlePackageUpdateLocked(packageName, activities[i],
                        UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                    changed = true;
                }
                if (handlePackageUpdateLocked(packageName, activities[i],
                        UsbManager.ACTION_USB_ACCESSORY_ATTACHED)) {
                    changed = true;
                }
            }

            if (changed) {
                writeSettingsLocked();
            }
        }
    }

    public boolean hasPermission(UsbDevice device) {
        synchronized (mLock) {
            int uid = Binder.getCallingUid();
            if (uid == Process.SYSTEM_UID || mDisablePermissionDialogs) {
                return true;
            }
            SparseBooleanArray uidList = mDevicePermissionMap.get(device.getDeviceName());
            if (uidList == null) {
                return false;
            }
            return uidList.get(uid);
        }
    }

    public boolean hasPermission(UsbAccessory accessory) {
        synchronized (mLock) {
            int uid = Binder.getCallingUid();
            if (uid == Process.SYSTEM_UID || mDisablePermissionDialogs) {
                return true;
            }
            SparseBooleanArray uidList = mAccessoryPermissionMap.get(accessory);
            if (uidList == null) {
                return false;
            }
            return uidList.get(uid);
        }
    }

    public void checkPermission(UsbDevice device) {
        if (!hasPermission(device)) {
            throw new SecurityException("User has not given permission to device " + device);
        }
    }

    public void checkPermission(UsbAccessory accessory) {
        if (!hasPermission(accessory)) {
            throw new SecurityException("User has not given permission to accessory " + accessory);
        }
    }

    private void requestPermissionDialog(Intent intent, String packageName, PendingIntent pi) {
        final int uid = Binder.getCallingUid();

        // compare uid with packageName to foil apps pretending to be someone else
        try {
            ApplicationInfo aInfo = mPackageManager.getApplicationInfo(packageName, 0);
            if (aInfo.uid != uid) {
                throw new IllegalArgumentException("package " + packageName +
                        " does not match caller's uid " + uid);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("package " + packageName + " not found");
        }

        long identity = Binder.clearCallingIdentity();
        intent.setClassName("com.android.systemui",
                "com.android.systemui.usb.UsbPermissionActivity");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Intent.EXTRA_INTENT, pi);
        intent.putExtra("package", packageName);
        intent.putExtra(Intent.EXTRA_UID, uid);
        try {
            mUserContext.startActivityAsUser(intent, mUser);
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "unable to start UsbPermissionActivity");
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void requestPermission(UsbDevice device, String packageName, PendingIntent pi) {
      Intent intent = new Intent();

        // respond immediately if permission has already been granted
      if (hasPermission(device)) {
            intent.putExtra(UsbManager.EXTRA_DEVICE, device);
            intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true);
            try {
                pi.send(mUserContext, 0, intent);
            } catch (PendingIntent.CanceledException e) {
                if (DEBUG) Slog.d(TAG, "requestPermission PendingIntent was cancelled");
            }
            return;
        }

        // start UsbPermissionActivity so user can choose an activity
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);
        requestPermissionDialog(intent, packageName, pi);
    }

    public void requestPermission(UsbAccessory accessory, String packageName, PendingIntent pi) {
        Intent intent = new Intent();

        // respond immediately if permission has already been granted
        if (hasPermission(accessory)) {
            intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true);
            try {
                pi.send(mUserContext, 0, intent);
            } catch (PendingIntent.CanceledException e) {
                if (DEBUG) Slog.d(TAG, "requestPermission PendingIntent was cancelled");
            }
            return;
        }

        intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        requestPermissionDialog(intent, packageName, pi);
    }

    public void setDevicePackage(UsbDevice device, String packageName) {
        DeviceFilter filter = new DeviceFilter(device);
        boolean changed = false;
        synchronized (mLock) {
            if (packageName == null) {
                changed = (mDevicePreferenceMap.remove(filter) != null);
            } else {
                changed = !packageName.equals(mDevicePreferenceMap.get(filter));
                if (changed) {
                    mDevicePreferenceMap.put(filter, packageName);
                }
            }
            if (changed) {
                writeSettingsLocked();
            }
        }
    }

    public void setAccessoryPackage(UsbAccessory accessory, String packageName) {
        AccessoryFilter filter = new AccessoryFilter(accessory);
        boolean changed = false;
        synchronized (mLock) {
            if (packageName == null) {
                changed = (mAccessoryPreferenceMap.remove(filter) != null);
            } else {
                changed = !packageName.equals(mAccessoryPreferenceMap.get(filter));
                if (changed) {
                    mAccessoryPreferenceMap.put(filter, packageName);
                }
            }
            if (changed) {
                writeSettingsLocked();
            }
        }
    }

    public void grantDevicePermission(UsbDevice device, int uid) {
        synchronized (mLock) {
            String deviceName = device.getDeviceName();
            SparseBooleanArray uidList = mDevicePermissionMap.get(deviceName);
            if (uidList == null) {
                uidList = new SparseBooleanArray(1);
                mDevicePermissionMap.put(deviceName, uidList);
            }
            uidList.put(uid, true);
        }
    }

    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        synchronized (mLock) {
            SparseBooleanArray uidList = mAccessoryPermissionMap.get(accessory);
            if (uidList == null) {
                uidList = new SparseBooleanArray(1);
                mAccessoryPermissionMap.put(accessory, uidList);
            }
            uidList.put(uid, true);
        }
    }

    public boolean hasDefaults(String packageName) {
        synchronized (mLock) {
            if (mDevicePreferenceMap.values().contains(packageName)) return true;
            if (mAccessoryPreferenceMap.values().contains(packageName)) return true;
            return false;
        }
    }

    public void clearDefaults(String packageName) {
        synchronized (mLock) {
            if (clearPackageDefaultsLocked(packageName)) {
                writeSettingsLocked();
            }
        }
    }

    private boolean clearPackageDefaultsLocked(String packageName) {
        boolean cleared = false;
        synchronized (mLock) {
            if (mDevicePreferenceMap.containsValue(packageName)) {
                // make a copy of the key set to avoid ConcurrentModificationException
                Object[] keys = mDevicePreferenceMap.keySet().toArray();
                for (int i = 0; i < keys.length; i++) {
                    Object key = keys[i];
                    if (packageName.equals(mDevicePreferenceMap.get(key))) {
                        mDevicePreferenceMap.remove(key);
                        cleared = true;
                    }
                }
            }
            if (mAccessoryPreferenceMap.containsValue(packageName)) {
                // make a copy of the key set to avoid ConcurrentModificationException
                Object[] keys = mAccessoryPreferenceMap.keySet().toArray();
                for (int i = 0; i < keys.length; i++) {
                    Object key = keys[i];
                    if (packageName.equals(mAccessoryPreferenceMap.get(key))) {
                        mAccessoryPreferenceMap.remove(key);
                        cleared = true;
                    }
                }
            }
            return cleared;
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw) {
        synchronized (mLock) {
            pw.println("  Device permissions:");
            for (String deviceName : mDevicePermissionMap.keySet()) {
                pw.print("    " + deviceName + ": ");
                SparseBooleanArray uidList = mDevicePermissionMap.get(deviceName);
                int count = uidList.size();
                for (int i = 0; i < count; i++) {
                    pw.print(Integer.toString(uidList.keyAt(i)) + " ");
                }
                pw.println("");
            }
            pw.println("  Accessory permissions:");
            for (UsbAccessory accessory : mAccessoryPermissionMap.keySet()) {
                pw.print("    " + accessory + ": ");
                SparseBooleanArray uidList = mAccessoryPermissionMap.get(accessory);
                int count = uidList.size();
                for (int i = 0; i < count; i++) {
                    pw.print(Integer.toString(uidList.keyAt(i)) + " ");
                }
                pw.println("");
            }
            pw.println("  Device preferences:");
            for (DeviceFilter filter : mDevicePreferenceMap.keySet()) {
                pw.println("    " + filter + ": " + mDevicePreferenceMap.get(filter));
            }
            pw.println("  Accessory preferences:");
            for (AccessoryFilter filter : mAccessoryPreferenceMap.keySet()) {
                pw.println("    " + filter + ": " + mAccessoryPreferenceMap.get(filter));
            }
        }
    }
}
