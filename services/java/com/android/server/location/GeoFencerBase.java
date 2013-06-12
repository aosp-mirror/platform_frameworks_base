/* Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
 *
 * Not a Contribution, Apache license notifications and license are retained
 * for attribution purposes only.
 *
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server.location;

import android.os.Binder;
import android.os.Parcelable;
import android.util.Log;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.location.GeoFenceParams;
import android.location.ILocationListener;
import java.io.PrintWriter;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collection;
import java.util.ArrayList;

/**
 * This class defines a base class for GeoFencers
 *
 * @hide
 */
public abstract class GeoFencerBase {
    private static final String TAG = "GeoFencerBase";
    private HashMap<PendingIntent,GeoFenceParams> mGeoFences;

    public GeoFencerBase() {
        mGeoFences = new HashMap<PendingIntent,GeoFenceParams>();
    }

    public void add(double latitude, double longitude,
                 float radius, long expiration, PendingIntent intent,
                 String packageName) {
        add(new GeoFenceParams(latitude, longitude, radius,
                                     expiration, intent, packageName));
    }

    public void add(GeoFenceParams geoFence) {
        synchronized(mGeoFences) {
            mGeoFences.put(geoFence.mIntent, geoFence);
        }
        if (!start(geoFence)) {
            synchronized(mGeoFences) {
                mGeoFences.remove(geoFence.mIntent);
            }
        }
    }

    public void remove(PendingIntent intent) {
        remove(intent, false);
    }

    public void remove(PendingIntent intent, boolean localOnly) {
        GeoFenceParams geoFence = null;

        synchronized(mGeoFences) {
            geoFence = mGeoFences.remove(intent);
        }

        if (geoFence != null) {
            if (!localOnly && !stop(intent)) {
                synchronized(mGeoFences) {
                    mGeoFences.put(geoFence.mIntent, geoFence);
                }
            }
        }
    }

    public int getNumbOfGeoFences() {
        return mGeoFences.size();
    }

    public Collection<GeoFenceParams> getAllGeoFences() {
        return mGeoFences.values();
    }

    public GeoFenceParams getGeoFence(PendingIntent intent) {
        return mGeoFences.get(intent);
    }

    public boolean hasCaller(int uid) {
        for (GeoFenceParams alert : mGeoFences.values()) {
            if (alert.mUid == uid) {
                return true;
            }
        }
        return false;
    }

    public void removeCaller(int uid) {
        ArrayList<PendingIntent> removedFences = null;
        for (GeoFenceParams alert : mGeoFences.values()) {
            if (alert.mUid == uid) {
                if (removedFences == null) {
                    removedFences = new ArrayList<PendingIntent>();
                }
                removedFences.add(alert.mIntent);
            }
        }
        if (removedFences != null) {
            for (int i = removedFences.size()-1; i>=0; i--) {
                mGeoFences.remove(removedFences.get(i));
            }
        }
    }

    public void transferService(GeoFencerBase geofencer) {
        for (GeoFenceParams alert : geofencer.mGeoFences.values()) {
            geofencer.stop(alert.mIntent);
            add(alert);
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        if (mGeoFences.size() > 0) {
            pw.println(prefix + "  GeoFences:");
            prefix += "    ";
            for (Map.Entry<PendingIntent, GeoFenceParams> i
                     : mGeoFences.entrySet()) {
                pw.println(prefix + i.getKey() + ":");
                i.getValue().dump(pw, prefix);
            }
        }
    }

    abstract protected boolean start(GeoFenceParams geoFence);
    abstract protected boolean stop(PendingIntent intent);
}
