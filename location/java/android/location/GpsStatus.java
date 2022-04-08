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

package android.location;

import android.annotation.NonNull;
import android.util.SparseArray;

import java.util.Iterator;
import java.util.NoSuchElementException;


/**
 * This class represents the current state of the GPS engine and is used in conjunction with {@link
 * GpsStatus.Listener}.
 *
 * @deprecated Use {@link GnssStatus} instead.
 */
@Deprecated
public final class GpsStatus {
    private static final int MAX_SATELLITES = 255;
    private static final int GLONASS_SVID_OFFSET = 64;
    private static final int BEIDOU_SVID_OFFSET = 200;
    private static final int SBAS_SVID_OFFSET = -87;

    private int mTimeToFirstFix;
    private final SparseArray<GpsSatellite> mSatellites = new SparseArray<>();

    private final class SatelliteIterator implements Iterator<GpsSatellite> {
        private final int mSatellitesCount;

        private int mIndex = 0;

        SatelliteIterator() {
            mSatellitesCount = mSatellites.size();
        }

        @Override
        public boolean hasNext() {
            for (; mIndex < mSatellitesCount; ++mIndex) {
                GpsSatellite satellite = mSatellites.valueAt(mIndex);
                if (satellite.mValid) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public GpsSatellite next() {
            while (mIndex < mSatellitesCount) {
                GpsSatellite satellite = mSatellites.valueAt(mIndex);
                ++mIndex;
                if (satellite.mValid) {
                    return satellite;
                }
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private Iterable<GpsSatellite> mSatelliteList = SatelliteIterator::new;

    /**
     * Event sent when the GPS system has started.
     */
    public static final int GPS_EVENT_STARTED = 1;

    /**
     * Event sent when the GPS system has stopped.
     */
    public static final int GPS_EVENT_STOPPED = 2;

    /**
     * Event sent when the GPS system has received its first fix since starting.
     * Call {@link #getTimeToFirstFix()} to find the time from start to first fix.
     */
    public static final int GPS_EVENT_FIRST_FIX = 3;

    /**
     * Event sent periodically to report GPS satellite status.
     * Call {@link #getSatellites()} to retrieve the status for each satellite.
     */
    public static final int GPS_EVENT_SATELLITE_STATUS = 4;

    /**
     * Used for receiving notifications when GPS status has changed.
     *
     * @deprecated Use {@link GnssStatus.Callback} instead.
     */
    @Deprecated
    public interface Listener {
        /**
         * Called to report changes in the GPS status.
         * The event number is one of:
         * <ul>
         * <li> {@link GpsStatus#GPS_EVENT_STARTED}
         * <li> {@link GpsStatus#GPS_EVENT_STOPPED}
         * <li> {@link GpsStatus#GPS_EVENT_FIRST_FIX}
         * <li> {@link GpsStatus#GPS_EVENT_SATELLITE_STATUS}
         * </ul>
         *
         * When this method is called, the client should call
         * {@link LocationManager#getGpsStatus} to get additional
         * status information.
         *
         * @param event event number for this notification
         */
        void onGpsStatusChanged(int event);
    }

    /**
     * Used for receiving NMEA sentences from the GPS.
     * NMEA 0183 is a standard for communicating with marine electronic devices
     * and is a common method for receiving data from a GPS, typically over a serial port.
     * See <a href="http://en.wikipedia.org/wiki/NMEA_0183">NMEA 0183</a> for more details.
     * You can implement this interface and call {@link LocationManager#addNmeaListener}
     * to receive NMEA data from the GPS engine.
     * @deprecated use {@link OnNmeaMessageListener} instead.
     */
    @Deprecated
    public interface NmeaListener {
        void onNmeaReceived(long timestamp, String nmea);
    }

    /**
     * Builds a GpsStatus from the given GnssStatus.
     */
    @NonNull
    public static GpsStatus create(@NonNull GnssStatus gnssStatus, int timeToFirstFix) {
        GpsStatus status = new GpsStatus();
        status.setStatus(gnssStatus, timeToFirstFix);
        return status;
    }

    /**
     * Builds an empty GpsStatus.
     *
     * @hide
     */
    static GpsStatus createEmpty() {
        return new GpsStatus();
    }

    private GpsStatus() {
    }

    /**
     * @hide
     */
    void setStatus(GnssStatus status, int timeToFirstFix) {
        for (int i = 0; i < mSatellites.size(); i++) {
            mSatellites.valueAt(i).mValid = false;
        }

        mTimeToFirstFix = timeToFirstFix;
        for (int i = 0; i < status.getSatelliteCount(); i++) {
            int constellationType = status.getConstellationType(i);
            int prn = status.getSvid(i);
            // Other satellites passed through these APIs before GnssSvStatus was availble.
            // GPS, SBAS & QZSS can pass through at their nominally
            // assigned prn number (as long as it fits in the valid 0-255 range below.)
            // Glonass, and Beidou are passed through with the defacto standard offsets
            // Other future constellation reporting (e.g. Galileo) needs to use
            // GnssSvStatus on (N level) HAL & Java layers.
            if (constellationType == GnssStatus.CONSTELLATION_GLONASS) {
                prn += GLONASS_SVID_OFFSET;
            } else if (constellationType == GnssStatus.CONSTELLATION_BEIDOU) {
                prn += BEIDOU_SVID_OFFSET;
            } else if (constellationType == GnssStatus.CONSTELLATION_SBAS) {
                prn += SBAS_SVID_OFFSET;
            } else if ((constellationType != GnssStatus.CONSTELLATION_GPS) &&
                    (constellationType != GnssStatus.CONSTELLATION_QZSS)) {
                continue;
            }
            if (prn <= 0 || prn > MAX_SATELLITES) {
                continue;
            }

            GpsSatellite satellite = mSatellites.get(prn);
            if (satellite == null) {
                satellite = new GpsSatellite(prn);
                mSatellites.put(prn, satellite);
            }

            satellite.mValid = true;
            satellite.mSnr = status.getCn0DbHz(i);
            satellite.mElevation = status.getElevationDegrees(i);
            satellite.mAzimuth = status.getAzimuthDegrees(i);
            satellite.mHasEphemeris = status.hasEphemerisData(i);
            satellite.mHasAlmanac = status.hasAlmanacData(i);
            satellite.mUsedInFix = status.usedInFix(i);
        }
    }

    /**
     * Returns the time required to receive the first fix since the most recent
     * restart of the GPS engine.
     *
     * @return time to first fix in milliseconds
     */
    public int getTimeToFirstFix() {
        return mTimeToFirstFix;
    }

    /**
     * Returns an array of {@link GpsSatellite} objects, which represent the
     * current state of the GPS engine.
     *
     * @return the list of satellites
     */
    public Iterable<GpsSatellite> getSatellites() {
        return mSatelliteList;
    }

    /**
     * Returns the maximum number of satellites that can be in the satellite
     * list that can be returned by {@link #getSatellites()}.
     *
     * @return the maximum number of satellites
     */
    public int getMaxSatellites() {
        return mSatellites.size();
    }

}
