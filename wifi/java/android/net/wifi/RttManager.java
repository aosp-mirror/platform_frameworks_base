package android.net.wifi;

import android.annotation.SystemApi;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import java.util.concurrent.CountDownLatch;

/** @hide */
@SystemApi
public class RttManager {

    private static final boolean DBG = true;
    private static final String TAG = "RttManager";

    /** @deprecated It is Not supported anymore. */
    @Deprecated
    public static final int RTT_TYPE_UNSPECIFIED        = 0;

    public static final int RTT_TYPE_ONE_SIDED          = 1;
    public static final int RTT_TYPE_TWO_SIDED          = 2;

    /** @deprecated It is not supported anymore. */
    @Deprecated
    public static final int RTT_TYPE_11_V               = 2;

    /** @deprecated It is not supported anymore. */
    @Deprecated
    public static final int RTT_TYPE_11_MC              = 4;

    /** @deprecated It is not supported anymore. */
    @Deprecated
    public static final int RTT_PEER_TYPE_UNSPECIFIED    = 0;

    public static final int RTT_PEER_TYPE_AP             = 1;
    public static final int RTT_PEER_TYPE_STA            = 2;       /* requires NAN */
    public static final int RTT_PEER_P2P_GO              = 3;
    public static final int RTT_PEER_P2P_CLIENT          = 4;
    public static final int RTT_PEER_NAN                 = 5;

    /**
     * @deprecated It is not supported anymore.
     * Use {@link android.net.wifi.RttManager#RTT_BW_20_SUPPORT} API.
     */
    @Deprecated
    public static final int RTT_CHANNEL_WIDTH_20      = 0;

    /**
     * @deprecated It is not supported anymore.
     * Use {@link android.net.wifi.RttManager#RTT_BW_40_SUPPORT} API.
     */
    @Deprecated
    public static final int RTT_CHANNEL_WIDTH_40      = 1;

    /**
     * @deprecated It is not supported anymore.
     * Use {@link android.net.wifi.RttManager#RTT_BW_80_SUPPORT} API.
     */
    @Deprecated
    public static final int RTT_CHANNEL_WIDTH_80      = 2;

    /**@deprecated It is not supported anymore.
     * Use {@link android.net.wifi.RttManager#RTT_BW_160_SUPPORT} API.
     */
    @Deprecated
    public static final int RTT_CHANNEL_WIDTH_160     = 3;

    /**@deprecated not supported anymore*/
    @Deprecated
    public static final int RTT_CHANNEL_WIDTH_80P80   = 4;

    /**@deprecated It is not supported anymore.
     * Use {@link android.net.wifi.RttManager#RTT_BW_5_SUPPORT} API.
     */
    @Deprecated
    public static final int RTT_CHANNEL_WIDTH_5       = 5;

    /**@deprecated It is not supported anymore.
     * Use {@link android.net.wifi.RttManager#RTT_BW_10_SUPPORT} API.
     */
    @Deprecated
    public static final int RTT_CHANNEL_WIDTH_10      = 6;

    /** @deprecated channel info must be specified. */
    @Deprecated
    public static final int RTT_CHANNEL_WIDTH_UNSPECIFIED = -1;

    public static final int RTT_STATUS_SUCCESS                  = 0;
    /** General failure*/
    public static final int RTT_STATUS_FAILURE                  = 1;
    /** Destination does not respond to RTT request*/
    public static final int RTT_STATUS_FAIL_NO_RSP              = 2;
    /** RTT request is rejected by the destination. Double side RTT only*/
    public static final int RTT_STATUS_FAIL_REJECTED            = 3;
    /** */
    public static final int RTT_STATUS_FAIL_NOT_SCHEDULED_YET   = 4;
    /** Timing measurement timeout*/
    public static final int RTT_STATUS_FAIL_TM_TIMEOUT          = 5;
    /** Destination is on a different channel from the RTT Request*/
    public static final int RTT_STATUS_FAIL_AP_ON_DIFF_CHANNEL  = 6;
    /** This type of Ranging is not support by Hardware*/
    public static final int RTT_STATUS_FAIL_NO_CAPABILITY       = 7;
    /** Request abort fro uncertain reason*/
    public static final int RTT_STATUS_ABORTED                  = 8;
    /** The T1-T4 or TOD/TOA Timestamp is illegal*/
    public static final int RTT_STATUS_FAIL_INVALID_TS          = 9;
    /** 11mc protocol level failed, eg, unrecognized FTMR/FTM frame*/
    public static final int RTT_STATUS_FAIL_PROTOCOL            = 10;
    /** Request can not be scheduled by hardware*/
    public static final int RTT_STATUS_FAIL_SCHEDULE            = 11;
    /** destination is busy now, you can try after a specified time from destination*/
    public static final int RTT_STATUS_FAIL_BUSY_TRY_LATER      = 12;
    /** Bad Request argument*/
    public static final int RTT_STATUS_INVALID_REQ              = 13;
    /** Wifi is not enabled*/
    public static final int RTT_STATUS_NO_WIFI                  = 14;
    /** Responder overrides param info, cannot range with new params 2-side RTT only*/
    public static final int RTT_STATUS_FAIL_FTM_PARAM_OVERRIDE  = 15;

