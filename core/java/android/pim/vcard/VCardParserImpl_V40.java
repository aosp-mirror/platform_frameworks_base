/*
 * Copyright (C) 2010 The Android Open Source Project
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
package android.pim.vcard;

import java.util.Set;


/**
 * <p>
 * Basic implementation parsing vCard 4.0.
 * </p>
 * <p>
 * vCard 4.0 is not published yet. Also this implementation is premature. 
 * </p>
 * @hide
 */
/* package */ class VCardParserImpl_V40 extends VCardParserImpl_V30 {
    // private static final String LOG_TAG = "VCardParserImpl_V40";

    public VCardParserImpl_V40() {
        super();
    }

    public VCardParserImpl_V40(final int vcardType) {
        super(vcardType);
    }

    @Override
    protected int getVersion() {
        return VCardConfig.VERSION_40;
    }

    @Override
    protected String getVersionString() {
        return VCardConstants.VERSION_V40;
    }

    /**
     * We escape "\N" into new line for safety.
     */
    @Override
    protected String maybeUnescapeText(final String text) {
        return unescapeText(text);
    }

    public static String unescapeText(final String text) {
        // TODO: more strictly, vCard 4.0 requires different type of unescaping rule
        //       toward each property.
        final StringBuilder builder = new StringBuilder();
        final int length = text.length();
        for (int i = 0; i < length; i++) {
            char ch = text.charAt(i);
            if (ch == '\\' && i < length - 1) {
                final char next_ch = text.charAt(++i);
                if (next_ch == 'n' || next_ch == 'N') {
                    builder.append("\n");
                } else {
                    builder.append(next_ch);
                }
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    public static String unescapeCharacter(final char ch) {
        if (ch == 'n' || ch == 'N') {
            return "\n";
        } else {
            return String.valueOf(ch);
        }
    }

    @Override
    protected Set<String> getKnownPropertyNameSet() {
        return VCardParser_V40.sKnownPropertyNameSet;
    }
}