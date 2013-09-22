/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net.wifi;

import android.os.Parcelable;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Describes the settings for batched wifi scans where the firmware performs many
 * scans and stores the timestamped results without waking the main processor each time.
 * This can give information over time with minimal battery impact.
 * @hide pending review
 */
public class BatchedScanSettings implements Parcelable {
    private static final String TAG = "BatchedScanSettings";

    /** Used to indicate no preference for an int value */
    public final static int UNSPECIFIED = Integer.MAX_VALUE;

    // TODO - make MIN/mAX dynamic and gservices adjustable.
    public final static int MIN_SCANS_PER_BATCH = 2;
    public final static int MAX_SCANS_PER_BATCH = 255;
    public final static int DEFAULT_SCANS_PER_BATCH = MAX_SCANS_PER_BATCH;

    public final static int MIN_AP_PER_SCAN = 2;
    public final static int MAX_AP_PER_SCAN = 255;
    public final static int DEFAULT_AP_PER_SCAN = 16;

    public final static int MIN_INTERVAL_SEC = 0;
    public final static int MAX_INTERVAL_SEC = 3600;
    public final static int DEFAULT_INTERVAL_SEC = 30;

    public final static int MIN_AP_FOR_DISTANCE = 0;
    public final static int MAX_AP_FOR_DISTANCE = MAX_AP_PER_SCAN;
    public final static int DEFAULT_AP_FOR_DISTANCE = 0;

    public final static int MAX_WIFI_CHANNEL = 196;

    /** The expected number of scans per batch.  Note that the firmware may drop scans
     *  leading to fewer scans during the normal batch scan duration.  This value need not
     *  be specified (may be set to {@link UNSPECIFIED}) by the application and we will try
     *  to scan as many times as the firmware can support.  If another app requests fewer
     *  scans per batch we will attempt to honor that.
     */
    public int maxScansPerBatch;

    /** The maximum desired AP listed per scan.  Fewer AP may be returned if that's all
     *  that the driver detected.  If another application requests more AP per scan that
     *  will take precedence.  The if more channels are detected than we request, the APs
     *  with the lowest signal strength will be dropped.
     */
    public int maxApPerScan;

    /** The channels used in the scan.  If all channels should be used, {@code null} may be
     *  specified.  If another application requests more channels or all channels, that
     *  will take precedence.
     */
    public Collection<String> channelSet;

    /** The time between the start of two sequential scans, in seconds.  If another
     *  application requests more frequent scans, that will take precedence.  If this
     * value is less than the duration of a scan, the next scan should start immediately.
     */
    public int scanIntervalSec;

    /** The number of the best (strongest signal) APs for which the firmware will
     *  attempt to get distance information (RTT).  Not all firmware supports this
     *  feature, so it may be ignored.  If another application requests a greater
     *  number, that will take precedence.
     */
    public int maxApForDistance;

    public BatchedScanSettings() {
        clear();
    }

    public void clear() {
        maxScansPerBatch = UNSPECIFIED;
        maxApPerScan = UNSPECIFIED;
        channelSet = null;
        scanIntervalSec = UNSPECIFIED;
        maxApForDistance = UNSPECIFIED;
    }

    public BatchedScanSettings(BatchedScanSettings source) {
        maxScansPerBatch = source.maxScansPerBatch;
        maxApPerScan = source.maxApPerScan;
        if (source.channelSet != null) {
            channelSet = new ArrayList(source.channelSet);
        }
        scanIntervalSec = source.scanIntervalSec;
        maxApForDistance = source.maxApForDistance;
    }

    private boolean channelSetIsValid() {
        if (channelSet == null || channelSet.isEmpty()) return true;
        for (String channel : channelSet) {
            try {
                int i = Integer.parseInt(channel);
                if (i > 0 && i <= MAX_WIFI_CHANNEL) continue;
            } catch (NumberFormatException e) {}
            if (channel.equals("A") || channel.equals("B")) continue;
            return false;
        }
        return true;
    }
    /** @hide */
    public boolean isInvalid() {
        if (maxScansPerBatch != UNSPECIFIED && (maxScansPerBatch < MIN_SCANS_PER_BATCH ||
                maxScansPerBatch > MAX_SCANS_PER_BATCH)) return true;
        if (maxApPerScan != UNSPECIFIED && (maxApPerScan < MIN_AP_PER_SCAN ||
                maxApPerScan > MAX_AP_PER_SCAN)) return true;
        if (channelSetIsValid() == false) return true;
        if (scanIntervalSec != UNSPECIFIED && (scanIntervalSec < MIN_INTERVAL_SEC ||
                scanIntervalSec > MAX_INTERVAL_SEC)) return true;
        if (maxApForDistance != UNSPECIFIED && (maxApForDistance < MIN_AP_FOR_DISTANCE ||
                maxApForDistance > MAX_AP_FOR_DISTANCE)) return true;
        return false;
    }

