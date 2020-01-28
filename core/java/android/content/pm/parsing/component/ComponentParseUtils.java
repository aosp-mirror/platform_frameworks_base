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

import android.annotation.AttrRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.pm.PackageParser;
import android.content.pm.PackageUserState;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.ParsingUtils;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.text.TextUtils;

import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/** @hide */
public class ComponentParseUtils {

    private static final String TAG = ParsingPackageUtils.TAG;

    public static boolean isImplicitlyExposedIntent(ParsedIntentInfo intentInfo) {
        return intentInfo.hasCategory(Intent.CATEGORY_BROWSABLE)
                || intentInfo.hasAction(Intent.ACTION_SEND)
                || intentInfo.hasAction(Intent.ACTION_SENDTO)
                || intentInfo.hasAction(Intent.ACTION_SEND_MULTIPLE);
    }

    static <Component extends ParsedComponent> ParseResult<Component> parseAllMetaData(
            ParsingPackage pkg, Resources res, XmlResourceParser parser, String tag,
            Component component, ParseInput input) throws XmlPullParserException, IOException {
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final ParseResult result;
            if ("meta-data".equals(parser.getName())) {
                result = ParsedComponentUtils.addMetaData(component, pkg, res, parser, input);
            } else {
                result = ParsingUtils.unknownTag(tag, pkg, parser, input);
            }

            if (result.isError()) {
                return input.error(result);
            }
        }

        return input.success(component);
    }

    @NonNull
    public static ParseResult<String> buildProcessName(@NonNull String pkg, String defProc,
            CharSequence procSeq, int flags, String[] separateProcesses, ParseInput input) {
        if ((flags & PackageParser.PARSE_IGNORE_PROCESSES) != 0 && !"system".contentEquals(
                procSeq)) {
            return input.success(defProc != null ? defProc : pkg);
        }
        if (separateProcesses != null) {
            for (int i = separateProcesses.length - 1; i >= 0; i--) {
                String sp = separateProcesses[i];
                if (sp.equals(pkg) || sp.equals(defProc) || sp.contentEquals(procSeq)) {
                    return input.success(pkg);
                }
            }
        }
        if (procSeq == null || procSeq.length() <= 0) {
            return input.success(defProc);
        }

        ParseResult<String> nameResult = ComponentParseUtils.buildCompoundName(pkg, procSeq,
                "process", input);
        return input.success(TextUtils.safeIntern(nameResult.getResult()));
    }

    @NonNull
    public static ParseResult<String> buildTaskAffinityName(String pkg, String defProc,
            CharSequence procSeq, ParseInput input) {
        if (procSeq == null) {
            return input.success(defProc);
        }
        if (procSeq.length() <= 0) {
            return input.success(null);
        }
        return buildCompoundName(pkg, procSeq, "taskAffinity", input);
    }

    public static ParseResult<String> buildCompoundName(String pkg, CharSequence procSeq,
            String type, ParseInput input) {
        String proc = procSeq.toString();
        char c = proc.charAt(0);
        if (pkg != null && c == ':') {
            if (proc.length() < 2) {
                return input.error("Bad " + type + " name " + proc + " in package " + pkg
                        + ": must be at least two characters");
            }
            String subName = proc.substring(1);
            String nameError = PackageParser.validateName(subName, false, false);
            if (nameError != null) {
                return input.error("Invalid " + type + " name " + proc + " in package " + pkg
                        + ": " + nameError);
            }
            return input.success(pkg + proc);
        }
        String nameError = PackageParser.validateName(proc, true, false);
        if (nameError != null && !"system".equals(proc)) {
            return input.error("Invalid " + type + " name " + proc + " in package " + pkg
                    + ": " + nameError);
        }
        return input.success(proc);
    }

    public static int flag(int flag, @AttrRes int attribute, TypedArray typedArray) {
        return typedArray.getBoolean(attribute, false) ? flag : 0;
    }

    public static int flag(int flag, @AttrRes int attribute, boolean defaultValue,
            TypedArray typedArray) {
        return typedArray.getBoolean(attribute, defaultValue) ? flag : 0;
    }

    /**
     * This is not state aware. Avoid and access through PackageInfoUtils in the system server.
     */
    @Nullable
    public static CharSequence getNonLocalizedLabel(
            ParsedComponent component) {
        return component.nonLocalizedLabel;
    }

    /**
     * This is not state aware. Avoid and access through PackageInfoUtils in the system server.
     *
     * This is a method of the utility class to discourage use.
     */
    public static int getIcon(ParsedComponent component) {
        return component.icon;
    }

    public static boolean isMatch(PackageUserState state, boolean isSystem,
            boolean isPackageEnabled, ParsedMainComponent component, int flags) {
        return state.isMatch(isSystem, isPackageEnabled, component.isEnabled(),
                component.isDirectBootAware(), component.getName(), flags);
    }

    public static boolean isEnabled(PackageUserState state, boolean isPackageEnabled,
            ParsedMainComponent parsedComponent, int flags) {
        return state.isEnabled(isPackageEnabled, parsedComponent.isEnabled(),
                parsedComponent.getName(), flags);
    }
}
