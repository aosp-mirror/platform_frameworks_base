/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.packageinstaller.permission.utils;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.res.Resources;
import android.location.ILocationManager;
import android.location.LocationManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;

import com.android.packageinstaller.R;

import java.util.ArrayList;

public class LocationUtils {

    public static final String LOCATION_PERMISSION = Manifest.permission_group.LOCATION;

    public static void showLocationDialog(final Context context, CharSequence label) {
        new AlertDialog.Builder(context)
                .setIcon(R.drawable.ic_dialog_alert_material)
                .setTitle(android.R.string.dialog_alert_title)
                .setMessage(context.getString(R.string.location_warning, label))
                .setNegativeButton(R.string.ok, null)
                .setPositiveButton(R.string.location_settings, new OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        context.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    }
                })
                .show();
    }

    public static boolean isLocationEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF;
    }

    public static boolean isLocationGroupAndProvider(String groupName, String packageName) {
        return LOCATION_PERMISSION.equals(groupName) && isNetworkLocationProvider(packageName);
    }

    private static boolean isNetworkLocationProvider(String packageName) {
        ILocationManager locationService = ILocationManager.Stub.asInterface(
                ServiceManager.getService(Context.LOCATION_SERVICE));
        try {
            return packageName.equals(locationService.getNetworkProviderPackage());
        } catch (RemoteException e) {
            return false;
        }
    }
}
