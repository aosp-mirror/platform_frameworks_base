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

package com.android.server.usb;

import static android.provider.Settings.Secure.USER_SETUP_COMPLETE;

import static com.android.internal.app.IntentForwarderActivity.FORWARD_INTENT_TO_MANAGED_PROFILE;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
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
import android.content.pm.UserInfo;
import android.content.res.XmlResourceParser;
import android.hardware.usb.AccessoryFilter;
import android.hardware.usb.DeviceFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.usb.UsbProfileGroupSettingsManagerProto;
import android.service.usb.UsbSettingsAccessoryPreferenceProto;
import android.service.usb.UsbSettingsDevicePreferenceProto;
import android.service.usb.UserPackageProto;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.Immutable;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.usb.flags.Flags;
import com.android.server.utils.EventLogger;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class UsbProfileGroupSettingsManager {
    /**
     * &lt;application&gt; level property that an app can specify to restrict any overlaying of
     * activities when usb device is attached.
     *
     *
     * <p>This should only be set by privileged apps having {@link Manifest.permission#MANAGE_USB}
     * permission.
     * @hide
     */
    public static final String PROPERTY_RESTRICT_USB_OVERLAY_ACTIVITIES =
            "android.app.PROPERTY_RESTRICT_USB_OVERLAY_ACTIVITIES";
    private static final String TAG = UsbProfileGroupSettingsManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int DUMPSYS_LOG_BUFFER = 200;

    /** Legacy settings file, before multi-user */
    private static final File sSingleUserSettingsFile = new File(
            "/data/system/usb_device_manager.xml");

    /** The parent user (main user of the profile group) */
    private final UserHandle mParentUser;

    private final AtomicFile mSettingsFile;
    private final boolean mDisablePermissionDialogs;

    private final Context mContext;

    private final PackageManager mPackageManager;

    private final ActivityManager mActivityManager;

    private final UserManager mUserManager;
    private final @NonNull UsbSettingsManager mSettingsManager;

    /** Maps DeviceFilter to user preferred application package */
    @GuardedBy("mLock")
    private final HashMap<DeviceFilter, UserPackage> mDevicePreferenceMap = new HashMap<>();

    /** Maps DeviceFilter to set of UserPackages not to ask for launch preference anymore */
    @GuardedBy("mLock")
    private final ArrayMap<DeviceFilter, ArraySet<UserPackage>> mDevicePreferenceDeniedMap =
            new ArrayMap<>();

    /** Maps AccessoryFilter to user preferred application package */
    @GuardedBy("mLock")
    private final HashMap<AccessoryFilter, UserPackage> mAccessoryPreferenceMap = new HashMap<>();

    /** Maps AccessoryFilter to set of UserPackages not to ask for launch preference anymore */
    @GuardedBy("mLock")
    private final ArrayMap<AccessoryFilter, ArraySet<UserPackage>> mAccessoryPreferenceDeniedMap =
            new ArrayMap<>();

    private final Object mLock = new Object();

    /**
     * If a async task to persist the mDevicePreferenceMap and mAccessoryPreferenceMap is currently
     * scheduled.
     */
    @GuardedBy("mLock")
    private boolean mIsWriteSettingsScheduled;

    private static EventLogger sEventLogger;

    /**
     * A package of a user.
     */
    @Immutable
    private static class UserPackage {
        /** User */
        final @NonNull UserHandle user;

        /** Package name */
        final @NonNull String packageName;

        /**
         * Create a description of a per user package.
         *
         * @param packageName The name of the package
         * @param user The user
         */
        private UserPackage(@NonNull String packageName, @NonNull UserHandle user) {
            this.packageName = packageName;
            this.user = user;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof UserPackage)) {
                return false;
            } else {
                UserPackage other = (UserPackage)obj;

                return user.equals(other.user) && packageName.equals(other.packageName);
            }
        }

        @Override
        public int hashCode() {
            int result = user.hashCode();
            result = 31 * result + packageName.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return user.getIdentifier() + "/" + packageName;
        }

        public void dump(DualDumpOutputStream dump, String idName, long id) {
            long token = dump.start(idName, id);

            dump.write("user_id", UserPackageProto.USER_ID, user.getIdentifier());
            dump.write("package_name", UserPackageProto.PACKAGE_NAME, packageName);

            dump.end(token);
        }
    }

    private class MyPackageMonitor extends PackageMonitor {
        @Override
        public void onPackageAdded(String packageName, int uid) {
            if (!mUserManager.isSameProfileGroup(mParentUser.getIdentifier(),
                    UserHandle.getUserId(uid))) {
                return;
            }

            handlePackageAdded(new UserPackage(packageName, UserHandle.getUserHandleForUid(uid)));
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            if (!mUserManager.isSameProfileGroup(mParentUser.getIdentifier(),
                    UserHandle.getUserId(uid))) {
                return;
            }

            clearDefaults(packageName, UserHandle.getUserHandleForUid(uid));
        }
    }

    MyPackageMonitor mPackageMonitor = new MyPackageMonitor();

    private final UsbHandlerManager mUsbHandlerManager;

    private final MtpNotificationManager mMtpNotificationManager;

    /**
     * Create new settings manager for a profile group.
     *
     * @param context The context of the service
     * @param user The parent profile
     * @param settingsManager The settings manager of the service
     * @param usbResolveActivityManager The resovle activity manager of the service
     */
    public UsbProfileGroupSettingsManager(@NonNull Context context, @NonNull UserHandle user,
            @NonNull UsbSettingsManager settingsManager,
            @NonNull UsbHandlerManager usbResolveActivityManager) {
        if (DEBUG) Slog.v(TAG, "Creating settings for " + user);

        Context parentUserContext;
        try {
            parentUserContext = context.createPackageContextAsUser("android", 0, user);
        } catch (NameNotFoundException e) {
            throw new RuntimeException("Missing android package");
        }

        mContext = context;
        mPackageManager = context.getPackageManager();
        mActivityManager = context.getSystemService(ActivityManager.class);
        mSettingsManager = settingsManager;
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);

        mParentUser = user;
        mSettingsFile = new AtomicFile(new File(
                Environment.getUserSystemDirectory(user.getIdentifier()),
                "usb_device_manager.xml"), "usb-state");

        mDisablePermissionDialogs = context.getResources().getBoolean(
                com.android.internal.R.bool.config_disableUsbPermissionDialogs);

        synchronized (mLock) {
            if (UserHandle.SYSTEM.equals(user)) {
                upgradeSingleUserLocked();
            }
            readSettingsLocked();
        }

        mPackageMonitor.register(context, null, UserHandle.ALL, true);
        mMtpNotificationManager = new MtpNotificationManager(
                parentUserContext,
                device -> resolveActivity(createDeviceAttachedIntent(device),
                        device, false /* showMtpNotification */));

        mUsbHandlerManager = usbResolveActivityManager;

        sEventLogger = new EventLogger(DUMPSYS_LOG_BUFFER,
                "UsbProfileGroupSettingsManager activity");
    }

    /**
     * Unregister all broadcast receivers. Must be called explicitly before
     * object deletion.
     */
    public void unregisterReceivers() {
        mPackageMonitor.unregister();
        mMtpNotificationManager.unregister();
    }

    /**
     * Remove all defaults and denied packages for a user.
     *
     * @param userToRemove The user
     */
    void removeUser(@NonNull UserHandle userToRemove) {
        synchronized (mLock) {
            boolean needToPersist = false;
            Iterator<Map.Entry<DeviceFilter, UserPackage>> devicePreferenceIt = mDevicePreferenceMap
                    .entrySet().iterator();
            while (devicePreferenceIt.hasNext()) {
                Map.Entry<DeviceFilter, UserPackage> entry = devicePreferenceIt.next();

                if (entry.getValue().user.equals(userToRemove)) {
                    devicePreferenceIt.remove();
                    needToPersist = true;
                }
            }

            Iterator<Map.Entry<AccessoryFilter, UserPackage>> accessoryPreferenceIt =
                    mAccessoryPreferenceMap.entrySet().iterator();
            while (accessoryPreferenceIt.hasNext()) {
                Map.Entry<AccessoryFilter, UserPackage> entry = accessoryPreferenceIt.next();

                if (entry.getValue().user.equals(userToRemove)) {
                    accessoryPreferenceIt.remove();
                    needToPersist = true;
                }
            }

            int numEntries = mDevicePreferenceDeniedMap.size();
            for (int i = 0; i < numEntries; i++) {
                ArraySet<UserPackage> userPackages = mDevicePreferenceDeniedMap.valueAt(i);
                for (int j = userPackages.size() - 1; j >= 0; j--) {
                    if (userPackages.valueAt(j).user.equals(userToRemove)) {
                        userPackages.removeAt(j);
                        needToPersist = true;
                    }
                }
            }

            numEntries = mAccessoryPreferenceDeniedMap.size();
            for (int i = 0; i < numEntries; i++) {
                ArraySet<UserPackage> userPackages = mAccessoryPreferenceDeniedMap.valueAt(i);
                for (int j = userPackages.size() - 1; j >= 0; j--) {
                    if (userPackages.valueAt(j).user.equals(userToRemove)) {
                        userPackages.removeAt(j);
                        needToPersist = true;
                    }
                }
            }

            if (needToPersist) {
                scheduleWriteSettingsLocked();
            }
        }
    }

    private void readPreference(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        String packageName = null;

        // If not set, assume it to be the parent profile
        UserHandle user = mParentUser;

        int count = parser.getAttributeCount();
        for (int i = 0; i < count; i++) {
            if ("package".equals(parser.getAttributeName(i))) {
                packageName = parser.getAttributeValue(i);
            }
            if ("user".equals(parser.getAttributeName(i))) {
                // Might return null if user is not known anymore
                user = mUserManager
                        .getUserForSerialNumber(Integer.parseInt(parser.getAttributeValue(i)));
            }
        }

        XmlUtils.nextElement(parser);
        if ("usb-device".equals(parser.getName())) {
            DeviceFilter filter = DeviceFilter.read(parser);
            if (user != null) {
                mDevicePreferenceMap.put(filter, new UserPackage(packageName, user));
            }
        } else if ("usb-accessory".equals(parser.getName())) {
            AccessoryFilter filter = AccessoryFilter.read(parser);
            if (user != null) {
                mAccessoryPreferenceMap.put(filter, new UserPackage(packageName, user));
            }
        }
        XmlUtils.nextElement(parser);
    }

    private void readPreferenceDeniedList(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        if (!XmlUtils.nextElementWithin(parser, outerDepth)) {
            return;
        }

        if ("usb-device".equals(parser.getName())) {
            DeviceFilter filter = DeviceFilter.read(parser);
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if ("user-package".equals(parser.getName())) {
                    try {
                        int userId = XmlUtils.readIntAttribute(parser, "user");

                        String packageName = XmlUtils.readStringAttribute(parser, "package");
                        if (packageName == null) {
                            Slog.e(TAG, "Unable to parse package name");
                        }

                        ArraySet<UserPackage> set = mDevicePreferenceDeniedMap.get(filter);
                        if (set == null) {
                            set = new ArraySet<>();
                            mDevicePreferenceDeniedMap.put(filter, set);
                        }
                        set.add(new UserPackage(packageName, UserHandle.of(userId)));
                    } catch (ProtocolException e) {
                        Slog.e(TAG, "Unable to parse user id", e);
                    }
                }
            }
        } else if ("usb-accessory".equals(parser.getName())) {
            AccessoryFilter filter = AccessoryFilter.read(parser);

            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                if ("user-package".equals(parser.getName())) {
                    try {
                        int userId = XmlUtils.readIntAttribute(parser, "user");

                        String packageName = XmlUtils.readStringAttribute(parser, "package");
                        if (packageName == null) {
                            Slog.e(TAG, "Unable to parse package name");
                        }

                        ArraySet<UserPackage> set = mAccessoryPreferenceDeniedMap.get(filter);
                        if (set == null) {
                            set = new ArraySet<>();
                            mAccessoryPreferenceDeniedMap.put(filter, set);
                        }
                        set.add(new UserPackage(packageName, UserHandle.of(userId)));
                    } catch (ProtocolException e) {
                        Slog.e(TAG, "Unable to parse user id", e);
                    }
                }
            }
        }

        while (parser.getDepth() > outerDepth) {
            parser.nextTag(); // ignore unknown tags
        }
    }

    /**
     * Upgrade any single-user settings from {@link #sSingleUserSettingsFile}.
     * Should only be called by owner.
     */
    @GuardedBy("mLock")
    private void upgradeSingleUserLocked() {
        if (sSingleUserSettingsFile.exists()) {
            mDevicePreferenceMap.clear();
            mAccessoryPreferenceMap.clear();

            FileInputStream fis = null;
            try {
                fis = new FileInputStream(sSingleUserSettingsFile);
                TypedXmlPullParser parser = Xml.resolvePullParser(fis);

                XmlUtils.nextElement(parser);
                while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                    final String tagName = parser.getName();
                    if ("preference".equals(tagName)) {
                        readPreference(parser);
                    } else {
                        XmlUtils.nextElement(parser);
                    }
                }
            } catch (IOException | XmlPullParserException e) {
                Log.wtf(TAG, "Failed to read single-user settings", e);
            } finally {
                IoUtils.closeQuietly(fis);
            }

            scheduleWriteSettingsLocked();

            // Success or failure, we delete single-user file
            sSingleUserSettingsFile.delete();
        }
    }

    @GuardedBy("mLock")
    private void readSettingsLocked() {
        if (DEBUG) Slog.v(TAG, "readSettingsLocked()");

        mDevicePreferenceMap.clear();
        mAccessoryPreferenceMap.clear();

        FileInputStream stream = null;
        try {
            stream = mSettingsFile.openRead();
            TypedXmlPullParser parser = Xml.resolvePullParser(stream);

            XmlUtils.nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if ("preference".equals(tagName)) {
                    readPreference(parser);
                } else if ("preference-denied-list".equals(tagName)) {
                    readPreferenceDeniedList(parser);
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

    /**
     * Schedule a async task to persist {@link #mDevicePreferenceMap} and
     * {@link #mAccessoryPreferenceMap}. If a task is already scheduled but not completed, do
     * nothing as the currently scheduled one will do the work.
     * <p>Called with {@link #mLock} held.</p>
     * <p>In the uncommon case that the system crashes in between the scheduling and the write the
     * update is lost.</p>
     */
    @GuardedBy("mLock")
    private void scheduleWriteSettingsLocked() {
        if (mIsWriteSettingsScheduled) {
            return;
        } else {
            mIsWriteSettingsScheduled = true;
        }

        AsyncTask.execute(() -> {
            synchronized (mLock) {
                FileOutputStream fos = null;
                try {
                    fos = mSettingsFile.startWrite();

                    TypedXmlSerializer serializer = Xml.resolveSerializer(fos);
                    serializer.startDocument(null, true);
                    serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output",
                                    true);
                    serializer.startTag(null, "settings");

                    for (DeviceFilter filter : mDevicePreferenceMap.keySet()) {
                        serializer.startTag(null, "preference");
                        serializer.attribute(null, "package",
                                mDevicePreferenceMap.get(filter).packageName);
                        serializer.attribute(null, "user",
                                String.valueOf(getSerial(mDevicePreferenceMap.get(filter).user)));
                        filter.write(serializer);
                        serializer.endTag(null, "preference");
                    }

                    for (AccessoryFilter filter : mAccessoryPreferenceMap.keySet()) {
                        serializer.startTag(null, "preference");
                        serializer.attribute(null, "package",
                                mAccessoryPreferenceMap.get(filter).packageName);
                        serializer.attribute(null, "user", String.valueOf(
                                        getSerial(mAccessoryPreferenceMap.get(filter).user)));
                        filter.write(serializer);
                        serializer.endTag(null, "preference");
                    }

                    int numEntries = mDevicePreferenceDeniedMap.size();
                    for (int i = 0; i < numEntries; i++) {
                        DeviceFilter filter = mDevicePreferenceDeniedMap.keyAt(i);
                        ArraySet<UserPackage> userPackageSet = mDevicePreferenceDeniedMap
                                .valueAt(i);
                        serializer.startTag(null, "preference-denied-list");
                        filter.write(serializer);

                        int numUserPackages = userPackageSet.size();
                        for (int j = 0; j < numUserPackages; j++) {
                            UserPackage userPackage = userPackageSet.valueAt(j);
                            serializer.startTag(null, "user-package");
                            serializer.attribute(null, "user",
                                    String.valueOf(getSerial(userPackage.user)));
                            serializer.attribute(null, "package", userPackage.packageName);
                            serializer.endTag(null, "user-package");
                        }
                        serializer.endTag(null, "preference-denied-list");
                    }

                    numEntries = mAccessoryPreferenceDeniedMap.size();
                    for (int i = 0; i < numEntries; i++) {
                        AccessoryFilter filter = mAccessoryPreferenceDeniedMap.keyAt(i);
                        ArraySet<UserPackage> userPackageSet =
                                mAccessoryPreferenceDeniedMap.valueAt(i);
                        serializer.startTag(null, "preference-denied-list");
                        filter.write(serializer);

                        int numUserPackages = userPackageSet.size();
                        for (int j = 0; j < numUserPackages; j++) {
                            UserPackage userPackage = userPackageSet.valueAt(j);
                            serializer.startTag(null, "user-package");
                            serializer.attribute(null, "user",
                                    String.valueOf(getSerial(userPackage.user)));
                            serializer.attribute(null, "package", userPackage.packageName);
                            serializer.endTag(null, "user-package");
                        }
                        serializer.endTag(null, "preference-denied-list");
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

                mIsWriteSettingsScheduled = false;
            }
        });
    }

    /**
     * Get {@link DeviceFilter} for all devices an activity should be launched for.
     *
     * @param pm The package manager used to get the device filter files
     * @param info The {@link ResolveInfo} for the activity that can handle usb device attached
     *             events
     *
     * @return The list of {@link DeviceFilter} the activity should be called for or {@code null} if
     *         none
     */
    @Nullable
    static ArrayList<DeviceFilter> getDeviceFilters(@NonNull PackageManager pm,
            @NonNull ResolveInfo info) {
        ArrayList<DeviceFilter> filters = null;
        ActivityInfo ai = info.activityInfo;

        XmlResourceParser parser = null;
        try {
            parser = ai.loadXmlMetaData(pm, UsbManager.ACTION_USB_DEVICE_ATTACHED);
            if (parser == null) {
                Slog.w(TAG, "no meta-data for " + info);
                return null;
            }

            XmlUtils.nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if ("usb-device".equals(tagName)) {
                    if (filters == null) {
                        filters = new ArrayList<>(1);
                    }
                    filters.add(DeviceFilter.read(parser));
                }
                XmlUtils.nextElement(parser);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unable to load component info " + info.toString(), e);
        } finally {
            if (parser != null) parser.close();
        }
        return filters;
    }

    /**
     * Get {@link AccessoryFilter} for all accessories an activity should be launched for.
     *
     * @param pm The package manager used to get the accessory filter files
     * @param info The {@link ResolveInfo} for the activity that can handle usb accessory attached
     *             events
     *
     * @return The list of {@link AccessoryFilter} the activity should be called for or {@code null}
     *         if none
     */
    static @Nullable ArrayList<AccessoryFilter> getAccessoryFilters(@NonNull PackageManager pm,
            @NonNull ResolveInfo info) {
        ArrayList<AccessoryFilter> filters = null;
        ActivityInfo ai = info.activityInfo;

        XmlResourceParser parser = null;
        try {
            parser = ai.loadXmlMetaData(pm, UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
            if (parser == null) {
                Slog.w(TAG, "no meta-data for " + info);
                return null;
            }

            XmlUtils.nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if ("usb-accessory".equals(tagName)) {
                    if (filters == null) {
                        filters = new ArrayList<>(1);
                    }
                    filters.add(AccessoryFilter.read(parser));
                }
                XmlUtils.nextElement(parser);
            }
        } catch (Exception e) {
            Slog.w(TAG, "Unable to load component info " + info.toString(), e);
        } finally {
            if (parser != null) parser.close();
        }
        return filters;
    }

    // Checks to see if a package matches a device or accessory.
    // Only one of device and accessory should be non-null.
    private boolean packageMatchesLocked(ResolveInfo info, UsbDevice device,
            UsbAccessory accessory) {
        if (isForwardMatch(info)) {
            return true;
        }

        if (device != null) {
            ArrayList<DeviceFilter> deviceFilters = getDeviceFilters(mPackageManager, info);
            if (deviceFilters != null) {
                int numDeviceFilters = deviceFilters.size();
                for (int i = 0; i < numDeviceFilters; i++) {
                    if (deviceFilters.get(i).matches(device)) {
                        return true;
                    }
                }
            }
        }

        if (accessory != null) {
            ArrayList<AccessoryFilter> accessoryFilters = getAccessoryFilters(mPackageManager,
                    info);
            if (accessoryFilters != null) {
                int numAccessoryFilters = accessoryFilters.size();
                for (int i = 0; i < numAccessoryFilters; i++) {
                    if (accessoryFilters.get(i).matches(accessory)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Resolve all activities that match an intent for all profiles of this group.
     *
     * @param intent The intent to resolve
     *
     * @return The {@link ResolveInfo}s for all profiles of the group
     */
    private @NonNull ArrayList<ResolveInfo> queryIntentActivitiesForAllProfiles(
            @NonNull Intent intent) {
        List<UserInfo> profiles = mUserManager.getEnabledProfiles(mParentUser.getIdentifier());

        ArrayList<ResolveInfo> resolveInfos = new ArrayList<>();
        int numProfiles = profiles.size();
        for (int i = 0; i < numProfiles; i++) {
            resolveInfos.addAll(mSettingsManager.getSettingsForUser(profiles.get(i).id)
                    .queryIntentActivities(intent));
        }

        return resolveInfos;
    }

    /**
     * If this match used to forward the intent to another profile?
     *
     * @param match The match
     *
     * @return {@code true} iff this is such a forward match
     */
    private boolean isForwardMatch(@NonNull ResolveInfo match) {
        return match.getComponentInfo().name.equals(FORWARD_INTENT_TO_MANAGED_PROFILE);
    }

    /**
     * Only return those matches with the highest priority.
     *
     * @param matches All matches, some might have lower priority
     *
     * @return The matches with the highest priority
     */
    @NonNull
    private ArrayList<ResolveInfo> preferHighPriority(@NonNull ArrayList<ResolveInfo> matches) {
        SparseArray<ArrayList<ResolveInfo>> highestPriorityMatchesByUserId = new SparseArray<>();
        SparseIntArray highestPriorityByUserId = new SparseIntArray();
        ArrayList<ResolveInfo> forwardMatches = new ArrayList<>();

        // Create list of highest priority matches per user in highestPriorityMatchesByUserId
        int numMatches = matches.size();
        for (int matchNum = 0; matchNum < numMatches; matchNum++) {
            ResolveInfo match = matches.get(matchNum);

            // Unnecessary forward matches are filtered out later, hence collect them all to add
            // them below
            if (isForwardMatch(match)) {
                forwardMatches.add(match);
                continue;
            }

            // If this a previously unknown user?
            if (highestPriorityByUserId.indexOfKey(match.targetUserId) < 0) {
                highestPriorityByUserId.put(match.targetUserId, Integer.MIN_VALUE);
                highestPriorityMatchesByUserId.put(match.targetUserId, new ArrayList<>());
            }

            // Find current highest priority matches for the current user
            int highestPriority = highestPriorityByUserId.get(match.targetUserId);
            ArrayList<ResolveInfo> highestPriorityMatches = highestPriorityMatchesByUserId.get(
                    match.targetUserId);

            if (match.priority == highestPriority) {
                highestPriorityMatches.add(match);
            } else if (match.priority > highestPriority) {
                highestPriorityByUserId.put(match.targetUserId, match.priority);

                highestPriorityMatches.clear();
                highestPriorityMatches.add(match);
            }
        }

        // Combine all users (+ forward matches) back together. This means that all non-forward
        // matches have the same priority for a user. Matches for different users might have
        // different priority.
        ArrayList<ResolveInfo> combinedMatches = new ArrayList<>(forwardMatches);
        int numMatchArrays = highestPriorityMatchesByUserId.size();
        for (int matchArrayNum = 0; matchArrayNum < numMatchArrays; matchArrayNum++) {
            combinedMatches.addAll(highestPriorityMatchesByUserId.valueAt(matchArrayNum));
        }

        return combinedMatches;
    }

    /**
     * If there are no matches for a profile, remove the forward intent to this profile.
     *
     * @param rawMatches The matches that contain all forward intents
     *
     * @return The matches with the unnecessary forward intents removed
     */
    @NonNull private ArrayList<ResolveInfo> removeForwardIntentIfNotNeeded(
            @NonNull ArrayList<ResolveInfo> rawMatches) {
        final int numRawMatches = rawMatches.size();

        // The raw matches contain the activities that can be started but also the intents to
        // forward the intent to the other profile
        int numParentActivityMatches = 0;
        int numNonParentActivityMatches = 0;
        for (int i = 0; i < numRawMatches; i++) {
            final ResolveInfo rawMatch = rawMatches.get(i);
            if (!isForwardMatch(rawMatch)) {
                if (UserHandle.getUserHandleForUid(
                        rawMatch.activityInfo.applicationInfo.uid).equals(mParentUser)) {
                    numParentActivityMatches++;
                } else {
                    numNonParentActivityMatches++;
                }
            }
        }

        // If only one profile has activity matches, we need to remove all switch intents
        if (numParentActivityMatches == 0 || numNonParentActivityMatches == 0) {
            ArrayList<ResolveInfo> matches = new ArrayList<>(
                    numParentActivityMatches + numNonParentActivityMatches);

            for (int i = 0; i < numRawMatches; i++) {
                ResolveInfo rawMatch = rawMatches.get(i);
                if (!isForwardMatch(rawMatch)) {
                    matches.add(rawMatch);
                }
            }
            return matches;

        } else {
            return rawMatches;
        }
    }

    private ArrayList<ResolveInfo> getDeviceMatchesLocked(UsbDevice device, Intent intent) {
        ArrayList<ResolveInfo> matches = new ArrayList<>();
        List<ResolveInfo> resolveInfos = queryIntentActivitiesForAllProfiles(intent);
        int count = resolveInfos.size();
        for (int i = 0; i < count; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);
            if (packageMatchesLocked(resolveInfo, device, null)) {
                matches.add(resolveInfo);
            }
        }

        return removeForwardIntentIfNotNeeded(preferHighPriority(matches));
    }

    private ArrayList<ResolveInfo> getAccessoryMatchesLocked(
            UsbAccessory accessory, Intent intent) {
        ArrayList<ResolveInfo> matches = new ArrayList<>();
        List<ResolveInfo> resolveInfos = queryIntentActivitiesForAllProfiles(intent);
        int count = resolveInfos.size();
        for (int i = 0; i < count; i++) {
            ResolveInfo resolveInfo = resolveInfos.get(i);
            if (packageMatchesLocked(resolveInfo, null, accessory)) {
                matches.add(resolveInfo);
            }
        }

        return removeForwardIntentIfNotNeeded(preferHighPriority(matches));
    }

    public void deviceAttached(UsbDevice device) {
        final Intent intent = createDeviceAttachedIntent(device);

        // Send broadcast to running activities with registered intent
        mContext.sendBroadcastAsUser(intent, UserHandle.ALL);

        //resolving activities only if there is no foreground activity restricting it.
        if (!shouldRestrictOverlayActivities()) {
            resolveActivity(intent, device, true /* showMtpNotification */);
        }
    }

    private void resolveActivity(Intent intent, UsbDevice device, boolean showMtpNotification) {
        final ArrayList<ResolveInfo> matches;
        final ActivityInfo defaultActivity;
        synchronized (mLock) {
            matches = getDeviceMatchesLocked(device, intent);
            defaultActivity = getDefaultActivityLocked(
                    matches, mDevicePreferenceMap.get(new DeviceFilter(device)));
        }

        if (showMtpNotification && MtpNotificationManager.shouldShowNotification(
                mPackageManager, device) && defaultActivity == null) {
            // Show notification if the device is MTP storage.
            mMtpNotificationManager.showNotification(device);
            return;
        }

        // Start activity with registered intent
        resolveActivity(intent, matches, defaultActivity, device, null);
    }

    /**
     * @return true if the user has not finished the setup process or if there are any
     * foreground applications with MANAGE_USB permission and restrict_usb_overlay_activities
     * enabled in the manifest file.
     */
    private boolean shouldRestrictOverlayActivities() {

        if (!Flags.allowRestrictionOfOverlayActivities()) return false;

        if (Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                USER_SETUP_COMPLETE,
                /* defaultValue= */ 1,
                UserHandle.CURRENT.getIdentifier())
                == 0) {
            Slog.d(TAG, "restricting usb overlay activities as setup is not complete");
            return true;
        }

        List<ActivityManager.RunningAppProcessInfo> appProcessInfos = mActivityManager
                .getRunningAppProcesses();

        List<String> filteredAppProcessInfos = new ArrayList<>();
        boolean shouldRestrictOverlayActivities;

        //filtering out applications in foreground.
        for (ActivityManager.RunningAppProcessInfo processInfo : appProcessInfos) {
            if (processInfo.importance <= ActivityManager
                    .RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                filteredAppProcessInfos.addAll(List.of(processInfo.pkgList));
            }
        }

        if (DEBUG) Slog.d(TAG, "packages in foreground : " + filteredAppProcessInfos);

        List<String> packagesHoldingManageUsbPermission =
                mPackageManager.getPackagesHoldingPermissions(
                        new String[]{android.Manifest.permission.MANAGE_USB},
                        PackageManager.MATCH_SYSTEM_ONLY).stream()
                        .map(packageInfo -> packageInfo.packageName).collect(Collectors.toList());

        //retaining only packages that hold the required permission
        filteredAppProcessInfos.retainAll(packagesHoldingManageUsbPermission);

        if (DEBUG) {
            Slog.d(TAG, "packages in foreground with required permission : "
                    + filteredAppProcessInfos);
        }

        shouldRestrictOverlayActivities = filteredAppProcessInfos.stream().anyMatch(pkg -> {
            try {
                boolean restrictUsbOverlayActivitiesForPackage = mPackageManager
                        .getProperty(PROPERTY_RESTRICT_USB_OVERLAY_ACTIVITIES, pkg).getBoolean();

                if (restrictUsbOverlayActivitiesForPackage) {
                    Slog.d(TAG, "restricting usb overlay activities as package " + pkg
                            + " is in foreground");
                }
                return restrictUsbOverlayActivitiesForPackage;
            } catch (NameNotFoundException e) {
                if (DEBUG) {
                    Slog.d(TAG, "property PROPERTY_RESTRICT_USB_OVERLAY_ACTIVITIES "
                            + "not present for " + pkg);
                }
                return false;
            }
        });

        if (!shouldRestrictOverlayActivities) {
            Slog.d(TAG, "starting of usb overlay activities");
        }
        return shouldRestrictOverlayActivities;
    }

    public void deviceAttachedForFixedHandler(UsbDevice device, ComponentName component) {
        final Intent intent = createDeviceAttachedIntent(device);

        // Send broadcast to running activity with registered intent
        mContext.sendBroadcastAsUser(intent, UserHandle.of(ActivityManager.getCurrentUser()));

        ApplicationInfo appInfo;
        try {
            // Fixed handlers are always for parent user
            appInfo = mPackageManager.getApplicationInfoAsUser(component.getPackageName(), 0,
                    mParentUser.getIdentifier());
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "Default USB handling package (" + component.getPackageName()
                    + ") not found  for user " + mParentUser);
            return;
        }

        mSettingsManager.mUsbService.getPermissionsForUser(UserHandle.getUserId(appInfo.uid))
                .grantDevicePermission(device, appInfo.uid);

        Intent activityIntent = new Intent(intent);
        activityIntent.setComponent(component);
        try {
            mContext.startActivityAsUser(activityIntent, mParentUser);
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "unable to start activity " + activityIntent);
        }
    }

    /**
     * Remove notifications for a usb device.
     *
     * @param device The device the notifications are for.
     */
    void usbDeviceRemoved(@NonNull UsbDevice device) {
        mMtpNotificationManager.hideNotification(device.getDeviceId());
    }

    public void accessoryAttached(UsbAccessory accessory) {
        Intent intent = new Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);

        final ArrayList<ResolveInfo> matches;
        final ActivityInfo defaultActivity;
        synchronized (mLock) {
            matches = getAccessoryMatchesLocked(accessory, intent);
            defaultActivity = getDefaultActivityLocked(
                    matches, mAccessoryPreferenceMap.get(new AccessoryFilter(accessory)));
        }

        sEventLogger.enqueue(new EventLogger.StringEvent("accessoryAttached: " + intent));
        resolveActivity(intent, matches, defaultActivity, null, accessory);
    }

    /**
     * Start the appropriate package when an device/accessory got attached.
     *
     * @param intent The intent to start the package
     * @param matches The available resolutions of the intent
     * @param defaultActivity The default activity for the device (if set)
     * @param device The device if a device was attached
     * @param accessory The accessory if a device was attached
     */
    private void resolveActivity(@NonNull Intent intent, @NonNull ArrayList<ResolveInfo> matches,
            @Nullable ActivityInfo defaultActivity, @Nullable UsbDevice device,
            @Nullable UsbAccessory accessory) {
        // Remove all matches which are on the denied list
        ArraySet deniedPackages = null;
        if (device != null) {
            deniedPackages = mDevicePreferenceDeniedMap.get(new DeviceFilter(device));
        } else if (accessory != null) {
            deniedPackages = mAccessoryPreferenceDeniedMap.get(new AccessoryFilter(accessory));
        }
        if (deniedPackages != null) {
            for (int i = matches.size() - 1; i >= 0; i--) {
                ResolveInfo match = matches.get(i);
                String packageName = match.activityInfo.packageName;
                UserHandle user = UserHandle
                        .getUserHandleForUid(match.activityInfo.applicationInfo.uid);
                if (deniedPackages.contains(new UserPackage(packageName, user))) {
                    matches.remove(i);
                }
            }
        }

        // don't show the resolver activity if there are no choices available
        if (matches.size() == 0) {
            if (accessory != null) {
                mUsbHandlerManager.showUsbAccessoryUriActivity(accessory, mParentUser);
            }
            // do nothing
            return;
        }

        if (defaultActivity != null) {
            UsbUserPermissionManager defaultRIUserPermissions =
                    mSettingsManager.mUsbService.getPermissionsForUser(
                            UserHandle.getUserId(defaultActivity.applicationInfo.uid));
            // grant permission for default activity
            if (device != null) {
                defaultRIUserPermissions
                        .grantDevicePermission(device, defaultActivity.applicationInfo.uid);
            } else if (accessory != null) {
                defaultRIUserPermissions.grantAccessoryPermission(accessory,
                        defaultActivity.applicationInfo.uid);
            }

            // start default activity directly
            try {
                intent.setComponent(
                        new ComponentName(defaultActivity.packageName, defaultActivity.name));

                UserHandle user = UserHandle.getUserHandleForUid(
                        defaultActivity.applicationInfo.uid);
                mContext.startActivityAsUser(intent, user);
            } catch (ActivityNotFoundException e) {
                Slog.e(TAG, "startActivity failed", e);
            }
        } else {
            if (matches.size() == 1) {
                mUsbHandlerManager.confirmUsbHandler(matches.get(0), device, accessory);
            } else {
                mUsbHandlerManager.selectUsbHandler(matches, mParentUser, intent);
            }
        }
    }

    /**
     * Returns a default activity for matched ResolveInfo.
     * @param matches Resolved activities matched with connected device/accesary.
     * @param userPackage Default activity choosed by a user before. Should be null if no activity
     *     is choosed by a user.
     * @return Default activity
     */
    private @Nullable ActivityInfo getDefaultActivityLocked(
            @NonNull ArrayList<ResolveInfo> matches,
            @Nullable UserPackage userPackage) {
        if (userPackage != null) {
            // look for default activity
            for (final ResolveInfo info : matches) {
                if (info.activityInfo != null && userPackage.equals(
                        new UserPackage(info.activityInfo.packageName,
                                UserHandle.getUserHandleForUid(
                                        info.activityInfo.applicationInfo.uid)))) {
                    return info.activityInfo;
                }
            }
        }

        if (matches.size() == 1) {
            final ActivityInfo activityInfo = matches.get(0).activityInfo;
            if (activityInfo != null) {
                if (mDisablePermissionDialogs) {
                    return activityInfo;
                }
                // System apps are considered default unless there are other matches
                if (activityInfo.applicationInfo != null
                        && (activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM)
                                != 0) {
                    return activityInfo;
                }
            }
        }

        return null;
    }

    @GuardedBy("mLock")
    private boolean clearCompatibleMatchesLocked(@NonNull UserPackage userPackage,
            @NonNull DeviceFilter filter) {
        ArrayList<DeviceFilter> keysToRemove = new ArrayList<>();

        // The keys in mDevicePreferenceMap are filters that match devices very narrowly
        for (DeviceFilter device : mDevicePreferenceMap.keySet()) {
            if (filter.contains(device)) {
                UserPackage currentMatch = mDevicePreferenceMap.get(device);
                if (!currentMatch.equals(userPackage)) {
                    keysToRemove.add(device);
                }
            }
        }

        if (!keysToRemove.isEmpty()) {
            for (DeviceFilter keyToRemove : keysToRemove) {
                mDevicePreferenceMap.remove(keyToRemove);
            }
        }

        return !keysToRemove.isEmpty();
    }

    @GuardedBy("mLock")
    private boolean clearCompatibleMatchesLocked(@NonNull UserPackage userPackage,
            @NonNull AccessoryFilter filter) {
        ArrayList<AccessoryFilter> keysToRemove = new ArrayList<>();

        // The keys in mAccessoryPreferenceMap are filters that match accessories very narrowly
        for (AccessoryFilter accessory : mAccessoryPreferenceMap.keySet()) {
            if (filter.contains(accessory)) {
                UserPackage currentMatch = mAccessoryPreferenceMap.get(accessory);
                if (!currentMatch.equals(userPackage)) {
                    keysToRemove.add(accessory);
                }
            }
        }

        if (!keysToRemove.isEmpty()) {
            for (AccessoryFilter keyToRemove : keysToRemove) {
                mAccessoryPreferenceMap.remove(keyToRemove);
            }
        }

        return !keysToRemove.isEmpty();
    }

    @GuardedBy("mLock")
    private boolean handlePackageAddedLocked(UserPackage userPackage, ActivityInfo aInfo,
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
                    if (clearCompatibleMatchesLocked(userPackage, filter)) {
                        changed = true;
                    }
                }
                else if ("usb-accessory".equals(tagName)) {
                    AccessoryFilter filter = AccessoryFilter.read(parser);
                    if (clearCompatibleMatchesLocked(userPackage, filter)) {
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
    // If so, clear any preferences for matching devices/accessories.
    private void handlePackageAdded(@NonNull UserPackage userPackage) {
        synchronized (mLock) {
            PackageInfo info;
            boolean changed = false;

            try {
                info = mPackageManager.getPackageInfoAsUser(userPackage.packageName,
                        PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA,
                        userPackage.user.getIdentifier());
            } catch (NameNotFoundException e) {
                Slog.e(TAG, "handlePackageUpdate could not find package " + userPackage, e);
                return;
            }

            ActivityInfo[] activities = info.activities;
            if (activities == null) return;
            for (int i = 0; i < activities.length; i++) {
                // check for meta-data, both for devices and accessories
                if (handlePackageAddedLocked(userPackage, activities[i],
                        UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                    changed = true;
                }

                if (handlePackageAddedLocked(userPackage, activities[i],
                        UsbManager.ACTION_USB_ACCESSORY_ATTACHED)) {
                    changed = true;
                }
            }

            if (changed) {
                scheduleWriteSettingsLocked();
            }
        }
    }

    /**
     * Get the serial number for a user handle.
     *
     * @param user The user handle
     *
     * @return The serial number
     */
    private int getSerial(@NonNull UserHandle user) {
        return mUserManager.getUserSerialNumber(user.getIdentifier());
    }

    /**
     * Set a package as default handler for a device.
     *
     * @param device The device that should be handled by default
     * @param packageName The default handler package
     * @param user The user the package belongs to
     */
    void setDevicePackage(@NonNull UsbDevice device, @Nullable String packageName,
            @NonNull UserHandle user) {
        DeviceFilter filter = new DeviceFilter(device);
        boolean changed;
        synchronized (mLock) {
            if (packageName == null) {
                changed = (mDevicePreferenceMap.remove(filter) != null);
            } else {
                UserPackage userPackage = new UserPackage(packageName, user);

                changed = !userPackage.equals(mDevicePreferenceMap.get(filter));
                if (changed) {
                    mDevicePreferenceMap.put(filter, userPackage);
                }
            }
            if (changed) {
                scheduleWriteSettingsLocked();
            }
        }
    }

    /**
     * Add package to the denied for handling a device
     *
     * @param device the device to add to the denied
     * @param packageNames the packages to not become handler
     * @param user the user
     */
    void addDevicePackagesToDenied(@NonNull UsbDevice device, @NonNull String[] packageNames,
            @NonNull UserHandle user) {
        if (packageNames.length == 0) {
            return;
        }
        DeviceFilter filter = new DeviceFilter(device);

        synchronized (mLock) {
            ArraySet<UserPackage> userPackages;
            if (mDevicePreferenceDeniedMap.containsKey(filter)) {
                userPackages = mDevicePreferenceDeniedMap.get(filter);
            } else {
                userPackages = new ArraySet<>();
                mDevicePreferenceDeniedMap.put(filter, userPackages);
            }

            boolean shouldWrite = false;
            for (String packageName : packageNames) {
                UserPackage userPackage = new UserPackage(packageName, user);
                if (!userPackages.contains(userPackage)) {
                    userPackages.add(userPackage);
                    shouldWrite = true;
                }
            }

            if (shouldWrite) {
                scheduleWriteSettingsLocked();
            }
        }
    }

    /**
     * Add package to the denied for handling a accessory
     *
     * @param accessory the accessory to add to the denied
     * @param packageNames the packages to not become handler
     * @param user the user
     */
    void addAccessoryPackagesToDenied(@NonNull UsbAccessory accessory,
            @NonNull String[] packageNames, @NonNull UserHandle user) {
        if (packageNames.length == 0) {
            return;
        }
        AccessoryFilter filter = new AccessoryFilter(accessory);

        synchronized (mLock) {
            ArraySet<UserPackage> userPackages;
            if (mAccessoryPreferenceDeniedMap.containsKey(filter)) {
                userPackages = mAccessoryPreferenceDeniedMap.get(filter);
            } else {
                userPackages = new ArraySet<>();
                mAccessoryPreferenceDeniedMap.put(filter, userPackages);
            }

            boolean shouldWrite = false;
            for (String packageName : packageNames) {
                UserPackage userPackage = new UserPackage(packageName, user);
                if (!userPackages.contains(userPackage)) {
                    userPackages.add(userPackage);
                    shouldWrite = true;
                }
            }

            if (shouldWrite) {
                scheduleWriteSettingsLocked();
            }
        }
    }

    /**
     * Remove UserPackage from the denied for handling a device
     *
     * @param device the device to remove denied packages from
     * @param packageName the packages to remove
     * @param user the user
     */
    void removeDevicePackagesFromDenied(@NonNull UsbDevice device, @NonNull String[] packageNames,
            @NonNull UserHandle user) {
        DeviceFilter filter = new DeviceFilter(device);

        synchronized (mLock) {
            ArraySet<UserPackage> userPackages = mDevicePreferenceDeniedMap.get(filter);

            if (userPackages != null) {
                boolean shouldWrite = false;
                for (String packageName : packageNames) {
                    UserPackage userPackage = new UserPackage(packageName, user);

                    if (userPackages.contains(userPackage)) {
                        userPackages.remove(userPackage);
                        shouldWrite = true;

                        if (userPackages.size() == 0) {
                            mDevicePreferenceDeniedMap.remove(filter);
                            break;
                        }
                    }
                }

                if (shouldWrite) {
                    scheduleWriteSettingsLocked();
                }
            }
        }
    }

    /**
     * Remove UserPackage from the denied for handling a accessory
     *
     * @param accessory the accessory to remove denied packages from
     * @param packageName the packages to remove
     * @param user the user
     */
    void removeAccessoryPackagesFromDenied(@NonNull UsbAccessory accessory,
            @NonNull String[] packageNames, @NonNull UserHandle user) {
        AccessoryFilter filter = new AccessoryFilter(accessory);

        synchronized (mLock) {
            ArraySet<UserPackage> userPackages = mAccessoryPreferenceDeniedMap.get(filter);

            if (userPackages != null) {
                boolean shouldWrite = false;
                for (String packageName : packageNames) {
                    UserPackage userPackage = new UserPackage(packageName, user);

                    if (userPackages.contains(userPackage)) {
                        userPackages.remove(userPackage);
                        shouldWrite = true;

                        if (userPackages.size() == 0) {
                            mAccessoryPreferenceDeniedMap.remove(filter);
                            break;
                        }
                    }
                }

                if (shouldWrite) {
                    scheduleWriteSettingsLocked();
                }
            }
        }
    }

    /**
     * Set a package as default handler for a accessory.
     *
     * @param accessory The accessory that should be handled by default
     * @param packageName The default handler package
     * @param user The user the package belongs to
     */
    void setAccessoryPackage(@NonNull UsbAccessory accessory, @Nullable String packageName,
            @NonNull UserHandle user) {
        AccessoryFilter filter = new AccessoryFilter(accessory);
        boolean changed;
        synchronized (mLock) {
            if (packageName == null) {
                changed = (mAccessoryPreferenceMap.remove(filter) != null);
            } else {
                UserPackage userPackage = new UserPackage(packageName, user);

                changed = !userPackage.equals(mAccessoryPreferenceMap.get(filter));
                if (changed) {
                    mAccessoryPreferenceMap.put(filter, userPackage);
                }
            }
            if (changed) {
                scheduleWriteSettingsLocked();
            }
        }
    }

    /**
     * Check if a package has is the default handler for any usb device or accessory.
     *
     * @param packageName The package name
     * @param user The user the package belongs to
     *
     * @return {@code true} iff the package is default for any usb device or accessory
     */
    boolean hasDefaults(@NonNull String packageName, @NonNull UserHandle user) {
        UserPackage userPackage = new UserPackage(packageName, user);
        synchronized (mLock) {
            if (mDevicePreferenceMap.values().contains(userPackage)) return true;
            return mAccessoryPreferenceMap.values().contains(userPackage);
        }
    }

    /**
     * Clear defaults for a package from any preference.
     *
     * @param packageName The package to remove
     * @param user The user the package belongs to
     */
    void clearDefaults(@NonNull String packageName, @NonNull UserHandle user) {
        UserPackage userPackage = new UserPackage(packageName, user);

        synchronized (mLock) {
            if (clearPackageDefaultsLocked(userPackage)) {
                scheduleWriteSettingsLocked();
            }
        }
    }

    /**
     * Clear defaults for a package from any preference (does not persist).
     *
     * @param userPackage The package to remove
     *
     * @return {@code true} iff at least one preference was cleared
     */
    private boolean clearPackageDefaultsLocked(@NonNull UserPackage userPackage) {
        boolean cleared = false;
        synchronized (mLock) {
            if (mDevicePreferenceMap.containsValue(userPackage)) {
                // make a copy of the key set to avoid ConcurrentModificationException
                DeviceFilter[] keys = mDevicePreferenceMap.keySet().toArray(new DeviceFilter[0]);
                for (int i = 0; i < keys.length; i++) {
                    DeviceFilter key = keys[i];
                    if (userPackage.equals(mDevicePreferenceMap.get(key))) {
                        mDevicePreferenceMap.remove(key);
                        cleared = true;
                    }
                }
            }
            if (mAccessoryPreferenceMap.containsValue(userPackage)) {
                // make a copy of the key set to avoid ConcurrentModificationException
                AccessoryFilter[] keys =
                        mAccessoryPreferenceMap.keySet().toArray(new AccessoryFilter[0]);
                for (int i = 0; i < keys.length; i++) {
                    AccessoryFilter key = keys[i];
                    if (userPackage.equals(mAccessoryPreferenceMap.get(key))) {
                        mAccessoryPreferenceMap.remove(key);
                        cleared = true;
                    }
                }
            }
            return cleared;
        }
    }

    public void dump(@NonNull DualDumpOutputStream dump, @NonNull String idName, long id) {
        long token = dump.start(idName, id);

        synchronized (mLock) {
            dump.write("parent_user_id", UsbProfileGroupSettingsManagerProto.PARENT_USER_ID,
                    mParentUser.getIdentifier());

            for (DeviceFilter filter : mDevicePreferenceMap.keySet()) {
                long devicePrefToken = dump.start("device_preferences",
                        UsbProfileGroupSettingsManagerProto.DEVICE_PREFERENCES);

                filter.dump(dump, "filter", UsbSettingsDevicePreferenceProto.FILTER);

                mDevicePreferenceMap.get(filter).dump(dump, "user_package",
                        UsbSettingsDevicePreferenceProto.USER_PACKAGE);

                dump.end(devicePrefToken);
            }
            for (AccessoryFilter filter : mAccessoryPreferenceMap.keySet()) {
                long accessoryPrefToken = dump.start("accessory_preferences",
                        UsbProfileGroupSettingsManagerProto.ACCESSORY_PREFERENCES);

                filter.dump(dump, "filter", UsbSettingsAccessoryPreferenceProto.FILTER);

                mAccessoryPreferenceMap.get(filter).dump(dump, "user_package",
                        UsbSettingsAccessoryPreferenceProto.USER_PACKAGE);

                dump.end(accessoryPrefToken);
            }
        }

        sEventLogger.dump(new DualOutputStreamDumpSink(dump,
                UsbProfileGroupSettingsManagerProto.INTENT));
        dump.end(token);
    }

    private static Intent createDeviceAttachedIntent(UsbDevice device) {
        Intent intent = new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        intent.putExtra(UsbManager.EXTRA_DEVICE, device);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        return intent;
    }
}
