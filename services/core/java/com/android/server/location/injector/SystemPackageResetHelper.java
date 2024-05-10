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

package com.android.server.location.injector;

import android.annotation.Nullable;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import com.android.internal.util.Preconditions;
import com.android.server.FgThread;

/** Listens to appropriate broadcasts for queries and resets. */
public class SystemPackageResetHelper extends PackageResetHelper {

    private final Context mContext;

    @Nullable
    private BroadcastReceiver mReceiver;

    public SystemPackageResetHelper(Context context) {
        mContext = context;
    }

    @Override
    protected void onRegister() {
        Preconditions.checkState(mReceiver == null);
        mReceiver = new Receiver();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        filter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        filter.addDataScheme("package");

        // We don't filter for Intent.ACTION_PACKAGE_DATA_CLEARED as 1) it refers to persistent
        // data, and 2) it should always be preceded by Intent.ACTION_PACKAGE_RESTARTED, which
        // refers to runtime data. in this way we also avoid redundant callbacks.

        mContext.registerReceiver(mReceiver, filter);
    }

    @Override
    protected void onUnregister() {
        Preconditions.checkState(mReceiver != null);
        mContext.unregisterReceiver(mReceiver);
        mReceiver = null;
    }

    private class Receiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }

            Uri data = intent.getData();
            if (data == null) {
                return;
            }

            String packageName = data.getSchemeSpecificPart();
            if (packageName == null) {
                return;
            }

            switch (action) {
                case Intent.ACTION_QUERY_PACKAGE_RESTART:
                    String[] packages = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                    if (packages != null) {
                        // it would be more efficient to pass through the whole array, but at the
                        // moment the array is always size 1, and this makes for a nicer callback.
                        for (String pkg : packages) {
                            if (queryResetableForPackage(pkg)) {
                                setResultCode(Activity.RESULT_OK);
                                break;
                            }
                        }
                    }
                    break;
                case Intent.ACTION_PACKAGE_CHANGED:
                    // make sure this is an enabled/disabled change to the package as a whole, not
                    // just some of its components. This avoids unnecessary work in the callback.
                    boolean isPackageChange = false;
                    String[] components = intent.getStringArrayExtra(
                            Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST);
                    if (components != null) {
                        for (String component : components) {
                            if (packageName.equals(component)) {
                                isPackageChange = true;
                                break;
                            }
                        }
                    }

                    if (isPackageChange) {
                        try {
                            ApplicationInfo appInfo =
                                    context.getPackageManager().getApplicationInfo(packageName,
                                            PackageManager.ApplicationInfoFlags.of(0));
                            if (!appInfo.enabled) {
                                // move off main thread
                                FgThread.getExecutor().execute(
                                        () -> notifyPackageReset(packageName));
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            return;
                        }
                    }
                    break;
                case Intent.ACTION_PACKAGE_REMOVED:
                    // fall through
                case Intent.ACTION_PACKAGE_RESTARTED:
                    // move off main thread
                    FgThread.getExecutor().execute(() -> notifyPackageReset(packageName));
                    break;
                default:
                    break;
            }
        }
    }
}
