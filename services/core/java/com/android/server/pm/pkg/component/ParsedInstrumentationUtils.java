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

import static com.android.server.pm.pkg.parsing.ParsingUtils.NOT_SET;

import android.annotation.NonNull;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;

import com.android.internal.R;
import com.android.internal.pm.pkg.component.ParsedInstrumentation;
import com.android.server.pm.pkg.parsing.ParsingPackage;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * @hide
 */
public class ParsedInstrumentationUtils {

    @NonNull
    public static ParseResult<ParsedInstrumentation> parseInstrumentation(ParsingPackage pkg,
            Resources res, XmlResourceParser parser, boolean useRoundIcon,
            ParseInput input) throws IOException, XmlPullParserException {
        ParsedInstrumentationImpl
                instrumentation = new ParsedInstrumentationImpl();
        String tag = "<" + parser.getName() + ">";

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestInstrumentation);
        try {
            ParseResult<ParsedInstrumentationImpl> result = ParsedComponentUtils.parseComponent(
                    instrumentation, tag, pkg, sa, useRoundIcon, input,
                    R.styleable.AndroidManifestInstrumentation_banner,
                    NOT_SET /*descriptionAttr*/,
                    R.styleable.AndroidManifestInstrumentation_icon,
                    R.styleable.AndroidManifestInstrumentation_label,
                    R.styleable.AndroidManifestInstrumentation_logo,
                    R.styleable.AndroidManifestInstrumentation_name,
                    R.styleable.AndroidManifestInstrumentation_roundIcon);
            if (result.isError()) {
                return input.error(result);
            }

            // @formatter:off
            // Note: don't allow this value to be a reference to a resource
            // that may change.
            instrumentation.setTargetPackage(sa.getNonResourceString(R.styleable.AndroidManifestInstrumentation_targetPackage))
                    .setTargetProcesses(sa.getNonResourceString(R.styleable.AndroidManifestInstrumentation_targetProcesses))
                    .setHandleProfiling(sa.getBoolean(R.styleable.AndroidManifestInstrumentation_handleProfiling, false))
                    .setFunctionalTest(sa.getBoolean(R.styleable.AndroidManifestInstrumentation_functionalTest, false));
            // @formatter:on
        } finally {
            sa.recycle();
        }

        ParseResult<ParsedInstrumentationImpl> result =
                ComponentParseUtils.parseAllMetaData(pkg, res, parser, tag, instrumentation, input);

        if (result.isError()) {
            return input.error(result);
        }

        return input.success(result.getResult());
    }
}
