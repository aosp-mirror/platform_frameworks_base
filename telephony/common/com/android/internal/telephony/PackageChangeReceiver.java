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
import android.os.HandlerThread;
import android.os.Looper;
import android.os.UserHandle;

/**
 * Helper class for monitoring the state of packages: adding, removing,
 * updating, and disappearing and reappearing on the SD card.
 */
public abstract class PackageChangeReceiver extends BroadcastReceiver {
    static final IntentFilter sPackageIntentFilter = new IntentFilter();
    private static HandlerThread sHandlerThread;
    static {
        sPackageIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        sPackageIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        sPackageIntentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        sPackageIntentFilter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        sPackageIntentFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        sPackageIntentFilter.addDataScheme("package");
    }
    // Keep an instance of Context around as long as we still want the receiver:
    // if the instance of Context gets garbage-collected, it'll unregister the receiver, so only
    // unset when we want to unregister.
    Context mRegisteredContext;

    /**
     * To register the intents that needed for monitoring the state of packages. Once this method
     * has been called on an instance of {@link PackageChangeReceiver}, all subsequent calls must
     * have the same {@code user} argument.
     */
    public void register(@NonNull Context context, @Nullable Looper thread,
            @Nullable UserHandle user) {
        if (mRegisteredContext != null) {
            throw new IllegalStateException("Already registered");
        }
        Handler handler = new Handler(thread == null ? getStaticLooper() : thread);
        mRegisteredContext = user == null ? context : context.createContextAsUser(user, 0);
        mRegisteredContext.registerReceiver(this, sPackageIntentFilter, null, handler);
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

    private static synchronized Looper getStaticLooper() {
        if (sHandlerThread == null) {
            sHandlerThread = new HandlerThread(PackageChangeReceiver.class.getSimpleName());
            sHandlerThread.start();
        }
        return sHandlerThread.getLooper();
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
