/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.SensorPrivacyManager.Sensors;
import android.hardware.SensorPrivacyManagerInternal;
import android.hardware.usb.AccessoryFilter;
import android.hardware.usb.DeviceFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Environment;
import android.os.Process;
import android.os.UserHandle;
import android.service.usb.UsbAccessoryPermissionProto;
import android.service.usb.UsbAccessoryPersistentPermissionProto;
import android.service.usb.UsbDevicePermissionProto;
import android.service.usb.UsbDevicePersistentPermissionProto;
import android.service.usb.UsbUidPermissionProto;
import android.service.usb.UsbUserPermissionsManagerProto;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.server.LocalServices;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * UsbUserPermissionManager manages usb device or accessory access permissions.
 *
 * @hide
 */
class UsbUserPermissionManager {
    private static final String TAG = UsbUserPermissionManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static final int SNET_EVENT_LOG_ID = 0x534e4554;

    @GuardedBy("mLock")
    /** Mapping of USB device name to list of UIDs with permissions for the device
     * Each entry lasts until device is disconnected*/
    private final ArrayMap<String, SparseBooleanArray> mDevicePermissionMap =
            new ArrayMap<>();
    @GuardedBy("mLock")
    /** Temporary mapping UsbAccessory to list of UIDs with permissions for the accessory
     * Each entry lasts until accessory is disconnected*/
    private final ArrayMap<UsbAccessory, SparseBooleanArray> mAccessoryPermissionMap =
            new ArrayMap<>();

    @GuardedBy("mLock")
    /** Maps USB device to list of UIDs with persistent permissions for the device*/
    private final ArrayMap<DeviceFilter, SparseBooleanArray>
            mDevicePersistentPermissionMap = new ArrayMap<>();
    @GuardedBy("mLock")
    /** Maps Usb Accessory to list of UIDs with persistent permissions for the accessory*/
    private final ArrayMap<AccessoryFilter, SparseBooleanArray>
            mAccessoryPersistentPermissionMap = new ArrayMap<>();

    private final Context mContext;
    private final UserHandle mUser;
    private final UsbUserSettingsManager mUsbUserSettingsManager;
    private final boolean mDisablePermissionDialogs;

    private final @NonNull AtomicFile mPermissionsFile;

    private final Object mLock = new Object();

    /**
     * If a async task to persist the mDevicePersistentPreferenceMap and
     * mAccessoryPersistentPreferenceMap is currently scheduled.
     */
    @GuardedBy("mLock")
    private boolean mIsCopyPermissionsScheduled;
    private final SensorPrivacyManagerInternal mSensorPrivacyMgrInternal;

    UsbUserPermissionManager(@NonNull Context context,
            @NonNull UsbUserSettingsManager usbUserSettingsManager) {
        mContext = context;
        mUser = context.getUser();
        mUsbUserSettingsManager = usbUserSettingsManager;
        mSensorPrivacyMgrInternal = LocalServices.getService(SensorPrivacyManagerInternal.class);
        mDisablePermissionDialogs = context.getResources().getBoolean(
                com.android.internal.R.bool.config_disableUsbPermissionDialogs);

        mPermissionsFile = new AtomicFile(new File(
                Environment.getUserSystemDirectory(mUser.getIdentifier()),
                "usb_permissions.xml"), "usb-permissions");
        synchronized (mLock) {
            readPermissionsLocked();
        }
    }

    /**
     * Removes access permissions of all packages for the USB accessory.
     *
     * @param accessory to remove permissions for
     */
    void removeAccessoryPermissions(@NonNull UsbAccessory accessory) {
        synchronized (mLock) {
            mAccessoryPermissionMap.remove(accessory);
        }
    }

    /**
     * Removes access permissions of all packages for the USB device.
     *
     * @param device to remove permissions for
     */
    void removeDevicePermissions(@NonNull UsbDevice device) {
        synchronized (mLock) {
            mDevicePermissionMap.remove(device.getDeviceName());
        }
    }

