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

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ShortcutParser {
    private static final String TAG = ShortcutService.TAG;

    private static final boolean DEBUG = ShortcutService.DEBUG || false; // DO NOT SUBMIT WITH TRUE

    @VisibleForTesting
    static final String METADATA_KEY = "android.pm.Shortcuts";

    private static final String TAG_SHORTCUTS = "shortcuts";
    private static final String TAG_SHORTCUT = "shortcut";

    @Nullable
    public static List<ShortcutInfo> parseShortcuts(ShortcutService service,
            String packageName, @UserIdInt int userId) throws IOException, XmlPullParserException {
        final PackageInfo pi = service.injectGetActivitiesWithMetadata(packageName, userId);

        List<ShortcutInfo> result = null;

        if (pi != null && pi.activities != null) {
            for (ActivityInfo activityInfo : pi.activities) {
                result = parseShortcutsOneFile(service, activityInfo, packageName, userId, result);
            }
        }
        return result;
    }

    private static List<ShortcutInfo> parseShortcutsOneFile(
            ShortcutService service,
            ActivityInfo activityInfo, String packageName, @UserIdInt int userId,
            List<ShortcutInfo> result) throws IOException, XmlPullParserException {
        XmlResourceParser parser = null;
        try {
            parser = service.injectXmlMetaData(activityInfo, METADATA_KEY);
            if (parser == null) {
                return result;
            }

            final ComponentName activity = new ComponentName(packageName, activityInfo.name);

            final AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;

            outer:
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > 0)) {
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                final int depth = parser.getDepth();
                final String tag = parser.getName();

                if (depth == 1 && TAG_SHORTCUTS.equals(tag)) {
                    continue; // Root tag.
                }
                if (depth == 2 && TAG_SHORTCUT.equals(tag)) {
                    final ShortcutInfo si = parseShortcutAttributes(
                            service, attrs, packageName, activity, userId);
                    if (ShortcutService.DEBUG) {
                        Slog.d(TAG, "Shortcut=" + si);
                    }
                    if (result != null) {
                        for (int i = result.size() - 1; i >= 0; i--) {
                            if (si.getId().equals(result.get(i).getId())) {
                                Slog.w(TAG, "Duplicate shortcut ID detected, skipping.");
                                continue outer;
                            }
                        }
                    }

                    if (si != null) {
                        if (result == null) {
                            result = new ArrayList<>();
                        }
                        result.add(si);
                    }
                    continue;
                }
                Slog.w(TAG, "Unknown tag " + tag);
            }
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
        return result;
    }

    private static ShortcutInfo parseShortcutAttributes(ShortcutService service,
            AttributeSet attrs, String packageName, ComponentName activity,
            @UserIdInt int userId) {
        final TypedArray sa = service.mContext.getResources().obtainAttributes(attrs,
                R.styleable.Shortcut);
        try {
            final String id = sa.getString(R.styleable.Shortcut_shortcutId);
            final boolean enabled = sa.getBoolean(R.styleable.Shortcut_enabled, true);
            final int rank = sa.getInt(R.styleable.Shortcut_shortcutRank, 0);
            final int iconResId = sa.getResourceId(R.styleable.Shortcut_shortcutIcon, 0);
            final int titleResId = sa.getResourceId(R.styleable.Shortcut_shortcutTitle, 0);
            final int textResId = sa.getResourceId(R.styleable.Shortcut_shortcutText, 0);
            final int disabledMessageResId = sa.getResourceId(
                    R.styleable.Shortcut_shortcutDisabledMessage, 0);
            final String categories = sa.getString(R.styleable.Shortcut_shortcutCategories);
            String intentAction = sa.getString(R.styleable.Shortcut_shortcutIntentAction);
            final String intentData = sa.getString(R.styleable.Shortcut_shortcutIntentData);

            if (TextUtils.isEmpty(id)) {
                Slog.w(TAG, "Shortcut ID must be provided. activity=" + activity);
                return null;
            }
            if (titleResId == 0) {
                Slog.w(TAG, "Shortcut title must be provided. activity=" + activity);
                return null;
            }
            if (TextUtils.isEmpty(intentAction)) {
                if (enabled) {
                    Slog.w(TAG, "Shortcut intent action must be provided. activity=" + activity);
                    return null;
                } else {
                    // Disabled shortcut doesn't have to have an action, but just set VIEW as the
                    // default.
                    intentAction = Intent.ACTION_VIEW;
                }
            }

            final ArraySet<String> categoriesSet;
            if (categories == null) {
                categoriesSet = null;
            } else {
                final String[] arr = categories.split(":");
                categoriesSet = new ArraySet<>(arr.length);
                for (String v : arr) {
                    categoriesSet.add(v);
                }
            }
            final Intent intent = new Intent(intentAction);
            if (!TextUtils.isEmpty(intentData)) {
                intent.setData(Uri.parse(intentData));
            }

            return createShortcutFromManifest(
                    service,
                    userId,
                    id,
                    packageName,
                    activity,
                    titleResId,
                    textResId,
                    disabledMessageResId,
                    categoriesSet,
                    intent,
                    rank,
                    iconResId,
                    enabled);
        } finally {
            sa.recycle();
        }
    }

    private static ShortcutInfo createShortcutFromManifest(ShortcutService service,
            @UserIdInt int userId, String id, String packageName, ComponentName activityComponent,
            int titleResId, int textResId, int disabledMessageResId, Set<String> categories,
            Intent intent, int rank, int iconResId, boolean enabled) {

        final int flags =
                (enabled ? ShortcutInfo.FLAG_MANIFEST : ShortcutInfo.FLAG_DISABLED)
                | ShortcutInfo.FLAG_IMMUTABLE
                | ((iconResId != 0) ? ShortcutInfo.FLAG_HAS_ICON_RES : 0);

        return new ShortcutInfo(
                userId,
                id,
                packageName,
                activityComponent,
                null, // icon
                null, // title string
                titleResId,
                null, // text string
                textResId,
                null, // disabled message string
                disabledMessageResId,
                categories,
                intent,
                null, // intent extras
                rank,
                null, // extras
                service.injectCurrentTimeMillis(),
                flags,
                iconResId,
                null); // bitmap path
    }
}
