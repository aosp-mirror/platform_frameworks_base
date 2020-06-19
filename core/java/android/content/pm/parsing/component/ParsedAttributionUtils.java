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
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** @hide */
public class ParsedAttributionUtils {

    @NonNull
    public static ParseResult<ParsedAttribution> parseAttribution(Resources res,
            XmlResourceParser parser, ParseInput input)
            throws IOException, XmlPullParserException {
        String attributionTag;
        int label;
        List<String> inheritFrom = null;

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestAttribution);
        if (sa == null) {
            return input.error("<attribution> could not be parsed");
        }

        try {
            attributionTag = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestAttribution_tag, 0);
            if (attributionTag == null) {
                return input.error("<attribution> does not specify android:tag");
            }
            if (attributionTag.length() > ParsedAttribution.MAX_ATTRIBUTION_TAG_LEN) {
                return input.error("android:tag is too long. Max length is "
                        + ParsedAttribution.MAX_ATTRIBUTION_TAG_LEN);
            }

            label = sa.getResourceId(R.styleable.AndroidManifestAttribution_label, 0);
            if (label == Resources.ID_NULL) {
                return input.error("<attribution> does not specify android:label");
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

            String tagName = parser.getName();
            if (tagName.equals("inherit-from")) {
                sa = res.obtainAttributes(parser,
                        R.styleable.AndroidManifestAttributionInheritFrom);
                if (sa == null) {
                    return input.error("<inherit-from> could not be parsed");
                }

                try {
                    String inheritFromId = sa.getNonConfigurationString(
                            R.styleable.AndroidManifestAttributionInheritFrom_tag, 0);

                    if (inheritFrom == null) {
                        inheritFrom = new ArrayList<>();
                    }
                    inheritFrom.add(inheritFromId);
                } finally {
                    sa.recycle();
                }
            } else {
                return input.error("Bad element under <attribution>: " + tagName);
            }
        }

        if (inheritFrom == null) {
            inheritFrom = Collections.emptyList();
        } else {
            ((ArrayList) inheritFrom).trimToSize();
        }

        return input.success(new ParsedAttribution(attributionTag, label, inheritFrom));
    }
}
