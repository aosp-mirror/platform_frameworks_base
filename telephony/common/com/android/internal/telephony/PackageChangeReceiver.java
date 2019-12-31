/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephony;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;

import com.android.internal.os.BackgroundThread;

/**
 * Helper class for monitoring the state of packages: adding, removing,
 * updating, and disappearing and reappearing on the SD card.
 */
public abstract class PackageChangeReceiver extends BroadcastReceiver {
    static final IntentFilter sPackageIntentFilter = new IntentFilter();
    static {
        sPackageIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        sPackageIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        sPackageIntentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        sPackageIntentFilter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        sPackageIntentFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        sPackageIntentFilter.addDataScheme("package");
    }
    Context mRegisteredContext;

    /**
     * To register the intents that needed for monitoring the state of packages
     */
    public void register(@NonNull Context context, @Nullable Looper thread,
            @Nullable UserHandle user) {
        if (mRegisteredContext != null) {
            throw new IllegalStateException("Already registered");
        }
        Handler handler = (thread == null) ? BackgroundThread.getHandler() : new Handler(thread);
        mRegisteredContext = context;
        if (handler != null) {
            Context contextForUser = user == null ? context : context.createContextAsUser(user, 0);
            contextForUser.registerReceiver(this, sPackageIntentFilter, null, handler);
        } else {
            throw new NullPointerException();
        }
    }

    /**
     * To unregister the intents for monitoring the state of packages
     */
    public void unregister() {
        if (mRegisteredContext == null) {
            throw new IllegalStateException("Not registered");
        }
        mRegisteredContext.unregisterReceiver(this);
        mRegisteredContext = null;
    }

    /**
     * This method is invoked when receive the Intent.ACTION_PACKAGE_ADDED
     */
    public void onPackageAdded(@Nullable String packageName) {
    }

    /**
     * This method is invoked when receive the Intent.ACTION_PACKAGE_REMOVED
     */
    public void onPackageRemoved(@Nullable String packageName) {
    }

    /**
     * This method is invoked when Intent.EXTRA_REPLACING as extra field is true
     */
    public void onPackageUpdateFinished(@Nullable String packageName) {
    }

    /**
     * This method is invoked when receive the Intent.ACTION_PACKAGE_CHANGED or
     * Intent.EXTRA_REPLACING as extra field is true
     */
    public void onPackageModified(@Nullable String packageName) {
    }

    /**
     * This method is invoked when receive the Intent.ACTION_QUERY_PACKAGE_RESTART and
     * Intent.ACTION_PACKAGE_RESTARTED
     */
    public void onHandleForceStop(@Nullable String[] packages, boolean doit) {
    }

    /**
     * This method is invoked when receive the Intent.ACTION_PACKAGE_REMOVED
     */
    public void onPackageDisappeared() {
    }

    /**
     * This method is invoked when receive the Intent.ACTION_PACKAGE_ADDED
     */
    public void onPackageAppeared() {
    }

    @Override
    public void onReceive(@Nullable Context context, @Nullable Intent intent) {
        String action = intent.getAction();

        if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
            String pkg = getPackageName(intent);
            if (pkg != null) {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    onPackageUpdateFinished(pkg);
                    onPackageModified(pkg);
                } else {
                    onPackageAdded(pkg);
                }
                onPackageAppeared();
            }
        } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
            String pkg = getPackageName(intent);
            if (pkg != null) {
                if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                    onPackageRemoved(pkg);
                }
                onPackageDisappeared();
            }
        } else if (Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
            String pkg = getPackageName(intent);
            if (pkg != null) {
                onPackageModified(pkg);
            }
        } else if (Intent.ACTION_QUERY_PACKAGE_RESTART.equals(action)) {
            String[] disappearingPackages = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
            onHandleForceStop(disappearingPackages, false);
        } else if (Intent.ACTION_PACKAGE_RESTARTED.equals(action)) {
            String[] disappearingPackages = new String[] {getPackageName(intent)};
            onHandleForceStop(disappearingPackages, true);
        }
    }

    String getPackageName(Intent intent) {
        Uri uri = intent.getData();
        String pkg = uri != null ? uri.getSchemeSpecificPart() : null;
        return pkg;
    }
}
