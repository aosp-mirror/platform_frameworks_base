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

package com.android.internal.pm.pkg.component;


import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.util.ArraySet;

import com.android.internal.R;
import com.android.internal.pm.pkg.parsing.ParsingPackage;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Set;

/**
 * Utility methods for handling the tag {@code <install-constraints/>}
 *
 * @hide
 */
public class InstallConstraintsTagParser {

    private static final String TAG_FINGERPRINT_PREFIX = "fingerprint-prefix";

    /**
     * @hide
     */
    public static ParseResult<ParsingPackage> parseInstallConstraints(
            ParseInput input, ParsingPackage pkg, Resources res, XmlResourceParser parser,
            Set<String> allowlist) throws XmlPullParserException, IOException {
        if (!allowlist.contains(pkg.getPackageName())) {
            return input.skip("install-constraints cannot be used by this package");
        }

        ParseResult<Set<String>> prefixes = parseFingerprintPrefixes(input, res, parser);
        if (prefixes.isSuccess()) {
            if (validateFingerprintPrefixes(prefixes.getResult())) {
                return input.success(pkg);
            } else {
                return input.skip(
                        "Install of this package is restricted on this device; device fingerprint"
                                + " does not start with one of the allowed prefixes");
            }
        }
        return input.skip(prefixes.getErrorMessage());
    }

    private static ParseResult<Set<String>> parseFingerprintPrefixes(
            ParseInput input, Resources res, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        Set<String> prefixes = new ArraySet<>();
        int type;
        while (true) {
            // move to the tag that contains the next prefix
            type = parser.next();
            if (type == XmlPullParser.END_TAG) {
                if (prefixes.size() == 0) {
                    return input.error("install-constraints must contain at least one constraint");
                }
                return input.success(prefixes);
            } else if (type == XmlPullParser.START_TAG) {
                if (ParsingPackageUtils.getAconfigFlags().skipCurrentElement(parser)) {
                    continue;
                }
                if (parser.getName().equals(TAG_FINGERPRINT_PREFIX)) {
                    ParseResult<String> parsedPrefix =
                            readFingerprintPrefixValue(input, res, parser);
                    if (parsedPrefix.isSuccess()) {
                        prefixes.add(parsedPrefix.getResult());
                    } else {
                        return input.error(parsedPrefix.getErrorMessage());
                    }
                } else {
                    return input.error("Unexpected tag: " + parser.getName());
                }

                // consume the end tag of this attribute
                type = parser.next();
                if (type != XmlPullParser.END_TAG) {
                    return input.error("Expected end tag; instead got " + type);
                }
            }
        }
    }

    private static ParseResult<String> readFingerprintPrefixValue(ParseInput input, Resources res,
            XmlResourceParser parser) {
        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestInstallConstraintsFingerprintPrefix);
        try {
            String value = sa.getString(
                    R.styleable.AndroidManifestInstallConstraintsFingerprintPrefix_value);
            if (value == null) {
                return input.error("Failed to specify prefix value");
            }
            return input.success(value);
        } finally {
            sa.recycle();
        }
    }

    private static boolean validateFingerprintPrefixes(Set<String> prefixes) {
        String fingerprint = Build.FINGERPRINT;
        for (String prefix : prefixes) {
            if (fingerprint.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
