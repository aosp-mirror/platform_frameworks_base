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

    // maps UID to user approved USB accessories
    private final SparseArray<ArrayList<AccessoryFilter>> mAccessoryPermissionMap =
            new SparseArray<ArrayList<AccessoryFilter>>();
    // Maps AccessoryFilter to user preferred application package
    private final HashMap<AccessoryFilter, String> mAccessoryPreferenceMap =
            new HashMap<AccessoryFilter, String>();

    private final Object mLock = new Object();

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
            synchronized (mLock) {
                // clear all activity preferences for the package
                if (clearPackageDefaultsLocked(packageName)) {
                    writeSettingsLocked();
                }
            }
        }

        public void onUidRemoved(int uid) {
            synchronized (mLock) {
                // clear all permissions for the UID
                if (clearUidDefaultsLocked(uid)) {
                    writeSettingsLocked();
                }
            }
        }
    }
    MyPackageMonitor mPackageMonitor = new MyPackageMonitor();

    public UsbDeviceSettingsManager(Context context) {
        mContext = context;
        synchronized (mLock) {
            readSettingsLocked();
        }
        mPackageMonitor.register(context, true);
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
        if ("usb-accessory".equals(parser.getName())) {
            AccessoryFilter filter = AccessoryFilter.read(parser);
            mAccessoryPreferenceMap.put(filter, packageName);
        }
        XmlUtils.nextElement(parser);
    }

    private void readSettingsLocked() {
        FileInputStream stream = null;
        try {
            stream = new FileInputStream(sSettingsFile);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, null);

            XmlUtils.nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if ("accessory-permission".equals(tagName)) {
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

    private void writeSettingsLocked() {
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

            int count = mAccessoryPermissionMap.size();
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

    // Checks to see if a package matches an accessory.
    private boolean packageMatchesLocked(ResolveInfo info, String metaDataName,
            UsbAccessory accessory) {
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
                if (accessory != null && "usb-accessory".equals(tagName)) {
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

    private final ArrayList<ResolveInfo> getAccessoryMatchesLocked(
            UsbAccessory accessory, Intent intent) {
        ArrayList<ResolveInfo> matches = new ArrayList<ResolveInfo>();
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent,
                PackageManager.GET_META_DATA);
        int count = resolveInfos.size();
        for (int i = 0; i < count; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);
            if (packageMatchesLocked(resolveInfo, intent.getAction(), accessory)) {
                matches.add(resolveInfo);
            }
        }
        return matches;
    }

    public void accessoryAttached(UsbAccessory accessory) {
        Intent accessoryIntent = new Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        accessoryIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        accessoryIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        ArrayList<ResolveInfo> matches;
        String defaultPackage;
        synchronized (mLock) {
            matches = getAccessoryMatchesLocked(accessory, accessoryIntent);
            // Launch our default activity directly, if we have one.
            // Otherwise we will start the UsbResolverActivity to allow the user to choose.
            defaultPackage = mAccessoryPreferenceMap.get(new AccessoryFilter(accessory));
        }

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
        intent.putParcelableArrayListExtra(UsbResolverActivity.EXTRA_RESOLVE_INFOS, matches);
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

    public void checkPermission(UsbAccessory accessory) {
        if (accessory == null) return;
        synchronized (mLock) {
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
        }
        throw new SecurityException("User has not given permission to accessory " + accessory);
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

    public void grantAccessoryPermission(UsbAccessory accessory, int uid) {
        synchronized (mLock) {
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
            writeSettingsLocked();
        }
    }

    public boolean hasDefaults(String packageName, int uid) {
        synchronized (mLock) {
            if (mAccessoryPermissionMap.get(uid) != null) return true;
            if (mAccessoryPreferenceMap.values().contains(packageName)) return true;
            return false;
        }
    }

    public void clearDefaults(String packageName, int uid) {
        synchronized (mLock) {
            boolean packageCleared = clearPackageDefaultsLocked(packageName);
            boolean uidCleared = clearUidDefaultsLocked(uid);
            if (packageCleared || uidCleared) {
                writeSettingsLocked();
            }
        }
    }

    private boolean clearUidDefaultsLocked(int uid) {
        boolean cleared = false;
        int index = mAccessoryPermissionMap.indexOfKey(uid);
        if (index >= 0) {
            mAccessoryPermissionMap.removeAt(index);
            cleared = true;
        }
        return cleared;
    }

    private boolean clearPackageDefaultsLocked(String packageName) {
        boolean cleared = false;
        synchronized (mLock) {
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
            pw.println("  Accessory permissions:");
            int count = mAccessoryPermissionMap.size();
            for (int i = 0; i < count; i++) {
                int uid = mAccessoryPermissionMap.keyAt(i);
                pw.println("    " + "uid " + uid + ":");
                ArrayList<AccessoryFilter> filters = mAccessoryPermissionMap.valueAt(i);
                for (AccessoryFilter filter : filters) {
                    pw.println("      " + filter);
                }
            }
            pw.println("  Accessory preferences:");
            for (AccessoryFilter filter : mAccessoryPreferenceMap.keySet()) {
                pw.println("    " + filter + ": " + mAccessoryPreferenceMap.get(filter));
            }
        }
    }
}
