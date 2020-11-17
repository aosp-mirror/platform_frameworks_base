/*
 * Copyright 2018 The Android Open Source Project
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
import android.text.TextUtils;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Represents a Share Target definition, read from the application's manifest (shortcuts.xml)
 */
class ShareTargetInfo {

    private static final String TAG_SHARE_TARGET = "share-target";
    private static final String ATTR_TARGET_CLASS = "targetClass";

    private static final String TAG_DATA = "data";
    private static final String ATTR_SCHEME = "scheme";
    private static final String ATTR_HOST = "host";
    private static final String ATTR_PORT = "port";
    private static final String ATTR_PATH = "path";
    private static final String ATTR_PATH_PATTERN = "pathPattern";
    private static final String ATTR_PATH_PREFIX = "pathPrefix";
    private static final String ATTR_MIME_TYPE = "mimeType";

    private static final String TAG_CATEGORY = "category";
    private static final String ATTR_NAME = "name";

    static class TargetData {
        final String mScheme;
        final String mHost;
        final String mPort;
        final String mPath;
        final String mPathPattern;
        final String mPathPrefix;
        final String mMimeType;

        TargetData(String scheme, String host, String port, String path, String pathPattern,
                String pathPrefix, String mimeType) {
            mScheme = scheme;
            mHost = host;
            mPort = port;
            mPath = path;
            mPathPattern = pathPattern;
            mPathPrefix = pathPrefix;
            mMimeType = mimeType;
        }

        public void toStringInner(StringBuilder strBuilder) {
            if (!TextUtils.isEmpty(mScheme)) {
                strBuilder.append(" scheme=").append(mScheme);
            }
            if (!TextUtils.isEmpty(mHost)) {
                strBuilder.append(" host=").append(mHost);
            }
            if (!TextUtils.isEmpty(mPort)) {
                strBuilder.append(" port=").append(mPort);
            }
            if (!TextUtils.isEmpty(mPath)) {
                strBuilder.append(" path=").append(mPath);
            }
            if (!TextUtils.isEmpty(mPathPattern)) {
                strBuilder.append(" pathPattern=").append(mPathPattern);
            }
            if (!TextUtils.isEmpty(mPathPrefix)) {
                strBuilder.append(" pathPrefix=").append(mPathPrefix);
            }
            if (!TextUtils.isEmpty(mMimeType)) {
                strBuilder.append(" mimeType=").append(mMimeType);
            }
        }

        @Override
        public String toString() {
            StringBuilder strBuilder = new StringBuilder();
            toStringInner(strBuilder);
            return strBuilder.toString();
        }
    }

    final TargetData[] mTargetData;
    final String mTargetClass;
    final String[] mCategories;

    ShareTargetInfo(TargetData[] data, String targetClass, String[] categories) {
        mTargetData = data;
        mTargetClass = targetClass;
        mCategories = categories;
    }

    @Override
    public String toString() {
        StringBuilder strBuilder = new StringBuilder();
        strBuilder.append("targetClass=").append(mTargetClass);
        for (int i = 0; i < mTargetData.length; i++) {
            strBuilder.append(" data={");
            mTargetData[i].toStringInner(strBuilder);
            strBuilder.append("}");
        }
        for (int i = 0; i < mCategories.length; i++) {
            strBuilder.append(" category=").append(mCategories[i]);
        }

        return strBuilder.toString();
    }

    void saveToXml(@NonNull TypedXmlSerializer out) throws IOException {
        out.startTag(null, TAG_SHARE_TARGET);

        ShortcutService.writeAttr(out, ATTR_TARGET_CLASS, mTargetClass);

        for (int i = 0; i < mTargetData.length; i++) {
            out.startTag(null, TAG_DATA);
            ShortcutService.writeAttr(out, ATTR_SCHEME, mTargetData[i].mScheme);
            ShortcutService.writeAttr(out, ATTR_HOST, mTargetData[i].mHost);
            ShortcutService.writeAttr(out, ATTR_PORT, mTargetData[i].mPort);
            ShortcutService.writeAttr(out, ATTR_PATH, mTargetData[i].mPath);
            ShortcutService.writeAttr(out, ATTR_PATH_PATTERN, mTargetData[i].mPathPattern);
            ShortcutService.writeAttr(out, ATTR_PATH_PREFIX, mTargetData[i].mPathPrefix);
            ShortcutService.writeAttr(out, ATTR_MIME_TYPE, mTargetData[i].mMimeType);
            out.endTag(null, TAG_DATA);
        }

        for (int i = 0; i < mCategories.length; i++) {
            out.startTag(null, TAG_CATEGORY);
            ShortcutService.writeAttr(out, ATTR_NAME, mCategories[i]);
            out.endTag(null, TAG_CATEGORY);
        }

        out.endTag(null, TAG_SHARE_TARGET);
    }

    static ShareTargetInfo loadFromXml(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        final String targetClass = ShortcutService.parseStringAttribute(parser, ATTR_TARGET_CLASS);
        final ArrayList<ShareTargetInfo.TargetData> targetData = new ArrayList<>();
        final ArrayList<String> categories = new ArrayList<>();

        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            if (type == XmlPullParser.START_TAG) {
                switch (parser.getName()) {
                    case TAG_DATA:
                        targetData.add(parseTargetData(parser));
                        break;
                    case TAG_CATEGORY:
                        categories.add(ShortcutService.parseStringAttribute(parser, ATTR_NAME));
                        break;
                }
            } else if (type == XmlPullParser.END_TAG && parser.getName().equals(TAG_SHARE_TARGET)) {
                break;
            }
        }
        if (targetData.isEmpty() || targetClass == null || categories.isEmpty()) {
            return null;
        }
        return new ShareTargetInfo(
                targetData.toArray(new ShareTargetInfo.TargetData[targetData.size()]),
                targetClass, categories.toArray(new String[categories.size()]));
    }

    private static ShareTargetInfo.TargetData parseTargetData(TypedXmlPullParser parser) {
        final String scheme = ShortcutService.parseStringAttribute(parser, ATTR_SCHEME);
        final String host = ShortcutService.parseStringAttribute(parser, ATTR_HOST);
        final String port = ShortcutService.parseStringAttribute(parser, ATTR_PORT);
        final String path = ShortcutService.parseStringAttribute(parser, ATTR_PATH);
        final String pathPattern = ShortcutService.parseStringAttribute(parser, ATTR_PATH_PATTERN);
        final String pathPrefix = ShortcutService.parseStringAttribute(parser, ATTR_PATH_PREFIX);
        final String mimeType = ShortcutService.parseStringAttribute(parser, ATTR_MIME_TYPE);

        return new ShareTargetInfo.TargetData(scheme, host, port, path, pathPattern, pathPrefix,
                mimeType);
    }
}
