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
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
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
    static final String METADATA_KEY = "android.app.shortcuts";

    private static final String TAG_SHORTCUTS = "shortcuts";
    private static final String TAG_SHORTCUT = "shortcut";
    private static final String TAG_INTENT = "intent";
    private static final String TAG_CATEGORIES = "categories";

    @Nullable
    public static List<ShortcutInfo> parseShortcuts(ShortcutService service,
            String packageName, @UserIdInt int userId) throws IOException, XmlPullParserException {
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, String.format("Scanning package %s for manifest shortcuts on user %d",
                    packageName, userId));
        }
        final List<ResolveInfo> activities = service.injectGetMainActivities(packageName, userId);
        if (activities == null || activities.size() == 0) {
            return null;
        }

        List<ShortcutInfo> result = null;

        try {
            final int size = activities.size();
            for (int i = 0; i < size; i++) {
                final ActivityInfo activityInfoNoMetadata = activities.get(i).activityInfo;
                if (activityInfoNoMetadata == null) {
                    continue;
                }

                final ActivityInfo activityInfoWithMetadata =
                        service.getActivityInfoWithMetadata(
                        activityInfoNoMetadata.getComponentName(), userId);
                if (activityInfoWithMetadata != null) {
                    result = parseShortcutsOneFile(
                            service, activityInfoWithMetadata, packageName, userId, result);
                }
            }
        } catch (RuntimeException e) {
            // Resource ID mismatch may cause various runtime exceptions when parsing XMLs,
            // But we don't crash the device, so just swallow them.
            service.wtf(
                    "Exception caught while parsing shortcut XML for package=" + packageName, e);
            return null;
        }
        return result;
    }

    private static List<ShortcutInfo> parseShortcutsOneFile(
            ShortcutService service,
            ActivityInfo activityInfo, String packageName, @UserIdInt int userId,
            List<ShortcutInfo> result) throws IOException, XmlPullParserException {
        if (ShortcutService.DEBUG) {
            Slog.d(TAG, String.format(
                    "Checking main activity %s", activityInfo.getComponentName()));
        }

        XmlResourceParser parser = null;
        try {
            parser = service.injectXmlMetaData(activityInfo, METADATA_KEY);
            if (parser == null) {
                return result;
            }

            final ComponentName activity = new ComponentName(packageName, activityInfo.name);

            final AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;

            int rank = 0;
            final int maxShortcuts = service.getMaxActivityShortcuts();
            int numShortcuts = 0;

            // We instantiate ShortcutInfo at <shortcut>, but we add it to the list at </shortcut>,
            // after parsing <intent>.  We keep the current one in here.
            ShortcutInfo currentShortcut = null;

            Set<String> categories = null;
            final ArrayList<Intent> intents = new ArrayList<>();

            outer:
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > 0)) {
                final int depth = parser.getDepth();
                final String tag = parser.getName();

                // When a shortcut tag is closing, publish.
                if ((type == XmlPullParser.END_TAG) && (depth == 2) && (TAG_SHORTCUT.equals(tag))) {
                    if (currentShortcut == null) {
                        // Shortcut was invalid.
                        continue;
                    }
                    final ShortcutInfo si = currentShortcut;
                    currentShortcut = null; // Make sure to null out for the next iteration.

                    if (si.isEnabled()) {
                        if (intents.size() == 0) {
                            Log.e(TAG, "Shortcut " + si.getId() + " has no intent. Skipping it.");
                            continue;
                        }
                    } else {
                        // Just set the default intent to disabled shortcuts.
                        intents.clear();
                        intents.add(new Intent(Intent.ACTION_VIEW));
                    }

                    if (numShortcuts >= maxShortcuts) {
                        Log.e(TAG, "More than " + maxShortcuts + " shortcuts found for "
                                + activityInfo.getComponentName() + ". Skipping the rest.");
                        return result;
                    }

                    // Same flag as what TaskStackBuilder adds.
                    intents.get(0).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TASK |
                            Intent.FLAG_ACTIVITY_TASK_ON_HOME);
                    try {
                        si.setIntents(intents.toArray(new Intent[intents.size()]));
                    } catch (RuntimeException e) {
                        // This shouldn't happen because intents in XML can't have complicated
                        // extras, but just in case Intent.parseIntent() supports such a thing one
                        // day.
                        Log.e(TAG, "Shortcut's extras contain un-persistable values. Skipping it.");
                        continue;
                    }
                    intents.clear();

                    if (categories != null) {
                        si.setCategories(categories);
                        categories = null;
                    }

                    if (result == null) {
                        result = new ArrayList<>();
                    }
                    result.add(si);
                    numShortcuts++;
                    rank++;
                    if (ShortcutService.DEBUG) {
                        Slog.d(TAG, "Shortcut added: " + si.toInsecureString());
                    }
                    continue;
                }

                // Otherwise, just look at start tags.
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }

                if (depth == 1 && TAG_SHORTCUTS.equals(tag)) {
                    continue; // Root tag.
                }
                if (depth == 2 && TAG_SHORTCUT.equals(tag)) {
                    final ShortcutInfo si = parseShortcutAttributes(
                            service, attrs, packageName, activity, userId, rank);
                    if (si == null) {
                        // Shortcut was invalid.
                        continue;
                    }
                    if (ShortcutService.DEBUG) {
                        Slog.d(TAG, "Shortcut found: " + si.toInsecureString());
                    }
                    if (result != null) {
                        for (int i = result.size() - 1; i >= 0; i--) {
                            if (si.getId().equals(result.get(i).getId())) {
                                Log.e(TAG, "Duplicate shortcut ID detected. Skipping it.");
                                continue outer;
                            }
                        }
                    }
                    currentShortcut = si;
                    categories = null;
                    continue;
                }
                if (depth == 3 && TAG_INTENT.equals(tag)) {
                    if ((currentShortcut == null)
                            || !currentShortcut.isEnabled()) {
                        Log.e(TAG, "Ignoring excessive intent tag.");
                        continue;
                    }

                    final Intent intent = Intent.parseIntent(service.mContext.getResources(),
                            parser, attrs);
                    if (TextUtils.isEmpty(intent.getAction())) {
                        Log.e(TAG, "Shortcut intent action must be provided. activity=" + activity);
                        currentShortcut = null; // Invalidate the current shortcut.
                        continue;
                    }
                    intents.add(intent);
                    continue;
                }
                if (depth == 3 && TAG_CATEGORIES.equals(tag)) {
                    if ((currentShortcut == null)
                            || (currentShortcut.getCategories() != null)) {
                        continue;
                    }
                    final String name = parseCategories(service, attrs);
                    if (TextUtils.isEmpty(name)) {
                        Log.e(TAG, "Empty category found. activity=" + activity);
                        continue;
                    }

                    if (categories == null) {
                        categories = new ArraySet<>();
                    }
                    categories.add(name);
                    continue;
                }

                Log.w(TAG, String.format("Invalid tag '%s' found at depth %d", tag, depth));
            }
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
        return result;
    }

    private static String parseCategories(ShortcutService service, AttributeSet attrs) {
        final TypedArray sa = service.mContext.getResources().obtainAttributes(attrs,
                R.styleable.ShortcutCategories);
        try {
            if (sa.getType(R.styleable.ShortcutCategories_name) == TypedValue.TYPE_STRING) {
                return sa.getNonResourceString(R.styleable.ShortcutCategories_name);
            } else {
                Log.w(TAG, "android:name for shortcut category must be string literal.");
                return null;
            }
        } finally {
            sa.recycle();
        }
    }

    private static ShortcutInfo parseShortcutAttributes(ShortcutService service,
            AttributeSet attrs, String packageName, ComponentName activity,
            @UserIdInt int userId, int rank) {
        final TypedArray sa = service.mContext.getResources().obtainAttributes(attrs,
                R.styleable.Shortcut);
        try {
            if (sa.getType(R.styleable.Shortcut_shortcutId) != TypedValue.TYPE_STRING) {
                Log.w(TAG, "android:shortcutId must be string literal. activity=" + activity);
                return null;
            }
            final String id = sa.getNonResourceString(R.styleable.Shortcut_shortcutId);
            final boolean enabled = sa.getBoolean(R.styleable.Shortcut_enabled, true);
            final int iconResId = sa.getResourceId(R.styleable.Shortcut_icon, 0);
            final int titleResId = sa.getResourceId(R.styleable.Shortcut_shortcutShortLabel, 0);
            final int textResId = sa.getResourceId(R.styleable.Shortcut_shortcutLongLabel, 0);
            final int disabledMessageResId = sa.getResourceId(
                    R.styleable.Shortcut_shortcutDisabledMessage, 0);

            if (TextUtils.isEmpty(id)) {
                Log.w(TAG, "android:shortcutId must be provided. activity=" + activity);
                return null;
            }
            if (titleResId == 0) {
                Log.w(TAG, "android:shortcutShortLabel must be provided. activity=" + activity);
                return null;
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
                    rank,
                    iconResId,
                    enabled);
        } finally {
            sa.recycle();
        }
    }

    private static ShortcutInfo createShortcutFromManifest(ShortcutService service,
            @UserIdInt int userId, String id, String packageName, ComponentName activityComponent,
            int titleResId, int textResId, int disabledMessageResId,
            int rank, int iconResId, boolean enabled) {

        final int flags =
                (enabled ? ShortcutInfo.FLAG_MANIFEST : ShortcutInfo.FLAG_DISABLED)
                | ShortcutInfo.FLAG_IMMUTABLE
                | ((iconResId != 0) ? ShortcutInfo.FLAG_HAS_ICON_RES : 0);

        // Note we don't need to set resource names here yet.  They'll be set when they're about
        // to be published.
        return new ShortcutInfo(
                userId,
                id,
                packageName,
                activityComponent,
                null, // icon
                null, // title string
                titleResId,
                null, // title res name
                null, // text string
                textResId,
                null, // text res name
                null, // disabled message string
                disabledMessageResId,
                null, // disabled message res name
                null, // categories
                null, // intent
                rank,
                null, // extras
                service.injectCurrentTimeMillis(),
                flags,
                iconResId,
                null, // icon res name
                null); // bitmap path
    }
}
