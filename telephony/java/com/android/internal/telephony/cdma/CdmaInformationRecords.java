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

package com.android.internal.telephony.cdma;
import static com.android.internal.telephony.RILConstants.*;
import android.os.Parcel;

public final class CdmaInformationRecords {
    public Object record;

    /**
     * Record type identifier
     */
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

    public CdmaInformationRecords(Parcel p) {
        int id = p.readInt();
        switch (id) {
            case RIL_CDMA_DISPLAY_INFO_REC:
            case RIL_CDMA_EXTENDED_DISPLAY_INFO_REC:
                record  = new CdmaDisplayInfoRec(id, p.readString());
                break;

            case RIL_CDMA_CALLED_PARTY_NUMBER_INFO_REC:
            case RIL_CDMA_CALLING_PARTY_NUMBER_INFO_REC:
            case RIL_CDMA_CONNECTED_NUMBER_INFO_REC:
                record = new CdmaNumberInfoRec(id, p.readString(), p.readInt(), p.readInt(),
                        p.readInt(), p.readInt());
                break;

            case RIL_CDMA_SIGNAL_INFO_REC:
                record = new CdmaSignalInfoRec(p.readInt(), p.readInt(), p.readInt(), p.readInt());
                break;

            case RIL_CDMA_REDIRECTING_NUMBER_INFO_REC:
                record = new CdmaRedirectingNumberInfoRec(p.readString(), p.readInt(), p.readInt(),
                        p.readInt(), p.readInt(), p.readInt());
                break;

            case RIL_CDMA_LINE_CONTROL_INFO_REC:
                record = new CdmaLineControlInfoRec(p.readInt(), p.readInt(), p.readInt(),
                        p.readInt());
                break;

            case RIL_CDMA_T53_CLIR_INFO_REC:
                record = new CdmaT53ClirInfoRec(p.readInt());
                break;

            case RIL_CDMA_T53_AUDIO_CONTROL_INFO_REC:
                record = new CdmaT53AudioControlInfoRec(p.readInt(), p.readInt());
                break;

            case RIL_CDMA_T53_RELEASE_INFO_REC:
                // TODO: WHAT to do, for now fall through and throw exception
            default:
                throw new RuntimeException("RIL_UNSOL_CDMA_INFO_REC: unsupported record. Got "
                                            + CdmaInformationRecords.idToString(id) + " ");

        }
    }

    public static String idToString(int id) {
        switch(id) {
        case RIL_CDMA_DISPLAY_INFO_REC: return "RIL_CDMA_DISPLAY_INFO_REC";
        case RIL_CDMA_CALLED_PARTY_NUMBER_INFO_REC: return "RIL_CDMA_CALLED_PARTY_NUMBER_INFO_REC";
        case RIL_CDMA_CALLING_PARTY_NUMBER_INFO_REC: return "RIL_CDMA_CALLING_PARTY_NUMBER_INFO_REC";
        case RIL_CDMA_CONNECTED_NUMBER_INFO_REC: return "RIL_CDMA_CONNECTED_NUMBER_INFO_REC";
        case RIL_CDMA_SIGNAL_INFO_REC: return "RIL_CDMA_SIGNAL_INFO_REC";
        case RIL_CDMA_REDIRECTING_NUMBER_INFO_REC: return "RIL_CDMA_REDIRECTING_NUMBER_INFO_REC";
        case RIL_CDMA_LINE_CONTROL_INFO_REC: return "RIL_CDMA_LINE_CONTROL_INFO_REC";
        case RIL_CDMA_EXTENDED_DISPLAY_INFO_REC: return "RIL_CDMA_EXTENDED_DISPLAY_INFO_REC";
        case RIL_CDMA_T53_CLIR_INFO_REC: return "RIL_CDMA_T53_CLIR_INFO_REC";
        case RIL_CDMA_T53_RELEASE_INFO_REC: return "RIL_CDMA_T53_RELEASE_INFO_REC";
        case RIL_CDMA_T53_AUDIO_CONTROL_INFO_REC: return "RIL_CDMA_T53_AUDIO_CONTROL_INFO_REC";
        default: return "<unknown record>";
        }
    }

    /**
     * Signal Information record from 3GPP2 C.S005 3.7.5.5
     */
    public static class CdmaSignalInfoRec {
        public boolean isPresent;   /* non-zero if signal information record is present */
        public int signalType;
        public int alertPitch;
        public int signal;

