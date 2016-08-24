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
import android.text.format.Formatter;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import libcore.util.Objects;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * User information used by {@link ShortcutService}.
 */
class ShortcutUser {
    private static final String TAG = ShortcutService.TAG;

    static final String TAG_ROOT = "user";
    private static final String TAG_LAUNCHER = "launcher";

    private static final String ATTR_VALUE = "value";
    private static final String ATTR_KNOWN_LOCALE_CHANGE_SEQUENCE_NUMBER = "locale-seq-no";

    static final class PackageWithUser {
        final int userId;
        final String packageName;

        private PackageWithUser(int userId, String packageName) {
            this.userId = userId;
            this.packageName = Preconditions.checkNotNull(packageName);
        }

        public static PackageWithUser of(int userId, String packageName) {
            return new PackageWithUser(userId, packageName);
        }

        public static PackageWithUser of(ShortcutPackageItem spi) {
            return new PackageWithUser(spi.getPackageUserId(), spi.getPackageName());
        }

        @Override
        public int hashCode() {
            return packageName.hashCode() ^ userId;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof PackageWithUser)) {
                return false;
            }
            final PackageWithUser that = (PackageWithUser) obj;

            return userId == that.userId && packageName.equals(that.packageName);
        }

        @Override
        public String toString() {
            return String.format("{Package: %d, %s}", userId, packageName);
        }
    }

    @UserIdInt
    private final int mUserId;

    private final ArrayMap<String, ShortcutPackage> mPackages = new ArrayMap<>();

    private final SparseArray<ShortcutPackage> mPackagesFromUid = new SparseArray<>();

    private final ArrayMap<PackageWithUser, ShortcutLauncher> mLaunchers = new ArrayMap<>();

    /** Default launcher that can access the launcher apps APIs. */
    private ComponentName mLauncherComponent;

    private long mKnownLocaleChangeSequenceNumber;

    public ShortcutUser(int userId) {
        mUserId = userId;
    }

    public int getUserId() {
        return mUserId;
    }

    // We don't expose this directly to non-test code because only ShortcutUser should add to/
    // remove from it.
    @VisibleForTesting
    ArrayMap<String, ShortcutPackage> getAllPackagesForTest() {
        return mPackages;
    }

    public ShortcutPackage removePackage(@NonNull ShortcutService s, @NonNull String packageName) {
        final ShortcutPackage removed = mPackages.remove(packageName);

        s.cleanupBitmapsForPackage(mUserId, packageName);

        return removed;
    }

    // We don't expose this directly to non-test code because only ShortcutUser should add to/
    // remove from it.
    @VisibleForTesting
    ArrayMap<PackageWithUser, ShortcutLauncher> getAllLaunchersForTest() {
        return mLaunchers;
    }

    public void addLauncher(ShortcutLauncher launcher) {
        mLaunchers.put(PackageWithUser.of(launcher.getPackageUserId(),
                launcher.getPackageName()), launcher);
    }

    public ShortcutLauncher removeLauncher(
            @UserIdInt int packageUserId, @NonNull String packageName) {
        return mLaunchers.remove(PackageWithUser.of(packageUserId, packageName));
    }

    public ShortcutPackage getPackageShortcuts(ShortcutService s, @NonNull String packageName) {
        ShortcutPackage ret = mPackages.get(packageName);
        if (ret == null) {
            ret = new ShortcutPackage(s, this, mUserId, packageName);
            mPackages.put(packageName, ret);
        } else {
            ret.attemptToRestoreIfNeededAndSave(s);
        }
        return ret;
    }

    public ShortcutLauncher getLauncherShortcuts(ShortcutService s, @NonNull String packageName,
            @UserIdInt int launcherUserId) {
        final PackageWithUser key = PackageWithUser.of(launcherUserId, packageName);
        ShortcutLauncher ret = mLaunchers.get(key);
        if (ret == null) {
            ret = new ShortcutLauncher(this, mUserId, packageName, launcherUserId);
            mLaunchers.put(key, ret);
        } else {
            ret.attemptToRestoreIfNeededAndSave(s);
        }
        return ret;
    }

    public void forAllPackages(Consumer<? super ShortcutPackage> callback) {
        final int size = mPackages.size();
        for (int i = 0; i < size; i++) {
            callback.accept(mPackages.valueAt(i));
        }
    }

    public void forAllLaunchers(Consumer<? super ShortcutLauncher> callback) {
        final int size = mLaunchers.size();
        for (int i = 0; i < size; i++) {
            callback.accept(mLaunchers.valueAt(i));
        }
    }

    public void forAllPackageItems(Consumer<? super ShortcutPackageItem> callback) {
        forAllLaunchers(callback);
        forAllPackages(callback);
    }

    public void forPackageItem(@NonNull String packageName, @UserIdInt int packageUserId,
            Consumer<ShortcutPackageItem> callback) {
        forAllPackageItems(spi -> {
            if ((spi.getPackageUserId() == packageUserId)
                    && spi.getPackageName().equals(packageName)) {
                callback.accept(spi);
            }
        });
    }

    /**
     * Reset all throttling counters for all packages, if there has been a system locale change.
     */
    public void resetThrottlingIfNeeded(ShortcutService s) {
        final long currentNo = s.getLocaleChangeSequenceNumber();
        if (mKnownLocaleChangeSequenceNumber < currentNo) {
            if (ShortcutService.DEBUG) {
                Slog.d(TAG, "LocaleChange detected for user " + mUserId);
            }

            mKnownLocaleChangeSequenceNumber = currentNo;

            forAllPackages(p -> p.resetRateLimiting(s));

            s.scheduleSaveUser(mUserId);
        }
    }

    /**
     * Called when a package is updated.
     */
    public void handlePackageUpdated(ShortcutService s, @NonNull String packageName,
            int newVersionCode) {
        if (!mPackages.containsKey(packageName)) {
            return;
        }
        getPackageShortcuts(s, packageName).handlePackageUpdated(s, newVersionCode);
    }

    public void attemptToRestoreIfNeededAndSave(ShortcutService s, @NonNull String packageName,
            @UserIdInt int packageUserId) {
        forPackageItem(packageName, packageUserId, spi -> {
            spi.attemptToRestoreIfNeededAndSave(s);
        });
    }

    public void saveToXml(ShortcutService s, XmlSerializer out, boolean forBackup)
            throws IOException, XmlPullParserException {
        out.startTag(null, TAG_ROOT);

        ShortcutService.writeAttr(out, ATTR_KNOWN_LOCALE_CHANGE_SEQUENCE_NUMBER,
                mKnownLocaleChangeSequenceNumber);

        ShortcutService.writeTagValue(out, TAG_LAUNCHER,
                mLauncherComponent);

        // Can't use forEachPackageItem due to the checked exceptions.
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
        if (forBackup) {
            if (!s.shouldBackupApp(spi.getPackageName(), spi.getPackageUserId())) {
                return; // Don't save.
            }
            if (spi.getPackageUserId() != spi.getOwnerUserId()) {
                return; // Don't save cross-user information.
            }
        }
        spi.saveToXml(out, forBackup);
    }

    public static ShortcutUser loadFromXml(ShortcutService s, XmlPullParser parser, int userId,
            boolean fromBackup) throws IOException, XmlPullParserException {
        final ShortcutUser ret = new ShortcutUser(userId);

        ret.mKnownLocaleChangeSequenceNumber = ShortcutService.parseLongAttribute(parser,
                ATTR_KNOWN_LOCALE_CHANGE_SEQUENCE_NUMBER);

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            final int depth = parser.getDepth();
            final String tag = parser.getName();

            if (depth == outerDepth + 1) {
                switch (tag) {
                    case TAG_LAUNCHER: {
                        ret.mLauncherComponent = ShortcutService.parseComponentNameAttribute(
                                parser, ATTR_VALUE);
                        continue;
                    }
                    case ShortcutPackage.TAG_ROOT: {
                        final ShortcutPackage shortcuts = ShortcutPackage.loadFromXml(
                                s, ret, parser, fromBackup);

                        // Don't use addShortcut(), we don't need to save the icon.
                        ret.mPackages.put(shortcuts.getPackageName(), shortcuts);
                        continue;
                    }

                    case ShortcutLauncher.TAG_ROOT: {
                        ret.addLauncher(
                                ShortcutLauncher.loadFromXml(parser, ret, userId, fromBackup));
                        continue;
                    }
                }
            }
            ShortcutService.warnForInvalidTag(depth, tag);
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
        pw.print("  Known locale seq#: ");
        pw.print(mKnownLocaleChangeSequenceNumber);
        pw.println();

        prefix += prefix + "  ";

        pw.print(prefix);
        pw.print("Default launcher: ");
        pw.print(mLauncherComponent);
        pw.println();

        for (int i = 0; i < mLaunchers.size(); i++) {
            mLaunchers.valueAt(i).dump(s, pw, prefix);
        }

        for (int i = 0; i < mPackages.size(); i++) {
            mPackages.valueAt(i).dump(s, pw, prefix);
        }

        pw.println();
        pw.print(prefix);
        pw.println("Bitmap directories: ");
        dumpDirectorySize(s, pw, prefix + "  ", s.getUserBitmapFilePath(mUserId));
    }

    private void dumpDirectorySize(@NonNull ShortcutService s, @NonNull PrintWriter pw,
            @NonNull String prefix, File path) {
        int numFiles = 0;
        long size = 0;
        final File[] children = path.listFiles();
        if (children != null) {
            for (File child : path.listFiles()) {
                if (child.isFile()) {
                    numFiles++;
                    size += child.length();
                } else if (child.isDirectory()) {
                    dumpDirectorySize(s, pw, prefix + "  ", child);
                }
            }
        }
        pw.print(prefix);
        pw.print("Path: ");
        pw.print(path.getName());
        pw.print("/ has ");
        pw.print(numFiles);
        pw.print(" files, size=");
        pw.print(size);
        pw.print(" (");
        pw.print(Formatter.formatFileSize(s.mContext, size));
        pw.println(")");
    }
}
