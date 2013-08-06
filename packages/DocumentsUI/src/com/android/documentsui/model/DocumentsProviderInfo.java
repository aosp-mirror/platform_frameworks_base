/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui.model;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.provider.DocumentsContract;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import com.android.documentsui.DocumentsActivity;
import com.google.android.collect.Lists;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;

/**
 * Representation of a storage backend.
 */
public class DocumentsProviderInfo {
    private static final String TAG = DocumentsActivity.TAG;

    public ProviderInfo providerInfo;
    public boolean customRoots;
    public List<Icon> customIcons;

    public static class Icon {
        public String mimeType;
        public Drawable icon;
    }

    private static final String TAG_DOCUMENTS_PROVIDER = "documents-provider";
    private static final String TAG_ICON = "icon";

    public static DocumentsProviderInfo buildRecents(Context context, ProviderInfo providerInfo) {
        final DocumentsProviderInfo info = new DocumentsProviderInfo();
        info.providerInfo = providerInfo;
        info.customRoots = false;
        return info;
    }

    public static DocumentsProviderInfo parseInfo(Context context, ProviderInfo providerInfo) {
        final DocumentsProviderInfo info = new DocumentsProviderInfo();
        info.providerInfo = providerInfo;
        info.customIcons = Lists.newArrayList();

        final PackageManager pm = context.getPackageManager();
        final Resources res;
        try {
            res = pm.getResourcesForApplication(providerInfo.applicationInfo);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Failed to find resources for " + providerInfo, e);
            return null;
        }

        XmlResourceParser parser = null;
        try {
            parser = providerInfo.loadXmlMetaData(
                    pm, DocumentsContract.META_DATA_DOCUMENT_PROVIDER);
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type = 0;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                final String tag = parser.getName();
                if (type == XmlPullParser.START_TAG && TAG_DOCUMENTS_PROVIDER.equals(tag)) {
                    final TypedArray a = res.obtainAttributes(
                            attrs, com.android.internal.R.styleable.DocumentsProviderInfo);
                    info.customRoots = a.getBoolean(
                            com.android.internal.R.styleable.DocumentsProviderInfo_customRoots,
                            false);
                    a.recycle();

                } else if (type == XmlPullParser.START_TAG && TAG_ICON.equals(tag)) {
                    final TypedArray a = res.obtainAttributes(
                            attrs, com.android.internal.R.styleable.Icon);
                    final Icon icon = new Icon();
                    icon.mimeType = a.getString(com.android.internal.R.styleable.Icon_mimeType);
                    icon.icon = a.getDrawable(com.android.internal.R.styleable.Icon_icon);
                    info.customIcons.add(icon);
                    a.recycle();
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to parse metadata", e);
            return null;
        } catch (XmlPullParserException e) {
            Log.w(TAG, "Failed to parse metadata", e);
            return null;
        } finally {
            IoUtils.closeQuietly(parser);
        }

        return info;
    }
}