    /**
     * Grants permission for USB device without showing system dialog for package with uid.
     *
     * @param device to grant permission for
     * @param uid to grant permission for
     */
    void grantDevicePermission(@NonNull UsbDevice device, int uid) {
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

    /**
     * Grants permission for USB accessory without showing system dialog for package with uid.
     *
     * @param accessory to grant permission for
     * @param uid to grant permission for
     */
    void grantAccessoryPermission(@NonNull UsbAccessory accessory, int uid) {
        synchronized (mLock) {
            SparseBooleanArray uidList = mAccessoryPermissionMap.get(accessory);
            if (uidList == null) {
                uidList = new SparseBooleanArray(1);
                mAccessoryPermissionMap.put(accessory, uidList);
            }
            uidList.put(uid, true);
        }
    }

    /**
     * Returns true if package with uid has permission to access the device.
     *
     * @param device to check permission for
     * @param pid to check permission for
     * @param uid to check permission for
     * @return {@code true} if package with uid has permission
     */
    boolean hasPermission(@NonNull UsbDevice device, @NonNull String packageName, int pid,
            int uid) {
        if (device.getHasVideoCapture()) {
            boolean isCameraPrivacyEnabled = mSensorPrivacyMgrInternal.isSensorPrivacyEnabled(
                    UserHandle.getUserId(uid), Sensors.CAMERA);
            if (DEBUG) {
                Slog.d(TAG, "isCameraPrivacyEnabled: " + isCameraPrivacyEnabled);
            }
            if (isCameraPrivacyEnabled || !isCameraPermissionGranted(packageName, pid, uid)) {
                return false;
            }
        }
        // Only check for microphone privacy and not RECORD_AUDIO permission, because access to usb
        // camera device with audio recording capabilities may still be granted with a warning
        if (device.getHasAudioCapture() && mSensorPrivacyMgrInternal.isSensorPrivacyEnabled(
                UserHandle.getUserId(uid), Sensors.MICROPHONE)) {
            if (DEBUG) {
                Slog.d(TAG,
                        "Access to device with audio recording capabilities denied because "
                                + "microphone privacy is enabled.");
            }
            return false;
        }
        synchronized (mLock) {
            if (uid == Process.SYSTEM_UID || mDisablePermissionDialogs) {
                return true;
            }
            DeviceFilter filter = new DeviceFilter(device);
            SparseBooleanArray permissionsForDevice = mDevicePersistentPermissionMap.get(filter);
            if (permissionsForDevice != null) {
                int idx = permissionsForDevice.indexOfKey(uid);
                if (idx >= 0) {
                    return permissionsForDevice.valueAt(idx);
                }
            }
            SparseBooleanArray uidList = mDevicePermissionMap.get(device.getDeviceName());
            if (uidList == null) {
                return false;
            }
            return uidList.get(uid);
        }
    }

    /**
     * Returns true if caller has permission to access the accessory.
     *
     * @param accessory to check permission for
     * @param uid to check permission for
     * @return {@code true} if caller has permssion
     */
    boolean hasPermission(@NonNull UsbAccessory accessory, int pid, int uid) {
        synchronized (mLock) {
            if (uid == Process.SYSTEM_UID
                    || mDisablePermissionDialogs
                    || mContext.checkPermission(
                        android.Manifest.permission.MANAGE_USB, pid, uid)
                         == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return true;
            }
            AccessoryFilter filter = new AccessoryFilter(accessory);
            SparseBooleanArray permissionsForAccessory =
                    mAccessoryPersistentPermissionMap.get(filter);
            if (permissionsForAccessory != null) {
                int idx = permissionsForAccessory.indexOfKey(uid);
                if (idx >= 0) {
                    return permissionsForAccessory.valueAt(idx);
                }
            }
            SparseBooleanArray uidList = mAccessoryPermissionMap.get(accessory);
            if (uidList == null) {
                return false;
            }
            return uidList.get(uid);
        }
    }

    void setDevicePersistentPermission(@NonNull UsbDevice device, int uid, boolean isGranted) {

        boolean isChanged;
        DeviceFilter filter = new DeviceFilter(device);
        synchronized (mLock) {
            SparseBooleanArray permissionsForDevice = mDevicePersistentPermissionMap.get(filter);
            if (permissionsForDevice == null) {
                permissionsForDevice = new SparseBooleanArray();
                mDevicePersistentPermissionMap.put(filter, permissionsForDevice);
            }
            int idx = permissionsForDevice.indexOfKey(uid);
            if (idx >= 0) {
                isChanged = permissionsForDevice.valueAt(idx) != isGranted;
                permissionsForDevice.setValueAt(idx, isGranted);
            } else {
                isChanged = true;
                permissionsForDevice.put(uid, isGranted);
            }

            if (isChanged) {
                scheduleWritePermissionsLocked();
            }
        }
    }

    void setAccessoryPersistentPermission(@NonNull UsbAccessory accessory, int uid,
            boolean isGranted) {

        boolean isChanged;
        AccessoryFilter filter = new AccessoryFilter(accessory);
        synchronized (mLock) {
            SparseBooleanArray permissionsForAccessory =
                    mAccessoryPersistentPermissionMap.get(filter);
            if (permissionsForAccessory == null) {
                permissionsForAccessory = new SparseBooleanArray();
                mAccessoryPersistentPermissionMap.put(filter, permissionsForAccessory);
            }
            int idx = permissionsForAccessory.indexOfKey(uid);
            if (idx >= 0) {
                isChanged = permissionsForAccessory.valueAt(idx) != isGranted;
                permissionsForAccessory.setValueAt(idx, isGranted);
            } else {
                isChanged = true;
                permissionsForAccessory.put(uid, isGranted);
            }

            if (isChanged) {
                scheduleWritePermissionsLocked();
            }
        }
    }

    private void readPermission(@NonNull XmlPullParser parser) throws XmlPullParserException,
            IOException {
        int uid;
        boolean isGranted;

        try {
            uid = XmlUtils.readIntAttribute(parser, "uid");
        } catch (NumberFormatException e) {
            Slog.e(TAG, "error reading usb permission uid", e);
            XmlUtils.skipCurrentTag(parser);
            return;
        }

        // only use "true"/"false" as valid values
        String isGrantedString = parser.getAttributeValue(null, "granted");
        if (isGrantedString == null || !(isGrantedString.equals(Boolean.TRUE.toString())
                || isGrantedString.equals(Boolean.FALSE.toString()))) {
            Slog.e(TAG, "error reading usb permission granted state");
            XmlUtils.skipCurrentTag(parser);
            return;
        }
        isGranted = isGrantedString.equals(Boolean.TRUE.toString());
        XmlUtils.nextElement(parser);
        if ("usb-device".equals(parser.getName())) {
            DeviceFilter filter = DeviceFilter.read(parser);
            int idx = mDevicePersistentPermissionMap.indexOfKey(filter);
            if (idx >= 0) {
                SparseBooleanArray permissionsForDevice =
                        mDevicePersistentPermissionMap.valueAt(idx);
                permissionsForDevice.put(uid, isGranted);
            } else {
                SparseBooleanArray permissionsForDevice = new SparseBooleanArray();
                mDevicePersistentPermissionMap.put(filter, permissionsForDevice);
                permissionsForDevice.put(uid, isGranted);
            }
        } else if ("usb-accessory".equals(parser.getName())) {
            AccessoryFilter filter = AccessoryFilter.read(parser);
            int idx = mAccessoryPersistentPermissionMap.indexOfKey(filter);
            if (idx >= 0) {
                SparseBooleanArray permissionsForAccessory =
                        mAccessoryPersistentPermissionMap.valueAt(idx);
                permissionsForAccessory.put(uid, isGranted);
            } else {
                SparseBooleanArray permissionsForAccessory = new SparseBooleanArray();
                mAccessoryPersistentPermissionMap.put(filter, permissionsForAccessory);
                permissionsForAccessory.put(uid, isGranted);
            }
        }
    }

    @GuardedBy("mLock")
    private void readPermissionsLocked() {
        mDevicePersistentPermissionMap.clear();
        mAccessoryPersistentPermissionMap.clear();

        try (FileInputStream in = mPermissionsFile.openRead()) {
            TypedXmlPullParser parser = Xml.resolvePullParser(in);

            XmlUtils.nextElement(parser);
            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if ("permission".equals(tagName)) {
                    readPermission(parser);
                } else {
                    XmlUtils.nextElement(parser);
                }
            }
        } catch (FileNotFoundException e) {
            if (DEBUG) Slog.d(TAG, "usb permissions file not found");
        } catch (Exception e) {
            Slog.e(TAG, "error reading usb permissions file, deleting to start fresh", e);
            mPermissionsFile.delete();
        }
    }

