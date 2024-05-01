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
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.UserPackage;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.pm.ShortcutService.DumpFilter;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Launcher information used by {@link ShortcutService}.
 *
 * All methods should be guarded by {@code ShortcutPackageItem#mPackageItemLock}.
 */
class ShortcutLauncher extends ShortcutPackageItem {
    private static final String TAG = ShortcutService.TAG;

    static final String TAG_ROOT = "launcher-pins";

    private static final String TAG_PACKAGE = "package";
    private static final String TAG_PIN = "pin";

    private static final String ATTR_LAUNCHER_USER_ID = "launcher-user";
    private static final String ATTR_VALUE = "value";
    private static final String ATTR_PACKAGE_NAME = "package-name";
    private static final String ATTR_PACKAGE_USER_ID = "package-user";

    private final int mOwnerUserId;

    /**
     * Package name -> IDs.
     */
    @GuardedBy("mPackageItemLock")
    private final ArrayMap<UserPackage, ArraySet<String>> mPinnedShortcuts = new ArrayMap<>();

    private ShortcutLauncher(@NonNull ShortcutUser shortcutUser,
            @UserIdInt int ownerUserId, @NonNull String packageName,
            @UserIdInt int launcherUserId, ShortcutPackageInfo spi) {
        super(shortcutUser, launcherUserId, packageName,
                spi != null ? spi : ShortcutPackageInfo.newEmpty());
        mOwnerUserId = ownerUserId;
    }

    public ShortcutLauncher(@NonNull ShortcutUser shortcutUser,
            @UserIdInt int ownerUserId, @NonNull String packageName,
            @UserIdInt int launcherUserId) {
        this(shortcutUser, ownerUserId, packageName, launcherUserId, null);
    }

    @Override
    public int getOwnerUserId() {
        return mOwnerUserId;
    }

    @Override
    protected boolean canRestoreAnyVersion() {
        // Launcher's pinned shortcuts can be restored to an older version.
        return true;
    }

    /**
     * Called when the new package can't receive the backup, due to signature or version mismatch.
     */
    private void onRestoreBlocked() {
        final ArrayList<UserPackage> pinnedPackages;
        synchronized (mPackageItemLock) {
            pinnedPackages = new ArrayList<>(mPinnedShortcuts.keySet());
            mPinnedShortcuts.clear();
        }
        for (int i = pinnedPackages.size() - 1; i >= 0; i--) {
            final UserPackage up = pinnedPackages.get(i);
            final ShortcutPackage p = mShortcutUser.getPackageShortcutsIfExists(up.packageName);
            if (p != null) {
                p.refreshPinnedFlags();
            }
        }
    }

    @Override
    protected void onRestored(int restoreBlockReason) {
        // For launcher, possible reasons here are DISABLED_REASON_SIGNATURE_MISMATCH or
        // DISABLED_REASON_BACKUP_NOT_SUPPORTED.
        // DISABLED_REASON_VERSION_LOWER will NOT happen because we don't check version
        // code for launchers.
        if (restoreBlockReason != ShortcutInfo.DISABLED_REASON_NOT_DISABLED) {
            onRestoreBlocked();
        }
    }

    /**
     * Pin the given shortcuts, replacing the current pinned ones.
     */
    public void pinShortcuts(@UserIdInt int packageUserId,
            @NonNull String packageName, @NonNull List<String> ids, boolean forPinRequest) {
        final ShortcutPackage packageShortcuts =
                mShortcutUser.getPackageShortcutsIfExists(packageName);
        if (packageShortcuts == null) {
            return; // No need to instantiate.
        }

        final UserPackage up = UserPackage.of(packageUserId, packageName);

        final int idSize = ids.size();
        if (idSize == 0) {
            synchronized (mPackageItemLock) {
                mPinnedShortcuts.remove(up);
            }
        } else {
            // Actually pin shortcuts.
            // This logic here is to make sure a launcher cannot pin a shortcut that is not
            // dynamic nor long-lived nor manifest but is pinned.
            // In this case, technically the shortcut doesn't exist to this launcher, so it
            // can't pin it.
            // (Maybe unnecessarily strict...)
            final ArraySet<String> floatingSet = new ArraySet<>();
            final ArraySet<String> newSet = new ArraySet<>();

            for (int i = 0; i < idSize; i++) {
                final String id = ids.get(i);
                final ShortcutInfo si = packageShortcuts.findShortcutById(id);
                if (si == null) {
                    continue;
                }
                if (si.isDynamic() || si.isLongLived()
                        || si.isManifestShortcut()
                        || forPinRequest) {
                    newSet.add(id);
                } else {
                    floatingSet.add(id);
                }
            }
            synchronized (mPackageItemLock) {
                final ArraySet<String> prevSet = mPinnedShortcuts.get(up);
                if (prevSet != null) {
                    for (String id : floatingSet) {
                        if (prevSet.contains(id)) {
                            newSet.add(id);
                        }
                    }
                }
                mPinnedShortcuts.put(up, newSet);
            }
        }

        packageShortcuts.refreshPinnedFlags();
    }

    /**
     * Return the pinned shortcut IDs for the publisher package.
     */
    @Nullable
    public ArraySet<String> getPinnedShortcutIds(@NonNull String packageName,
            @UserIdInt int packageUserId) {
        synchronized (mPackageItemLock) {
            final ArraySet<String> pinnedShortcuts = mPinnedShortcuts.get(
                    UserPackage.of(packageUserId, packageName));
            return pinnedShortcuts == null ? null : new ArraySet<>(pinnedShortcuts);
        }
    }

    /**
     * Return true if the given shortcut is pinned by this launcher.<code></code>
     */
    public boolean hasPinned(ShortcutInfo shortcut) {
        synchronized (mPackageItemLock) {
            final ArraySet<String> pinned = mPinnedShortcuts.get(
                    UserPackage.of(shortcut.getUserId(), shortcut.getPackage()));
            return (pinned != null) && pinned.contains(shortcut.getId());
        }
    }

    /**
     * Additionally pin a shortcut. c.f. {@link #pinShortcuts(int, String, List, boolean)}
     */
    public void addPinnedShortcut(@NonNull String packageName, @UserIdInt int packageUserId,
            String id, boolean forPinRequest) {
        final ArrayList<String> pinnedList;
        synchronized (mPackageItemLock) {
            final ArraySet<String> pinnedSet = mPinnedShortcuts.get(
                    UserPackage.of(packageUserId, packageName));
            if (pinnedSet != null) {
                pinnedList = new ArrayList<>(pinnedSet.size() + 1);
                pinnedList.addAll(pinnedSet);
            } else {
                pinnedList = new ArrayList<>(1);
            }
        }
        pinnedList.add(id);

        pinShortcuts(packageUserId, packageName, pinnedList, forPinRequest);
    }

    boolean cleanUpPackage(String packageName, @UserIdInt int packageUserId) {
        synchronized (mPackageItemLock) {
            return mPinnedShortcuts.remove(UserPackage.of(packageUserId, packageName)) != null;
        }
    }

    public void ensurePackageInfo() {
        final PackageInfo pi = mShortcutUser.mService.getPackageInfoWithSignatures(
                getPackageName(), getPackageUserId());
        if (pi == null) {
            Slog.w(TAG, "Package not found: " + getPackageName());
            return;
        }
        getPackageInfo().updateFromPackageInfo(pi);
    }

    /**
     * Persist.
     */
    @Override
    public void saveToXml(TypedXmlSerializer out, boolean forBackup)
            throws IOException {
        if (forBackup && !getPackageInfo().isBackupAllowed()) {
            // If an launcher app doesn't support backup&restore, then nothing to do.
            return;
        }
        final ArrayMap<UserPackage, ArraySet<String>> pinnedShortcuts;
        synchronized (mPackageItemLock) {
            pinnedShortcuts = new ArrayMap<>(mPinnedShortcuts);
        }
        final int size = pinnedShortcuts.size();
        if (size == 0) {
            return; // Nothing to write.
        }

        out.startTag(null, TAG_ROOT);
        ShortcutService.writeAttr(out, ATTR_PACKAGE_NAME, getPackageName());
        ShortcutService.writeAttr(out, ATTR_LAUNCHER_USER_ID, getPackageUserId());
        getPackageInfo().saveToXml(mShortcutUser.mService, out, forBackup);

        for (int i = 0; i < size; i++) {
            final UserPackage up = pinnedShortcuts.keyAt(i);

            if (forBackup && (up.userId != getOwnerUserId())) {
                continue; // Target package on a different user, skip. (i.e. work profile)
            }

            out.startTag(null, TAG_PACKAGE);
            ShortcutService.writeAttr(out, ATTR_PACKAGE_NAME, up.packageName);
            ShortcutService.writeAttr(out, ATTR_PACKAGE_USER_ID, up.userId);

            final ArraySet<String> ids = pinnedShortcuts.valueAt(i);
            final int idSize = ids.size();
            for (int j = 0; j < idSize; j++) {
                ShortcutService.writeTagValue(out, TAG_PIN, ids.valueAt(j));
            }
            out.endTag(null, TAG_PACKAGE);
        }

        out.endTag(null, TAG_ROOT);
    }

    public static ShortcutLauncher loadFromFile(File path, ShortcutUser shortcutUser,
            int ownerUserId, boolean fromBackup) {
        try (ResilientAtomicFile file = getResilientFile(path)) {
            FileInputStream in = null;
            try {
                in = file.openRead();
                if (in == null) {
                    Slog.d(TAG, "Not found " + path);
                    return null;
                }

                ShortcutLauncher ret = null;
                TypedXmlPullParser parser = Xml.resolvePullParser(in);

                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG) {
                        continue;
                    }
                    final int depth = parser.getDepth();

                    final String tag = parser.getName();
                    if (ShortcutService.DEBUG_LOAD) {
                        Slog.d(TAG, String.format("depth=%d type=%d name=%s", depth, type, tag));
                    }
                    if ((depth == 1) && TAG_ROOT.equals(tag)) {
                        ret = loadFromXml(parser, shortcutUser, ownerUserId, fromBackup);
                        continue;
                    }
                    ShortcutService.throwForInvalidTag(depth, tag);
                }
                return ret;
            } catch (Exception e) {
                Slog.e(TAG, "Failed to read file " + file.getBaseFile(), e);
                file.failRead(in, e);
                return loadFromFile(path, shortcutUser, ownerUserId, fromBackup);
            }
        }
    }

    /**
     * Load.
     */
    public static ShortcutLauncher loadFromXml(TypedXmlPullParser parser, ShortcutUser shortcutUser,
            int ownerUserId, boolean fromBackup) throws IOException, XmlPullParserException {
        final String launcherPackageName = ShortcutService.parseStringAttribute(parser,
                ATTR_PACKAGE_NAME);

        // If restoring, just use the real user ID.
        final int launcherUserId =
                fromBackup ? ownerUserId
                : ShortcutService.parseIntAttribute(parser, ATTR_LAUNCHER_USER_ID, ownerUserId);

        final ShortcutLauncher ret = new ShortcutLauncher(shortcutUser, ownerUserId,
                launcherPackageName, launcherUserId);

        ArraySet<String> ids = null;
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
                    case ShortcutPackageInfo.TAG_ROOT:
                        ret.getPackageInfo().loadFromXml(parser, fromBackup);
                        continue;
                    case TAG_PACKAGE: {
                        final String packageName = ShortcutService.parseStringAttribute(parser,
                                ATTR_PACKAGE_NAME);
                        final int packageUserId = fromBackup ? ownerUserId
                                : ShortcutService.parseIntAttribute(parser,
                                ATTR_PACKAGE_USER_ID, ownerUserId);
                        ids = new ArraySet<>();
                        synchronized (ret.mPackageItemLock) {
                            ret.mPinnedShortcuts.put(
                                    UserPackage.of(packageUserId, packageName), ids);
                        }
                        continue;
                    }
                }
            }
            if (depth == outerDepth + 2) {
                switch (tag) {
                    case TAG_PIN: {
                        if (ids == null) {
                            Slog.w(TAG, TAG_PIN + " in invalid place");
                        } else {
                            ids.add(ShortcutService.parseStringAttribute(parser, ATTR_VALUE));
                        }
                        continue;
                    }
                }
            }
            ShortcutService.warnForInvalidTag(depth, tag);
        }
        return ret;
    }

    public void dump(@NonNull PrintWriter pw, @NonNull String prefix, DumpFilter filter) {
        pw.println();

        pw.print(prefix);
        pw.print("Launcher: ");
        pw.print(getPackageName());
        pw.print("  Package user: ");
        pw.print(getPackageUserId());
        pw.print("  Owner user: ");
        pw.print(getOwnerUserId());
        pw.println();

        getPackageInfo().dump(pw, prefix + "  ");
        pw.println();

        final ArrayMap<UserPackage, ArraySet<String>> pinnedShortcuts;
        synchronized (mPackageItemLock) {
            pinnedShortcuts = new ArrayMap<>(mPinnedShortcuts);
        }
        final int size = pinnedShortcuts.size();
        for (int i = 0; i < size; i++) {
            pw.println();

            final UserPackage up = pinnedShortcuts.keyAt(i);

            pw.print(prefix);
            pw.print("  ");
            pw.print("Package: ");
            pw.print(up.packageName);
            pw.print("  User: ");
            pw.println(up.userId);

            final ArraySet<String> ids = pinnedShortcuts.valueAt(i);
            final int idSize = ids.size();

            for (int j = 0; j < idSize; j++) {
                pw.print(prefix);
                pw.print("    Pinned: ");
                pw.print(ids.valueAt(j));
                pw.println();
            }
        }
    }

    @Override
    public JSONObject dumpCheckin(boolean clear) throws JSONException {
        final JSONObject result = super.dumpCheckin(clear);

        // Nothing really interesting to dump.

        return result;
    }

    @Override
    protected File getShortcutPackageItemFile() {
        final File path = new File(mShortcutUser.mService.injectUserDataPath(
                mShortcutUser.getUserId()), ShortcutUser.DIRECTORY_LUANCHERS);
        // Package user id and owner id can have different values for ShortcutLaunchers. Adding
        // user Id to the file name to create a unique path. Owner id is used in the root path.
        final String fileName = getPackageName() + getPackageUserId() + ".xml";
        return new File(path, fileName);
    }
}
