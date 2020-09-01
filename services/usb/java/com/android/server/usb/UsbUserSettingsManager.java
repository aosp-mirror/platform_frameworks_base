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

import static com.android.internal.util.dump.DumpUtils.writeComponentName;
import static com.android.server.usb.UsbProfileGroupSettingsManager.getAccessoryFilters;
import static com.android.server.usb.UsbProfileGroupSettingsManager.getDeviceFilters;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.XmlResourceParser;
import android.hardware.usb.AccessoryFilter;
import android.hardware.usb.DeviceFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.UserHandle;
import android.service.usb.UsbAccessoryAttachedActivities;
import android.service.usb.UsbDeviceAttachedActivities;
import android.service.usb.UsbUserSettingsManagerProto;
import android.util.Slog;

import com.android.internal.util.XmlUtils;
import com.android.internal.util.dump.DualDumpOutputStream;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.List;

class UsbUserSettingsManager {
    private static final String TAG = UsbUserSettingsManager.class.getSimpleName();
    private static final boolean DEBUG = false;

    private final UserHandle mUser;

    private final Context mUserContext;
    private final PackageManager mPackageManager;

    private final Object mLock = new Object();

    UsbUserSettingsManager(Context context, UserHandle user) {
        if (DEBUG) Slog.v(TAG, "Creating settings for " + user);

        try {
            mUserContext = context.createPackageContextAsUser("android", 0, user);
        } catch (NameNotFoundException e) {
            throw new RuntimeException("Missing android package");
        }

        mPackageManager = mUserContext.getPackageManager();

        mUser = user;
    }

    /**
     * Get all activities that can handle the device/accessory attached intent.
     *
     * @param intent The intent to handle
     *
     * @return The resolve infos of the activities that can handle the intent
     */
    List<ResolveInfo> queryIntentActivities(@NonNull Intent intent) {
        return mPackageManager.queryIntentActivitiesAsUser(intent, PackageManager.GET_META_DATA,
                mUser.getIdentifier());
    }

    /**
     * Can the app be the default for the USB device. I.e. can the app be launched by default if
     * the device is plugged in.
     *
     * @param device The device the app would be default for
     * @param packageName The package name of the app
     *
     * @return {@code true} if the app can be default
     */
    boolean canBeDefault(@NonNull UsbDevice device, String packageName) {
        ActivityInfo[] activities = getPackageActivities(packageName);
        if (activities != null) {
            int numActivities = activities.length;
            for (int i = 0; i < numActivities; i++) {
                ActivityInfo activityInfo = activities[i];

                try (XmlResourceParser parser = activityInfo.loadXmlMetaData(mPackageManager,
                        UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                    if (parser == null) {
                        continue;
                    }

                    XmlUtils.nextElement(parser);
                    while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                        if ("usb-device".equals(parser.getName())) {
                            DeviceFilter filter = DeviceFilter.read(parser);
                            if (filter.matches(device)) {
                                return true;
                            }
                        }

                        XmlUtils.nextElement(parser);
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Unable to load component info " + activityInfo.toString(), e);
                }
            }
        }

        return false;
    }

    /**
     * Can the app be the default for the USB accessory. I.e. can the app be launched by default if
     * the accessory is plugged in.
     *
     * @param accessory The accessory the app would be default for
     * @param packageName The package name of the app
     *
     * @return {@code true} if the app can be default
     */
    boolean canBeDefault(@NonNull UsbAccessory accessory, String packageName) {
        ActivityInfo[] activities = getPackageActivities(packageName);
        if (activities != null) {
            int numActivities = activities.length;
            for (int i = 0; i < numActivities; i++) {
                ActivityInfo activityInfo = activities[i];

                try (XmlResourceParser parser = activityInfo.loadXmlMetaData(mPackageManager,
                        UsbManager.ACTION_USB_ACCESSORY_ATTACHED)) {
                    if (parser == null) {
                        continue;
                    }

                    XmlUtils.nextElement(parser);
                    while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                        if ("usb-accessory".equals(parser.getName())) {
                            AccessoryFilter filter = AccessoryFilter.read(parser);
                            if (filter.matches(accessory)) {
                                return true;
                            }
                        }

                        XmlUtils.nextElement(parser);
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Unable to load component info " + activityInfo.toString(), e);
                }
            }
        }

        return false;
    }

    private ActivityInfo[] getPackageActivities(String packageName) {
        try {
            PackageInfo packageInfo = mPackageManager.getPackageInfo(packageName,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
            return packageInfo.activities;
        } catch (PackageManager.NameNotFoundException e) {
            // ignore
        }
        return null;
    }

    public void dump(@NonNull DualDumpOutputStream dump, @NonNull String idName, long id) {
        long token = dump.start(idName, id);

        synchronized (mLock) {
            dump.write("user_id", UsbUserSettingsManagerProto.USER_ID, mUser.getIdentifier());

            List<ResolveInfo> deviceAttachedActivities = queryIntentActivities(
                    new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED));
            int numDeviceAttachedActivities = deviceAttachedActivities.size();
            for (int activityNum = 0; activityNum < numDeviceAttachedActivities; activityNum++) {
                ResolveInfo deviceAttachedActivity = deviceAttachedActivities.get(activityNum);

                long deviceAttachedActivityToken = dump.start("device_attached_activities",
                        UsbUserSettingsManagerProto.DEVICE_ATTACHED_ACTIVITIES);

                writeComponentName(dump, "activity", UsbDeviceAttachedActivities.ACTIVITY,
                        new ComponentName(deviceAttachedActivity.activityInfo.packageName,
                                deviceAttachedActivity.activityInfo.name));

                ArrayList<DeviceFilter> deviceFilters = getDeviceFilters(mPackageManager,
                        deviceAttachedActivity);
                if (deviceFilters != null) {
                    int numDeviceFilters = deviceFilters.size();
                    for (int filterNum = 0; filterNum < numDeviceFilters; filterNum++) {
                        deviceFilters.get(filterNum).dump(dump, "filters",
                                UsbDeviceAttachedActivities.FILTERS);
                    }
                }

                dump.end(deviceAttachedActivityToken);
            }

            List<ResolveInfo> accessoryAttachedActivities =
                    queryIntentActivities(new Intent(UsbManager.ACTION_USB_ACCESSORY_ATTACHED));
            int numAccessoryAttachedActivities = accessoryAttachedActivities.size();
            for (int activityNum = 0; activityNum < numAccessoryAttachedActivities; activityNum++) {
                ResolveInfo accessoryAttachedActivity =
                        accessoryAttachedActivities.get(activityNum);

                long accessoryAttachedActivityToken = dump.start("accessory_attached_activities",
                        UsbUserSettingsManagerProto.ACCESSORY_ATTACHED_ACTIVITIES);

                writeComponentName(dump, "activity", UsbAccessoryAttachedActivities.ACTIVITY,
                        new ComponentName(accessoryAttachedActivity.activityInfo.packageName,
                                accessoryAttachedActivity.activityInfo.name));

                ArrayList<AccessoryFilter> accessoryFilters = getAccessoryFilters(mPackageManager,
                        accessoryAttachedActivity);
                if (accessoryFilters != null) {
                    int numAccessoryFilters = accessoryFilters.size();
                    for (int filterNum = 0; filterNum < numAccessoryFilters; filterNum++) {
                        accessoryFilters.get(filterNum).dump(dump, "filters",
                                UsbAccessoryAttachedActivities.FILTERS);
                    }
                }

                dump.end(accessoryAttachedActivityToken);
            }
        }

        dump.end(token);
    }
}
