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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * Launcher information used by {@link ShortcutService}.
 */
class ShortcutLauncher {
    private static final String TAG = ShortcutService.TAG;

    static final String TAG_ROOT = "launcher-pins";

    private static final String TAG_PACKAGE = "package";
    private static final String TAG_PIN = "pin";

    private static final String ATTR_VALUE = "value";
    private static final String ATTR_PACKAGE_NAME = "package-name";

    @UserIdInt
    final int mUserId;

    @NonNull
    final String mPackageName;

    /**
     * Package name -> IDs.
     */
    final private ArrayMap<String, ArraySet<String>> mPinnedShortcuts = new ArrayMap<>();

    ShortcutLauncher(@UserIdInt int userId, @NonNull String packageName) {
        mUserId = userId;
        mPackageName = packageName;
    }

    public void pinShortcuts(@NonNull ShortcutService s, @NonNull String packageName,
            @NonNull List<String> ids) {
        final int idSize = ids.size();
        if (idSize == 0) {
            mPinnedShortcuts.remove(packageName);
        } else {
            final ArraySet<String> prevSet = mPinnedShortcuts.get(packageName);

            // Pin shortcuts.  Make sure only pin the ones that were visible to the caller.
            // i.e. a non-dynamic, pinned shortcut by *other launchers* shouldn't be pinned here.

            final ShortcutPackage packageShortcuts =
                    s.getPackageShortcutsLocked(packageName, mUserId);
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
            mPinnedShortcuts.put(packageName, newSet);
        }
        s.getPackageShortcutsLocked(packageName, mUserId).refreshPinnedFlags(s);
    }

    /**
     * Return the pinned shortcut IDs for the publisher package.
     */
    public ArraySet<String> getPinnedShortcutIds(@NonNull String packageName) {
        return mPinnedShortcuts.get(packageName);
    }

    boolean cleanUpPackage(String packageName) {
        return mPinnedShortcuts.remove(packageName) != null;
    }

    /**
     * Persist.
     */
    public void saveToXml(XmlSerializer out) throws IOException {
        final int size = mPinnedShortcuts.size();
        if (size == 0) {
            return; // Nothing to write.
        }

        out.startTag(null, TAG_ROOT);
        ShortcutService.writeAttr(out, ATTR_PACKAGE_NAME,
                mPackageName);

        for (int i = 0; i < size; i++) {
            out.startTag(null, TAG_PACKAGE);
            ShortcutService.writeAttr(out, ATTR_PACKAGE_NAME,
                    mPinnedShortcuts.keyAt(i));

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
    public static ShortcutLauncher loadFromXml(XmlPullParser parser, int userId)
            throws IOException, XmlPullParserException {
        final String launcherPackageName = ShortcutService.parseStringAttribute(parser,
                ATTR_PACKAGE_NAME);

        final ShortcutLauncher ret = new ShortcutLauncher(userId, launcherPackageName);

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
            switch (tag) {
                case TAG_PACKAGE: {
                    final String packageName = ShortcutService.parseStringAttribute(parser,
                            ATTR_PACKAGE_NAME);
                    ids = new ArraySet<>();
                    ret.mPinnedShortcuts.put(packageName, ids);
                    continue;
                }
                case TAG_PIN: {
                    ids.add(ShortcutService.parseStringAttribute(parser,
                            ATTR_VALUE));
                    continue;
                }
            }
            throw ShortcutService.throwForInvalidTag(depth, tag);
        }
        return ret;
    }

    public void dump(@NonNull ShortcutService s, @NonNull PrintWriter pw, @NonNull String prefix) {
        pw.println();

        pw.print(prefix);
        pw.print("Launcher: ");
        pw.print(mPackageName);
        pw.println();

        final int size = mPinnedShortcuts.size();
        for (int i = 0; i < size; i++) {
            pw.println();

            pw.print(prefix);
            pw.print("  ");
            pw.print("Package: ");
            pw.println(mPinnedShortcuts.keyAt(i));

            final ArraySet<String> ids = mPinnedShortcuts.valueAt(i);
            final int idSize = ids.size();

            for (int j = 0; j < idSize; j++) {
                pw.print(prefix);
                pw.print("    ");
                pw.print(ids.valueAt(j));
                pw.println();
            }
        }
    }
}
