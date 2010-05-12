/* Copyright (c) 2010, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of Code Aurora nor
 *       the names of its contributors may be used to endorse or promote
 *       products derived from this software without specific prior written
 *       permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package com.android.internal.telephony;

public class UUSInfo {

    /*
     * User-to-User signaling Info activation types derived from 3GPP 23.087
     * v8.0
     */

    public static final int UUS_TYPE1_IMPLICIT = 0;

    public static final int UUS_TYPE1_REQUIRED = 1;

    public static final int UUS_TYPE1_NOT_REQUIRED = 2;

    public static final int UUS_TYPE2_REQUIRED = 3;

    public static final int UUS_TYPE2_NOT_REQUIRED = 4;

    public static final int UUS_TYPE3_REQUIRED = 5;

    public static final int UUS_TYPE3_NOT_REQUIRED = 6;

    /*
     * User-to-User Signaling Information data coding schemes. Possible values
     * for Octet 3 (Protocol Discriminator field) in the UUIE. The values have
     * been specified in section 10.5.4.25 of 3GPP TS 24.008
     */

    public static final int UUS_DCS_USP = 0; /* User specified protocol */

    public static final int UUS_DCS_OSIHLP = 1; /* OSI higher layer protocol */

    public static final int UUS_DCS_X244 = 2; /* X.244 */

    public static final int UUS_DCS_RMCF = 3; /*
                                               * Reserved for system management
                                               * convergence function
                                               */

    public static final int UUS_DCS_IA5c = 4; /* IA5 characters */

    private int uusType;

    private int uusDcs;

    private byte[] uusData;

    public UUSInfo() {
        this.uusType = UUS_TYPE1_IMPLICIT;
        this.uusDcs = UUS_DCS_IA5c;
        this.uusData = null;
    }

    public UUSInfo(int uusType, int uusDcs, byte[] uusData) {
        this.uusType = uusType;
        this.uusDcs = uusDcs;
        this.uusData = uusData;
    }

    public int getDcs() {
        return uusDcs;
    }

    public void setDcs(int uusDcs) {
        this.uusDcs = uusDcs;
    }

    public int getType() {
        return uusType;
    }

    public void setType(int uusType) {
        this.uusType = uusType;
    }

    public byte[] getUserData() {
        return uusData;
    }

    public void setUserData(byte[] uusData) {
        this.uusData = uusData;
    }
}
