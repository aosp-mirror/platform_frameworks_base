/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.pkg.component;

import android.R;
import android.annotation.NonNull;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/** @hide */
public class ParsedApexSystemServiceUtils {

    @NonNull
    public static ParseResult<ParsedApexSystemService> parseApexSystemService(
            Resources res, XmlResourceParser parser, ParseInput input)
            throws XmlPullParserException, IOException {
        final ParsedApexSystemServiceImpl systemService =
                new ParsedApexSystemServiceImpl();
        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestApexSystemService);
        try {
            String className = sa.getString(
                    R.styleable.AndroidManifestApexSystemService_name);
            if (TextUtils.isEmpty(className)) {
                return input.error("<apex-system-service> does not have name attribute");
            }

            String jarPath = sa.getString(
                    R.styleable.AndroidManifestApexSystemService_path);
            String minSdkVersion = sa.getString(
                    R.styleable.AndroidManifestApexSystemService_minSdkVersion);
            String maxSdkVersion = sa.getString(
                    R.styleable.AndroidManifestApexSystemService_maxSdkVersion);
            int initOrder = sa.getInt(R.styleable.AndroidManifestApexSystemService_initOrder, 0);

            systemService.setName(className)
                    .setMinSdkVersion(minSdkVersion)
                    .setMaxSdkVersion(maxSdkVersion)
                    .setInitOrder(initOrder);

            if (!TextUtils.isEmpty(jarPath)) {
                systemService.setJarPath(jarPath);
            }

            return input.success(systemService);
        } finally {
            sa.recycle();
        }
    }
}
