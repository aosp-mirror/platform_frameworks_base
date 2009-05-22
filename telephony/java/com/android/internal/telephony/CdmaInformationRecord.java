/*
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

package com.android.internal.telephony;

/**
 * TODO(Teleca): This class was poorly implemented and didn't
 * follow the Android coding conventions. It is now more or less
 * follows the conventions but there is still some work, see the
 * TODO's.
 */


public class CdmaInformationRecord {
    public int messageName;

    public CdmaDisplayInfoRec displayInfoRec;
    public CdmaNumberInfoRec numberInfoRec;
    public CdmaSignalInfoRec signalInfoRec;
    public CdmaRedirectingNumberInfoRec redirectingNumberInfoRec;
    public CdmaLineControlInfoRec lineControlInfoRec;
    public CdmaT53ClirInfoRec cdmaT53ClirInfoRec;
    public CdmaT53AudioControlInfoRec cdmaT53AudioControlInfoRec;

    public static final int RIL_CDMA_DISPLAY_INFO_REC = 0;
    public static final int RIL_CDMA_CALLED_PARTY_NUMBER_INFO_REC = 1;
    public static final int RIL_CDMA_CALLING_PARTY_NUMBER_INFO_REC = 2;
    public static final int RIL_CDMA_CONNECTED_NUMBER_INFO_REC = 3;
    public static final int RIL_CDMA_SIGNAL_INFO_REC = 4;
    public static final int RIL_CDMA_REDIRECTING_NUMBER_INFO_REC = 5;
    public static final int RIL_CDMA_LINE_CONTROL_INFO_REC = 6;
    public static final int RIL_CDMA_EXTENDED_DISPLAY_INFO_REC = 7;
    public static final int RIL_CDMA_T53_CLIR_INFO_REC = 8;
    public static final int RIL_CDMA_T53_RELEASE_INFO_REC = 9;
    public static final int RIL_CDMA_T53_AUDIO_CONTROL_INFO_REC = 10;

    public CdmaInformationRecord(int messageName) {
        this.messageName = messageName;
    }

    void createDisplayInfo(int length, char buffer[]) {
        displayInfoRec = new CdmaDisplayInfoRec(length, buffer);
    }

    void createNumberInfo(int length, char buffer[]) {
        numberInfoRec = new CdmaNumberInfoRec(length, buffer);
    }

    void createSignalInfo(char buffer[]) {
        signalInfoRec = new CdmaSignalInfoRec(buffer);
    }

    void createRedirectingNumberInfo(int length, char buffer[], int reason) {
        redirectingNumberInfoRec = new CdmaRedirectingNumberInfoRec(length, buffer, reason);
    }

    void createLineControlInfo(char buffer[]) {
        lineControlInfoRec = new CdmaLineControlInfoRec(buffer);
    }

    void createT53ClirInfo(char buffer) {
        cdmaT53ClirInfoRec = new CdmaT53ClirInfoRec(buffer);
    }

    void createT53AudioControlInfo(char ul, char dl) {
        cdmaT53AudioControlInfoRec = new CdmaT53AudioControlInfoRec(ul, dl);
    }

    /**
     * TODO(Teleca): Add comments for each class giving the
     * document and section where the information is defined
     * as shown CdmaSignalInfoRec. Also add a toString to
     * each of these to ease debugging.
     */

    /**
     * Signal Information record from 3GPP2 C.S005 3.7.5.5
     */
    public static class CdmaSignalInfoRec {
        public boolean isPresent;   /* non-zero if signal information record is present */
        public int signalType;
        public int alertPitch;
        public int signalCode;

        public CdmaSignalInfoRec() {}

        public CdmaSignalInfoRec(char buffer[]) {
            isPresent = buffer[0] == 1;
            signalType = buffer[1];
            alertPitch = buffer[2];
            signalCode = buffer[3];
        }

        @Override
        public String toString() {
            return "CdmaSignalInfo: {" +
                    " isPresent: " + isPresent +
                    ", signalType: " + signalType +
                    ", alertPitch: " + alertPitch +
                    ", signalCode: " + signalCode +
                    " }";
        }
    }

    public static class CdmaDisplayInfoRec {
        public char alphaLen;
        public char alphaBuf[];

        public CdmaDisplayInfoRec(int length, char buffer[]) {
            alphaLen = (char)length;
            alphaBuf = new char[length];
            for(int i = 0; i < length; i++)
                alphaBuf[i] = buffer[i];
        }
    }

    public static class CdmaNumberInfoRec {
        public int len;
        public char buf[];
        public char numberType;
        public char numberPlan;
        public char pi; // TODO(Teleca): poor name, no meaning
        public char si; // TODO(Teleca): poor name

        public CdmaNumberInfoRec(int length, char buffer[]) {
            int i;

            len = length;
            buf = new char[length];
            for (i = 0; i < len; i++) {
                buf[i] = buffer[i];
            }

            numberType = buffer[i++];
            numberPlan = buffer[i++];
            pi = buffer[i++];
            si = buffer[i++];
        }
    }

    public static class CdmaRedirectingNumberInfoRec {
        public static final int REASON_UNKNOWN = 0;
        public static final int REASON_CALL_FORWARDING_BUSY = 1;
        public static final int REASON_CALL_FORWARDING_NO_REPLY = 2;
        public static final int REASON_CALLED_DTE_OUT_OF_ORDER = 9;
        public static final int REASON_CALL_FORWARDING_BY_THE_CALLED_DTE = 10;
        public static final int REASON_CALL_FORWARDING_UNCONDITIONAL = 15;

        public CdmaNumberInfoRec numberInfoRec;
        public int redirectingReason;

        public CdmaRedirectingNumberInfoRec(int length, char buffer[], int reason) {
            numberInfoRec = new CdmaNumberInfoRec(length, buffer);
            redirectingReason = reason;
        }
    }

    public static class CdmaLineControlInfoRec {
        public char lineCtrlPolarityIncluded;
        public char lineCtrlToggle;
        public char lineCtrlReverse;
        public char lineCtrlPowerDenial;

        CdmaLineControlInfoRec(char buffer[]) {
            lineCtrlPolarityIncluded = buffer[0];
            lineCtrlToggle = buffer[1];
            lineCtrlReverse = buffer[2];
            lineCtrlPowerDenial = buffer[3];
        }
    }

    // TODO(Teleca): A class for a single character, is this needed?
    public static class CdmaT53ClirInfoRec {
        public char cause;

        public CdmaT53ClirInfoRec(char buffer) {
            cause = buffer;
        }
    }

    public static class CdmaT53AudioControlInfoRec {
        public char uplink;
        public char downlink;

        public CdmaT53AudioControlInfoRec(char ul, char dl) {
            uplink = ul;
            downlink = dl;
        }
    }
}
