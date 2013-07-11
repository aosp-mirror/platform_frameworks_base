/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.photography;

import android.graphics.Point;
import android.graphics.Rect;

/**
 * <p>The results of a single image capture from the image sensor.</p>
 *
 * <p>Contains the final configuration for the capture hardware (sensor, lens,
 * flash), the processing pipeline, the control algorithms, and the output
 * buffers.</p>
 *
 * <p>CaptureResults are produced by a {@link CameraDevice} after processing a
 * {@link CaptureRequest}. All properties listed for capture requests can also
 * be queried on the capture result, to determine the final values used for
 * capture. The result also includes additional metadata about the state of the
 * camera device during the capture.</p>
 *
 */
public final class CaptureResult extends CameraMetadata {

    /**
     * The timestamp representing the start of image capture, in nanoseconds.
     * This corresponds to the timestamp available through
     * {@link android.graphics.SurfaceTexture#getTimestamp SurfaceTexture.getTimestamp()}
     * or {@link android.media.Image#getTimestamp Image.getTimestamp()} for this
     * capture's image data.
     */
    public static final Key<Long> SENSOR_TIMESTAMP =
            new Key<Long>("android.sensor.timestamp", Long.TYPE);

    /**
     * The state of the camera device's auto-exposure algorithm. One of the
     * CONTROL_AE_STATE_* enumerations.
     */
    public static final Key<Integer> CONTROL_AE_STATE =
            new Key<Integer>("android.control.aeState", Integer.TYPE);

    /**
     * The auto-exposure algorithm is inactive.
     * @see CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_INACTIVE = 0;

    /**
     * The auto-exposure algorithm is currently searching for proper exposure.
     * @see CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_SEARCHING = 1;

    /**
     * The auto-exposure algorithm has reached proper exposure values for the
     * current scene.
     * @see CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_CONVERGED = 2;

    /**
     * The auto-exposure algorithm has been locked to its current values.
     * @see CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_LOCKED = 3;

    /**
     * The auto-exposure algorithm has reached proper exposure values as with
     * CONTROL_AE_STATE_CONVERGED, but the scene is too dark to take a good
     * quality image without firing the camera flash.
     * @see CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_FLASH_REQUIRED = 4;

    /**
     * The precapture sequence of the auto-exposure algorithm has been triggered,
     * and is underway.
     * @see CONTROL_AE_STATE
     */
    public static final int CONTROL_AE_STATE_PRECAPTURE =5;

    /**
     * The list of faces detected in this capture. Available if face detection
     * was enabled for this capture
     */
    public static final Key<Face[]> STATISTICS_DETECTED_FACES =
            new Key<Face[]>("android.statistics.faces", Face[].class);

    // TODO: Many many more

    /**
     * @hide
     */
    public CaptureResult() {
    }

    /**
     * Describes a face detected in an image.
     */
    public static class Face {

        /**
         * <p>Bounds of the face. A rectangle relative to the sensor's
         * {@link CameraProperties#SENSOR_ACTIVE_ARRAY_SIZE}, with (0,0)
         * representing the top-left corner of the active array rectangle.</p>
         */
        public Rect getBounds() {
            return mBounds;
        }

        /* <p>The confidence level for the detection of the face. The range is 1 to
         * 100. 100 is the highest confidence.</p>
         *
         * <p>Depending on the device, even very low-confidence faces may be
         * listed, so applications should filter out faces with low confidence,
         * depending on the use case. For a typical point-and-shoot camera
         * application that wishes to display rectangles around detected faces,
         * filtering out faces with confidence less than 50 is recommended.</p>
         *
         */
        public int getScore() {
            return mScore;
        }

        /**
         * An unique id per face while the face is visible to the tracker. If
         * the face leaves the field-of-view and comes back, it will get a new
         * id. This is an optional field, may not be supported on all devices.
         * If not supported, id will always be set to -1. The optional fields
         * are supported as a set. Either they are all valid, or none of them
         * are.
         */
        public int getId() {
            return mId;
        }

        /**
         * The coordinates of the center of the left eye. The coordinates are in
         * the same space as the ones for {@link #getBounds}. This is an
         * optional field, may not be supported on all devices. If not
         * supported, the value will always be set to null. The optional fields
         * are supported as a set. Either they are all valid, or none of them
         * are.
         */
        public Point getLeftEye() {
            return mLeftEye;
        }

        /**
         * The coordinates of the center of the right eye. The coordinates are
         * in the same space as the ones for {@link #getBounds}.This is an
         * optional field, may not be supported on all devices. If not
         * supported, the value will always be set to null. The optional fields
         * are supported as a set. Either they are all valid, or none of them
         * are.
         */
        public Point getRightEye() {
            return mRightEye;
        }

        /**
         * The coordinates of the center of the mouth.  The coordinates are in
         * the same space as the ones for {@link #getBounds}. This is an optional
         * field, may not be supported on all devices. If not supported, the
         * value will always be set to null. The optional fields are supported
         * as a set. Either they are all valid, or none of them are.
         */
        public Point getMouth() {
            return mMouth;
        }

        private Rect mBounds;
        private int mScore;
        private int mId;
        private Point mLeftEye;
        private Point mRightEye;
        private Point mMouth;
    }
}
