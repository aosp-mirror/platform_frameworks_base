/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.companiondevicemanager;

import android.annotation.NonNull;
import android.annotation.StringRes;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.ApplicationInfoFlags;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.text.Html;
import android.text.Spanned;

/**
 * Utilities.
 */
class Utils {
    private static final String COMPANION_DEVICE_ACTIVITY_VENDOR_ICON =
            "android.companion.vendor_icon";
    private static final String COMPANION_DEVICE_ACTIVITY_VENDOR_NAME =
            "android.companion.vendor_name";

    /**
     * Convert an instance of a "locally-defined" ResultReceiver to an instance of
     * {@link android.os.ResultReceiver} itself, which the receiving process will be able to
     * unmarshall.
     */
    static <T extends ResultReceiver> ResultReceiver prepareResultReceiverForIpc(T resultReceiver) {
        final Parcel parcel = Parcel.obtain();
        resultReceiver.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        final ResultReceiver ipcFriendly = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        return ipcFriendly;
    }

    static @NonNull CharSequence getApplicationLabel(
            @NonNull Context context, @NonNull String packageName, int userId)
            throws PackageManager.NameNotFoundException {
        final PackageManager packageManager = context.getPackageManager();

        final ApplicationInfo appInfo = packageManager.getApplicationInfoAsUser(
                packageName, PackageManager.ApplicationInfoFlags.of(0), userId);

        return packageManager.getApplicationLabel(appInfo);
    }

    static @NonNull Drawable getApplicationIcon(@NonNull Context context,
            @NonNull String packageName) throws PackageManager.NameNotFoundException {
        final PackageManager packageManager = context.getPackageManager();
        return packageManager.getApplicationIcon(packageName);
    }

    static Spanned getHtmlFromResources(
            @NonNull Context context, @StringRes int resId, CharSequence... formatArgs) {
        final String[] escapedArgs = new String[formatArgs.length];
        for (int i = 0; i < escapedArgs.length; i++) {
            escapedArgs[i] = Html.escapeHtml(formatArgs[i]);
        }
        final String plain = context.getString(resId, (Object[]) escapedArgs);
        return Html.fromHtml(plain, 0);
    }

    static @NonNull Drawable getVendorHeaderIcon(@NonNull Context context,
            @NonNull String packageName, int userId) throws PackageManager.NameNotFoundException {
        final ApplicationInfo appInfo = getApplicationInfo(context, packageName, userId);
        final Bundle bundle = appInfo.metaData;
        int resId = bundle == null ? 0 : bundle.getInt(COMPANION_DEVICE_ACTIVITY_VENDOR_ICON, 0);

        if (bundle == null || resId == 0) {
            return getApplicationIcon(context, packageName);
        }

        return context.createPackageContext(packageName, /* flags= */ 0).getDrawable(resId);
    }

    static CharSequence getVendorHeaderName(@NonNull Context context,
            @NonNull String packageName, int userId) throws PackageManager.NameNotFoundException {
        final ApplicationInfo appInfo = getApplicationInfo(context, packageName, userId);
        final Bundle bundle = appInfo.metaData;

        if (bundle == null) {
            return "";
        }

        return appInfo.metaData.getCharSequence(COMPANION_DEVICE_ACTIVITY_VENDOR_NAME, "");
    }

    static boolean hasVendorIcon(@NonNull Context context,
            @NonNull String packageName, int userId) throws PackageManager.NameNotFoundException {
        final ApplicationInfo appInfo = getApplicationInfo(context, packageName, userId);
        final Bundle bundle = appInfo.metaData;

        if (bundle == null) {
            return false;
        } else {
            return bundle.getInt(COMPANION_DEVICE_ACTIVITY_VENDOR_ICON) != 0;
        }
    }

    /**
     * Getting ApplicationInfo from meta-data.
     */
    private static @NonNull ApplicationInfo getApplicationInfo(@NonNull Context context,
            @NonNull String packageName, int userId) throws PackageManager.NameNotFoundException {
        final PackageManager packageManager = context.getPackageManager();
        final ApplicationInfoFlags flags = ApplicationInfoFlags.of(PackageManager.GET_META_DATA);
        final ApplicationInfo appInfo = packageManager.getApplicationInfoAsUser(
                packageName, flags, userId);

        return appInfo;
    }

    static @NonNull Drawable getIcon(@NonNull Context context, int resId) {
        Drawable icon = context.getResources().getDrawable(resId, null);
        return icon;
    }

    static void runOnMainThread(Runnable runnable) {
        if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
            runnable.run();
        } else {
            Handler.getMain().post(runnable);
        }
    }

    private Utils() {
    }
}
