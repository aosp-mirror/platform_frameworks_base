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

import android.text.TextUtils;

/**
 * Represents a Share Target definition, read from the application's manifest (shortcuts.xml)
 */
class ShareTargetInfo {
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
}
