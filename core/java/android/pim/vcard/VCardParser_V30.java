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
 * The class used to parse vCard 3.0.
 * Please refer to vCard Specification 3.0 (http://tools.ietf.org/html/rfc2426).
 */
public class VCardParser_V30 implements VCardParser {
    public static final Set<String> sKnownPropertyNameSet =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "BEGIN", "LOGO", "PHOTO", "LABEL", "FN", "TITLE", "SOUND", 
                    "VERSION", "TEL", "EMAIL", "TZ", "GEO", "NOTE", "URL",
                    "BDAY", "ROLE", "REV", "UID", "KEY", "MAILER", // 2.1
                    "NAME", "PROFILE", "SOURCE", "NICKNAME", "CLASS",
                    "SORT-STRING", "CATEGORIES", "PRODID"))); // 3.0
    
    // Although "7bit" and "BASE64" is not allowed in vCard 3.0, we allow it for safety.
    public static final Set<String> sAcceptableEncoding =
            Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
                    "7BIT", "8BIT", "BASE64", "B")));

    private final VCardParserImpl_V30 mVCardParserImpl;

    public VCardParser_V30() {
        mVCardParserImpl = new VCardParserImpl_V30();
    }

    public VCardParser_V30(VCardSourceDetector detector) {
        mVCardParserImpl = new VCardParserImpl_V30(detector);
    }

    public VCardParser_V30(int parseType) {
        mVCardParserImpl = new VCardParserImpl_V30(parseType);
    }


    //// Implemented methods 
    
    public boolean parse(InputStream is, VCardInterpreter interepreter)
            throws IOException, VCardException {
        return mVCardParserImpl.parse(is, VCardConfig.DEFAULT_TEMPORARY_CHARSET, interepreter);
    }

    public boolean parse(InputStream is, String charset, VCardInterpreter interpreter)
            throws IOException, VCardException {
        return mVCardParserImpl.parse(is, charset, interpreter);
    }

    public boolean parse(InputStream is, String charset,
            VCardInterpreter interpreter, boolean canceled)
            throws IOException, VCardException {
        return mVCardParserImpl.parse(is, charset, interpreter, canceled);
    }

    public void cancel() {
        mVCardParserImpl.cancel();
    }
}