    @GuardedBy("mLock")
    private void scheduleWritePermissionsLocked() {
        if (mIsCopyPermissionsScheduled) {
            return;
        }
        mIsCopyPermissionsScheduled = true;

        AsyncTask.execute(() -> {
            int numDevices;
            DeviceFilter[] devices;
            int[][] uidsForDevices;
            boolean[][] grantedValuesForDevices;

            int numAccessories;
            AccessoryFilter[] accessories;
            int[][] uidsForAccessories;
            boolean[][] grantedValuesForAccessories;

            synchronized (mLock) {
                // Copy the permission state so we can write outside of lock
                numDevices = mDevicePersistentPermissionMap.size();
                devices = new DeviceFilter[numDevices];
                uidsForDevices = new int[numDevices][];
                grantedValuesForDevices = new boolean[numDevices][];
                for (int deviceIdx = 0; deviceIdx < numDevices; deviceIdx++) {
                    devices[deviceIdx] =
                            new DeviceFilter(mDevicePersistentPermissionMap.keyAt(deviceIdx));
                    SparseBooleanArray permissions =
                            mDevicePersistentPermissionMap.valueAt(deviceIdx);
                    int numPermissions = permissions.size();
                    uidsForDevices[deviceIdx] = new int[numPermissions];
                    grantedValuesForDevices[deviceIdx] = new boolean[numPermissions];
                    for (int permissionIdx = 0; permissionIdx < numPermissions; permissionIdx++) {
                        uidsForDevices[deviceIdx][permissionIdx] = permissions.keyAt(permissionIdx);
                        grantedValuesForDevices[deviceIdx][permissionIdx] =
                                permissions.valueAt(permissionIdx);
                    }
                }

                numAccessories = mAccessoryPersistentPermissionMap.size();
                accessories = new AccessoryFilter[numAccessories];
                uidsForAccessories = new int[numAccessories][];
                grantedValuesForAccessories = new boolean[numAccessories][];
                for (int accessoryIdx = 0; accessoryIdx < numAccessories; accessoryIdx++) {
                    accessories[accessoryIdx] = new AccessoryFilter(
                                    mAccessoryPersistentPermissionMap.keyAt(accessoryIdx));
                    SparseBooleanArray permissions =
                            mAccessoryPersistentPermissionMap.valueAt(accessoryIdx);
                    int numPermissions = permissions.size();
                    uidsForAccessories[accessoryIdx] = new int[numPermissions];
                    grantedValuesForAccessories[accessoryIdx] = new boolean[numPermissions];
                    for (int permissionIdx = 0; permissionIdx < numPermissions; permissionIdx++) {
                        uidsForAccessories[accessoryIdx][permissionIdx] =
                                permissions.keyAt(permissionIdx);
                        grantedValuesForAccessories[accessoryIdx][permissionIdx] =
                                permissions.valueAt(permissionIdx);
                    }
                }
                mIsCopyPermissionsScheduled = false;
            }

            synchronized (mPermissionsFile) {
                FileOutputStream out = null;
                try {
                    out = mPermissionsFile.startWrite();
                    TypedXmlSerializer serializer = Xml.resolveSerializer(out);
                    serializer.startDocument(null, true);
                    serializer.startTag(null, "permissions");

                    for (int i = 0; i < numDevices; i++) {
                        int numPermissions = uidsForDevices[i].length;
                        for (int j = 0; j < numPermissions; j++) {
                            serializer.startTag(null, "permission");
                            serializer.attribute(null, "uid",
                                    Integer.toString(uidsForDevices[i][j]));
                            serializer.attribute(null, "granted",
                                    Boolean.toString(grantedValuesForDevices[i][j]));
                            devices[i].write(serializer);
                            serializer.endTag(null, "permission");
                        }
                    }

                    for (int i = 0; i < numAccessories; i++) {
                        int numPermissions = uidsForAccessories[i].length;
                        for (int j = 0; j < numPermissions; j++) {
                            serializer.startTag(null, "permission");
                            serializer.attribute(null, "uid",
                                    Integer.toString(uidsForAccessories[i][j]));
                            serializer.attribute(null, "granted",
                                    Boolean.toString(grantedValuesForDevices[i][j]));
                            accessories[i].write(serializer);
                            serializer.endTag(null, "permission");
                        }
                    }

                    serializer.endTag(null, "permissions");
                    serializer.endDocument();

                    mPermissionsFile.finishWrite(out);
                } catch (IOException e) {
                    Slog.e(TAG, "Failed to write permissions", e);
                    if (out != null) {
                        mPermissionsFile.failWrite(out);
                    }
                }
            }
        });
    }