    public static final int REASON_UNSPECIFIED              = -1;
    public static final int REASON_NOT_AVAILABLE            = -2;
    public static final int REASON_INVALID_LISTENER         = -3;
    public static final int REASON_INVALID_REQUEST          = -4;
    /** Do not have required permission */
    public static final int REASON_PERMISSION_DENIED        = -5;

    public static final String DESCRIPTION_KEY  = "android.net.wifi.RttManager.Description";

    /**
     * RTT BW supported bit mask, used as RTT param bandWidth too
     */
    public static final int RTT_BW_5_SUPPORT   = 0x01;
    public static final int RTT_BW_10_SUPPORT  = 0x02;
    public static final int RTT_BW_20_SUPPORT  = 0x04;
    public static final int RTT_BW_40_SUPPORT  = 0x08;
    public static final int RTT_BW_80_SUPPORT  = 0x10;
    public static final int RTT_BW_160_SUPPORT = 0x20;

    /**
     * RTT Preamble Support bit mask
     */
    public static final int PREAMBLE_LEGACY  = 0x01;
    public static final int PREAMBLE_HT      = 0x02;
    public static final int PREAMBLE_VHT     = 0x04;

    /** @deprecated Use the new {@link android.net.wifi.RttManager.RttCapabilities} API */
    @Deprecated
    public class Capabilities {
        public int supportedType;
        public int supportedPeerType;
    }

    /** @deprecated Use the new {@link android.net.wifi.RttManager#getRttCapabilities()} API.*/
    @Deprecated
    public Capabilities getCapabilities() {
        return new Capabilities();
    }

    /**
     * This class describe the RTT capability of the Hardware
     */
    public static class RttCapabilities implements Parcelable {
        /** @deprecated It is not supported*/
        @Deprecated
        public boolean supportedType;
        /** @deprecated It is not supported*/
        @Deprecated
        public boolean supportedPeerType;
        //1-sided rtt measurement is supported
        public boolean oneSidedRttSupported;
        //11mc 2-sided rtt measurement is supported
        public boolean twoSided11McRttSupported;
        //location configuration information supported
        public boolean lciSupported;
        //location civic records supported
        public boolean lcrSupported;
        //preamble supported, see bit mask definition above
        public int preambleSupported;
        //RTT bandwidth supported
        public int bwSupported;

        @Override
        public String toString() {
            StringBuffer sb = new StringBuffer();
            sb.append("oneSidedRtt ").
            append(oneSidedRttSupported ? "is Supported. " : "is not supported. ").
            append("twoSided11McRtt ").
            append(twoSided11McRttSupported ? "is Supported. " : "is not supported. ").
            append("lci ").
            append(lciSupported ? "is Supported. " : "is not supported. ").
            append("lcr ").
            append(lcrSupported ? "is Supported. " : "is not supported. ");

            if ((preambleSupported & PREAMBLE_LEGACY) != 0) {
                sb.append("Legacy ");
            }

            if ((preambleSupported & PREAMBLE_HT) != 0) {
                sb.append("HT ");
            }

            if ((preambleSupported & PREAMBLE_VHT) != 0) {
                sb.append("VHT ");
            }

            sb.append("is supported. \n");

            if ((bwSupported & RTT_BW_5_SUPPORT) != 0) {
                sb.append("5 MHz ");
            }

            if ((bwSupported & RTT_BW_10_SUPPORT) != 0) {
                sb.append("10 MHz ");
            }

            if ((bwSupported & RTT_BW_20_SUPPORT) != 0) {
                sb.append("20 MHz ");
            }

            if ((bwSupported & RTT_BW_40_SUPPORT) != 0) {
                sb.append("40 MHz ");
            }

            if ((bwSupported & RTT_BW_80_SUPPORT) != 0) {
                sb.append("80 MHz ");
            }

            if ((bwSupported & RTT_BW_160_SUPPORT) != 0) {
                sb.append("160 MHz ");
            }

            sb.append("is supported.");

            return sb.toString();
        }
        /** Implement the Parcelable interface {@hide} */
        @Override
        public int describeContents() {
            return 0;
        }

