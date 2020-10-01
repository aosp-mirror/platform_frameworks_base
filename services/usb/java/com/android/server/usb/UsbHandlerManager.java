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
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.UserHandle;
import android.util.Slog;

import java.util.ArrayList;

/**
 * UsbResolveActivityManager creates UI dialogs for user to pick or confirm handler for
 * usb attach event.
 *
 * @hide
 */
class UsbHandlerManager {
    private static final String LOG_TAG = UsbHandlerManager.class.getSimpleName();

    private final Context mContext;

    UsbHandlerManager(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Shows dialog to user to allow them to optionally visit that URL for more
     * information or software downloads if the attached USB accessory has a valid
     * URL associated with it.
     *
     * @param accessory The accessory to confirm in the UI dialog
     * @param user The user to start the UI dialog
     */
    void showUsbAccessoryUriActivity(@NonNull UsbAccessory accessory,
            @NonNull UserHandle user) {
        String uri = accessory.getUri();
        if (uri != null && uri.length() > 0) {
            // display URI to user
            Intent dialogIntent = createDialogIntent();
            dialogIntent.setComponent(ComponentName.unflattenFromString(
                    mContext.getResources().getString(
                            com.android.internal.R.string.config_usbAccessoryUriActivity)));
            dialogIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
            dialogIntent.putExtra("uri", uri);
            try {
                mContext.startActivityAsUser(dialogIntent, user);
            } catch (ActivityNotFoundException e) {
                Slog.e(LOG_TAG, "unable to start UsbAccessoryUriActivity");
            }
        }
    }

    /**
     * Shows dialog to user to confirm the package to start when the USB device
     * or accessory is attached and there is only one package claims to handle this
     * USB device or accessory.
     *
     * @param rInfo The ResolveInfo of the package to confirm in the UI dialog
     * @param device The USB device to confirm
     * @param accessory The USB accessory to confirm
     */
    void confirmUsbHandler(@NonNull ResolveInfo rInfo, @Nullable UsbDevice device,
            @Nullable UsbAccessory accessory) {
        Intent resolverIntent = createDialogIntent();
        // start UsbConfirmActivity if there is only one choice
        resolverIntent.setComponent(ComponentName.unflattenFromString(
                mContext.getResources().getString(
                        com.android.internal.R.string.config_usbConfirmActivity)));
        resolverIntent.putExtra("rinfo", rInfo);
        UserHandle user =
                UserHandle.getUserHandleForUid(rInfo.activityInfo.applicationInfo.uid);

        if (device != null) {
            resolverIntent.putExtra(UsbManager.EXTRA_DEVICE, device);
        } else {
            resolverIntent.putExtra(UsbManager.EXTRA_ACCESSORY, accessory);
        }

        try {
            mContext.startActivityAsUser(resolverIntent, user);
        } catch (ActivityNotFoundException e) {
            Slog.e(LOG_TAG, "unable to start activity " + resolverIntent, e);
        }
    }

    /**
     * Shows dialog to user to select the package to start when the USB device
     * or accessory is attached and there are more than one package claim to handle this
     * USB device or accessory.
     *
     * @param matches The available resolutions of the intent
     * @param user The user to start UI dialog
     * @param intent The intent to start the UI dialog
     */
    void selectUsbHandler(@NonNull ArrayList<ResolveInfo> matches,
            @NonNull UserHandle user, @NonNull Intent intent) {
        Intent resolverIntent = createDialogIntent();
        resolverIntent.setComponent(ComponentName.unflattenFromString(
                mContext.getResources().getString(
                        com.android.internal.R.string.config_usbResolverActivity)));
        resolverIntent.putParcelableArrayListExtra("rlist", matches);
        resolverIntent.putExtra(Intent.EXTRA_INTENT, intent);

        try {
            mContext.startActivityAsUser(resolverIntent, user);
        } catch (ActivityNotFoundException e) {
            Slog.e(LOG_TAG, "unable to start activity " + resolverIntent, e);
        }
    }

    private Intent createDialogIntent() {
        Intent intent = new Intent();
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