    /** @hide */
    public void constrain() {
        if (scanIntervalSec == UNSPECIFIED) {
            scanIntervalSec = DEFAULT_INTERVAL_SEC;
        } else if (scanIntervalSec < MIN_INTERVAL_SEC) {
            scanIntervalSec = MIN_INTERVAL_SEC;
        } else if (scanIntervalSec > MAX_INTERVAL_SEC) {
            scanIntervalSec = MAX_INTERVAL_SEC;
        }

        if (maxScansPerBatch == UNSPECIFIED) {
            maxScansPerBatch = DEFAULT_SCANS_PER_BATCH;
        } else if (maxScansPerBatch < MIN_SCANS_PER_BATCH) {
            maxScansPerBatch = MIN_SCANS_PER_BATCH;
        } else if (maxScansPerBatch > MAX_SCANS_PER_BATCH) {
            maxScansPerBatch = MAX_SCANS_PER_BATCH;
        }

        if (maxApPerScan == UNSPECIFIED) {
            maxApPerScan = DEFAULT_AP_PER_SCAN;
        } else if (maxApPerScan < MIN_AP_PER_SCAN) {
            maxApPerScan = MIN_AP_PER_SCAN;
        } else if (maxApPerScan > MAX_AP_PER_SCAN) {
            maxApPerScan = MAX_AP_PER_SCAN;
        }

        if (maxApForDistance == UNSPECIFIED) {
            maxApForDistance = DEFAULT_AP_FOR_DISTANCE;
        } else if (maxApForDistance < MIN_AP_FOR_DISTANCE) {
            maxApForDistance = MIN_AP_FOR_DISTANCE;
        } else if (maxApForDistance > MAX_AP_FOR_DISTANCE) {
            maxApForDistance = MAX_AP_FOR_DISTANCE;
        }
    }


    @Override
    public boolean equals(Object obj) {
        if (obj instanceof BatchedScanSettings == false) return false;
        BatchedScanSettings o = (BatchedScanSettings)obj;
        if (maxScansPerBatch != o.maxScansPerBatch ||
              maxApPerScan != o.maxApPerScan ||
              scanIntervalSec != o.scanIntervalSec ||
              maxApForDistance != o.maxApForDistance) return false;
        if (channelSet == null) {
            return (o.channelSet == null);
        }
        return channelSet.equals(o.channelSet);
    }

    @Override
    public int hashCode() {
        return maxScansPerBatch +
                (maxApPerScan * 3) +
                (scanIntervalSec * 5) +
                (maxApForDistance * 7) +
                (channelSet.hashCode() * 11);
    }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        String none = "<none>";

        sb.append("BatchScanSettings [maxScansPerBatch: ").
                append(maxScansPerBatch == UNSPECIFIED ? none : maxScansPerBatch).
                append(", maxApPerScan: ").append(maxApPerScan == UNSPECIFIED? none : maxApPerScan).
                append(", scanIntervalSec: ").
                append(scanIntervalSec == UNSPECIFIED ? none : scanIntervalSec).
                append(", maxApForDistance: ").
                append(maxApForDistance == UNSPECIFIED ? none : maxApForDistance).
                append(", channelSet: ");
        if (channelSet == null) {
            sb.append("ALL");
        } else {
            sb.append("<");
            for (String channel : channelSet) {
                sb.append(" " + channel);
            }
            sb.append(">");
        }
        sb.append("]");
        return sb.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(maxScansPerBatch);
        dest.writeInt(maxApPerScan);
        dest.writeInt(scanIntervalSec);
        dest.writeInt(maxApForDistance);
        dest.writeInt(channelSet == null ? 0 : channelSet.size());
        if (channelSet != null) {
            for (String channel : channelSet) dest.writeString(channel);
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<BatchedScanSettings> CREATOR =
        new Creator<BatchedScanSettings>() {
            public BatchedScanSettings createFromParcel(Parcel in) {
                BatchedScanSettings settings = new BatchedScanSettings();
                settings.maxScansPerBatch = in.readInt();
                settings.maxApPerScan = in.readInt();
                settings.scanIntervalSec = in.readInt();
                settings.maxApForDistance = in.readInt();
                int channelCount = in.readInt();
                if (channelCount > 0) {
                    settings.channelSet = new ArrayList(channelCount);
                    while (channelCount-- > 0) {
                        settings.channelSet.add(in.readString());
                    }
                }
                return settings;
            }

            public BatchedScanSettings[] newArray(int size) {
                return new BatchedScanSettings[size];
            }
        };
}