        /** Implement the Parcelable interface {@hide} */
        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(oneSidedRttSupported ? 1 : 0);
            dest.writeInt(twoSided11McRttSupported ? 1 : 0);
            dest.writeInt(lciSupported ? 1 : 0);
            dest.writeInt(lcrSupported ? 1 : 0);
            dest.writeInt(preambleSupported);
            dest.writeInt(bwSupported);

        }

        /** Implement the Parcelable interface {@hide} */
        public static final Creator<RttCapabilities> CREATOR =
            new Creator<RttCapabilities>() {
               public RttCapabilities createFromParcel(Parcel in) {
                    RttCapabilities capabilities = new RttCapabilities();
                    capabilities.oneSidedRttSupported = in.readInt() == 1 ? true : false;
                        capabilities.twoSided11McRttSupported = in.readInt() == 1 ? true : false;
                        capabilities.lciSupported = in.readInt() == 1 ? true : false;
                        capabilities.lcrSupported = in.readInt() == 1 ? true : false;
                        capabilities.preambleSupported = in.readInt();
                        capabilities.bwSupported = in.readInt();
                        return capabilities;
                    }
                /** Implement the Parcelable interface {@hide} */
                @Override
                public RttCapabilities[] newArray(int size) {
                    return new RttCapabilities[size];
                }
             };
    }

    public RttCapabilities getRttCapabilities() {
        synchronized (sCapabilitiesLock) {
            if (mRttCapabilities == null) {
                try {
                    mRttCapabilities = mService.getRttCapabilities();
                } catch (RemoteException e) {
                    Log.e(TAG, "Can not get RTT Capabilities");
                }
            }
            return mRttCapabilities;
        }
    }

    /** specifies parameters for RTT request */
    public static class RttParams {
        /**
         * type of destination device being ranged
         * currently only support RTT_PEER_TYPE_AP
         * Range:RTT_PEER_TYPE_xxxx Default value:RTT_PEER_TYPE_AP
         */
        public int deviceType;

        /**
         * type of RTT measurement method. Need check scan result and RttCapabilities first
         * Range: RTT_TYPE_ONE_SIDED or RTT_TYPE_TWO_SIDED
         * Default value: RTT_TYPE_ONE_SIDED
         */
        public int requestType;

        /**
         * mac address of the device being ranged
         * Default value: null
         */
        public String bssid;

        /**
         * The primary control channel over which the client is
         * communicating with the AP.Same as ScanResult.frequency
         * Default value: 0
         */
        public int frequency;

        /**
         * channel width of the destination AP. Same as ScanResult.channelWidth
         * Default value: 0
         */
        public int channelWidth;

        /**
         * Not used if the AP bandwidth is 20 MHz
         * If the AP use 40, 80 or 160 MHz, this is the center frequency
         * if the AP use 80 + 80 MHz, this is the center frequency of the first segment
         * same as ScanResult.centerFreq0
         * Default value: 0
         */
         public int centerFreq0;

         /**
          * Only used if the AP bandwidth is 80 + 80 MHz
          * if the AP use 80 + 80 MHz, this is the center frequency of the second segment
          * same as ScanResult.centerFreq1
          * Default value: 0
          */
          public int centerFreq1;

        /**
         * number of samples to be taken
         * @deprecated Use the new {@link android.net.wifi.RttManager.RttParams#numSamplesPerBurst}
         */
        @Deprecated
        public int num_samples;

        /**
         * number of retries if a sample fails
         * @deprecated
         * Use {@link android.net.wifi.RttManager.RttParams#numRetriesPerMeasurementFrame} API.
         */
        @Deprecated
        public int num_retries;

        /** Number of burst in exp , 2^x. 0 means single shot measurement, range 0-15
         * Currently only single shot is supported
         * Default value: 0
         */
        public int numberBurst;

        /**
         * valid only if numberBurst > 1, interval between burst(100ms).
         * Range : 0-31, 0--means no specific
         * Default value: 0
         */
        public int interval;

        /**
         * number of samples to be taken in one burst
         * Range: 1-31
         * Default value: 8
         */
        public int numSamplesPerBurst;

        /** number of retries for each measurement frame if a sample fails
         *  Only used by single side RTT,
         *  Range 0 - 3 Default value: 0
         */
        public int numRetriesPerMeasurementFrame;

        /**
         * number of retries for FTMR frame (control frame) if it fails.
         * Only used by 80211MC double side RTT
         * Range: 0-3  Default Value : 0
         */
        public int numRetriesPerFTMR;

        /**
         * Request LCI information, only available when choose double side RTT measurement
         * need check RttCapabilties first.
         * Default value: false
         * */
        public boolean LCIRequest;

        /**
         * Request LCR information, only available when choose double side RTT measurement
         * need check RttCapabilties first.
         * Default value: false
         * */
        public boolean LCRRequest;

        /**
         * Timeout for each burst, (250 * 2^x) us,
         * Range 1-11 and 15. 15 means no control Default value: 15
         * */
        public int burstTimeout;

        /** preamble used for RTT measurement
         *  Range: PREAMBLE_LEGACY, PREAMBLE_HT, PREAMBLE_VHT
         *  Default value: PREAMBLE_HT
         */
        public int preamble;

        /** bandWidth used for RTT measurement.User need verify the highest BW the destination
         * support (from scan result etc) before set this value. Wider channels result usually give
         * better accuracy. However, the frame loss can increase too.
         * should be one of RTT_BW_5_SUPPORT to RTT_BW_160_SUPPORT. However, need check
         * RttCapabilities firstto verify HW support this bandwidth.
         * Default value:RTT_BW_20_SUPPORT
         */
        public int bandwidth;

        public RttParams() {
            //provide initial value for RttParams
            deviceType = RTT_PEER_TYPE_AP;
            requestType = RTT_TYPE_ONE_SIDED;
            numberBurst = 0;
            numSamplesPerBurst = 8;
            numRetriesPerMeasurementFrame  = 0;
            numRetriesPerFTMR = 0;
            burstTimeout = 15;
            preamble = PREAMBLE_HT;
            bandwidth = RTT_BW_20_SUPPORT;
        }
    }

    /** pseudo-private class used to parcel arguments */
    public static class ParcelableRttParams implements Parcelable {

        public RttParams mParams[];

        ParcelableRttParams(RttParams[] params) {
            mParams = params;
        }

        /** Implement the Parcelable interface {@hide} */
        public int describeContents() {
            return 0;
        }

        /** Implement the Parcelable interface {@hide} */
        public void writeToParcel(Parcel dest, int flags) {
            if (mParams != null) {
                dest.writeInt(mParams.length);

                for (RttParams params : mParams) {
                    dest.writeInt(params.deviceType);
                    dest.writeInt(params.requestType);
                    dest.writeString(params.bssid);
                    dest.writeInt(params.channelWidth);
                    dest.writeInt(params.frequency);
                    dest.writeInt(params.centerFreq0);
                    dest.writeInt(params.centerFreq1);
                    dest.writeInt(params.numberBurst);
                    dest.writeInt(params.interval);
                    dest.writeInt(params.numSamplesPerBurst);
                    dest.writeInt(params.numRetriesPerMeasurementFrame);
                    dest.writeInt(params.numRetriesPerFTMR);
                    dest.writeInt(params.LCIRequest ? 1 : 0);
                    dest.writeInt(params.LCRRequest ? 1 : 0);
                    dest.writeInt(params.burstTimeout);
                    dest.writeInt(params.preamble);
                    dest.writeInt(params.bandwidth);
                }
            } else {
                dest.writeInt(0);
            }
        }

        /** Implement the Parcelable interface {@hide} */
        public static final Creator<ParcelableRttParams> CREATOR =
                new Creator<ParcelableRttParams>() {
                    public ParcelableRttParams createFromParcel(Parcel in) {

                        int num = in.readInt();

                        if (num == 0) {
                            return new ParcelableRttParams(null);
                        }

                        RttParams params[] = new RttParams[num];
                        for (int i = 0; i < num; i++) {
                            params[i] = new RttParams();
                            params[i].deviceType = in.readInt();
                            params[i].requestType = in.readInt();
                            params[i].bssid = in.readString();
                            params[i].channelWidth = in.readInt();
                            params[i].frequency = in.readInt();
                            params[i].centerFreq0 = in.readInt();
                            params[i].centerFreq1 = in.readInt();
                            params[i].numberBurst = in.readInt();
                            params[i].interval = in.readInt();
                            params[i].numSamplesPerBurst = in.readInt();
                            params[i].numRetriesPerMeasurementFrame = in.readInt();
                            params[i].numRetriesPerFTMR = in.readInt();
                            params[i].LCIRequest = in.readInt() == 1 ? true : false;
                            params[i].LCRRequest = in.readInt() == 1 ? true : false;
                            params[i].burstTimeout = in.readInt();
                            params[i].preamble = in.readInt();
                            params[i].bandwidth = in.readInt();
                        }

                        ParcelableRttParams parcelableParams = new ParcelableRttParams(params);
                        return parcelableParams;
                    }

                    public ParcelableRttParams[] newArray(int size) {
                        return new ParcelableRttParams[size];
                    }
                };
    }

    public static class WifiInformationElement {
        /** Information Element ID 0xFF means element is invalid. */
        public byte id;
        public byte[] data;
    }
    /** specifies RTT results */
    public static class RttResult {
        /** mac address of the device being ranged. */
        public String bssid;

        /** # of burst for this measurement. */
        public int burstNumber;

        /** total number of measurement frames attempted in this measurement. */
        public int measurementFrameNumber;

        /** total successful number of measurement frames in this measurement. */
        public int successMeasurementFrameNumber;

        /**
         * Maximum number of frames per burst supported by peer. Two side RTT only
         * Valid only if less than request
         */
        public int frameNumberPerBurstPeer;

        /** status of the request */
        public int status;

        /**
         * type of the request used
         * @deprecated Use {@link android.net.wifi.RttManager.RttResult#measurementType}
         */
        @Deprecated
        public int requestType;

        /** RTT measurement method type used, should be one of RTT_TYPE_ONE_SIDED or
         *  RTT_TYPE_TWO_SIDED.
         */
        public int measurementType;

        /**
         * only valid when status ==  RTT_STATUS_FAIL_BUSY_TRY_LATER
         * please retry RTT measurement after this duration since peer indicate busy at ths moment
         *  Unit S  Range:1-31
         */
        public int retryAfterDuration;

        /** timestamp of completion, in microsecond since boot. */
        public long ts;

        /** average RSSI observed, unit of 0.5 dB. */
        public int rssi;

        /**
         * RSSI spread (i.e. max - min)
         * @deprecated Use {@link android.net.wifi.RttManager.RttResult#rssiSpread} API.
         */
        @Deprecated
        public int rssi_spread;

        /**RSSI spread (i.e. max - min), unit of 0.5 dB. */
        public int rssiSpread;

        /**
         * average transmit rate
         * @deprecated Use {@link android.net.wifi.RttManager.RttResult#txRate} API.
         */
        @Deprecated
        public int tx_rate;

        /** average transmit rate. Unit (100kbps). */
        public int txRate;

        /** average receiving rate Unit (100kbps). */
        public int rxRate;

       /**
        * average round trip time in nano second
        * @deprecated  Use {@link android.net.wifi.RttManager.RttResult#rtt} API.
        */
        @Deprecated
        public long rtt_ns;

        /** average round trip time in 0.1 nano second. */
        public long rtt;

        /**
         * standard deviation observed in round trip time
         * @deprecated Use {@link android.net.wifi.RttManager.RttResult#rttStandardDeviation} API.
         */
        @Deprecated
        public long rtt_sd_ns;

        /** standard deviation of RTT in 0.1 ns. */
        public long rttStandardDeviation;

        /**
         * spread (i.e. max - min) round trip time
         * @deprecated Use {@link android.net.wifi.RttManager.RttResult#rttSpread} API.
         */
        @Deprecated
        public long rtt_spread_ns;

        /** spread (i.e. max - min) RTT in 0.1 ns. */
        public long rttSpread;

        /**
         * average distance in centimeter, computed based on rtt_ns
         * @deprecated use {@link android.net.wifi.RttManager.RttResult#distance} API.
         */
        @Deprecated
        public int distance_cm;

        /** average distance in cm, computed based on rtt. */
        public int distance;

        /**
         * standard deviation observed in distance
         * @deprecated
         * Use {@link .android.net.wifi.RttManager.RttResult#distanceStandardDeviation} API.
         */
        @Deprecated
        public int distance_sd_cm;

        /** standard deviation observed in distance in cm. */
        public int distanceStandardDeviation;

        /**
         * spread (i.e. max - min) distance
         * @deprecate Use {@link android.net.wifi.RttManager.RttResult#distanceSpread} API.
         */
        @Deprecated
        public int distance_spread_cm;

        /** spread (i.e. max - min) distance in cm. */
        public int distanceSpread;

        /** the duration of this measurement burst, unit ms. */
        public int burstDuration;

        /** Burst number supported by peer after negotiation, 2side RTT only*/
        public int negotiatedBurstNum;

        /** LCI information Element, only available for double side RTT. */
        public WifiInformationElement LCI;

        /** LCR information Element, only available to double side RTT. */
        public WifiInformationElement LCR;
    }


    /** pseudo-private class used to parcel results. */
    public static class ParcelableRttResults implements Parcelable {

        public RttResult mResults[];

        public ParcelableRttResults(RttResult[] results) {
            mResults = results;
        }

        /** Implement the Parcelable interface {@hide} */
        public int describeContents() {
            return 0;
        }

        /** Implement the Parcelable interface {@hide} */
        public void writeToParcel(Parcel dest, int flags) {
            if (mResults != null) {
                dest.writeInt(mResults.length);
                for (RttResult result : mResults) {
                    dest.writeString(result.bssid);
                    dest.writeInt(result.burstNumber);
                    dest.writeInt(result.measurementFrameNumber);
                    dest.writeInt(result.successMeasurementFrameNumber);
                    dest.writeInt(result.frameNumberPerBurstPeer);
                    dest.writeInt(result.status);
                    dest.writeInt(result.measurementType);
                    dest.writeInt(result.retryAfterDuration);
                    dest.writeLong(result.ts);
                    dest.writeInt(result.rssi);
                    dest.writeInt(result.rssiSpread);
                    dest.writeInt(result.txRate);
                    dest.writeLong(result.rtt);
                    dest.writeLong(result.rttStandardDeviation);
                    dest.writeLong(result.rttSpread);
                    dest.writeInt(result.distance);
                    dest.writeInt(result.distanceStandardDeviation);
                    dest.writeInt(result.distanceSpread);
                    dest.writeInt(result.burstDuration);
                    dest.writeInt(result.negotiatedBurstNum);
                    dest.writeByte(result.LCI.id);
                    if (result.LCI.id != (byte) 0xFF) {
                        dest.writeByte((byte)result.LCI.data.length);
                        dest.writeByteArray(result.LCI.data);
                    }
                    dest.writeByte(result.LCR.id);
                    if (result.LCR.id != (byte) 0xFF) {
                        dest.writeInt((byte) result.LCR.data.length);
                        dest.writeByte(result.LCR.id);
                    }
                }
            } else {
                dest.writeInt(0);
            }
        }

        /** Implement the Parcelable interface {@hide} */
        public static final Creator<ParcelableRttResults> CREATOR =
                new Creator<ParcelableRttResults>() {
                    public ParcelableRttResults createFromParcel(Parcel in) {

                        int num = in.readInt();

                        if (num == 0) {
                            return new ParcelableRttResults(null);
                        }

                        RttResult results[] = new RttResult[num];
                        for (int i = 0; i < num; i++) {
                            results[i] = new RttResult();
                            results[i].bssid = in.readString();
                            results[i].burstNumber = in.readInt();
                            results[i].measurementFrameNumber = in.readInt();
                            results[i].successMeasurementFrameNumber = in.readInt();
                            results[i].frameNumberPerBurstPeer = in.readInt();
                            results[i].status = in.readInt();
                            results[i].measurementType = in.readInt();
                            results[i].retryAfterDuration = in.readInt();
                            results[i].ts = in.readLong();
                            results[i].rssi = in.readInt();
                            results[i].rssiSpread = in.readInt();
                            results[i].txRate = in.readInt();
                            results[i].rtt = in.readLong();
                            results[i].rttStandardDeviation = in.readLong();
                            results[i].rttSpread = in.readLong();
                            results[i].distance = in.readInt();
                            results[i].distanceStandardDeviation = in.readInt();
                            results[i].distanceSpread = in.readInt();
                            results[i].burstDuration = in.readInt();
                            results[i].negotiatedBurstNum = in.readInt();
                            results[i].LCI = new WifiInformationElement();
                            results[i].LCI.id = in.readByte();
                            if (results[i].LCI.id != (byte) 0xFF) {
                                byte length = in.readByte();
                                results[i].LCI.data = new byte[length];
                                in.readByteArray(results[i].LCI.data);
                            }
                            results[i].LCR = new WifiInformationElement();
                            results[i].LCR.id = in.readByte();
                            if (results[i].LCR.id != (byte) 0xFF) {
                                byte length = in.readByte();
                                results[i].LCR.data = new byte[length];
                                in.readByteArray(results[i].LCR.data);
                            }
                        }

                        ParcelableRttResults parcelableResults = new ParcelableRttResults(results);
                        return parcelableResults;
                    }

                    public ParcelableRttResults[] newArray(int size) {
                        return new ParcelableRttResults[size];
                    }
                };
    }


    public static interface RttListener {
        public void onSuccess(RttResult[] results);
        public void onFailure(int reason, String description);
        public void onAborted();
    }

    private boolean rttParamSanity(RttParams params, int index) {
        if (mRttCapabilities == null) {
            if(getRttCapabilities() == null) {
                Log.e(TAG, "Can not get RTT capabilities");
                throw new IllegalStateException("RTT chip is not working");
            }
        }

        if (params.deviceType != RTT_PEER_TYPE_AP) {
            return false;
        } else if (params.requestType != RTT_TYPE_ONE_SIDED && params.requestType !=
                RTT_TYPE_TWO_SIDED) {
            Log.e(TAG, "Request " + index + ": Illegal Request Type: " + params.requestType);
            return false;
        } else if (params.requestType == RTT_TYPE_ONE_SIDED &&
                !mRttCapabilities.oneSidedRttSupported) {
            Log.e(TAG, "Request " + index + ": One side RTT is not supported");
            return false;
        } else if (params.requestType == RTT_TYPE_TWO_SIDED &&
                !mRttCapabilities.twoSided11McRttSupported) {
            Log.e(TAG, "Request " + index + ": two side RTT is not supported");
            return false;
        }  else if(params.bssid == null || params.bssid.isEmpty()) {
            Log.e(TAG,"No BSSID in params");
            return false;
        } else if ( params.numberBurst != 0 ) {
            Log.e(TAG, "Request " + index + ": Illegal number of burst: " + params.numberBurst);
            return false;
        } else if (params.numSamplesPerBurst <= 0 || params.numSamplesPerBurst > 31) {
            Log.e(TAG, "Request " + index + ": Illegal sample number per burst: " +
                    params.numSamplesPerBurst);
            return false;
        } else if (params.numRetriesPerMeasurementFrame < 0 ||
                params.numRetriesPerMeasurementFrame > 3) {
            Log.e(TAG, "Request " + index + ": Illegal measurement frame retry number:" +
                    params.numRetriesPerMeasurementFrame);
            return false;
        } else if(params.numRetriesPerFTMR < 0 ||
                params.numRetriesPerFTMR > 3) {
            Log.e(TAG, "Request " + index + ": Illegal FTMR frame retry number:" +
                    params.numRetriesPerFTMR);
            return false;
        } else if (params.LCIRequest && !mRttCapabilities.lciSupported) {
            Log.e(TAG, "Request " + index + ": LCI is not supported");
            return false;
        } else if (params.LCRRequest && !mRttCapabilities.lcrSupported) {
            Log.e(TAG, "Request " + index + ": LCR is not supported");
            return false;
        } else if (params.burstTimeout < 1 ||
                (params.burstTimeout > 11 && params.burstTimeout != 15)){
            Log.e(TAG, "Request " + index + ": Illegal burst timeout: " + params.burstTimeout);
            return false;
        } else if ((params.preamble & mRttCapabilities.preambleSupported) == 0) {
            Log.e(TAG, "Request " + index + ": Do not support this preamble: " + params.preamble);
            return false;
        } else if ((params.bandwidth & mRttCapabilities.bwSupported) == 0) {
            Log.e(TAG, "Request " + index + ": Do not support this bandwidth: " + params.bandwidth);
            return false;
        }

        return true;
    }

    /**
     * Request to start an RTT ranging
     *
     * @param params  -- RTT request Parameters
     * @param listener -- Call back to inform RTT result
     * @exception throw IllegalArgumentException when params are illegal
     *            throw IllegalStateException when RttCapabilities do not exist
     */

    public void startRanging(RttParams[] params, RttListener listener) {
        int index  = 0;
        for(RttParams rttParam : params) {
            if (!rttParamSanity(rttParam, index)) {
                throw new IllegalArgumentException("RTT Request Parameter Illegal");
            }
            index++;
        }
        validateChannel();
        ParcelableRttParams parcelableParams = new ParcelableRttParams(params);
        Log.i(TAG, "Send RTT request to RTT Service");
        sAsyncChannel.sendMessage(CMD_OP_START_RANGING,
                0, putListener(listener), parcelableParams);
    }

    public void stopRanging(RttListener listener) {
        validateChannel();
        sAsyncChannel.sendMessage(CMD_OP_STOP_RANGING, 0, removeListener(listener));
    }

    /* private methods */
    public static final int BASE = Protocol.BASE_WIFI_RTT_MANAGER;

    public static final int CMD_OP_START_RANGING        = BASE + 0;
    public static final int CMD_OP_STOP_RANGING         = BASE + 1;
    public static final int CMD_OP_FAILED               = BASE + 2;
    public static final int CMD_OP_SUCCEEDED            = BASE + 3;
    public static final int CMD_OP_ABORTED              = BASE + 4;

    private Context mContext;
    private IRttManager mService;
    private RttCapabilities mRttCapabilities;

    private static final int INVALID_KEY = 0;
    private static int sListenerKey = 1;

    private static final SparseArray sListenerMap = new SparseArray();
    private static final Object sListenerMapLock = new Object();
    private static final Object sCapabilitiesLock = new Object();

    private static AsyncChannel sAsyncChannel;
    private static CountDownLatch sConnected;

    private static final Object sThreadRefLock = new Object();
    private static int sThreadRefCount;
    private static HandlerThread sHandlerThread;

    /**
     * Create a new WifiScanner instance.
     * Applications will almost always want to use
     * {@link android.content.Context#getSystemService Context.getSystemService()} to retrieve
     * the standard {@link android.content.Context#WIFI_RTT_SERVICE Context.WIFI_RTT_SERVICE}.
     * @param context the application context
     * @param service the Binder interface
     * @hide
     */

    public RttManager(Context context, IRttManager service) {
        mContext = context;
        mService = service;
        init();
    }

    private void init() {
        synchronized (sThreadRefLock) {
            if (++sThreadRefCount == 1) {
                Messenger messenger = null;
                try {
                    Log.d(TAG, "Get the messenger from " + mService);
                    messenger = mService.getMessenger();
                } catch (RemoteException e) {
                    /* do nothing */
                } catch (SecurityException e) {
                    /* do nothing */
                }

                if (messenger == null) {
                    sAsyncChannel = null;
                    return;
                }

                sHandlerThread = new HandlerThread("RttManager");
                sAsyncChannel = new AsyncChannel();
                sConnected = new CountDownLatch(1);

                sHandlerThread.start();
                Handler handler = new ServiceHandler(sHandlerThread.getLooper());
                sAsyncChannel.connect(mContext, handler, messenger);
                try {
                    sConnected.await();
                } catch (InterruptedException e) {
                    Log.e(TAG, "interrupted wait at init");
                }
            }
        }
    }

    private void validateChannel() {
        if (sAsyncChannel == null) throw new IllegalStateException(
                "No permission to access and change wifi or a bad initialization");
    }

    private static int putListener(Object listener) {
        if (listener == null) return INVALID_KEY;
        int key;
        synchronized (sListenerMapLock) {
            do {
                key = sListenerKey++;
            } while (key == INVALID_KEY);
            sListenerMap.put(key, listener);
        }
        return key;
    }

    private static Object getListener(int key) {
        if (key == INVALID_KEY) return null;
        synchronized (sListenerMapLock) {
            Object listener = sListenerMap.get(key);
            return listener;
        }
    }

    private static int getListenerKey(Object listener) {
        if (listener == null) return INVALID_KEY;
        synchronized (sListenerMapLock) {
            int index = sListenerMap.indexOfValue(listener);
            if (index == -1) {
                return INVALID_KEY;
            } else {
                return sListenerMap.keyAt(index);
            }
        }
    }

    private static Object removeListener(int key) {
        if (key == INVALID_KEY) return null;
        synchronized (sListenerMapLock) {
            Object listener = sListenerMap.get(key);
            sListenerMap.remove(key);
            return listener;
        }
    }

    private static int removeListener(Object listener) {
        int key = getListenerKey(listener);
        if (key == INVALID_KEY) return key;
        synchronized (sListenerMapLock) {
            sListenerMap.remove(key);
            return key;
        }
    }

    private static class ServiceHandler extends Handler {
        ServiceHandler(Looper looper) {
            super(looper);
        }
        @Override
        public void handleMessage(Message msg) {
            Log.i(TAG, "RTT manager get message: " + msg.what);
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        sAsyncChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                    } else {
                        Log.e(TAG, "Failed to set up channel connection");
                        // This will cause all further async API calls on the WifiManager
                        // to fail and throw an exception
                        sAsyncChannel = null;
                    }
                    sConnected.countDown();
                    return;
                case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                    return;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    Log.e(TAG, "Channel connection lost");
                    // This will cause all further async API calls on the WifiManager
                    // to fail and throw an exception
                    sAsyncChannel = null;
                    getLooper().quit();
                    return;
            }

            Object listener = getListener(msg.arg2);
            if (listener == null) {
                Log.e(TAG, "invalid listener key = " + msg.arg2 );
                return;
            } else {
                Log.i(TAG, "listener key = " + msg.arg2);
            }

            switch (msg.what) {
                /* ActionListeners grouped together */
                case CMD_OP_SUCCEEDED :
                    reportSuccess(listener, msg);
                    removeListener(msg.arg2);
                    break;
                case CMD_OP_FAILED :
                    reportFailure(listener, msg);
                    removeListener(msg.arg2);
                    break;
                case CMD_OP_ABORTED :
                    ((RttListener) listener).onAborted();
                    removeListener(msg.arg2);
                    break;
                default:
                    if (DBG) Log.d(TAG, "Ignoring message " + msg.what);
                    return;
            }
        }

        void reportSuccess(Object listener, Message msg) {
            RttListener rttListener = (RttListener) listener;
            ParcelableRttResults parcelableResults = (ParcelableRttResults) msg.obj;
            ((RttListener) listener).onSuccess(parcelableResults.mResults);
        }

        void reportFailure(Object listener, Message msg) {
            RttListener rttListener = (RttListener) listener;
            Bundle bundle = (Bundle) msg.obj;
            ((RttListener) listener).onFailure(msg.arg1, bundle.getString(DESCRIPTION_KEY));
        }
    }

}

