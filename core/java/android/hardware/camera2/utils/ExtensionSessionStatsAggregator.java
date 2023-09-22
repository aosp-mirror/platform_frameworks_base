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

package android.hardware.camera2.utils;

import android.annotation.NonNull;
import android.hardware.CameraExtensionSessionStats;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Utility class to aggregate metrics specific to Camera Extensions and pass them to
 * {@link CameraManager}. {@link android.hardware.camera2.CameraExtensionSession} should call
 * {@link #commit} before closing the session.
 *
 * @hide
 */
public class ExtensionSessionStatsAggregator {
    private static final boolean DEBUG = false;
    private static final String TAG = ExtensionSessionStatsAggregator.class.getSimpleName();

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private final Object mLock = new Object(); // synchronizes access to all fields of the class
    private boolean mIsDone = false; // marks the aggregator as "done".
                                     // Mutations and commits become no-op if this is true.
    private final CameraExtensionSessionStats mStats;

    public ExtensionSessionStatsAggregator(@NonNull String cameraId, boolean isAdvanced) {
        if (DEBUG) {
            Log.v(TAG, "Creating new Extension Session Stats Aggregator");
        }
        mStats = new CameraExtensionSessionStats();
        mStats.key = "";
        mStats.cameraId = cameraId;
        mStats.isAdvanced = isAdvanced;
    }

    /**
     * Set client package name
     *
     * @param clientName package name of the client that these stats are associated with.
     */
    public void setClientName(@NonNull String clientName) {
        synchronized (mLock) {
            if (mIsDone) {
                return;
            }
            if (DEBUG) {
                Log.v(TAG, "Setting clientName: " + clientName);
            }
            mStats.clientName = clientName;
        }
    }

    /**
     * Set extension type.
     *
     * @param extensionType Type of extension. Must match one of
     *                      {@code CameraExtensionCharacteristics#EXTENSION_*}
     */
    public void setExtensionType(int extensionType) {
        synchronized (mLock) {
            if (mIsDone) {
                return;
            }
            if (DEBUG) {
                Log.v(TAG, "Setting type: " + extensionType);
            }
            mStats.type = extensionType;
        }
    }

    /**
     * Asynchronously commits the stats to CameraManager on a background thread.
     *
     * @param isFinal marks the stats as final and prevents any further commits or changes. This
     *                should be set to true when the stats are considered final for logging,
     *                for example right before the capture session is about to close
     */
    public void commit(boolean isFinal) {
        // Call binder on a background thread to reduce latencies from metrics logging.
        mExecutor.execute(() -> {
            synchronized (mLock) {
                if (mIsDone) {
                    return;
                }
                mIsDone = isFinal;
                if (DEBUG) {
                    Log.v(TAG, "Committing: " + prettyPrintStats(mStats));
                }
                mStats.key = CameraManager.reportExtensionSessionStats(mStats);
            }
        });
    }

    private static String prettyPrintStats(@NonNull CameraExtensionSessionStats stats) {
        return CameraExtensionSessionStats.class.getSimpleName() + ":\n"
                + "  key: '" + stats.key + "'\n"
                + "  cameraId: '" + stats.cameraId + "'\n"
                + "  clientName: '" + stats.clientName + "'\n"
                + "  type: '" + stats.type + "'\n"
                + "  isAdvanced: '" + stats.isAdvanced + "'\n";
    }

    /**
     * Return the current statistics key
     *
     * @return the current statistics key
     */
    public String getStatsKey() {
        return mStats.key;
    }
}
