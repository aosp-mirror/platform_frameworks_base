/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.util.ArrayMap;
import android.util.Slog;

import libcore.util.Objects;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * User information used by {@link ShortcutService}.
 */
class ShortcutUser {
    private static final String TAG = ShortcutService.TAG;

    static final String TAG_ROOT = "user";
    private static final String TAG_LAUNCHER = "launcher";

    private static final String ATTR_VALUE = "value";

    @UserIdInt
    final int mUserId;

    private final ArrayMap<String, ShortcutPackage> mPackages = new ArrayMap<>();

    private final ArrayMap<String, ShortcutLauncher> mLaunchers = new ArrayMap<>();

    private final ArrayMap<String, ShortcutPackageInfo> mPackageInfos = new ArrayMap<>();

    private ComponentName mLauncherComponent;

    public ShortcutUser(int userId) {
        mUserId = userId;
    }

    public ArrayMap<String, ShortcutPackage> getPackages() {
        return mPackages;
    }

    public ArrayMap<String, ShortcutLauncher> getLaunchers() {
        return mLaunchers;
    }

    public ArrayMap<String, ShortcutPackageInfo> getPackageInfos() {
        return mPackageInfos;
    }

    public ShortcutPackage getPackageShortcuts(@NonNull String packageName) {
        ShortcutPackage ret = mPackages.get(packageName);
        if (ret == null) {
            ret = new ShortcutPackage(mUserId, packageName);
            mPackages.put(packageName, ret);
        }
        return ret;
    }

    public ShortcutLauncher getLauncherShortcuts(@NonNull String packageName) {
        ShortcutLauncher ret = mLaunchers.get(packageName);
        if (ret == null) {
            ret = new ShortcutLauncher(mUserId, packageName);
            mLaunchers.put(packageName, ret);
        }
        return ret;
    }

    public void ensurePackageInfo(ShortcutService s, String packageName, @UserIdInt int userId) {
        final ShortcutPackageInfo existing = mPackageInfos.get(packageName);

        if (existing != null) {
            return;
        }
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, String.format("Fetching package info: %s user=%d", packageName, userId));
        }
        final ShortcutPackageInfo newSpi = ShortcutPackageInfo.generateForInstalledPackage(
                s, packageName, userId);
        mPackageInfos.put(packageName, newSpi);
        s.scheduleSaveUser(mUserId);
    }

    public void saveToXml(ShortcutService s, XmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException {
        out.startTag(null, TAG_ROOT);

        ShortcutService.writeTagValue(out, TAG_LAUNCHER,
                mLauncherComponent);

        {
            final int size = mPackageInfos.size();
            for (int i = 0; i < size; i++) {
                saveShortcutPackageItem(s, out, mPackageInfos.valueAt(i), forBackup);
            }
        }
        {
            final int size = mLaunchers.size();
            for (int i = 0; i < size; i++) {
                saveShortcutPackageItem(s, out, mLaunchers.valueAt(i), forBackup);
            }
        }
        {
            final int size = mPackages.size();
            for (int i = 0; i < size; i++) {
                saveShortcutPackageItem(s, out, mPackages.valueAt(i), forBackup);
            }
        }

        out.endTag(null, TAG_ROOT);
    }

    private void saveShortcutPackageItem(ShortcutService s, XmlSerializer out,
            ShortcutPackageItem spi, boolean forBackup) throws IOException, XmlPullParserException {
        if (forBackup && !s.shouldBackupApp(spi.getPackageName(), mUserId)) {
            return; // Don't save.
        }
        spi.saveToXml(out, forBackup);
    }

    public static ShortcutUser loadFromXml(XmlPullParser parser, int userId)
            throws IOException, XmlPullParserException {
        final ShortcutUser ret = new ShortcutUser(userId);

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();
            switch (tag) {
                case TAG_LAUNCHER: {
                    ret.mLauncherComponent = ShortcutService.parseComponentNameAttribute(
                            parser, ATTR_VALUE);
                    continue;
                }
                case ShortcutPackage.TAG_ROOT: {
                    final ShortcutPackage shortcuts = ShortcutPackage.loadFromXml(parser, userId);

                    // Don't use addShortcut(), we don't need to save the icon.
                    ret.getPackages().put(shortcuts.getPackageName(), shortcuts);
                    continue;
                }

                case ShortcutLauncher.TAG_ROOT: {
                    final ShortcutLauncher shortcuts =
                            ShortcutLauncher.loadFromXml(parser, userId);

                    ret.getLaunchers().put(shortcuts.getPackageName(), shortcuts);
                    continue;
                }

                case ShortcutPackageInfo.TAG_ROOT: {
                    final ShortcutPackageInfo pi =
                            ShortcutPackageInfo.loadFromXml(parser);

                    ret.getPackageInfos().put(pi.getPackageName(), pi);
                    continue;
                }
            }
            throw ShortcutService.throwForInvalidTag(depth, tag);
        }
        return ret;
    }

    public ComponentName getLauncherComponent() {
        return mLauncherComponent;
    }

    public void setLauncherComponent(ShortcutService s, ComponentName launcherComponent) {
        if (Objects.equal(mLauncherComponent, launcherComponent)) {
            return;
        }
        mLauncherComponent = launcherComponent;
        s.scheduleSaveUser(mUserId);
    }

    public void resetThrottling() {
        for (int i = mPackages.size() - 1; i >= 0; i--) {
            mPackages.valueAt(i).resetThrottling();
        }
    }

    public void dump(@NonNull ShortcutService s, @NonNull PrintWriter pw, @NonNull String prefix) {
        pw.print(prefix);
        pw.print("User: ");
        pw.print(mUserId);
        pw.println();

        pw.print(prefix);
        pw.print("  ");
        pw.print("Default launcher: ");
        pw.print(mLauncherComponent);
        pw.println();

        for (int i = 0; i < mLaunchers.size(); i++) {
            mLaunchers.valueAt(i).dump(s, pw, prefix + "  ");
        }

        for (int i = 0; i < mPackages.size(); i++) {
            mPackages.valueAt(i).dump(s, pw, prefix + "  ");
        }

        for (int i = 0; i < mPackageInfos.size(); i++) {
            mPackageInfos.valueAt(i).dump(s, pw, prefix + "  ");
        }
    }
}
