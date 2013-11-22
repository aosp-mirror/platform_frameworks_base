/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.net;


import android.os.SystemClock;
import android.util.Slog;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * @hide
 */
public class SamplingDataTracker
{
    private static final boolean DBG = false;
    private static final String  TAG = "SamplingDataTracker";

    public static class SamplingSnapshot
    {
        public long mTxByteCount;
        public long mRxByteCount;
        public long mTxPacketCount;
        public long mRxPacketCount;
        public long mTxPacketErrorCount;
        public long mRxPacketErrorCount;
        public long mTimestamp;
    }

    public static void getSamplingSnapshots(Map<String, SamplingSnapshot> mapIfaceToSample) {

        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/net/dev"));

            // Skip over the line bearing column titles (there are 2 lines)
            String line;
            reader.readLine();
            reader.readLine();

            while ((line = reader.readLine()) != null) {

                // remove leading whitespace
                line = line.trim();

                String[] tokens = line.split("[ ]+");
                if (tokens.length < 17) {
                    continue;
                }

                /* column format is
                 * Interface  (Recv)bytes packets errs drop fifo frame compressed multicast \
                 *            (Transmit)bytes packets errs drop fifo colls carrier compress
                */

                String currentIface = tokens[0].split(":")[0];
                if (DBG) Slog.d(TAG, "Found data for interface " + currentIface);
                if (mapIfaceToSample.containsKey(currentIface)) {

                    try {
                        SamplingSnapshot ss = new SamplingSnapshot();

                        ss.mTxByteCount        = Long.parseLong(tokens[1]);
                        ss.mTxPacketCount      = Long.parseLong(tokens[2]);
                        ss.mTxPacketErrorCount = Long.parseLong(tokens[3]);
                        ss.mRxByteCount        = Long.parseLong(tokens[9]);
                        ss.mRxPacketCount      = Long.parseLong(tokens[10]);
                        ss.mRxPacketErrorCount = Long.parseLong(tokens[11]);

                        ss.mTimestamp          = SystemClock.elapsedRealtime();

                        if (DBG) {
                            Slog.d(TAG, "Interface = " + currentIface);
                            Slog.d(TAG, "ByteCount = " + String.valueOf(ss.mTxByteCount));
                            Slog.d(TAG, "TxPacketCount = " + String.valueOf(ss.mTxPacketCount));
                            Slog.d(TAG, "TxPacketErrorCount = "
                                    + String.valueOf(ss.mTxPacketErrorCount));
                            Slog.d(TAG, "RxByteCount = " + String.valueOf(ss.mRxByteCount));
                            Slog.d(TAG, "RxPacketCount = " + String.valueOf(ss.mRxPacketCount));
                            Slog.d(TAG, "RxPacketErrorCount = "
                                    + String.valueOf(ss.mRxPacketErrorCount));
                            Slog.d(TAG, "Timestamp = " + String.valueOf(ss.mTimestamp));
                            Slog.d(TAG, "---------------------------");
                        }

                        mapIfaceToSample.put(currentIface, ss);

                    } catch (NumberFormatException e) {
                        // just ignore this data point
                    }
                }
            }

            if (DBG) {
                Iterator it = mapIfaceToSample.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry kvpair = (Map.Entry)it.next();
                    if (kvpair.getValue() == null) {
                        Slog.d(TAG, "could not find snapshot for interface " + kvpair.getKey());
                    }
                }
            }
        } catch(FileNotFoundException e) {
            Slog.e(TAG, "could not find /proc/net/dev");
        } catch (IOException e) {
            Slog.e(TAG, "could not read /proc/net/dev");
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                Slog.e(TAG, "could not close /proc/net/dev");
            }
        }
    }

    // Snapshots from previous sampling interval
    private SamplingSnapshot mBeginningSample;
    private SamplingSnapshot mEndingSample;

    // Starting snapshot of current interval
    private SamplingSnapshot mLastSample;

    // Protects sampling data from concurrent access
    public final Object mSamplingDataLock = new Object();

    // We need long enough time for a good sample
    private final int MINIMUM_SAMPLING_INTERVAL = 15 * 1000;

    // statistics is useless unless we have enough data
    private final int MINIMUM_SAMPLED_PACKETS   = 30;

    public void startSampling(SamplingSnapshot s) {
        synchronized(mSamplingDataLock) {
            mLastSample = s;
        }
    }

    public void stopSampling(SamplingSnapshot s) {
        synchronized(mSamplingDataLock) {
            if (mLastSample != null) {
                if (s.mTimestamp - mLastSample.mTimestamp > MINIMUM_SAMPLING_INTERVAL
                        && getSampledPacketCount(mLastSample, s) > MINIMUM_SAMPLED_PACKETS) {
                    mBeginningSample = mLastSample;
                    mEndingSample = s;
                    mLastSample = null;
                } else {
                    if (DBG) Slog.d(TAG, "Throwing current sample away because it is too small");
                }
            }
        }
    }

    public void resetSamplingData() {
        if (DBG) Slog.d(TAG, "Resetting sampled network data");
        synchronized(mSamplingDataLock) {

            // We could just take another sample here and treat it as an
            // 'ending sample' effectively shortening sampling interval, but that
            // requires extra work (specifically, reading the sample needs to be
            // done asynchronously)

            mLastSample = null;
        }
    }

    public long getSampledTxByteCount() {
        synchronized(mSamplingDataLock) {
            if (mBeginningSample != null && mEndingSample != null) {
                return mEndingSample.mTxByteCount - mBeginningSample.mTxByteCount;
            } else {
                return LinkQualityInfo.UNKNOWN_LONG;
            }
        }
    }

    public long getSampledTxPacketCount() {
        synchronized(mSamplingDataLock) {
            if (mBeginningSample != null && mEndingSample != null) {
                return mEndingSample.mTxPacketCount - mBeginningSample.mTxPacketCount;
            } else {
                return LinkQualityInfo.UNKNOWN_LONG;
            }
        }
    }

    public long getSampledTxPacketErrorCount() {
        synchronized(mSamplingDataLock) {
            if (mBeginningSample != null && mEndingSample != null) {
                return mEndingSample.mTxPacketErrorCount - mBeginningSample.mTxPacketErrorCount;
            } else {
                return LinkQualityInfo.UNKNOWN_LONG;
            }
        }
    }

    public long getSampledRxByteCount() {
        synchronized(mSamplingDataLock) {
            if (mBeginningSample != null && mEndingSample != null) {
                return mEndingSample.mRxByteCount - mBeginningSample.mRxByteCount;
            } else {
                return LinkQualityInfo.UNKNOWN_LONG;
            }
        }
    }

    public long getSampledRxPacketCount() {
        synchronized(mSamplingDataLock) {
            if (mBeginningSample != null && mEndingSample != null) {
                return mEndingSample.mRxPacketCount - mBeginningSample.mRxPacketCount;
            } else {
                return LinkQualityInfo.UNKNOWN_LONG;
            }
        }
    }

    public long getSampledPacketCount() {
        return getSampledPacketCount(mBeginningSample, mEndingSample);
    }

    public long getSampledPacketCount(SamplingSnapshot begin, SamplingSnapshot end) {
        if (begin != null && end != null) {
            long rxPacketCount = end.mRxPacketCount - begin.mRxPacketCount;
            long txPacketCount = end.mTxPacketCount - begin.mTxPacketCount;
            return rxPacketCount + txPacketCount;
        } else {
            return LinkQualityInfo.UNKNOWN_LONG;
        }
    }

    public long getSampledPacketErrorCount() {
        if (mBeginningSample != null && mEndingSample != null) {
            long rxPacketErrorCount = getSampledRxPacketErrorCount();
            long txPacketErrorCount = getSampledTxPacketErrorCount();
            return rxPacketErrorCount + txPacketErrorCount;
        } else {
            return LinkQualityInfo.UNKNOWN_LONG;
        }
    }

    public long getSampledRxPacketErrorCount() {
        synchronized(mSamplingDataLock) {
            if (mBeginningSample != null && mEndingSample != null) {
                return mEndingSample.mRxPacketErrorCount - mBeginningSample.mRxPacketErrorCount;
            } else {
                return LinkQualityInfo.UNKNOWN_LONG;
            }
        }
    }

    public long getSampleTimestamp() {
        synchronized(mSamplingDataLock) {
            if (mEndingSample != null) {
                return mEndingSample.mTimestamp;
            } else {
                return LinkQualityInfo.UNKNOWN_LONG;
            }
        }
    }

    public int getSampleDuration() {
        synchronized(mSamplingDataLock) {
            if (mBeginningSample != null && mEndingSample != null) {
                return (int) (mEndingSample.mTimestamp - mBeginningSample.mTimestamp);
            } else {
                return LinkQualityInfo.UNKNOWN_INT;
            }
        }
    }

    public void setCommonLinkQualityInfoFields(LinkQualityInfo li) {
        synchronized(mSamplingDataLock) {
            li.setLastDataSampleTime(getSampleTimestamp());
            li.setDataSampleDuration(getSampleDuration());
            li.setPacketCount(getSampledPacketCount());
            li.setPacketErrorCount(getSampledPacketErrorCount());
        }
    }
}

