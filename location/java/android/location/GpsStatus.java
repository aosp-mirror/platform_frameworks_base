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

import android.util.SparseArray;

import java.util.Iterator;
import java.util.NoSuchElementException;


/**
 * This class represents the current state of the GPS engine.
 * This class is used in conjunction with the {@link Listener} interface.
 */
public final class GpsStatus {
    private static final int NUM_SATELLITES = 255;

    /* These package private values are modified by the LocationManager class */
    private int mTimeToFirstFix;
    private final SparseArray<GpsSatellite> mSatellites = new SparseArray<>();

    private final class SatelliteIterator implements Iterator<GpsSatellite> {

        private final SparseArray<GpsSatellite> mSatellites;
        private final int mSatellitesCount;

        private int mIndex = 0;

        SatelliteIterator(SparseArray<GpsSatellite> satellites) {
            mSatellites = satellites;
            mSatellitesCount = satellites.size();
        }

        public boolean hasNext() {
            for (; mIndex < mSatellitesCount; ++mIndex) {
                GpsSatellite satellite = mSatellites.valueAt(mIndex);
                if (satellite.mValid) {
                    return true;
                }
            }
            return false;
        }

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

        public void remove() {
            throw new UnsupportedOperationException();
        }
    }

    private Iterable<GpsSatellite> mSatelliteList = new Iterable<GpsSatellite>() {
        public Iterator<GpsSatellite> iterator() {
            return new SatelliteIterator(mSatellites);
        }
    };

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
     */
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
     */
    public interface NmeaListener {
        void onNmeaReceived(long timestamp, String nmea);
    }

    // For API-compat a public ctor() is not available
    GpsStatus() {}

    /**
     * Used internally within {@link LocationManager} to copy GPS status
     * data from the Location Manager Service to its cached GpsStatus instance.
     * Is synchronized to ensure that GPS status updates are atomic.
     */
    synchronized void setStatus(int svCount, int[] prns, float[] snrs,
            float[] elevations, float[] azimuths, int ephemerisMask,
            int almanacMask, int usedInFixMask) {
        clearSatellites();
        for (int i = 0; i < svCount; i++) {
            int prn = prns[i];
            int prnShift = (1 << (prn - 1));
            if (prn > 0 && prn <= NUM_SATELLITES) {
                GpsSatellite satellite = mSatellites.get(prn);
                if (satellite == null) {
                    satellite = new GpsSatellite(prn);
                    mSatellites.put(prn, satellite);
                }

                satellite.mValid = true;
                satellite.mSnr = snrs[i];
                satellite.mElevation = elevations[i];
                satellite.mAzimuth = azimuths[i];
                satellite.mHasEphemeris = ((ephemerisMask & prnShift) != 0);
                satellite.mHasAlmanac = ((almanacMask & prnShift) != 0);
                satellite.mUsedInFix = ((usedInFixMask & prnShift) != 0);
            }
        }
    }

    /**
     * Used by {@link LocationManager#getGpsStatus} to copy LocationManager's
     * cached GpsStatus instance to the client's copy.
     * Since this method is only used within {@link LocationManager#getGpsStatus},
     * it does not need to be synchronized.
     */
    void setStatus(GpsStatus status) {
        mTimeToFirstFix = status.getTimeToFirstFix();
        clearSatellites();

        SparseArray<GpsSatellite> otherSatellites = status.mSatellites;
        int otherSatellitesCount = otherSatellites.size();
        int satelliteIndex = 0;
        // merge both sparse arrays, note that we have already invalidated the elements in the
        // receiver array
        for (int i = 0; i < otherSatellitesCount; ++i) {
            GpsSatellite otherSatellite = otherSatellites.valueAt(i);
            int otherSatellitePrn = otherSatellite.getPrn();

            int satellitesCount = mSatellites.size();
            while (satelliteIndex < satellitesCount
                    && mSatellites.valueAt(satelliteIndex).getPrn() < otherSatellitePrn) {
                ++satelliteIndex;
            }

            if (satelliteIndex < mSatellites.size()) {
                GpsSatellite satellite = mSatellites.valueAt(satelliteIndex);
                if (satellite.getPrn() == otherSatellitePrn) {
                    satellite.setStatus(otherSatellite);
                } else {
                    satellite = new GpsSatellite(otherSatellitePrn);
                    satellite.setStatus(otherSatellite);
                    mSatellites.put(otherSatellitePrn, satellite);
                }
            } else {
                GpsSatellite satellite = new GpsSatellite(otherSatellitePrn);
                satellite.setStatus(otherSatellite);
                mSatellites.append(otherSatellitePrn, satellite);
            }
        }
    }

    void setTimeToFirstFix(int ttff) {
        mTimeToFirstFix = ttff;
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
        return NUM_SATELLITES;
    }

    private void clearSatellites() {
        int satellitesCount = mSatellites.size();
        for (int i = 0; i < satellitesCount; i++) {
            GpsSatellite satellite = mSatellites.valueAt(i);
            satellite.mValid = false;
        }
    }
}
