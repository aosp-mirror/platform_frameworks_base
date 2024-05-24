/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.location.altitude;

import android.content.Context;
import android.frameworks.location.altitude.AddMslAltitudeToLocationRequest;
import android.frameworks.location.altitude.AddMslAltitudeToLocationResponse;
import android.frameworks.location.altitude.GetGeoidHeightRequest;
import android.frameworks.location.altitude.GetGeoidHeightResponse;
import android.frameworks.location.altitude.IAltitudeService;
import android.location.Location;
import android.location.altitude.AltitudeConverter;
import android.os.RemoteException;

import com.android.server.SystemService;

import java.io.IOException;

/**
 * Manages altitude information exchange through the HAL, e.g., geoid height requests such that
 * vendors can perform altitude conversions.
 *
 * @hide
 */
public class AltitudeService extends IAltitudeService.Stub {

    private final AltitudeConverter mAltitudeConverter = new AltitudeConverter();
    private final Context mContext;

    /** @hide */
    public AltitudeService(Context context) {
        mContext = context;
    }

    @Override
    public AddMslAltitudeToLocationResponse addMslAltitudeToLocation(
            AddMslAltitudeToLocationRequest request) throws RemoteException {
        Location location = new Location("");
        location.setLatitude(request.latitudeDegrees);
        location.setLongitude(request.longitudeDegrees);
        location.setAltitude(request.altitudeMeters);
        location.setVerticalAccuracyMeters(request.verticalAccuracyMeters);

        AddMslAltitudeToLocationResponse response = new AddMslAltitudeToLocationResponse();
        try {
            mAltitudeConverter.addMslAltitudeToLocation(mContext, location);
        } catch (IOException e) {
            response.success = false;
            return response;
        }
        response.mslAltitudeMeters = location.getMslAltitudeMeters();
        response.mslAltitudeAccuracyMeters = location.getMslAltitudeAccuracyMeters();
        response.success = true;
        return response;
    }

    @Override
    public GetGeoidHeightResponse getGeoidHeight(GetGeoidHeightRequest request)
            throws RemoteException {
        try {
            return mAltitudeConverter.getGeoidHeight(mContext, request);
        } catch (IOException e) {
            GetGeoidHeightResponse response = new GetGeoidHeightResponse();
            response.success = false;
            return response;
        }
    }

    @Override
    public String getInterfaceHash() {
        return IAltitudeService.HASH;
    }

    @Override
    public int getInterfaceVersion() {
        return IAltitudeService.VERSION;
    }

    /** @hide */
    public static class Lifecycle extends SystemService {

        private static final String SERVICE_NAME = IAltitudeService.DESCRIPTOR + "/default";

        private AltitudeService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new AltitudeService(getContext());
            publishBinderService(SERVICE_NAME, mService);
        }
    }
}
