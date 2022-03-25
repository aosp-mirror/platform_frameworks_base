/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.app;

import android.app.LocaleConfig;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.LocaleList;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

public class AppLocaleStore {
    private static final String TAG = AppLocaleStore.class.getSimpleName();

    public static ArrayList<Locale> getAppSupportedLocales(Context context, String packageName) {
        ArrayList<Locale> appSupportedLocales = new ArrayList<>();
        LocaleList packageLocaleList = getPackageLocales(context, packageName);

        if (packageLocaleList != null && packageLocaleList.size() > 0) {
            for (int i = 0; i < packageLocaleList.size(); i++) {
                appSupportedLocales.add(packageLocaleList.get(i));
            }
            Log.d(TAG, "getAppSupportedLocales from LocaleConfig. Size: "
                    + appSupportedLocales.size());
        } else {
            String[] languages = getAssetLocales(context, packageName);
            for (String language : languages) {
                appSupportedLocales.add(Locale.forLanguageTag(language));
            }
            Log.d(TAG, "getAppSupportedLocales from asset. Size: "
                    + appSupportedLocales.size());
        }
        return appSupportedLocales;
    }

    private static LocaleList getPackageLocales(Context context, String packageName) {
        try {
            LocaleConfig localeConfig =
                    new LocaleConfig(context.createPackageContext(packageName, 0));
            if (localeConfig.getStatus() == LocaleConfig.STATUS_SUCCESS) {
                return localeConfig.getSupportedLocales();
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Can not found the package name : " + packageName + " / " + e);
        }
        return null;
    }

    private static String[] getAssetLocales(Context context, String packageName) {
        try {
            PackageManager packageManager = context.getPackageManager();
            String[] locales = packageManager.getResourcesForApplication(
                    packageManager.getPackageInfo(packageName, PackageManager.MATCH_ALL)
                            .applicationInfo).getAssets().getNonSystemLocales();
            if (locales == null) {
                Log.i(TAG, "[" + packageName + "] locales are null.");
                return new String[0];
            } else if (locales.length <= 0) {
                Log.i(TAG, "[" + packageName + "] locales length is 0.");
                return new String[0];
            }
            return locales;
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Can not found the package name : " + packageName + " / " + e);
        }
        return new String[0];
    }

}
