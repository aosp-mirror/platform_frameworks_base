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

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.XmlResourceParser;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Process;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.content.PackageMonitor;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedOutputStream;
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

class UsbDeviceSettingsManager {

    private static final String TAG = "UsbDeviceSettingsManager";
    private static final File sSettingsFile = new File("/data/system/usb_device_manager.xml");

    private final Context mContext;

    // maps UID to user approved USB devices
    final SparseArray<ArrayList<DeviceFilter>> mDevicePermissionMap =
            new SparseArray<ArrayList<DeviceFilter>>();
    // maps UID to user approved USB accessories
    final SparseArray<ArrayList<AccessoryFilter>> mAccessoryPermissionMap =
            new SparseArray<ArrayList<AccessoryFilter>>();
    // Maps DeviceFilter to user preferred application package
    final HashMap<DeviceFilter, String> mDevicePreferenceMap =
            new HashMap<DeviceFilter, String>();
    // Maps DeviceFilter to user preferred application package
    final HashMap<AccessoryFilter, String> mAccessoryPreferenceMap =
            new HashMap<AccessoryFilter, String>();

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

        public DeviceFilter(int vid, int pid, int clasz, int subclass, int protocol) {
            mVendorId = vid;
            mProductId = pid;
            mClass = clasz;
            mSubclass = subclass;
            mProtocol = protocol;
        }

        public DeviceFilter(UsbDevice device) {
            mVendorId = device.getVendorId();
            mProductId = device.getProductId();
            mClass = device.getDeviceClass();
            mSubclass = device.getDeviceSubclass();
            mProtocol = device.getDeviceProtocol();
        }

