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

public abstract class VCardParser {
    protected final int mParseType;
    protected boolean mCanceled;

    public VCardParser() {
        this(VCardConfig.PARSE_TYPE_UNKNOWN);
    }

    public VCardParser(int parseType) {
        mParseType = parseType;
    }

    /**
     * <P>
     * Parses the given stream and send the VCard data into VCardBuilderBase object.
     * </P.
     * <P>
     * Note that vCard 2.1 specification allows "CHARSET" parameter, and some career sets
     * local encoding to it. For example, Japanese phone career uses Shift_JIS, which is
     * formally allowed in VCard 2.1, but not recommended in VCard 3.0. In VCard 2.1,
     * In some exreme case, some VCard may have different charsets in one VCard (though
     * we do not see any device which emits such kind of malicious data)
     * </P>
     * <P>
     * In order to avoid "misunderstanding" charset as much as possible, this method
     * use "ISO-8859-1" for reading the stream. When charset is specified in some property
     * (with "CHARSET=..." parameter), the string is decoded to raw bytes and encoded to
     * the charset. This method assumes that "ISO-8859-1" has 1 to 1 mapping in all 8bit
     * characters, which is not completely sure. In some cases, this "decoding-encoding"
     * scheme may fail. To avoid the case,
     * </P>
     * <P>
     * We recommend you to use {@link VCardSourceDetector} and detect which kind of source the
     * VCard comes from and explicitly specify a charset using the result.
     * </P>
     *
     * @param is The source to parse.
     * @param interepreter A {@link VCardInterpreter} object which used to construct data.
     * @return Returns true for success. Otherwise returns false.
     * @throws IOException, VCardException
     */
    public abstract boolean parse(InputStream is, VCardInterpreter interepreter)
            throws IOException, VCardException;
    
    /**
     * <P>
     * The method variants which accept charset.
     * </P>
     * <P>
     * RFC 2426 "recommends" (not forces) to use UTF-8, so it may be OK to use
     * UTF-8 as an encoding when parsing vCard 3.0. But note that some Japanese
     * phone uses Shift_JIS as a charset (e.g. W61SH), and another uses
     * "CHARSET=SHIFT_JIS", which is explicitly prohibited in vCard 3.0 specification (e.g. W53K).
     * </P>
     *
     * @param is The source to parse.
     * @param charset Charset to be used.
     * @param builder The VCardBuilderBase object.
     * @return Returns true when successful. Otherwise returns false.
     * @throws IOException, VCardException
     */
    public abstract boolean parse(InputStream is, String charset, VCardInterpreter builder)
            throws IOException, VCardException;
    
    /**
     * The method variants which tells this object the operation is already canceled.
     */
    public abstract void parse(InputStream is, String charset,
            VCardInterpreter builder, boolean canceled)
        throws IOException, VCardException;
    
    /**
     * Cancel parsing.
     * Actual cancel is done after the end of the current one vcard entry parsing.
     */
    public void cancel() {
        mCanceled = true;
    }
}
