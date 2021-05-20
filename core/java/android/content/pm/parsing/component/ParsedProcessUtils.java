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
import android.content.pm.ApplicationInfo;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.ParsingUtils;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.R;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Set;

/** @hide */
public class ParsedProcessUtils {

    private static final String TAG = ParsingUtils.TAG;

    @NonNull
    private static ParseResult<Set<String>> parseDenyPermission(Set<String> perms,
            Resources res, XmlResourceParser parser, ParseInput input)
            throws IOException, XmlPullParserException {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestDenyPermission);
        try {
            String perm = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestDenyPermission_name, 0);
            if (perm != null && perm.equals(android.Manifest.permission.INTERNET)) {
                perms = CollectionUtils.add(perms, perm);
            }
        } finally {
            sa.recycle();
        }
        XmlUtils.skipCurrentTag(parser);
        return input.success(perms);
    }

    @NonNull
    private static ParseResult<Set<String>> parseAllowPermission(Set<String> perms, Resources res,
            XmlResourceParser parser, ParseInput input)
            throws IOException, XmlPullParserException {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestAllowPermission);
        try {
            String perm = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestAllowPermission_name, 0);
            if (perm != null && perm.equals(android.Manifest.permission.INTERNET)) {
                perms = CollectionUtils.remove(perms, perm);
            }
        } finally {
            sa.recycle();
        }
        XmlUtils.skipCurrentTag(parser);
        return input.success(perms);
    }

    @NonNull
    private static ParseResult<ParsedProcess> parseProcess(Set<String> perms, String[] separateProcesses,
            ParsingPackage pkg, Resources res, XmlResourceParser parser, int flags,
            ParseInput input) throws IOException, XmlPullParserException {
        ParsedProcess proc = new ParsedProcess();
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestProcess);
        try {
            if (perms != null) {
                proc.deniedPermissions = new ArraySet<>(perms);
            }

            proc.name = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestProcess_process, 0);
            ParseResult<String> processNameResult = ComponentParseUtils.buildProcessName(
                    pkg.getPackageName(), pkg.getPackageName(), proc.name, flags, separateProcesses,
                    input);
            if (processNameResult.isError()) {
                return input.error(processNameResult);
            }

            proc.name = processNameResult.getResult();

            if (proc.name == null || proc.name.length() <= 0) {
                return input.error("<process> does not specify android:process");
            }

            proc.gwpAsanMode = sa.getInt(R.styleable.AndroidManifestProcess_gwpAsanMode, -1);
            proc.memtagMode = sa.getInt(R.styleable.AndroidManifestProcess_memtagMode, -1);
            if (sa.hasValue(R.styleable.AndroidManifestProcess_nativeHeapZeroInitialized)) {
                Boolean v = sa.getBoolean(
                        R.styleable.AndroidManifestProcess_nativeHeapZeroInitialized, false);
                proc.nativeHeapZeroInitialized =
                        v ? ApplicationInfo.ZEROINIT_ENABLED : ApplicationInfo.ZEROINIT_DISABLED;
            }
        } finally {
            sa.recycle();
        }

        int type;
        final int innerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            ParseResult<?> result;

            String tagName = parser.getName();
            switch (tagName) {
                case "deny-permission":
                    ParseResult<Set<String>> denyResult = parseDenyPermission(
                            proc.deniedPermissions, res, parser, input);
                    result = denyResult;
                    if (denyResult.isSuccess()) {
                        proc.deniedPermissions = denyResult.getResult();
                    }
                    break;
                case "allow-permission":
                    ParseResult<Set<String>> allowResult = parseAllowPermission(
                            proc.deniedPermissions, res, parser, input);
                    result = allowResult;
                    if (allowResult.isSuccess()) {
                        proc.deniedPermissions = allowResult.getResult();
                    }
                    break;
                default:
                    result = ParsingUtils.unknownTag("<process>", pkg, parser, input);
                    break;
            }

            if (result.isError()) {
                return input.error(result);
            }
        }

        return input.success(proc);
    }

    @NonNull
    public static ParseResult<ArrayMap<String, ParsedProcess>> parseProcesses(
            String[] separateProcesses, ParsingPackage pkg, Resources res,
            XmlResourceParser parser, int flags, ParseInput input)
            throws IOException, XmlPullParserException {
        Set<String> deniedPerms = null;
        ArrayMap<String, ParsedProcess> processes = new ArrayMap<>();

        int type;
        final int innerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            ParseResult<?> result;

            String tagName = parser.getName();
            switch (tagName) {
                case "deny-permission":
                    ParseResult<Set<String>> denyResult = parseDenyPermission(deniedPerms, res,
                            parser, input);
                    result = denyResult;
                    if (denyResult.isSuccess()) {
                        deniedPerms = denyResult.getResult();
                    }
                    break;
                case "allow-permission":
                    ParseResult<Set<String>> allowResult = parseAllowPermission(deniedPerms, res,
                            parser, input);
                    result = allowResult;
                    if (allowResult.isSuccess()) {
                        deniedPerms = allowResult.getResult();
                    }
                    break;
                case "process":
                    ParseResult<ParsedProcess> processResult = parseProcess(deniedPerms,
                            separateProcesses, pkg, res, parser, flags, input);
                    result = processResult;
                    if (processResult.isSuccess()) {
                        ParsedProcess process = processResult.getResult();
                        if (processes.put(process.name, process) != null) {
                            result = input.error(
                                    "<process> specified existing name '" + process.name + "'");
                        }
                    }
                    break;
                default:
                    result = ParsingUtils.unknownTag("<processes>", pkg, parser, input);
                    break;
            }

            if (result.isError()) {
                return input.error(result);
            }

        }

        return input.success(processes);
    }
}
