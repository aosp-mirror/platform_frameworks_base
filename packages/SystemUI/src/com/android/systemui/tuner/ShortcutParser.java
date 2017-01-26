/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.tuner;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Icon;
import android.util.AttributeSet;
import android.util.Xml;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ShortcutParser {
    private static final String SHORTCUTS = "android.app.shortcuts";
    private static final String SHORTCUT = "shortcut";
    private static final String INTENT = "intent";

    private final Context mContext;
    private final String mPkg;
    private final int mResId;
    private final String mName;
    private Resources mResources;
    private AttributeSet mAttrs;

    public ShortcutParser(Context context, ComponentName component) throws NameNotFoundException {
        this(context, component.getPackageName(), component.getClassName(),
                getResId(context, component));
    }

    private static int getResId(Context context, ComponentName component)
            throws NameNotFoundException {
        ActivityInfo i = context.getPackageManager().getActivityInfo(
                component, PackageManager.GET_META_DATA);
        int resId = 0;
        if (i.metaData != null && i.metaData.containsKey(SHORTCUTS)) {
            resId = i.metaData.getInt(SHORTCUTS);
        }
        return resId;
    }

    public ShortcutParser(Context context, String pkg, String name, int resId) {
        mContext = context;
        mPkg = pkg;
        mResId = resId;
        mName = name;
    }

    public List<Shortcut> getShortcuts() {
        List<Shortcut> list = new ArrayList<>();
        if (mResId != 0) {
            try {
                mResources = mContext.getPackageManager().getResourcesForApplication(mPkg);
                XmlResourceParser parser = mResources.getXml(mResId);
                mAttrs = Xml.asAttributeSet(parser);
                int type;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type != XmlPullParser.START_TAG) {
                        continue;
                    }
                    if (parser.getName().equals(SHORTCUT)) {
                        Shortcut c = parseShortcut(parser);
                        if (c != null) {
                            list.add(c);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return list;
    }

    private Shortcut parseShortcut(XmlResourceParser parser)
            throws IOException, XmlPullParserException {
        final TypedArray sa = mResources.obtainAttributes(mAttrs, R.styleable.Shortcut);
        Shortcut c = new Shortcut();

        final boolean enabled = sa.getBoolean(R.styleable.Shortcut_enabled, true);
        if (!enabled) return null;
        final String id = sa.getString(R.styleable.Shortcut_shortcutId);
        final int iconResId = sa.getResourceId(R.styleable.Shortcut_icon, 0);
        final int titleResId = sa.getResourceId(R.styleable.Shortcut_shortcutShortLabel, 0);

        c.pkg = mPkg;
        c.icon = Icon.createWithResource(mPkg, iconResId);
        c.id = id;
        c.label = mResources.getString(titleResId);
        c.name = mName;
        int type;
        while ((type = parser.next()) != XmlPullParser.END_TAG) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            if (parser.getName().equals(INTENT)) {
                c.intent = Intent.parseIntent(mResources, parser, mAttrs);
            }
        }
        return c.intent != null ? c : null;
    }

    public static class Shortcut {
        public Intent intent;
        public String label;
        public Icon icon;
        public String pkg;
        public String id;
        public String name;

        public static Shortcut create(Context context, String value) {
            String[] sp = value.split("::");
            try {
                for (Shortcut shortcut : new ShortcutParser(context,
                        new ComponentName(sp[0], sp[1])).getShortcuts()) {
                    if (shortcut.id.equals(sp[2])) {
                        return shortcut;
                    }
                }
            } catch (NameNotFoundException e) {
            }
            return null;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append(pkg);
            builder.append("::");
            builder.append(name);
            builder.append("::");
            builder.append(id);
            return builder.toString();
        }
    }
}
