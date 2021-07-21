/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm.parsing.component;

import android.annotation.NonNull;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageParser;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.ParsingUtils;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.PatternMatcher;
import android.util.Slog;
import android.util.TypedValue;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Iterator;

/** @hide */
public class ParsedIntentInfoUtils {

    private static final String TAG = ParsingUtils.TAG;

    public static final boolean DEBUG = false;

    @NonNull
    public static ParseResult<ParsedIntentInfo> parseIntentInfo(String className,
            ParsingPackage pkg, Resources res, XmlResourceParser parser, boolean allowGlobs,
            boolean allowAutoVerify, ParseInput input)
            throws XmlPullParserException, IOException {
        ParsedIntentInfo intentInfo = new ParsedIntentInfo();
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestIntentFilter);
        try {
            intentInfo.setPriority(sa.getInt(R.styleable.AndroidManifestIntentFilter_priority, 0));
            intentInfo.setOrder(sa.getInt(R.styleable.AndroidManifestIntentFilter_order, 0));

            TypedValue v = sa.peekValue(R.styleable.AndroidManifestIntentFilter_label);
            if (v != null) {
                intentInfo.labelRes = v.resourceId;
                if (v.resourceId == 0) {
                    intentInfo.nonLocalizedLabel = v.coerceToString();
                }
            }

            if (ParsingPackageUtils.sUseRoundIcon) {
                intentInfo.icon = sa.getResourceId(
                        R.styleable.AndroidManifestIntentFilter_roundIcon, 0);
            }

            if (intentInfo.icon == 0) {
                intentInfo.icon = sa.getResourceId(R.styleable.AndroidManifestIntentFilter_icon, 0);
            }

            if (allowAutoVerify) {
                intentInfo.setAutoVerify(sa.getBoolean(
                        R.styleable.AndroidManifestIntentFilter_autoVerify,
                        false));
            }
        } finally {
            sa.recycle();
        }
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final ParseResult result;
            String nodeName = parser.getName();
            switch (nodeName) {
                case "action": {
                    String value = parser.getAttributeValue(PackageParser.ANDROID_RESOURCES,
                            "name");
                    if (value == null) {
                        result = input.error("No value supplied for <android:name>");
                    } else if (value.isEmpty()) {
                        intentInfo.addAction(value);
                        // Prior to R, this was not a failure
                        result = input.deferError("No value supplied for <android:name>",
                                ParseInput.DeferredError.EMPTY_INTENT_ACTION_CATEGORY);
                    } else {
                        intentInfo.addAction(value);
                        result = input.success(null);
                    }
                    break;
                }
                case "category": {
                    String value = parser.getAttributeValue(PackageParser.ANDROID_RESOURCES,
                            "name");
                    if (value == null) {
                        result = input.error("No value supplied for <android:name>");
                    } else if (value.isEmpty()) {
                        intentInfo.addCategory(value);
                        // Prior to R, this was not a failure
                        result = input.deferError("No value supplied for <android:name>",
                                ParseInput.DeferredError.EMPTY_INTENT_ACTION_CATEGORY);
                    } else {
                        intentInfo.addCategory(value);
                        result = input.success(null);
                    }
                    break;
                }
                case "data":
                    result = parseData(intentInfo, res, parser, allowGlobs, input);
                    break;
                default:
                    result = ParsingUtils.unknownTag("<intent-filter>", pkg, parser, input);
                    break;
            }

            if (result.isError()) {
                return input.error(result);
            }
        }

        intentInfo.hasDefault = intentInfo.hasCategory(Intent.CATEGORY_DEFAULT);

        if (DEBUG) {
            final StringBuilder cats = new StringBuilder("Intent d=");
            cats.append(intentInfo.isHasDefault());
            cats.append(", cat=");

            final Iterator<String> it = intentInfo.categoriesIterator();
            if (it != null) {
                while (it.hasNext()) {
                    cats.append(' ');
                    cats.append(it.next());
                }
            }
            Slog.d(TAG, cats.toString());
        }

        return input.success(intentInfo);
    }

    @NonNull
    private static ParseResult<ParsedIntentInfo> parseData(ParsedIntentInfo intentInfo,
            Resources resources, XmlResourceParser parser, boolean allowGlobs, ParseInput input) {
        TypedArray sa = resources.obtainAttributes(parser, R.styleable.AndroidManifestData);
        try {
            String str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_mimeType, 0);
            if (str != null) {
                try {
                    intentInfo.addDataType(str);
                } catch (IntentFilter.MalformedMimeTypeException e) {
                    return input.error(e.toString());
                }
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_mimeGroup, 0);
            if (str != null) {
                intentInfo.addMimeGroup(str);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_scheme, 0);
            if (str != null) {
                intentInfo.addDataScheme(str);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_ssp, 0);
            if (str != null) {
                intentInfo.addDataSchemeSpecificPart(str,
                        PatternMatcher.PATTERN_LITERAL);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_sspPrefix, 0);
            if (str != null) {
                intentInfo.addDataSchemeSpecificPart(str,
                        PatternMatcher.PATTERN_PREFIX);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_sspPattern, 0);
            if (str != null) {
                if (!allowGlobs) {
                    return input.error(
                            "sspPattern not allowed here; ssp must be literal");
                }
                intentInfo.addDataSchemeSpecificPart(str,
                        PatternMatcher.PATTERN_SIMPLE_GLOB);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_sspAdvancedPattern, 0);
            if (str != null) {
                if (!allowGlobs) {
                    return input.error(
                            "sspAdvancedPattern not allowed here; ssp must be literal");
                }
                intentInfo.addDataSchemeSpecificPart(str,
                        PatternMatcher.PATTERN_ADVANCED_GLOB);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_sspSuffix, 0);
            if (str != null) {
                intentInfo.addDataSchemeSpecificPart(str,
                        PatternMatcher.PATTERN_SUFFIX);
            }


            String host = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_host, 0);
            String port = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_port, 0);
            if (host != null) {
                intentInfo.addDataAuthority(host, port);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_path, 0);
            if (str != null) {
                intentInfo.addDataPath(str, PatternMatcher.PATTERN_LITERAL);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_pathPrefix, 0);
            if (str != null) {
                intentInfo.addDataPath(str, PatternMatcher.PATTERN_PREFIX);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_pathPattern, 0);
            if (str != null) {
                if (!allowGlobs) {
                    return input.error(
                            "pathPattern not allowed here; path must be literal");
                }
                intentInfo.addDataPath(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_pathAdvancedPattern, 0);
            if (str != null) {
                if (!allowGlobs) {
                    return input.error(
                            "pathAdvancedPattern not allowed here; path must be literal");
                }
                intentInfo.addDataPath(str, PatternMatcher.PATTERN_ADVANCED_GLOB);
            }

            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestData_pathSuffix, 0);
            if (str != null) {
                intentInfo.addDataPath(str, PatternMatcher.PATTERN_SUFFIX);
            }


            return input.success(null);
        } finally {
            sa.recycle();
        }
    }
}
