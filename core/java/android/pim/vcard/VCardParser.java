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
     * Parses the given stream and send the vCard data into VCardBuilderBase object.
     * </P.
     * <P>
     * Note that vCard 2.1 specification allows "CHARSET" parameter, and some career sets
     * local encoding to it. For example, Japanese phone career uses Shift_JIS, which is
     * formally allowed in vCard 2.1, but not allowed in vCard 3.0. In vCard 2.1,
     * In some exreme case, it is allowed for vCard to have different charsets in one vCard.
     * </P>
     * <P>
     * We recommend you use {@link VCardSourceDetector} and detect which kind of source the
     * vCard comes from and explicitly specify a charset using the result.
     * </P>
     *
     * @param is The source to parse.
     * @param interepreter A {@link VCardInterpreter} object which used to construct data.
     * @return Returns true for success. Otherwise returns false.
     * @throws IOException, VCardException
     */
    public final boolean parse(InputStream is, VCardInterpreter interepreter)
            throws IOException, VCardException {
        return parse(is, VCardConfig.DEFAULT_TEMPORARY_CHARSET, interepreter);
    }

    /**
     * <P>
     * The method variants which accept charset.
     * </P>
     *
     * @param is The source to parse.
     * @param charset Charset to be used.
     * @param interpreter The VCardBuilderBase object.
     * @return Returns true when successful. Otherwise returns false.
     * @throws IOException, VCardException
     */
    public abstract boolean parse(InputStream is, String charset,
            VCardInterpreter interpreter)
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
