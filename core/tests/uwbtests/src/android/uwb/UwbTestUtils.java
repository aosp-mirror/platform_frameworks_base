/*
 * Copyright 2020 The Android Open Source Project
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

package android.uwb;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class UwbTestUtils {
    private UwbTestUtils() {}

    public static boolean isUwbSupported(Context context) {
        PackageManager packageManager = context.getPackageManager();
        return packageManager.hasSystemFeature(PackageManager.FEATURE_UWB);
    }

    public static AngleMeasurement getAngleMeasurement() {
        return new AngleMeasurement.Builder()
                .setRadians(getDoubleInRange(-Math.PI, Math.PI))
                .setErrorRadians(getDoubleInRange(0, Math.PI))
                .setConfidenceLevel(getDoubleInRange(0, 1))
                .build();
    }

    public static AngleOfArrivalMeasurement getAngleOfArrivalMeasurement() {
        return new AngleOfArrivalMeasurement.Builder()
                .setAltitude(getAngleMeasurement())
                .setAzimuth(getAngleMeasurement())
                .build();
    }

    public static DistanceMeasurement getDistanceMeasurement() {
        return new DistanceMeasurement.Builder()
                .setMeters(getDoubleInRange(0, 100))
                .setErrorMeters(getDoubleInRange(0, 10))
                .setConfidenceLevel(getDoubleInRange(0, 1))
                .build();
    }

    public static RangingMeasurement getRangingMeasurement() {
        return getRangingMeasurement(getUwbAddress(false));
    }

    public static RangingMeasurement getRangingMeasurement(UwbAddress address) {
        return new RangingMeasurement.Builder()
                .setDistanceMeasurement(getDistanceMeasurement())
                .setAngleOfArrivalMeasurement(getAngleOfArrivalMeasurement())
                .setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos())
                .setRemoteDeviceAddress(address != null ? address : getUwbAddress(false))
                .setStatus(RangingMeasurement.RANGING_STATUS_SUCCESS)
                .build();
    }

    public static List<RangingMeasurement> getRangingMeasurements(int num) {
        List<RangingMeasurement> result = new ArrayList<>();
        for (int i = 0; i < num; i++) {
            result.add(getRangingMeasurement());
        }
        return result;
    }

    public static RangingReport getRangingReports(int numMeasurements) {
        RangingReport.Builder builder = new RangingReport.Builder();
        for (int i = 0; i < numMeasurements; i++) {
            builder.addMeasurement(getRangingMeasurement());
        }
        return builder.build();
    }

    private static double getDoubleInRange(double min, double max) {
        return min + (max - min) * Math.random();
    }

    public static UwbAddress getUwbAddress(boolean isShortAddress) {
        byte[] addressBytes = new byte[isShortAddress ? UwbAddress.SHORT_ADDRESS_BYTE_LENGTH :
                UwbAddress.EXTENDED_ADDRESS_BYTE_LENGTH];
        for (int i = 0; i < addressBytes.length; i++) {
            addressBytes[i] = (byte) getDoubleInRange(1, 255);
        }
        return UwbAddress.fromBytes(addressBytes);
    }

    public static Executor getExecutor() {
        return new Executor() {
            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };
    }
}
