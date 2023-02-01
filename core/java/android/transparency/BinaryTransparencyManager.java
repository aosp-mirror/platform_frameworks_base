/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.transparency;

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.os.IBinaryTransparencyService;

import java.util.Map;

/**
 * BinaryTransparencyManager defines a number of system interfaces that other system apps or
 * services can make use of, when trying to get more information about the various binaries
 * that are installed on this device.
 * @hide
 */
@SystemService(Context.BINARY_TRANSPARENCY_SERVICE)
public class BinaryTransparencyManager {
    private static final String TAG = "TransparencyManager";

    private final Context mContext;
    private final IBinaryTransparencyService mService;

    /**
     * Constructor
     * @param context The calling context.
     * @param service A valid instance of IBinaryTransparencyService.
     * @hide
     */
    public BinaryTransparencyManager(Context context, IBinaryTransparencyService service) {
        mContext = context;
        mService = service;
    }


    /**
     * Obtains a string containing information that describes the signed images that are installed
     * on this device. Currently, this piece of information is identified as the VBMeta digest.
     * @return A String containing the VBMeta Digest of the signed partitions loaded on this device.
     */
    @NonNull
    public String getSignedImageInfo() {
        try {
            return mService.getSignedImageInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a map of all installed APEXs consisting of package name to SHA256 hash of the
     * package.
     * @return A Map with the following entries: {apex package name : sha256 digest of package}
     */
    @NonNull
    public Map getApexInfo() {
        try {
            Slog.d(TAG, "Calling backend's getApexInfo()");
            return mService.getApexInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}
