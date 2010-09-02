/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.pim.vcard.exception.VCardException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * <p>
 * vCard parser for vCard 3.0. See RFC 2426 for more detail.
 * </p>
 * <p>
 * This parser allows vCard format which is not allowed in the RFC, since
 * we have seen several vCard 3.0 files which don't comply with it.
 * </p>
 * <p>
 * e.g. vCard 3.0 does not allow "CHARSET" attribute, but some actual files
 * have it and they uses non UTF-8 charsets. UTF-8 is recommended in RFC 2426,
 * but it is not a must. We silently allow "CHARSET".
 * </p>
 */
public class VCardParser_V30 implements VCardParser {
    /* package */ static final Set<String> sKnownPropertyNameSet =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "BEGIN", "LOGO", "PHOTO", "LABEL", "FN", "TITLE", "SOUND", 
                    "VERSION", "TEL", "EMAIL", "TZ", "GEO", "NOTE", "URL",
                    "BDAY", "ROLE", "REV", "UID", "KEY", "MAILER", // 2.1
                    "NAME", "PROFILE", "SOURCE", "NICKNAME", "CLASS",
                    "SORT-STRING", "CATEGORIES", "PRODID"))); // 3.0

    /**
     * <p>
     * A unmodifiable Set storing the values for the type "ENCODING", available in the vCard 3.0.
     * </p>
     * <p>
     * Though vCard 2.1 specification does not allow "7BIT" or "BASE64", we allow them for safety.
     * </p>
     * <p>
     * "QUOTED-PRINTABLE" is not allowed in vCard 3.0 and not in this parser either,
     * because the encoding ambiguates how the vCard file to be parsed.
     * </p>
     */
    /* package */ static final Set<String> sAcceptableEncoding =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    VCardConstants.PARAM_ENCODING_7BIT,
                    VCardConstants.PARAM_ENCODING_8BIT,
                    VCardConstants.PARAM_ENCODING_BASE64,
                    VCardConstants.PARAM_ENCODING_B)));

    private final VCardParserImpl_V30 mVCardParserImpl;

    public VCardParser_V30() {
        mVCardParserImpl = new VCardParserImpl_V30();
    }

    public VCardParser_V30(int vcardType) {
        mVCardParserImpl = new VCardParserImpl_V30(vcardType);
    }

    public void parse(InputStream is, VCardInterpreter interepreter)
            throws IOException, VCardException {
        mVCardParserImpl.parse(is, interepreter);
    }

    public void cancel() {
        mVCardParserImpl.cancel();
    }
}
