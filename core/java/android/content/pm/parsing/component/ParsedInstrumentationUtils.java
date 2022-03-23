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
import android.content.pm.parsing.ParsingPackage;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;

import com.android.internal.R;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/** @hide */
public class ParsedInstrumentationUtils {

    @NonNull
    public static ParseResult<ParsedInstrumentation> parseInstrumentation(ParsingPackage pkg,
            Resources res, XmlResourceParser parser, boolean useRoundIcon,
            ParseInput input) throws IOException, XmlPullParserException {
        ParsedInstrumentation
                instrumentation = new ParsedInstrumentation();
        String tag = "<" + parser.getName() + ">";

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestInstrumentation);
        try {
            ParseResult<ParsedInstrumentation> result = ParsedComponentUtils.parseComponent(
                    instrumentation, tag, pkg, sa, useRoundIcon, input,
                    R.styleable.AndroidManifestInstrumentation_banner,
                    null /*descriptionAttr*/,
                    R.styleable.AndroidManifestInstrumentation_icon,
                    R.styleable.AndroidManifestInstrumentation_label,
                    R.styleable.AndroidManifestInstrumentation_logo,
                    R.styleable.AndroidManifestInstrumentation_name,
                    R.styleable.AndroidManifestInstrumentation_roundIcon);
            if (result.isError()) {
                return result;
            }

            // @formatter:off
            // Note: don't allow this value to be a reference to a resource
            // that may change.
            instrumentation.setTargetPackage(sa.getNonResourceString(R.styleable.AndroidManifestInstrumentation_targetPackage));
            instrumentation.setTargetProcesses(sa.getNonResourceString(R.styleable.AndroidManifestInstrumentation_targetProcesses));
            instrumentation.handleProfiling = sa.getBoolean(R.styleable.AndroidManifestInstrumentation_handleProfiling, false);
            instrumentation.functionalTest = sa.getBoolean(R.styleable.AndroidManifestInstrumentation_functionalTest, false);
            // @formatter:on
        } finally {
            sa.recycle();
        }

        return ComponentParseUtils.parseAllMetaData(pkg, res, parser, tag, instrumentation,
                input);
    }
}
