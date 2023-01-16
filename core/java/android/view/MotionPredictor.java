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

package android.view;

import android.annotation.NonNull;
import android.content.Context;

import libcore.util.NativeAllocationRegistry;

import java.util.Arrays;
import java.util.List;

/**
 * Calculate motion predictions.
 *
 * Feed motion events to this class in order to generate the predicted events. The prediction
 * functionality may not be available on all devices. Check if a specific source is supported on a
 * given input device using #isPredictionAvailable.
 *
 * Send all of the events that were received from the system here in order to generate complete,
 * accurate predictions. When processing the returned predictions, make sure to consider all of the
 * {@link MotionEvent#getHistoricalAxisValue historical samples}.
 */
// Acts as a pass-through to the native MotionPredictor object.
// Do not store any state in this Java layer, or add any business logic here. All of the
// implementation details should go into the native MotionPredictor.
// The context / resource access must be here rather than in native layer due to the lack of the
// corresponding native API surface.
public final class MotionPredictor {

    private static class RegistryHolder {
        public static final NativeAllocationRegistry REGISTRY =
                NativeAllocationRegistry.createMalloced(
                        MotionPredictor.class.getClassLoader(),
                        nativeGetNativeMotionPredictorFinalizer());
    }

    // Pointer to the native object.
    private final long mPtr;
    private final Context mContext;

    /**
     * Create a new MotionPredictor for the provided {@link Context}.
     * @param context The context for the predictions
     */
    public MotionPredictor(@NonNull Context context) {
        mContext = context;
        final int offsetNanos = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_motionPredictionOffsetNanos);
        mPtr = nativeInitialize(offsetNanos);
        RegistryHolder.REGISTRY.registerNativeAllocation(this, mPtr);
    }

    /**
     * Record a movement so that in the future, a prediction for the current gesture can be
     * generated. Ensure to add all motions from the gesture of interest to generate the correct
     * prediction.
     * @param event The received event
     */
    public void record(@NonNull MotionEvent event) {
        nativeRecord(mPtr, event);
    }

    /**
     * Get predicted events for all gestures that have been provided to the 'record' function.
     * If events from multiple devices were sent to 'record', this will produce a separate
     * {@link MotionEvent} for each device id. The returned list may be empty if no predictions for
     * any of the added events are available.
     * Predictions may not reach the requested timestamp if the confidence in the prediction results
     * is low.
     *
     * @param predictionTimeNanos The time that the prediction should target, in the
     * {@link android.os.SystemClock#uptimeMillis} time base, but in nanoseconds.
     *
     * @return the list of predicted motion events, for each device id. Ensure to check the
     * historical data in addition to the latest ({@link MotionEvent#getX getX()},
     * {@link MotionEvent#getY getY()}) coordinates for smoothest prediction curves. Empty list is
     * returned if predictions are not supported, or not possible for the current set of gestures.
     */
    @NonNull
    public List<MotionEvent> predict(long predictionTimeNanos) {
        return Arrays.asList(nativePredict(mPtr, predictionTimeNanos));
    }

    /**
     * Check whether this device supports motion predictions for the given source type.
     *
     * @param deviceId The input device id
     * @param source The source of input events
     * @return True if the current device supports predictions, false otherwise.
     */
    public boolean isPredictionAvailable(int deviceId, int source) {
        // Device-specific override
        if (!mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enableMotionPrediction)) {
            return false;
        }
        return nativeIsPredictionAvailable(mPtr, deviceId, source);
    }

    private static native long nativeInitialize(int offsetNanos);
    private static native void nativeRecord(long nativePtr, MotionEvent event);
    private static native MotionEvent[] nativePredict(long nativePtr, long predictionTimeNanos);
    private static native boolean nativeIsPredictionAvailable(long nativePtr, int deviceId,
            int source);
    private static native long nativeGetNativeMotionPredictorFinalizer();
}
