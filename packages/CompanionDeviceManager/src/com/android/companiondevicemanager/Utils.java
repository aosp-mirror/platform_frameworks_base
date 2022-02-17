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
            @NonNull Context context, @NonNull String packageName) {
        final PackageManager packageManager = context.getPackageManager();
        final ApplicationInfo appInfo;
        try {
            appInfo = packageManager.getApplicationInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
        return packageManager.getApplicationLabel(appInfo);
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
