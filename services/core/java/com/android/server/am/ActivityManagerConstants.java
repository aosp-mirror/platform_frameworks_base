/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import android.content.ContentResolver;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.Slog;

import java.io.PrintWriter;

/**
 * Settings constants that can modify the activity manager's behavior.
 */
final class ActivityManagerConstants extends ContentObserver {
    // Key names stored in the settings value.
    private static final String KEY_ENFORCE_BG_CHECK = "enforce_bg_check";
    private static final String KEY_MAX_CACHED_PROCESSES = "max_cached_processes";

    private static final boolean DEFAULT_ENFORCE_BG_CHECK = SystemProperties.getBoolean(
            "debug.bgcheck", false);
    private static final int DEFAULT_MAX_CACHED_PROCESSES = 32;

    // Enforce background check on apps targeting O?
    public boolean ENFORCE_BG_CHECK = DEFAULT_ENFORCE_BG_CHECK;

    // Maximum number of cached processes we will allow.
    public int MAX_CACHED_PROCESSES = DEFAULT_MAX_CACHED_PROCESSES;

    private final ActivityManagerService mService;
    private ContentResolver mResolver;
    private final KeyValueListParser mParser = new KeyValueListParser(',');

    private int mOverrideMaxCachedProcesses = -1;

    // The maximum number of cached processes we will keep around before killing them.
    // NOTE: this constant is *only* a control to not let us go too crazy with
    // keeping around processes on devices with large amounts of RAM.  For devices that
    // are tighter on RAM, the out of memory killer is responsible for killing background
    // processes as RAM is needed, and we should *never* be relying on this limit to
    // kill them.  Also note that this limit only applies to cached background processes;
    // we have no limit on the number of service, visible, foreground, or other such
    // processes and the number of those processes does not count against the cached
    // process limit.
    public int CUR_MAX_CACHED_PROCESSES;

    // The maximum number of empty app processes we will let sit around.
    public int CUR_MAX_EMPTY_PROCESSES;

    // The number of empty apps at which we don't consider it necessary to do
    // memory trimming.
    public int CUR_TRIM_EMPTY_PROCESSES;

    // The number of cached at which we don't consider it necessary to do
    // memory trimming.
    public int CUR_TRIM_CACHED_PROCESSES;

    public ActivityManagerConstants(ActivityManagerService service, Handler handler) {
        super(handler);
        mService = service;
        updateMaxCachedProcesses();
    }

    public void start(ContentResolver resolver) {
        mResolver = resolver;
        mResolver.registerContentObserver(Settings.Global.getUriFor(
                Settings.Global.ACTIVITY_MANAGER_CONSTANTS), false, this);
        updateConstants();
    }

    public void setOverrideMaxCachedProcesses(int value) {
        mOverrideMaxCachedProcesses = value;
        updateMaxCachedProcesses();
    }

    public int getOverrideMaxCachedProcesses() {
        return mOverrideMaxCachedProcesses;
    }

    public static int computeEmptyProcessLimit(int totalProcessLimit) {
        return totalProcessLimit/2;
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        updateConstants();
    }

    private void updateConstants() {
        synchronized (mService) {
            try {
                mParser.setString(Settings.Global.getString(mResolver,
                        Settings.Global.ACTIVITY_MANAGER_CONSTANTS));
            } catch (IllegalArgumentException e) {
                // Failed to parse the settings string, log this and move on
                // with defaults.
                Slog.e("ActivityManagerConstants", "Bad activity manager config settings", e);
            }

            ENFORCE_BG_CHECK = mParser.getBoolean(KEY_ENFORCE_BG_CHECK, DEFAULT_ENFORCE_BG_CHECK);
            MAX_CACHED_PROCESSES = mParser.getInt(KEY_MAX_CACHED_PROCESSES,
                    DEFAULT_MAX_CACHED_PROCESSES);
            updateMaxCachedProcesses();
        }
    }

    private void updateMaxCachedProcesses() {
        CUR_MAX_CACHED_PROCESSES = mOverrideMaxCachedProcesses < 0
                ? MAX_CACHED_PROCESSES : mOverrideMaxCachedProcesses;
        CUR_MAX_EMPTY_PROCESSES = computeEmptyProcessLimit(CUR_MAX_CACHED_PROCESSES);

        // Note the trim levels do NOT depend on the override process limit, we want
        // to consider the same level the point where we do trimming regardless of any
        // additional enforced limit.
        final int rawMaxEmptyProcesses = computeEmptyProcessLimit(MAX_CACHED_PROCESSES);
        CUR_TRIM_EMPTY_PROCESSES = rawMaxEmptyProcesses/2;
        CUR_TRIM_CACHED_PROCESSES = (MAX_CACHED_PROCESSES-rawMaxEmptyProcesses)/3;
    }

    void dump(PrintWriter pw) {
        pw.println("ACTIVITY MANAGER SETTINGS (dumpsys activity settings) "
                + Settings.Global.ACTIVITY_MANAGER_CONSTANTS + ":");

        pw.print("  "); pw.print(KEY_ENFORCE_BG_CHECK); pw.print("=");
        pw.println(ENFORCE_BG_CHECK);

        pw.print("  "); pw.print(KEY_MAX_CACHED_PROCESSES); pw.print("=");
        pw.println(MAX_CACHED_PROCESSES);

        pw.println();
        if (mOverrideMaxCachedProcesses >= 0) {
            pw.print("  mOverrideMaxCachedProcesses="); pw.println(mOverrideMaxCachedProcesses);
        }
        pw.print("  CUR_MAX_CACHED_PROCESSES="); pw.println(CUR_MAX_CACHED_PROCESSES);
        pw.print("  CUR_MAX_EMPTY_PROCESSES="); pw.println(CUR_MAX_EMPTY_PROCESSES);
        pw.print("  CUR_TRIM_EMPTY_PROCESSES="); pw.println(CUR_TRIM_EMPTY_PROCESSES);
        pw.print("  CUR_TRIM_CACHED_PROCESSES="); pw.println(CUR_TRIM_CACHED_PROCESSES);
    }
}
