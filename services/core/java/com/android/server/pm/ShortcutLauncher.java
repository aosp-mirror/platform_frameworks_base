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
import android.content.pm.ShortcutInfo;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.ShortcutUser.PackageWithUser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Launcher information used by {@link ShortcutService}.
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
    final private ArrayMap<PackageWithUser, ArraySet<String>> mPinnedShortcuts = new ArrayMap<>();

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

    /**
     * Called when the new package can't receive the backup, due to signature or version mismatch.
     */
    @Override
    protected void onRestoreBlocked(ShortcutService s) {
        final ArrayList<PackageWithUser> pinnedPackages =
                new ArrayList<>(mPinnedShortcuts.keySet());
        mPinnedShortcuts.clear();
        for (int i = pinnedPackages.size() - 1; i >= 0; i--) {
            final PackageWithUser pu = pinnedPackages.get(i);
            s.getPackageShortcutsLocked(pu.packageName, pu.userId)
                    .refreshPinnedFlags(s);
        }
    }

    @Override
    protected void onRestored(ShortcutService s) {
        // Nothing to do.
    }

    public void pinShortcuts(@NonNull ShortcutService s, @UserIdInt int packageUserId,
            @NonNull String packageName, @NonNull List<String> ids) {
        final ShortcutPackage packageShortcuts =
                s.getPackageShortcutsLocked(packageName, packageUserId);

        final PackageWithUser pu = PackageWithUser.of(packageUserId, packageName);

        final int idSize = ids.size();
        if (idSize == 0) {
            mPinnedShortcuts.remove(pu);
        } else {
            final ArraySet<String> prevSet = mPinnedShortcuts.get(pu);

            // Pin shortcuts.  Make sure only pin the ones that were visible to the caller.
            // i.e. a non-dynamic, pinned shortcut by *other launchers* shouldn't be pinned here.

            final ArraySet<String> newSet = new ArraySet<>();

            for (int i = 0; i < idSize; i++) {
                final String id = ids.get(i);
                final ShortcutInfo si = packageShortcuts.findShortcutById(id);
                if (si == null) {
                    continue;
                }
                if (si.isDynamic() || (prevSet != null && prevSet.contains(id))) {
                    newSet.add(id);
                }
            }
            mPinnedShortcuts.put(pu, newSet);
        }
        packageShortcuts.refreshPinnedFlags(s);
    }

    /**
     * Return the pinned shortcut IDs for the publisher package.
     */
    public ArraySet<String> getPinnedShortcutIds(@NonNull String packageName,
            @UserIdInt int packageUserId) {
        return mPinnedShortcuts.get(PackageWithUser.of(packageUserId, packageName));
    }

    boolean cleanUpPackage(String packageName, @UserIdInt int packageUserId) {
        return mPinnedShortcuts.remove(PackageWithUser.of(packageUserId, packageName)) != null;
    }

    /**
     * Persist.
     */
    @Override
    public void saveToXml(XmlSerializer out, boolean forBackup)
            throws IOException {
        final int size = mPinnedShortcuts.size();
        if (size == 0) {
            return; // Nothing to write.
        }

        out.startTag(null, TAG_ROOT);
        ShortcutService.writeAttr(out, ATTR_PACKAGE_NAME, getPackageName());
        ShortcutService.writeAttr(out, ATTR_LAUNCHER_USER_ID, getPackageUserId());
        getPackageInfo().saveToXml(out);

        for (int i = 0; i < size; i++) {
            final PackageWithUser pu = mPinnedShortcuts.keyAt(i);

            if (forBackup && (pu.userId != getOwnerUserId())) {
                continue; // Target package on a different user, skip. (i.e. work profile)
            }

            out.startTag(null, TAG_PACKAGE);
            ShortcutService.writeAttr(out, ATTR_PACKAGE_NAME, pu.packageName);
            ShortcutService.writeAttr(out, ATTR_PACKAGE_USER_ID, pu.userId);

            final ArraySet<String> ids = mPinnedShortcuts.valueAt(i);
            final int idSize = ids.size();
            for (int j = 0; j < idSize; j++) {
                ShortcutService.writeTagValue(out, TAG_PIN, ids.valueAt(j));
            }
            out.endTag(null, TAG_PACKAGE);
        }

        out.endTag(null, TAG_ROOT);
    }

    /**
     * Load.
     */
    public static ShortcutLauncher loadFromXml(XmlPullParser parser, ShortcutUser shortcutUser,
            int ownerUserId, boolean fromBackup) throws IOException, XmlPullParserException {
        final String launcherPackageName = ShortcutService.parseStringAttribute(parser,
                ATTR_PACKAGE_NAME);

        // If restoring, just use the real user ID.
        final int launcherUserId =
                fromBackup ? ownerUserId
                : ShortcutService.parseIntAttribute(parser, ATTR_LAUNCHER_USER_ID, ownerUserId);

        final ShortcutLauncher ret = new ShortcutLauncher(shortcutUser, launcherUserId,
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
                        ret.mPinnedShortcuts.put(
                                PackageWithUser.of(packageUserId, packageName), ids);
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

    public void dump(@NonNull ShortcutService s, @NonNull PrintWriter pw, @NonNull String prefix) {
        pw.println();

        pw.print(prefix);
        pw.print("Launcher: ");
        pw.print(getPackageName());
        pw.print("  Package user: ");
        pw.print(getPackageUserId());
        pw.print("  Owner user: ");
        pw.print(getOwnerUserId());
        pw.println();

        getPackageInfo().dump(s, pw, prefix + "  ");
        pw.println();

        final int size = mPinnedShortcuts.size();
        for (int i = 0; i < size; i++) {
            pw.println();

            final PackageWithUser pu = mPinnedShortcuts.keyAt(i);

            pw.print(prefix);
            pw.print("  ");
            pw.print("Package: ");
            pw.print(pu.packageName);
            pw.print("  User: ");
            pw.println(pu.userId);

            final ArraySet<String> ids = mPinnedShortcuts.valueAt(i);
            final int idSize = ids.size();

            for (int j = 0; j < idSize; j++) {
                pw.print(prefix);
                pw.print("    Pinned: ");
                pw.print(ids.valueAt(j));
                pw.println();
            }
        }
    }

    @VisibleForTesting
    ArraySet<String> getAllPinnedShortcutsForTest(String packageName, int packageUserId) {
        return new ArraySet<>(mPinnedShortcuts.get(PackageWithUser.of(packageUserId, packageName)));
    }
}
