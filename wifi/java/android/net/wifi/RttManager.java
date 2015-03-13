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

    /** @deprecated Type must be specified*/
    @Deprecated
    public static final int RTT_TYPE_UNSPECIFIED        = 0;
    public static final int RTT_TYPE_ONE_SIDED          = 1;

    /** @deprecated It is not supported*/
    @Deprecated
    public static final int RTT_TYPE_11_V               = 2;
    public static final int RTT_TYPE_TWO_SIDED          = 4;

    /** @deprecated It is not supported*/
    @Deprecated
    public static final int RTT_TYPE_11_MC              = 4;

    public static final int RTT_PEER_TYPE_UNSPECIFIED    = 0;
    public static final int RTT_PEER_TYPE_AP             = 1;
    public static final int RTT_PEER_TYPE_STA            = 2;       /* requires NAN */

    public static final int RTT_CHANNEL_WIDTH_20      = 0;
    public static final int RTT_CHANNEL_WIDTH_40      = 1;
    public static final int RTT_CHANNEL_WIDTH_80      = 2;
    public static final int RTT_CHANNEL_WIDTH_160     = 3;
    public static final int RTT_CHANNEL_WIDTH_80P80   = 4;
    public static final int RTT_CHANNEL_WIDTH_5       = 5;
    public static final int RTT_CHANNEL_WIDTH_10      = 6;

    /** @deprecated channel info must be specified*/
    @Deprecated
    public static final int RTT_CHANNEL_WIDTH_UNSPECIFIED = -1;

    public static final int RTT_STATUS_SUCCESS                  = 0;
    public static final int RTT_STATUS_FAILURE                  = 1;
    public static final int RTT_STATUS_FAIL_NO_RSP              = 2;
    public static final int RTT_STATUS_FAIL_REJECTED            = 3;
    public static final int RTT_STATUS_FAIL_NOT_SCHEDULED_YET   = 4;
    public static final int RTT_STATUS_FAIL_TM_TIMEOUT          = 5;
    public static final int RTT_STATUS_FAIL_AP_ON_DIFF_CHANNEL  = 6;
    public static final int RTT_STATUS_FAIL_NO_CAPABILITY       = 7;
    public static final int RTT_STATUS_ABORTED                  = 8;
    //if the T1-T4 or TOD/TOA Timestamp is illegal
    public static final int RTT_STATUS_FAIL_INVALID_TS          = 9;
    //11mc protocol failed, eg, unrecognized FTMR/FTM
    public static final int RTT_STATUS_FAIL_PROTOCOL            = 10;
    public static final int RTT_STATUS_FAIL_SCHEDULE            = 11;
    public static final int RTT_STATUS_FAIL_BUSY_TRY_LATER      = 12;

    public static final int REASON_UNSPECIFIED              = -1;
    public static final int REASON_NOT_AVAILABLE            = -2;
    public static final int REASON_INVALID_LISTENER         = -3;
    public static final int REASON_INVALID_REQUEST          = -4;

    public static final String DESCRIPTION_KEY  = "android.net.wifi.RttManager.Description";

    /**
     * RTT BW supported bit mask
     */
    public static final int RTT_BW_5_SUPPORT   = 0x1;
    public static final int RTT_BW_10_SUPPORT  = 0x2;
    public static final int RTT_BW_20_SUPPORT  = 0x4;
    public static final int RTT_BW_40_SUPPORT  = 0x8;
    public static final int RTT_BW_80_SUPPORT  = 0x10;
    public static final int RTT_BW_160_SUPPORT = 0x20;

    /**
     * RTT Preamble Support bit mask
     */
    public static final int PREAMBLE_LEGACY  = 0x1;
    public static final int PREAMBLE_HT      = 0x2;
    public static final int PREAMBLE_VHT     = 0x4;

    /** @deprecated It has been replaced by RttCapabilities*/
    @Deprecated
    public class Capabilities {
        public int supportedType;
        public int supportedPeerType;
    }

    /** @deprecated It has been replaced by getRttCapabilities*/
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
         * type of destination device being ranged; one of RTT_PEER_TYPE_AP or RTT_PEER_TYPE_STA
         */
        public int deviceType;

        /**
         * type of RTT measurement method; one of RTT_TYPE_ONE_SIDED or RTT_TYPE_TWO_SIDED.
         */
        public int requestType;

        /** mac address of the device being ranged */
        public String bssid;

        /**
         * The primary 20 MHz frequency (in MHz) of the channel over which the client is
         * communicating with the access point.Similar as ScanResult.frequency
         */
        public int frequency;

        /**
         * channel width used for RTT measurement. User need verify the highest BW the destination
         * support (from scan result etc) before set this value. Wider channels result usually give
         * better accuracy. However, the frame loss can increase. Similar as ScanResult.channelWidth
         */
        public int channelWidth;

        /**
         * Not used if the AP bandwidth is 20 MHz
         * If the AP use 40, 80 or 160 MHz, this is the center frequency
         * if the AP use 80 + 80 MHz, this is the center frequency of the first segment
         * similar as ScanResult.centerFreq0
         */
         public int centerFreq0;

         /**
          * Only used if the AP bandwidth is 80 + 80 MHz
          * if the AP use 80 + 80 MHz, this is the center frequency of the second segment
          * similar as ScanResult.centerFreq1
          */
          public int centerFreq1;
        /**
         * number of samples to be taken
         * @deprecated  It has been replaced by numSamplesPerBurst
         */
        @Deprecated
        public int num_samples;

        /**
         * number of retries if a sample fails
         * @deprecated It has been replaced by numRetriesPerMeasurementFrame
         */
        @Deprecated
        public int num_retries;

        /** Number of burst. fixed to 1 for single side RTT*/
        public int numberBurst;

        /** valid only if numberBurst > 1, interval between burst(ms). Not used by singe side RTT */
        public int interval;

        /** number of samples to be taken in one burst*/
        public int numSamplesPerBurst;

        /** number of retries for each measurement frame if a sample fails
         *  Only used by single side RTT
         */
        public int numRetriesPerMeasurementFrame;

        /** number of retries for FTMR frame if fails Only used by 80211MC double side RTT */
        public int numRetriesPerFTMR;

        /** Request LCI information */
        public boolean LCIRequest;

        /** Request LCR information */
        public boolean LCRRequest;

        /** Timeout for each burst, unit of 250 us*/
        public int burstTimeout;

        /** preamble used for RTT measurement
         *  should be one of PREAMBLE_LEGACY, PREAMBLE_HT, PREAMBLE_VHT
         */
        public int preamble;

        /** bandWidth used for RTT measurement.User need verify the highest BW the destination
         * support (from scan result etc) before set this value. Wider channels result usually give
         * better accuracy. However, the frame loss can increase too.
         * should be one of RTT_CHANNEL_WIDTH_20 to RTT_CHANNEL_WIDTH_80
         */
        public int bandwidth;

        public RttParams() {
            //provide initial value for RttParams
            deviceType = RTT_PEER_TYPE_AP;
            numberBurst = 1;
            numSamplesPerBurst = 8;
            numRetriesPerMeasurementFrame  = 0;
            burstTimeout = 40 + numSamplesPerBurst *4;
            preamble = PREAMBLE_LEGACY;
            bandwidth = RTT_CHANNEL_WIDTH_20;
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

    public class wifiInformationElement {
        /** Information Element ID*/
        public int id;
        public String data;
    }
    /** specifies RTT results */
    public static class RttResult {
        /** mac address of the device being ranged */
        public String bssid;

        /** # of burst for this measurement*/
        public int burstNumber;

        /** total number of measurement frames in this measurement*/
        public int measurementFrameNumber;

        /** total successful number of measurement frames in this measurement*/
        public int successMeasurementFrameNumber;

        /** Maximum number of frames per burst supported by peer */
        public int frameNumberPerBurstPeer;

        /** status of the request */
        public int status;

        /**
         * type of the request used
         * @deprecated It has been replaced by measurementType
         */
        @Deprecated
        public int requestType;

        /** RTT measurement method type used, shoudl be one of RTT_TYPE_ONE_SIDED or
         *  RTT_TYPE_TWO_SIDED.
         */
        public int measurementType;

        /** please retry RTT measurement after this S since peer indicate busy at ths moment*/
        public int retryAfterDuration;

        /** timestamp of completion, in microsecond since boot */
        public long ts;

        /** average RSSI observed, unit of 0.5 dB */
        public int rssi;

        /**
         * RSSI spread (i.e. max - min)
         * @deprecated It has been replaced by rssi_spread
         */
        @Deprecated
        public int rssi_spread;

        /**RSSI spread (i.e. max - min), unit of 0.5 dB */
        public int rssiSpread;

        /**
         * average transmit rate
         * @deprecated It has been replaced by txRate
         */
        @Deprecated
        public int tx_rate;

        /** average transmit rate */
        public int txRate;

        /** average receiving rate */
        public int rxRate;

       /**
        * average round trip time in nano second
        * @deprecated  It has been replaced by rtt
        */
        @Deprecated
        public long rtt_ns;

        /** average round trip time in 0.1 nano second */
        public long rtt;

        /**
         * standard deviation observed in round trip time
         * @deprecated It has been replaced by rttStandardDeviation
         */
        @Deprecated
        public long rtt_sd_ns;

        /** standard deviation of RTT in 0.1 ns */
        public long rttStandardDeviation;

        /**
         * spread (i.e. max - min) round trip time
         * @deprecated It has been replaced by rttSpread
         */
        @Deprecated
        public long rtt_spread_ns;

        /** spread (i.e. max - min) RTT in 0.1 ns */
        public long rttSpread;

        /**
         * average distance in centimeter, computed based on rtt_ns
         * @deprecated It has been replaced by distance
         */
        @Deprecated
        public int distance_cm;

        /** average distance in cm, computed based on rtt */
        public int distance;

        /**
         * standard deviation observed in distance
         * @deprecated It has been replaced with distanceStandardDeviation
         */
        @Deprecated
        public int distance_sd_cm;

        /** standard deviation observed in distance in cm*/
        public int distanceStandardDeviation;

        /**
         * spread (i.e. max - min) distance
         * @deprecated It has been replaced by distanceSpread
         */
        @Deprecated
        public int distance_spread_cm;

        /** spread (i.e. max - min) distance in cm */
        public int distanceSpread;

        /** the duration of this measurement burst*/
        public int burstDuration;

        /** LCI information Element*/
        wifiInformationElement LCI;

        /** LCR information Element*/
        wifiInformationElement LCR;
    }


    /** pseudo-private class used to parcel results */
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
                    //dest.writeInt(result.LCI.id);
                    //dest.writeString(result.LCI.data);
                    //dest.writeInt(result.LCR.id);
                    //dest.writeString(result.LCR.data);
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
                            //results[i].LCI.id = in.readInt();
                            //results[i].LCI.data = in.readString();
                            //results[i].LCR.id = in.readInt();
                            //results[i].LCR.data = in.readString();
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
                //throw new IllegalStateException("RTT chip is not working");
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
        } else if ( params.numberBurst <= 0 ) {
            Log.e(TAG, "Request " + index + ": Illegal number of burst: " + params.numberBurst);
            return false;
        } else if (params.numberBurst >  1 && params.interval <= 0) {
            Log.e(TAG, "Request " + index + ": Illegal interval value: " + params.interval);
            return false;
        } else if (params.numSamplesPerBurst <= 0) {
            Log.e(TAG, "Request " + index + ": Illegal sample number per burst: " +
                    params.numSamplesPerBurst);
            return false;
        } else if (params.numRetriesPerMeasurementFrame < 0 || params.numRetriesPerFTMR < 0) {
            Log.e(TAG, "Request " + index + ": Illegal retry number");
            return false;
        } else if (params.LCIRequest && !mRttCapabilities.lciSupported) {
            Log.e(TAG, "Request " + index + ": LCI is not supported");
            return false;
        } else if (params.LCRRequest && !mRttCapabilities.lcrSupported) {
            Log.e(TAG, "Request " + index + ": LCR is not supported");
            return false;
        } else if (params.burstTimeout <= 0){
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

                sHandlerThread = new HandlerThread("WifiScanner");
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
                if (DBG) Log.d(TAG, "invalid listener key = " + msg.arg2);
                return;
            } else {
                if (DBG) Log.d(TAG, "listener key = " + msg.arg2);
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

