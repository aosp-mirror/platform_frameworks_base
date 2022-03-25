/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.settingslib.users;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.MediaStore;

/**
 * Utility class that contains helper methods to determine if the current user has permission and
 * the device is in a proper state to start an activity for a given action.
 */
public class PhotoCapabilityUtils {

    /**
     * Check if the current user can perform any activity for
     * android.media.action.IMAGE_CAPTURE action.
     */
    public static boolean canTakePhoto(Context context) {
        return context.getPackageManager().queryIntentActivities(
                new Intent(MediaStore.ACTION_IMAGE_CAPTURE),
                PackageManager.MATCH_DEFAULT_ONLY).size() > 0;
    }

    /**
     * Check if the current user can perform any activity for
     * ACTION_PICK_IMAGES action for images.
     * Returns false if the device is currently locked and
     * requires a PIN, pattern or password to unlock.
     */
    public static boolean canChoosePhoto(Context context) {
        Intent intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
        intent.setType("image/*");
        boolean canPerformActivityForGetImage =
                context.getPackageManager().queryIntentActivities(intent, 0).size() > 0;
        // on locked device we can't access the images
        return canPerformActivityForGetImage && !isDeviceLocked(context);
    }

    /**
     * Check if the current user can perform any activity for
     * com.android.camera.action.CROP action for images.
     * Returns false if the device is currently locked and
     * requires a PIN, pattern or password to unlock.
     */
    public static boolean canCropPhoto(Context context) {
        Intent intent = new Intent("com.android.camera.action.CROP");
        intent.setType("image/*");
        boolean canPerformActivityForCropping =
                context.getPackageManager().queryIntentActivities(intent, 0).size() > 0;
        // on locked device we can't start a cropping activity
        return canPerformActivityForCropping && !isDeviceLocked(context);
    }

    private static boolean isDeviceLocked(Context context) {
        KeyguardManager keyguardManager = context.getSystemService(KeyguardManager.class);
        return keyguardManager == null || keyguardManager.isDeviceLocked();
    }

}