        public static DeviceFilter read(XmlPullParser parser)
                throws XmlPullParserException, IOException {
            int vendorId = -1;
            int productId = -1;
            int deviceClass = -1;
            int deviceSubclass = -1;
            int deviceProtocol = -1;

            int count = parser.getAttributeCount();
            for (int i = 0; i < count; i++) {
                String name = parser.getAttributeName(i);
                // All attribute values are ints
                int value = Integer.parseInt(parser.getAttributeValue(i));

                if ("vendor-id".equals(name)) {
                    vendorId = value;
                } else if ("product-id".equals(name)) {
                    productId = value;
                } else if ("class".equals(name)) {
                    deviceClass = value;
                } else if ("subclass".equals(name)) {
                    deviceSubclass = value;
                } else if ("protocol".equals(name)) {
                    deviceProtocol = value;
                }
            }
            return new DeviceFilter(vendorId, productId,
                    deviceClass, deviceSubclass, deviceProtocol);
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

        @Override
        public boolean equals(Object obj) {
            // can't compare if we have wildcard strings
            if (mVendorId == -1 || mProductId == -1 ||
                    mClass == -1 || mSubclass == -1 || mProtocol == -1) {
                return false;
            }
            if (obj instanceof DeviceFilter) {
                DeviceFilter filter = (DeviceFilter)obj;
                return (filter.mVendorId == mVendorId &&
                        filter.mProductId == mProductId &&
                        filter.mClass == mClass &&
                        filter.mSubclass == mSubclass &&
                        filter.mProtocol == mProtocol);
            }
            if (obj instanceof UsbDevice) {
                UsbDevice device = (UsbDevice)obj;
                return (device.getVendorId() == mVendorId &&
                        device.getProductId() == mProductId &&
                        device.getDeviceClass() == mClass &&
                        device.getDeviceSubclass() == mSubclass &&
                        device.getDeviceProtocol() == mProtocol);
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
                    ",mProtocol=" + mProtocol + "]";
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
        // USB accessory type (or null for unspecified)
        public final String mType;
        // USB accessory version (or null for unspecified)
        public final String mVersion;

        public AccessoryFilter(String manufacturer, String model, String type, String version) {
            mManufacturer = manufacturer;
            mModel = model;
            mType = type;
            mVersion = version;
        }

        public AccessoryFilter(UsbAccessory accessory) {
            mManufacturer = accessory.getManufacturer();
            mModel = accessory.getModel();
            mType = accessory.getType();
            mVersion = accessory.getVersion();
        }

        public static AccessoryFilter read(XmlPullParser parser)
                throws XmlPullParserException, IOException {
            String manufacturer = null;
            String model = null;
            String type = null;
            String version = null;

            int count = parser.getAttributeCount();
            for (int i = 0; i < count; i++) {
                String name = parser.getAttributeName(i);
                String value = parser.getAttributeValue(i);

                if ("manufacturer".equals(name)) {
                    manufacturer = value;
                } else if ("model".equals(name)) {
                    model = value;
                } else if ("type".equals(name)) {
                    type = value;
                } else if ("version".equals(name)) {
                    version = value;
                }
             }
             return new AccessoryFilter(manufacturer, model, type, version);
        }

        public void write(XmlSerializer serializer)throws IOException {
            serializer.startTag(null, "usb-accessory");
            if (mManufacturer != null) {
                serializer.attribute(null, "manufacturer", mManufacturer);
            }
            if (mModel != null) {
                serializer.attribute(null, "model", mModel);
            }
            if (mType != null) {
                serializer.attribute(null, "type", mType);
            }
            if (mVersion != null) {
                serializer.attribute(null, "version", mVersion);
            }
            serializer.endTag(null, "usb-accessory");
        }

        public boolean matches(UsbAccessory acc) {
            if (mManufacturer != null && !acc.getManufacturer().equals(mManufacturer)) return false;
            if (mModel != null && !acc.getModel().equals(mModel)) return false;
            if (mType != null && !acc.getType().equals(mType)) return false;
            if (mVersion != null && !acc.getVersion().equals(mVersion)) return false;
            return true;
        }

        @Override
        public boolean equals(Object obj) {
            // can't compare if we have wildcard strings
            if (mManufacturer == null || mModel == null || mType == null || mVersion == null) {
                return false;
            }
            if (obj instanceof AccessoryFilter) {
                AccessoryFilter filter = (AccessoryFilter)obj;
                return (mManufacturer.equals(filter.mManufacturer) &&
                        mModel.equals(filter.mModel) &&
                        mType.equals(filter.mType) &&
                        mVersion.equals(filter.mVersion));
            }
            if (obj instanceof UsbAccessory) {
                UsbAccessory accessory = (UsbAccessory)obj;
                return (mManufacturer.equals(accessory.getManufacturer()) &&
                        mModel.equals(accessory.getModel()) &&
                        mType.equals(accessory.getType()) &&
                        mVersion.equals(accessory.getVersion()));
            }
            return false;
        }

        @Override
        public int hashCode() {
            return ((mManufacturer == null ? 0 : mManufacturer.hashCode()) ^
                    (mModel == null ? 0 : mModel.hashCode()) ^
                    (mType == null ? 0 : mType.hashCode()) ^
                    (mVersion == null ? 0 : mVersion.hashCode()));
        }

        @Override
        public String toString() {
            return "AccessoryFilter[mManufacturer=\"" + mManufacturer +
                                "\", mModel=\"" + mModel +
                                "\", mType=\"" + mType +
                                "\", mVersion=\"" + mVersion + "\"]";
        }
    }

    private class MyPackageMonitor extends PackageMonitor {
        public void onPackageRemoved(String packageName, int uid) {
            // clear all activity preferences for the package
            if (clearPackageDefaults(packageName)) {
                writeSettings();
            }
        }

        public void onUidRemoved(int uid) {
            // clear all permissions for the UID
            if (clearUidDefaults(uid)) {
                writeSettings();
            }
        }
    }
    MyPackageMonitor mPackageMonitor = new MyPackageMonitor();

    public UsbDeviceSettingsManager(Context context) {
        mContext = context;
        readSettings();
        mPackageMonitor.register(context, true);
    }

    private void readDevicePermission(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int uid = -1;
        ArrayList<DeviceFilter> filters = new ArrayList<DeviceFilter>();
        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if ("uid".equals(parser.getAttributeName(i))) {
                uid = Integer.parseInt(parser.getAttributeValue(i));
                break;
            }
        }
        XmlUtils.nextElement(parser);
        while ("usb-device".equals(parser.getName())) {
            filters.add(DeviceFilter.read(parser));
            XmlUtils.nextElement(parser);
        }
        mDevicePermissionMap.put(uid, filters);
    }

    private void readAccessoryPermission(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int uid = -1;
        ArrayList<AccessoryFilter> filters = new ArrayList<AccessoryFilter>();
        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if ("uid".equals(parser.getAttributeName(i))) {
                uid = Integer.parseInt(parser.getAttributeValue(i));
                break;
            }
        }
        XmlUtils.nextElement(parser);
        while ("usb-accessory".equals(parser.getName())) {
            filters.add(AccessoryFilter.read(parser));
            XmlUtils.nextElement(parser);
        }
        mAccessoryPermissionMap.put(uid, filters);
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

    private void readSettings() {
        FileInputStream stream = null;
        try {
            stream = new FileInputStream(sSettingsFile);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            XmlUtils.nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if ("device-permission".equals(tagName)) {
                    readDevicePermission(parser);
                } else if ("accessory-permission".equals(tagName)) {
                    readAccessoryPermission(parser);
                } else if ("preference".equals(tagName)) {
                    readPreference(parser);
                 } else {
                    XmlUtils.nextElement(parser);
                }
            }
        } catch (FileNotFoundException e) {
            Log.w(TAG, "settings file not found");
        } catch (Exception e) {
            Log.e(TAG, "error reading settings file, deleting to start fresh", e);
            sSettingsFile.delete();
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                }
            }
        }
    }

    private void writeSettings() {
        FileOutputStream fos = null;
        try {
            FileOutputStream fstr = new FileOutputStream(sSettingsFile);
            Log.d(TAG, "writing settings to " + fstr);
            BufferedOutputStream str = new BufferedOutputStream(fstr);
            FastXmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(str, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startTag(null, "settings");

            int count = mDevicePermissionMap.size();
            for (int i = 0; i < count; i++) {
                int uid = mDevicePermissionMap.keyAt(i);
                ArrayList<DeviceFilter> filters = mDevicePermissionMap.valueAt(i);
                serializer.startTag(null, "device-permission");
                serializer.attribute(null, "uid", Integer.toString(uid));
                int filterCount = filters.size();
                for (int j = 0; j < filterCount; j++) {
                    filters.get(j).write(serializer);
                }
                serializer.endTag(null, "device-permission");
            }

            count = mAccessoryPermissionMap.size();
            for (int i = 0; i < count; i++) {
                int uid = mAccessoryPermissionMap.keyAt(i);
                ArrayList<AccessoryFilter> filters = mAccessoryPermissionMap.valueAt(i);
                serializer.startTag(null, "accessory-permission");
                serializer.attribute(null, "uid", Integer.toString(uid));
                int filterCount = filters.size();
                for (int j = 0; j < filterCount; j++) {
                    filters.get(j).write(serializer);
                }
                serializer.endTag(null, "accessory-permission");
            }

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

            str.flush();
            FileUtils.sync(fstr);
            str.close();
        } catch (Exception e) {
            Log.e(TAG, "error writing settings file, deleting to start fresh", e);
            sSettingsFile.delete();
        }
    }

    // Checks to see if a package matches a device or accessory.
    // Only one of device and accessory should be non-null.
    private boolean packageMatches(ResolveInfo info, String metaDataName,
            UsbDevice device, UsbAccessory accessory) {
        ActivityInfo ai = info.activityInfo;
        PackageManager pm = mContext.getPackageManager();

        XmlResourceParser parser = null;
        try {
            parser = ai.loadXmlMetaData(pm, metaDataName);
            if (parser == null) {
                Log.w(TAG, "no meta-data for " + info);
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
            Log.w(TAG, "Unable to load component info " + info.toString(), e);
        } finally {
            if (parser != null) parser.close();
        }
        return false;
    }

    private final ArrayList<ResolveInfo> getDeviceMatches(UsbDevice device, Intent intent) {
        ArrayList<ResolveInfo> matches = new ArrayList<ResolveInfo>();
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent,
                PackageManager.GET_META_DATA);
        int count = resolveInfos.size();
        for (int i = 0; i < count; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);
            if (packageMatches(resolveInfo, intent.getAction(), device, null)) {
                matches.add(resolveInfo);
            }
        }
        return matches;
    }

    private final ArrayList<ResolveInfo> getAccessoryMatches(UsbAccessory accessory, Intent intent) {
        ArrayList<ResolveInfo> matches = new ArrayList<ResolveInfo>();
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent,
                PackageManager.GET_META_DATA);
        int count = resolveInfos.size();
        for (int i = 0; i < count; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);
            if (packageMatches(resolveInfo, intent.getAction(), null, accessory)) {
                matches.add(resolveInfo);
            }
        }
        return matches;
    }

    public void deviceAttached(UsbDevice device) {
        Intent deviceIntent = new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        deviceIntent.putExtra(UsbManager.EXTRA_DEVICE, device);
        deviceIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        ArrayList<ResolveInfo> matches = getDeviceMatches(device, deviceIntent);
        // Launch our default activity directly, if we have one.
        // Otherwise we will start the UsbResolverActivity to allow the user to choose.
        String defaultPackage = mDevicePreferenceMap.get(new DeviceFilter(device));
        if (defaultPackage != null) {
            int count = matches.size();
            for (int i = 0; i < count; i++) {
                ResolveInfo rInfo = matches.get(i);
                if (rInfo.activityInfo != null &&
                        defaultPackage.equals(rInfo.activityInfo.packageName)) {
                    try {
                        deviceIntent.setComponent(new ComponentName(
                                defaultPackage, rInfo.activityInfo.name));
                        mContext.startActivity(deviceIntent);
                    } catch (ActivityNotFoundException e) {
                        Log.e(TAG, "startActivity failed", e);
                    }
                    return;
                }
            }
        }

        Intent intent = new Intent(mContext, UsbResolverActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        intent.putExtra(Intent.EXTRA_INTENT, deviceIntent);
        intent.putParcelableArrayListExtra(UsbResolverActivity.EXTRA_RESOLVE_INFOS,
                matches);
        try {
            mContext.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "unable to start UsbResolverActivity");
        }
    }

    public void deviceDetached(UsbDevice device) {
        Intent intent = new Intent(UsbManager.ACTION_USB_DEVICE_DETACHED);
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);
        Log.d(TAG, "usbDeviceRemoved, sending " + intent);
        mContext.sendBroadcast(intent);
    }

    public void accessoryAttached(UsbAccessory accessory) {
        Intent accessoryIntent = new Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        accessoryIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        accessoryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        ArrayList<ResolveInfo> matches = getAccessoryMatches(accessory, accessoryIntent);
        // Launch our default activity directly, if we have one.
        // Otherwise we will start the UsbResolverActivity to allow the user to choose.
        String defaultPackage = mAccessoryPreferenceMap.get(new AccessoryFilter(accessory));
        if (defaultPackage != null) {
            int count = matches.size();
            for (int i = 0; i < count; i++) {
                ResolveInfo rInfo = matches.get(i);
                if (rInfo.activityInfo != null &&
                        defaultPackage.equals(rInfo.activityInfo.packageName)) {
                    try {
                        accessoryIntent.setComponent(new ComponentName(
                                defaultPackage, rInfo.activityInfo.name));
                        mContext.startActivity(accessoryIntent);
                    } catch (ActivityNotFoundException e) {
                        Log.e(TAG, "startActivity failed", e);
                    }
                    return;
                }
            }
        }

        Intent intent = new Intent(mContext, UsbResolverActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        intent.putExtra(Intent.EXTRA_INTENT, accessoryIntent);
        intent.putParcelableArrayListExtra(UsbResolverActivity.EXTRA_RESOLVE_INFOS,
                matches);
        try {
            mContext.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "unable to start UsbResolverActivity");
        }
    }

    public void accessoryDetached(UsbAccessory accessory) {
        Intent intent = new Intent(
                UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        mContext.sendBroadcast(intent);
    }

    public void checkPermission(UsbDevice device) {
        if (device == null) return;
        ArrayList<DeviceFilter> filterList = mDevicePermissionMap.get(Binder.getCallingUid());
        if (filterList != null) {
            int count = filterList.size();
            for (int i = 0; i < count; i++) {
                DeviceFilter filter = filterList.get(i);
                if (filter.equals(device)) {
                    // permission allowed
                    return;
                }
            }
        }
        throw new SecurityException("User has not given permission to device " + device);
    }

    public void checkPermission(UsbAccessory accessory) {
        if (accessory == null) return;
        ArrayList<AccessoryFilter> filterList = mAccessoryPermissionMap.get(Binder.getCallingUid());
        if (filterList != null) {
            int count = filterList.size();
            for (int i = 0; i < count; i++) {
                AccessoryFilter filter = filterList.get(i);
                if (filter.equals(accessory)) {
                    // permission allowed
                    return;
                }
            }
        }
        throw new SecurityException("User has not given permission to accessory " + accessory);
    }

    public void setDevicePackage(UsbDevice device, String packageName) {
        DeviceFilter filter = new DeviceFilter(device);
        if (packageName == null) {
            mDevicePreferenceMap.remove(filter);
        } else {
            mDevicePreferenceMap.put(filter, packageName);
        }
       // FIXME - only if changed
        writeSettings();
    }

    public void setAccessoryPackage(UsbAccessory accessory, String packageName) {
        AccessoryFilter filter = new AccessoryFilter(accessory);
        if (packageName == null) {
            mAccessoryPreferenceMap.remove(filter);
        } else {
            mAccessoryPreferenceMap.put(filter, packageName);
        }
        // FIXME - only if changed
        writeSettings();
    }

    public void grantDevicePermission(UsbDevice device, int uid) {
        ArrayList<DeviceFilter> filterList = mDevicePermissionMap.get(uid);
        if (filterList == null) {
            filterList = new ArrayList<DeviceFilter>();
            mDevicePermissionMap.put(uid, filterList);
        } else {
            int count = filterList.size();
            for (int i = 0; i < count; i++) {
                if (filterList.get(i).equals(device)) return;
            }
        }
        filterList.add(new DeviceFilter(device));
        writeSettings();
    }

    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        ArrayList<AccessoryFilter> filterList = mAccessoryPermissionMap.get(uid);
        if (filterList == null) {
            filterList = new ArrayList<AccessoryFilter>();
            mAccessoryPermissionMap.put(uid, filterList);
        } else {
            int count = filterList.size();
            for (int i = 0; i < count; i++) {
                if (filterList.get(i).equals(accessory)) return;
            }
        }
        filterList.add(new AccessoryFilter(accessory));
        writeSettings();
    }

    public boolean hasDefaults(String packageName, int uid) {
        if (mDevicePermissionMap.get(uid) != null) return true;
        if (mAccessoryPermissionMap.get(uid) != null) return true;
        if (mDevicePreferenceMap.values().contains(packageName)) return true;
        if (mAccessoryPreferenceMap.values().contains(packageName)) return true;
        return false;
    }

    public void clearDefaults(String packageName, int uid) {
        boolean packageCleared = clearPackageDefaults(packageName);
        boolean uidCleared = clearUidDefaults(uid);
        if (packageCleared || uidCleared) {
            writeSettings();
        }
    }

    private boolean clearUidDefaults(int uid) {
        boolean cleared = false;
        int index = mDevicePermissionMap.indexOfKey(uid);
        if (index >= 0) {
            mDevicePermissionMap.removeAt(index);
            cleared = true;
        }
        index = mAccessoryPermissionMap.indexOfKey(uid);
        if (index >= 0) {
            mAccessoryPermissionMap.removeAt(index);
            cleared = true;
        }
        return cleared;
    }

    private boolean clearPackageDefaults(String packageName) {
        boolean cleared = false;
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

    public void dump(FileDescriptor fd, PrintWriter pw) {
        pw.println("  Device permissions:");
        int count = mDevicePermissionMap.size();
        for (int i = 0; i < count; i++) {
            int uid = mDevicePermissionMap.keyAt(i);
            pw.println("    " + "uid " + uid + ":");
            ArrayList<DeviceFilter> filters = mDevicePermissionMap.valueAt(i);
            for (DeviceFilter filter : filters) {
                pw.println("      " + filter);
            }
        }
        pw.println("  Accessory permissions:");
        count = mAccessoryPermissionMap.size();
        for (int i = 0; i < count; i++) {
            int uid = mAccessoryPermissionMap.keyAt(i);
            pw.println("    " + "uid " + uid + ":");
            ArrayList<AccessoryFilter> filters = mAccessoryPermissionMap.valueAt(i);
            for (AccessoryFilter filter : filters) {
                pw.println("      " + filter);
            }
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
