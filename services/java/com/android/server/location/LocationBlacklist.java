/*
 * Copyright (C) 2012 The Android Open Source Project
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


package com.android.server.location;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import com.android.server.LocationManagerService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Allows applications to be blacklisted from location updates at run-time.
 *
 * This is a silent blacklist. Applications can still call Location Manager
 * API's, but they just won't receive any locations.
 */
public final class LocationBlacklist extends ContentObserver {
    private static final String TAG = "LocationBlacklist";
    private static final boolean D = LocationManagerService.D;
    private static final String BLACKLIST_CONFIG_NAME = "locationPackagePrefixBlacklist";
    private static final String WHITELIST_CONFIG_NAME = "locationPackagePrefixWhitelist";

    private final Context mContext;
    private final Object mLock = new Object();

    // all fields below synchronized on mLock
    private String[] mWhitelist = new String[0];
    private String[] mBlacklist = new String[0];
    
    public LocationBlacklist(Context context, Handler handler) {
        super(handler);
        mContext = context;
    }

    public void init() {
        mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                BLACKLIST_CONFIG_NAME), false, this);
//        mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
//                WHITELIST_CONFIG_NAME), false, this);
        reloadBlacklist();
    }

    private void reloadBlacklist() {
        String blacklist[] = getStringArray(BLACKLIST_CONFIG_NAME);
        String whitelist[] = getStringArray(WHITELIST_CONFIG_NAME);
        synchronized (mLock) {
            mWhitelist = whitelist;
            Slog.i(TAG, "whitelist: " + Arrays.toString(mWhitelist));
            mBlacklist = blacklist;
            Slog.i(TAG, "blacklist: " + Arrays.toString(mBlacklist));
        }
    }

    /**
     * Return true if in blacklist
     * (package name matches blacklist, and does not match whitelist)
     */
    public boolean isBlacklisted(String packageName) {
        synchronized (mLock) {
            for (String black : mBlacklist) {
                if (packageName.startsWith(black)) {
                    if (inWhitelist(packageName)) {
                        continue;
                    } else {
                        if (D) Log.d(TAG, "dropping location (blacklisted): "
                                + packageName + " matches " + black);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Return true if any of packages are in whitelist
     */
    private boolean inWhitelist(String pkg) {
        synchronized (mLock) {
            for (String white : mWhitelist) {
                if (pkg.startsWith(white)) return true;
            }
        }
        return false;
    }

    @Override
    public void onChange(boolean selfChange) {
        reloadBlacklist();
    }

    private String[] getStringArray(String key) {
        String flatString = Settings.Secure.getString(mContext.getContentResolver(), key);
        if (flatString == null) {
            return new String[0];
        }
        String[] splitStrings = flatString.split(",");
        ArrayList<String> result = new ArrayList<String>();
        for (String pkg : splitStrings) {
            pkg = pkg.trim();
            if (pkg.isEmpty()) {
                continue;
            }
            result.add(pkg);
        }
        return result.toArray(new String[result.size()]);
    }

    public void dump(PrintWriter pw) {
        pw.println("mWhitelist=" + Arrays.toString(mWhitelist) + " mBlacklist=" +
                Arrays.toString(mBlacklist));
    }
}
