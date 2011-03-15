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
import android.content.res.XmlResourceParser;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.FileUtils;
import android.os.Process;
import android.util.Log;
import android.util.SparseBooleanArray;
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
    private final PackageManager mPackageManager;

    // Temporary mapping UsbAccessory to list of UIDs with permissions for the accessory
    private final HashMap<UsbAccessory, SparseBooleanArray> mAccessoryPermissionMap =
            new HashMap<UsbAccessory, SparseBooleanArray>();
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

        public void onPackageAdded(String packageName, int uid) {
            handlePackageUpdate(packageName);
        }

        public void onPackageChanged(String packageName, int uid, String[] components) {
            handlePackageUpdate(packageName);
        }

        public void onPackageRemoved(String packageName, int uid) {
            clearDefaults(packageName);
        }
    }
    MyPackageMonitor mPackageMonitor = new MyPackageMonitor();

    public UsbDeviceSettingsManager(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        synchronized (mLock) {
            readSettingsLocked();
        }
        mPackageMonitor.register(context, true);
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
                if ("preference".equals(tagName)) {
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

        XmlResourceParser parser = null;
        try {
            parser = ai.loadXmlMetaData(mPackageManager, metaDataName);
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
        List<ResolveInfo> resolveInfos = mPackageManager.queryIntentActivities(intent,
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

        resolveActivity(intent, matches, defaultPackage, accessory);
    }

    public void accessoryDetached(UsbAccessory accessory) {
        // clear temporary permissions for the accessory
        mAccessoryPermissionMap.remove(accessory);

        Intent intent = new Intent(
                UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        mContext.sendBroadcast(intent);
    }

    private void resolveActivity(Intent intent, ArrayList<ResolveInfo> matches,
            String defaultPackage, UsbAccessory accessory) {
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
                        mContext.startActivity(dialogIntent);
                    } catch (ActivityNotFoundException e) {
                        Log.e(TAG, "unable to start UsbAccessoryUriActivity");
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
            grantAccessoryPermission(accessory, defaultRI.activityInfo.applicationInfo.uid);

            // start default activity directly
            try {
                intent.setComponent(
                        new ComponentName(defaultRI.activityInfo.packageName,
                                defaultRI.activityInfo.name));
                mContext.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "startActivity failed", e);
            }
        } else {
            Intent resolverIntent = new Intent();
            resolverIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (count == 1) {
                // start UsbConfirmActivity if there is only one choice
                resolverIntent.setClassName("com.android.systemui",
                        "com.android.systemui.usb.UsbConfirmActivity");
                resolverIntent.putExtra("rinfo", matches.get(0));
                resolverIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            } else {
                // start UsbResolverActivity so user can choose an activity
                resolverIntent.setClassName("com.android.systemui",
                        "com.android.systemui.usb.UsbResolverActivity");
                resolverIntent.putParcelableArrayListExtra("rlist", matches);
                resolverIntent.putExtra(Intent.EXTRA_INTENT, intent);
            }
            try {
                mContext.startActivity(resolverIntent);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "unable to start activity " + resolverIntent);
            }
        }
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
                if ("usb-accessory".equals(tagName)) {
                    AccessoryFilter filter = AccessoryFilter.read(parser);
                    if (clearCompatibleMatchesLocked(packageName, filter)) {
                        changed = true;
                    }
                }
                XmlUtils.nextElement(parser);
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to load component info " + aInfo.toString(), e);
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
                Log.e(TAG, "handlePackageUpdate could not find package " + packageName, e);
                return;
            }

            ActivityInfo[] activities = info.activities;
            if (activities == null) return;
            for (int i = 0; i < activities.length; i++) {
                // check for meta-data, both for devices and accessories
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

    public boolean hasPermission(UsbAccessory accessory) {
        synchronized (mLock) {
            SparseBooleanArray uidList = mAccessoryPermissionMap.get(accessory);
            if (uidList == null) {
                return false;
            }
            return uidList.get(Binder.getCallingUid());
        }
    }

    public void checkPermission(UsbAccessory accessory) {
        if (!hasPermission(accessory)) {
            throw new SecurityException("User has not given permission to accessory " + accessory);
        }
    }

    private void requestPermissionDialog(Intent intent, String packageName, PendingIntent pi) {
        int uid = Binder.getCallingUid();

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
        intent.putExtra("uid", uid);
        try {
            mContext.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "unable to start UsbPermissionActivity");
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void requestPermission(UsbAccessory accessory, String packageName, PendingIntent pi) {
      Intent intent = new Intent();

        // respond immediately if permission has already been granted
        if (hasPermission(accessory)) {
            intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true);
           try {
                pi.send(mContext, 0, intent);
            } catch (PendingIntent.CanceledException e) {
                Log.w(TAG, "requestPermission PendingIntent was cancelled");
            }
            return;
        }

        intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        requestPermissionDialog(intent, packageName, pi);
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
            return mAccessoryPreferenceMap.values().contains(packageName);
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
            for (UsbAccessory accessory : mAccessoryPermissionMap.keySet()) {
                pw.print("    " + accessory + ": ");
                SparseBooleanArray uidList = mAccessoryPermissionMap.get(accessory);
                int count = uidList.size();
                for (int i = 0; i < count; i++) {
                    pw.print(Integer.toString(uidList.keyAt(i)) + " ");
                }
                pw.println("");
            }
            pw.println("  Accessory preferences:");
            for (AccessoryFilter filter : mAccessoryPreferenceMap.keySet()) {
                pw.println("    " + filter + ": " + mAccessoryPreferenceMap.get(filter));
            }
        }
    }
}
