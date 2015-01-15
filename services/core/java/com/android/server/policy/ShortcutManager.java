/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server.policy;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyCharacterMap;
import com.android.internal.util.XmlUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Manages quick launch shortcuts by:
 * <li> Keeping the local copy in sync with the database (this is an observer)
 * <li> Returning a shortcut-matching intent to clients
 */
class ShortcutManager {
    private static final String TAG = "ShortcutManager";

    private static final String TAG_BOOKMARKS = "bookmarks";
    private static final String TAG_BOOKMARK = "bookmark";

    private static final String ATTRIBUTE_PACKAGE = "package";
    private static final String ATTRIBUTE_CLASS = "class";
    private static final String ATTRIBUTE_SHORTCUT = "shortcut";
    private static final String ATTRIBUTE_CATEGORY = "category";

    private final SparseArray<ShortcutInfo> mShortcuts = new SparseArray<>();

    private final Context mContext;
    
    public ShortcutManager(Context context) {
        mContext = context;
        loadShortcuts();
    }

    /**
     * Gets the shortcut intent for a given keycode+modifier. Make sure you
     * strip whatever modifier is used for invoking shortcuts (for example,
     * if 'Sym+A' should invoke a shortcut on 'A', you should strip the
     * 'Sym' bit from the modifiers before calling this method.
     * <p>
     * This will first try an exact match (with modifiers), and then try a
     * match without modifiers (primary character on a key).
     * 
     * @param kcm The key character map of the device on which the key was pressed.
     * @param keyCode The key code.
     * @param metaState The meta state, omitting any modifiers that were used
     * to invoke the shortcut.
     * @return The intent that matches the shortcut, or null if not found.
     */
    public Intent getIntent(KeyCharacterMap kcm, int keyCode, int metaState) {
        ShortcutInfo shortcut = null;

        // First try the exact keycode (with modifiers).
        int shortcutChar = kcm.get(keyCode, metaState);
        if (shortcutChar != 0) {
            shortcut = mShortcuts.get(shortcutChar);
        }

        // Next try the primary character on that key.
        if (shortcut == null) {
            shortcutChar = Character.toLowerCase(kcm.getDisplayLabel(keyCode));
            if (shortcutChar != 0) {
                shortcut = mShortcuts.get(shortcutChar);
            }
        }

        return (shortcut != null) ? shortcut.intent : null;
    }

    private void loadShortcuts() {
        PackageManager packageManager = mContext.getPackageManager();
        try {
            XmlResourceParser parser = mContext.getResources().getXml(
                    com.android.internal.R.xml.bookmarks);
            XmlUtils.beginDocument(parser, TAG_BOOKMARKS);

            while (true) {
                XmlUtils.nextElement(parser);

                if (parser.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                if (!TAG_BOOKMARK.equals(parser.getName())) {
                    break;
                }

                String packageName = parser.getAttributeValue(null, ATTRIBUTE_PACKAGE);
                String className = parser.getAttributeValue(null, ATTRIBUTE_CLASS);
                String shortcutName = parser.getAttributeValue(null, ATTRIBUTE_SHORTCUT);
                String categoryName = parser.getAttributeValue(null, ATTRIBUTE_CATEGORY);

                if (TextUtils.isEmpty(shortcutName)) {
                    Log.w(TAG, "Unable to get shortcut for: " + packageName + "/" + className);
                    continue;
                }

                final int shortcutChar = shortcutName.charAt(0);

                final Intent intent;
                final String title;
                if (packageName != null && className != null) {
                    ActivityInfo info = null;
                    ComponentName componentName = new ComponentName(packageName, className);
                    try {
                        info = packageManager.getActivityInfo(componentName, 0);
                    } catch (PackageManager.NameNotFoundException e) {
                        String[] packages = packageManager.canonicalToCurrentPackageNames(
                                new String[] { packageName });
                        componentName = new ComponentName(packages[0], className);
                        try {
                            info = packageManager.getActivityInfo(componentName, 0);
                        } catch (PackageManager.NameNotFoundException e1) {
                            Log.w(TAG, "Unable to add bookmark: " + packageName
                                    + "/" + className, e);
                            continue;
                        }
                    }

                    intent = new Intent(Intent.ACTION_MAIN);
                    intent.addCategory(Intent.CATEGORY_LAUNCHER);
                    intent.setComponent(componentName);
                    title = info.loadLabel(packageManager).toString();
                } else if (categoryName != null) {
                    intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, categoryName);
                    title = "";
                } else {
                    Log.w(TAG, "Unable to add bookmark for shortcut " + shortcutName
                            + ": missing package/class or category attributes");
                    continue;
                }

                ShortcutInfo shortcut = new ShortcutInfo(title, intent);
                mShortcuts.put(shortcutChar, shortcut);
            }
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Got exception parsing bookmarks.", e);
        } catch (IOException e) {
            Log.w(TAG, "Got exception parsing bookmarks.", e);
        }
    }

    private static final class ShortcutInfo {
        public final String title;
        public final Intent intent;

        public ShortcutInfo(String title, Intent intent) {
            this.title = title;
            this.intent = intent;
        }
    }
}
