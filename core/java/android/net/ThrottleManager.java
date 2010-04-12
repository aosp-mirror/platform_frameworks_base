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

package android.net;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.os.Binder;
import android.os.RemoteException;

/**
 * Class that handles throttling.  It provides read/write numbers per interface
 * and methods to apply throttled rates.
 * {@hide}
 */
public class ThrottleManager
{
    /**
     * Broadcast each polling period to indicate new data counts.
     *
     * Includes four extras:
     * EXTRA_CYCLE_READ - a long of the read bytecount for the current cycle
     * EXTRA_CYCLE_WRITE -a long of the write bytecount for the current cycle
     * EXTRA_CYLCE_START -a long of MS for the cycle start time
     * EXTRA_CYCLE_END   -a long of MS for the cycle stop time
     * {@hide}
     */
    public static final String THROTTLE_POLL_ACTION = "android.net.thrott.POLL_ACTION";
    /**
     * The lookup key for a long for the read bytecount for this period.  Retrieve with
     * {@link android.content.Intent#getLongExtra(String)}.
     * {@hide}
     */
    public static final String EXTRA_CYCLE_READ = "cycleRead";
    /**
     * contains a long of the number of bytes written in the cycle
     * {@hide}
     */
    public static final String EXTRA_CYCLE_WRITE = "cycleWrite";
    /**
     * contains a long of the number of bytes read in the cycle
     * {@hide}
     */
    public static final String EXTRA_CYCLE_START = "cycleStart";
    /**
     * contains a long of the ms since 1970 used to init a calendar, etc for the end
     * of the cycle
     * {@hide}
     */
    public static final String EXTRA_CYCLE_END = "cycleEnd";

    /**
     * Broadcast when the thottle level changes.
     * {@hide}
     */
    public static final String THROTTLE_ACTION = "android.net.thrott.THROTTLE_ACTION";
    /**
     * int of the current bandwidth in TODO
     * {@hide}
     */
    public static final String EXTRA_THROTTLE_LEVEL = "level";

    /**
     * Broadcast on boot and whenever the settings change.
     * {@hide}
     */
    public static final String POLICY_CHANGED_ACTION = "android.net.thrott.POLICY_CHANGED_ACTION";

    // {@hide}
    public static final int DIRECTION_TX = 0;
    // {@hide}
    public static final int DIRECTION_RX = 1;

    // {@hide}
    public static final int PERIOD_CYCLE  = 0;
    // {@hide}
    public static final int PERIOD_YEAR   = 1;
    // {@hide}
    public static final int PERIOD_MONTH  = 2;
    // {@hide}
    public static final int PERIOD_WEEK   = 3;
    // @hide
    public static final int PERIOD_7DAY   = 4;
    // @hide
    public static final int PERIOD_DAY    = 5;
    // @hide
    public static final int PERIOD_24HOUR = 6;
    // @hide
    public static final int PERIOD_HOUR   = 7;
    // @hide
    public static final int PERIOD_60MIN  = 8;
    // @hide
    public static final int PERIOD_MINUTE = 9;
    // @hide
    public static final int PERIOD_60SEC  = 10;
    // @hide
    public static final int PERIOD_SECOND = 11;

    

    /**
     * returns a long of the ms from the epoch to the time the current cycle ends for the
     * named interface
     * {@hide}
     */
    public long getResetTime(String iface) {
        try {
            return mService.getResetTime(iface);
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * returns a long of the ms from the epoch to the time the current cycle started for the
     * named interface
     * {@hide}
     */
    public long getPeriodStartTime(String iface) {
        try {
            return mService.getPeriodStartTime(iface);
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * returns a long of the byte count either read or written on the named interface
     * for the period described.  Direction is either DIRECTION_RX or DIRECTION_TX and
     * period may only be PERIOD_CYCLE for the current cycle (other periods may be supported
     * in the future).  Ago indicates the number of periods in the past to lookup - 0 means
     * the current period, 1 is the last one, 2 was two periods ago..
     * {@hide}
     */
    public long getByteCount(String iface, int direction, int period, int ago) {
        try {
            return mService.getByteCount(iface, direction, period, ago);
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * returns the number of bytes read+written after which a particular cliff
     * takes effect on the named iface.  Currently only cliff #1 is supported (1 step)
     * {@hide}
     */
    public long getCliffThreshold(String iface, int cliff) {
        try {
            return mService.getCliffThreshold(iface, cliff);
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * returns the thottling bandwidth (bps) for a given cliff # on the named iface.
     * only cliff #1 is currently supported.
     * {@hide}
     */
    public int getCliffLevel(String iface, int cliff) {
        try {
            return mService.getCliffLevel(iface, cliff);
        } catch (RemoteException e) {
            return -1;
        }
    }

    /**
     * returns the help URI for throttling
     * {@hide}
     */
    public String getHelpUri() {
        try {
            return mService.getHelpUri();
        } catch (RemoteException e) {
            return null;
        }
    }


    private IThrottleManager mService;

    /**
     * Don't allow use of default constructor.
     */
    @SuppressWarnings({"UnusedDeclaration"})
    private ThrottleManager() {
    }

    /**
     * {@hide}
     */
    public ThrottleManager(IThrottleManager service) {
        if (service == null) {
            throw new IllegalArgumentException(
                "ThrottleManager() cannot be constructed with null service");
        }
        mService = service;
    }
}