        public CdmaSignalInfoRec() {}

        public CdmaSignalInfoRec(int isPresent, int signalType, int alertPitch, int signal) {
            this.isPresent = isPresent != 0;
            this.signalType = signalType;
            this.alertPitch = alertPitch;
            this.signal = signal;
        }

        @Override
        public String toString() {
            return "CdmaSignalInfo: {" +
                    " isPresent: " + isPresent +
                    ", signalType: " + signalType +
                    ", alertPitch: " + alertPitch +
                    ", signal: " + signal +
                    " }";
        }
    }

    public static class CdmaDisplayInfoRec {
        public int id;
        public String alpha;

        public CdmaDisplayInfoRec(int id, String alpha) {
            this.id = id;
            this.alpha = alpha;
        }

        @Override
        public String toString() {
            return "CdmaDisplayInfoRec: {" +
                    " id: " + CdmaInformationRecords.idToString(id) +
                    ", alpha: " + alpha +
                    " }";
        }
    }

    public static class CdmaNumberInfoRec {
        public int id;
        public String number;
        public byte numberType;
        public byte numberPlan;
        public byte pi;
        public byte si;

        public CdmaNumberInfoRec(int id, String number, int numberType, int numberPlan, int pi,
                int si) {
            this.number = number;
            this.numberType = (byte)numberType;
            this.numberPlan = (byte)numberPlan;
            this.pi = (byte)pi;
            this.si = (byte)si;
        }

        @Override
        public String toString() {
            return "CdmaNumberInfoRec: {" +
                    " id: " + CdmaInformationRecords.idToString(id) +
                    ", number: " + number +
                    ", numberType: " + numberType +
                    ", numberPlan: " + numberPlan +
                    ", pi: " + pi +
                    ", si: " + si +
                    " }";
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

        public CdmaRedirectingNumberInfoRec(String number, int numberType, int numberPlan,
                int pi, int si, int reason) {
            numberInfoRec = new CdmaNumberInfoRec(RIL_CDMA_REDIRECTING_NUMBER_INFO_REC,
                                                  number, numberType, numberPlan, pi, si);
            redirectingReason = reason;
        }

        @Override
        public String toString() {
            return "CdmaNumberInfoRec: {" +
                    " numberInfoRec: " + numberInfoRec +
                    ", redirectingReason: " + redirectingReason +
                    " }";
        }
    }

    public static class CdmaLineControlInfoRec {
        public byte lineCtrlPolarityIncluded;
        public byte lineCtrlToggle;
        public byte lineCtrlReverse;
        public byte lineCtrlPowerDenial;

        public CdmaLineControlInfoRec(int lineCtrlPolarityIncluded, int lineCtrlToggle,
                int lineCtrlReverse, int lineCtrlPowerDenial) {
            this.lineCtrlPolarityIncluded = (byte)lineCtrlPolarityIncluded;
            this.lineCtrlToggle = (byte)lineCtrlToggle;
            this.lineCtrlReverse = (byte)lineCtrlReverse;
            this.lineCtrlPowerDenial = (byte)lineCtrlPowerDenial;
        }

        @Override
        public String toString() {
            return "CdmaLineControlInfoRec: {" +
                    " lineCtrlPolarityIncluded: " + lineCtrlPolarityIncluded +
                    " lineCtrlToggle: " + lineCtrlToggle +
                    " lineCtrlReverse: " + lineCtrlReverse +
                    " lineCtrlPowerDenial: " + lineCtrlPowerDenial +
                    " }";
        }
    }

    public static class CdmaT53ClirInfoRec {
        public byte cause;

        public CdmaT53ClirInfoRec(int cause) {
            this.cause = (byte)cause;
        }

        @Override
        public String toString() {
            return "CdmaT53ClirInfoRec: {" +
                    " cause: " + cause +
                    " }";
        }
    }

    public static class CdmaT53AudioControlInfoRec {
        public byte uplink;
        public byte downlink;

        public CdmaT53AudioControlInfoRec(int uplink, int downlink) {
            this.uplink = (byte) uplink;
            this.downlink = (byte) downlink;
        }

        @Override
        public String toString() {
            return "CdmaT53AudioControlInfoRec: {" +
                    " uplink: " + uplink +
                    " downlink: " + downlink +
                    " }";
        }
    }
}
