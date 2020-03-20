/*
 * Copyright (C) 2007 Esmertec AG.
 * Copyright (C) 2007 The Android Open Source Project
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

package com.google.android.mms.pdu;

import android.compat.annotation.UnsupportedAppUsage;

import com.google.android.mms.InvalidHeaderValueException;

/**
 * Multimedia message PDU.
 */
public class MultimediaMessagePdu extends GenericPdu{
    /**
     * The body.
     */
    private PduBody mMessageBody;

    /**
     * Constructor.
     */
    @UnsupportedAppUsage
    public MultimediaMessagePdu() {
        super();
    }

    /**
     * Constructor.
     *
     * @param header the header of this PDU
     * @param body the body of this PDU
     */
    @UnsupportedAppUsage
    public MultimediaMessagePdu(PduHeaders header, PduBody body) {
        super(header);
        mMessageBody = body;
    }

    /**
     * Constructor with given headers.
     *
     * @param headers Headers for this PDU.
     */
    MultimediaMessagePdu(PduHeaders headers) {
        super(headers);
    }

    /**
     * Get body of the PDU.
     *
     * @return the body
     */
    @UnsupportedAppUsage
    public PduBody getBody() {
        return mMessageBody;
    }

    /**
     * Set body of the PDU.
     *
     * @param body the body
     */
    @UnsupportedAppUsage
    public void setBody(PduBody body) {
        mMessageBody = body;
    }

    /**
     * Get subject.
     *
     * @return the value
     */
    @UnsupportedAppUsage
    public EncodedStringValue getSubject() {
        return mPduHeaders.getEncodedStringValue(PduHeaders.SUBJECT);
    }

    /**
     * Set subject.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    @UnsupportedAppUsage
    public void setSubject(EncodedStringValue value) {
        mPduHeaders.setEncodedStringValue(value, PduHeaders.SUBJECT);
    }

    /**
     * Get To value.
     *
     * @return the value
     */
    @UnsupportedAppUsage
    public EncodedStringValue[] getTo() {
        return mPduHeaders.getEncodedStringValues(PduHeaders.TO);
    }

    /**
     * Add a "To" value.
     *
     * @param value the value
     * @throws NullPointerException if the value is null.
     */
    @UnsupportedAppUsage
    public void addTo(EncodedStringValue value) {
        mPduHeaders.appendEncodedStringValue(value, PduHeaders.TO);
    }

    /**
     * Get X-Mms-Priority value.
     *
     * @return the value
     */
    @UnsupportedAppUsage
    public int getPriority() {
        return mPduHeaders.getOctet(PduHeaders.PRIORITY);
    }

    /**
     * Set X-Mms-Priority value.
     *
     * @param value the value
     * @throws InvalidHeaderValueException if the value is invalid.
     */
    @UnsupportedAppUsage
    public void setPriority(int value) throws InvalidHeaderValueException {
        mPduHeaders.setOctet(value, PduHeaders.PRIORITY);
    }

    /**
     * Get Date value.
     *
     * @return the value
     */
    @UnsupportedAppUsage
    public long getDate() {
        return mPduHeaders.getLongInteger(PduHeaders.DATE);
    }

    /**
     * Set Date value in seconds.
     *
     * @param value the value
     */
    @UnsupportedAppUsage
    public void setDate(long value) {
        mPduHeaders.setLongInteger(value, PduHeaders.DATE);
    }
}
