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

package android.content.pm.parsing;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageParser;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.XmlResourceParser;
import android.util.Slog;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/** @hide **/
public class ParsingUtils {

    public static final String TAG = "PackageParsing";

    public static final String ANDROID_RES_NAMESPACE = "http://schemas.android.com/apk/res/android";

    public static final int DEFAULT_MIN_SDK_VERSION = 1;
    public static final int DEFAULT_TARGET_SDK_VERSION = 0;

    @Nullable
    public static String buildClassName(String pkg, CharSequence clsSeq) {
        if (clsSeq == null || clsSeq.length() <= 0) {
            return null;
        }
        String cls = clsSeq.toString();
        char c = cls.charAt(0);
        if (c == '.') {
            return pkg + cls;
        }
        if (cls.indexOf('.') < 0) {
            StringBuilder b = new StringBuilder(pkg);
            b.append('.');
            b.append(cls);
            return b.toString();
        }
        return cls;
    }

    @NonNull
    public static ParseResult unknownTag(String parentTag, ParsingPackage pkg,
            XmlResourceParser parser, ParseInput input) throws IOException, XmlPullParserException {
        if (PackageParser.RIGID_PARSER) {
            return input.error("Bad element under " + parentTag + ": " + parser.getName());
        }
        Slog.w(TAG, "Unknown element under " + parentTag + ": "
                + parser.getName() + " at " + pkg.getBaseApkPath() + " "
                + parser.getPositionDescription());
        XmlUtils.skipCurrentTag(parser);
        return input.success(null); // Type doesn't matter
    }
}