    /**
     * Creates UI dialog to request permission for the given package to access the device
     * or accessory.
     *
     * @param device       The USB device attached
     * @param accessory    The USB accessory attached
     * @param canBeDefault Whether the calling pacakge can set as default handler
     *                     of the USB device or accessory
     * @param packageName  The package name of the calling package
     * @param uid          The uid of the calling package
     * @param userContext  The context to start the UI dialog
     * @param pi           PendingIntent for returning result
     */
    void requestPermissionDialog(@Nullable UsbDevice device,
            @Nullable UsbAccessory accessory,
            boolean canBeDefault,
            @NonNull String packageName,
            int uid,
            @NonNull Context userContext,
            @NonNull PendingIntent pi) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent();
            if (device != null) {
                intent.putExtra(UsbManager.EXTRA_DEVICE, device);
            } else {
                intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            }
            intent.putExtra(Intent.EXTRA_INTENT, pi);
            intent.putExtra(Intent.EXTRA_UID, uid);
            intent.putExtra(UsbManager.EXTRA_CAN_BE_DEFAULT, canBeDefault);
            intent.putExtra(UsbManager.EXTRA_PACKAGE, packageName);
            intent.setComponent(
                    ComponentName.unflattenFromString(userContext.getResources().getString(
                            com.android.internal.R.string.config_usbPermissionActivity)));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            userContext.startActivityAsUser(intent, mUser);
        } catch (ActivityNotFoundException e) {
            Slog.e(TAG, "unable to start UsbPermissionActivity");
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void dump(@NonNull DualDumpOutputStream dump, String idName, long id) {
        long token = dump.start(idName, id);
        synchronized (mLock) {
            dump.write("user_id", UsbUserPermissionsManagerProto.USER_ID, mUser.getIdentifier());
            int numMappings = mDevicePermissionMap.size();
            for (int mappingsIdx = 0; mappingsIdx < numMappings; mappingsIdx++) {
                String deviceName = mDevicePermissionMap.keyAt(mappingsIdx);
                long devicePermissionToken = dump.start("device_permissions",
                        UsbUserPermissionsManagerProto.DEVICE_PERMISSIONS);

                dump.write("device_name", UsbDevicePermissionProto.DEVICE_NAME, deviceName);

                SparseBooleanArray uidList = mDevicePermissionMap.valueAt(mappingsIdx);
                int numUids = uidList.size();
                for (int uidsIdx = 0; uidsIdx < numUids; uidsIdx++) {
                    dump.write("uids", UsbDevicePermissionProto.UIDS, uidList.keyAt(uidsIdx));
                }

                dump.end(devicePermissionToken);
            }

            numMappings = mAccessoryPermissionMap.size();
            for (int mappingsIdx = 0; mappingsIdx < numMappings; ++mappingsIdx) {
                UsbAccessory accessory = mAccessoryPermissionMap.keyAt(mappingsIdx);
                long accessoryPermissionToken = dump.start("accessory_permissions",
                        UsbUserPermissionsManagerProto.ACCESSORY_PERMISSIONS);

                dump.write("accessory_description",
                        UsbAccessoryPermissionProto.ACCESSORY_DESCRIPTION,
                        accessory.getDescription());

                SparseBooleanArray uidList = mAccessoryPermissionMap.valueAt(mappingsIdx);
                int numUids = uidList.size();
                for (int uidsIdx = 0; uidsIdx < numUids; uidsIdx++) {
                    dump.write("uids", UsbAccessoryPermissionProto.UIDS, uidList.keyAt(uidsIdx));
                }

                dump.end(accessoryPermissionToken);
            }

            numMappings = mDevicePersistentPermissionMap.size();
            for (int mappingsIdx = 0; mappingsIdx < numMappings; mappingsIdx++) {
                DeviceFilter filter = mDevicePersistentPermissionMap.keyAt(mappingsIdx);
                long devicePermissionToken = dump.start("device_persistent_permissions",
                        UsbUserPermissionsManagerProto.DEVICE_PERSISTENT_PERMISSIONS);
                filter.dump(dump, "device",
                        UsbDevicePersistentPermissionProto.DEVICE_FILTER);
                SparseBooleanArray permissions =
                        mDevicePersistentPermissionMap.valueAt(mappingsIdx);
                int numPermissions = permissions.size();
                for (int permissionsIdx = 0; permissionsIdx < numPermissions; permissionsIdx++) {
                    long uidPermissionToken = dump.start("uid_permission",
                            UsbDevicePersistentPermissionProto.PERMISSION_VALUES);
                    dump.write("uid", UsbUidPermissionProto.UID, permissions.keyAt(permissionsIdx));
                    dump.write("is_granted",
                            UsbUidPermissionProto.IS_GRANTED, permissions.valueAt(permissionsIdx));
                    dump.end(uidPermissionToken);
                }
                dump.end(devicePermissionToken);
            }

            numMappings = mAccessoryPersistentPermissionMap.size();
            for (int mappingsIdx = 0; mappingsIdx < numMappings; mappingsIdx++) {
                AccessoryFilter filter = mAccessoryPersistentPermissionMap.keyAt(mappingsIdx);
                long accessoryPermissionToken = dump.start("accessory_persistent_permissions",
                        UsbUserPermissionsManagerProto.ACCESSORY_PERSISTENT_PERMISSIONS);
                filter.dump(dump, "accessory",
                        UsbAccessoryPersistentPermissionProto.ACCESSORY_FILTER);
                SparseBooleanArray permissions =
                        mAccessoryPersistentPermissionMap.valueAt(mappingsIdx);
                int numPermissions = permissions.size();
                for (int permissionsIdx = 0; permissionsIdx < numPermissions; permissionsIdx++) {
                    long uidPermissionToken = dump.start("uid_permission",
                            UsbAccessoryPersistentPermissionProto.PERMISSION_VALUES);
                    dump.write("uid", UsbUidPermissionProto.UID, permissions.keyAt(permissionsIdx));
                    dump.write("is_granted",
                            UsbUidPermissionProto.IS_GRANTED, permissions.valueAt(permissionsIdx));
                    dump.end(uidPermissionToken);
                }
                dump.end(accessoryPermissionToken);
            }
        }
        dump.end(token);
    }

    /**
     * Check for camera permission of the calling process.
     *
     * @param packageName Package name of the caller.
     * @param pid         Linux pid of the calling process.
     * @param uid         Linux uid of the calling process.
     * @return True in case camera permission is available, False otherwise.
     */
    private boolean isCameraPermissionGranted(String packageName, int pid, int uid) {
        int targetSdkVersion = android.os.Build.VERSION_CODES.P;
        try {
            ApplicationInfo aInfo = mContext.getPackageManager().getApplicationInfo(packageName, 0);
            // compare uid with packageName to foil apps pretending to be someone else
            if (aInfo.uid != uid) {
                Slog.i(TAG, "Package " + packageName + " does not match caller's uid " + uid);
                return false;
            }
            targetSdkVersion = aInfo.targetSdkVersion;
        } catch (PackageManager.NameNotFoundException e) {
            Slog.i(TAG, "Package not found, likely due to invalid package name!");
            return false;
        }

        if (targetSdkVersion >= android.os.Build.VERSION_CODES.P) {
            int allowed = mContext.checkPermission(android.Manifest.permission.CAMERA, pid, uid);
            if (android.content.pm.PackageManager.PERMISSION_DENIED == allowed) {
                Slog.i(TAG, "Camera permission required for USB video class devices");
                return false;
            }
        }

        return true;
    }

    public void checkPermission(UsbDevice device, String packageName, int pid, int uid) {
        if (!hasPermission(device, packageName, pid, uid)) {
            throw new SecurityException("User has not given " + uid + "/" + packageName
                    + " permission to access device " + device.getDeviceName());
        }
    }

    public void checkPermission(UsbAccessory accessory, int pid, int uid) {
        if (!hasPermission(accessory, pid, uid)) {
            throw new SecurityException("User has not given " + uid + " permission to accessory "
                    + accessory);
        }
    }

    private void requestPermissionDialog(@Nullable UsbDevice device,
            @Nullable UsbAccessory accessory,
            boolean canBeDefault,
            String packageName,
            PendingIntent pi,
            int uid) {
        boolean throwException = false;

        // compare uid with packageName to foil apps pretending to be someone else
        try {
            ApplicationInfo aInfo = mContext.getPackageManager().getApplicationInfo(packageName, 0);
            if (aInfo.uid != uid) {
                Slog.w(TAG, "package " + packageName
                        + " does not match caller's uid " + uid);
                EventLog.writeEvent(SNET_EVENT_LOG_ID, "180104273", -1, "");
                throwException = true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            throwException = true;
        } finally {
            if (throwException)
                throw new IllegalArgumentException("package " + packageName + " not found");
        }

        requestPermissionDialog(device, accessory, canBeDefault, packageName, uid, mContext, pi);
    }

    public void requestPermission(UsbDevice device, String packageName, PendingIntent pi, int pid,
            int uid) {
        Intent intent = new Intent();

        // respond immediately if permission has already been granted
        if (hasPermission(device, packageName, pid, uid)) {
            intent.putExtra(UsbManager.EXTRA_DEVICE, device);
            intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true);
            try {
                pi.send(mContext, 0, intent);
            } catch (PendingIntent.CanceledException e) {
                if (DEBUG) Slog.d(TAG, "requestPermission PendingIntent was cancelled");
            }
            return;
        }
        // If the app doesn't have camera permission do not request permission to the USB device.
        // Note that if the USB camera also has a microphone, a warning will be shown to the user if
        // the app doesn't have RECORD_AUDIO permission.
        if (device.getHasVideoCapture()) {
            if (!isCameraPermissionGranted(packageName, pid, uid)) {
                intent.putExtra(UsbManager.EXTRA_DEVICE, device);
                intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                try {
                    pi.send(mContext, 0, intent);
                } catch (PendingIntent.CanceledException e) {
                    if (DEBUG) Slog.d(TAG, "requestPermission PendingIntent was cancelled");
                }
                return;
            }
        }

        requestPermissionDialog(device, null,
                mUsbUserSettingsManager.canBeDefault(device, packageName), packageName, pi, uid);
    }

    public void requestPermission(UsbAccessory accessory, String packageName, PendingIntent pi,
            int pid, int uid) {
        // respond immediately if permission has already been granted
        if (hasPermission(accessory, pid, uid)) {
            Intent intent = new Intent();
            intent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            intent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true);
            try {
                pi.send(mContext, 0, intent);
            } catch (PendingIntent.CanceledException e) {
                if (DEBUG) Slog.d(TAG, "requestPermission PendingIntent was cancelled");
            }
            return;
        }

        requestPermissionDialog(null, accessory,
                mUsbUserSettingsManager.canBeDefault(accessory, packageName), packageName, pi, uid);
    }
}
