/*
 * Copyright (C) 2013 The Android Open Source Project
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


package android.hardware.camera2.params;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureResult;

/**
 * Describes a face detected in an image.
 */
public final class Face {

    /**
     * The ID is {@code -1} when the optional set of fields is unsupported.
     *
     * @see #getId()
     */
    public static final int ID_UNSUPPORTED = -1;

    /**
     * The minimum possible value for the confidence level.
     *
     * @see #getScore()
     */
    public static final int SCORE_MIN = 1;

    /**
     * The maximum possible value for the confidence level.
     *
     * @see #getScore()
     */
    public static final int SCORE_MAX = 100;

    private Rect mBounds;
    private int mScore;
    private int mId;
    private Point mLeftEye;
    private Point mRightEye;
    private Point mMouth;

    /**
     * Create a new face with all fields set.
     *
     * <p>The id, leftEyePosition, rightEyePosition, and mouthPosition are considered optional.
     * They are only required when the {@link CaptureResult} reports that the value of key
     * {@link CaptureResult#STATISTICS_FACE_DETECT_MODE} is
     * {@link CameraMetadata#STATISTICS_FACE_DETECT_MODE_FULL}.
     * If the id is {@value #ID_UNSUPPORTED} then the leftEyePosition, rightEyePosition, and
     * mouthPositions are guaranteed to be {@code null}. Otherwise, each of leftEyePosition,
     * rightEyePosition, and mouthPosition may be independently null or not-null.</p>
     *
     * @param bounds Bounds of the face.
     * @param score Confidence level between {@value #SCORE_MIN}-{@value #SCORE_MAX}.
     * @param id A unique ID per face visible to the tracker.
     * @param leftEyePosition The position of the left eye.
     * @param rightEyePosition The position of the right eye.
     * @param mouthPosition The position of the mouth.
     *
     * @throws IllegalArgumentException
     *             if bounds is {@code null},
     *             or if the confidence is not in the range of
     *             {@value #SCORE_MIN}-{@value #SCORE_MAX},
     *             or if id is {@value #ID_UNSUPPORTED} and
     *               leftEyePosition/rightEyePosition/mouthPosition aren't all null,
     *             or else if id is negative.
     *
     * @hide
     */
    public Face(@NonNull Rect bounds, int score, int id,
            @NonNull Point leftEyePosition, @NonNull Point rightEyePosition,
            @NonNull Point mouthPosition) {
        init(bounds, score, id, leftEyePosition, rightEyePosition, mouthPosition);
    }

    /**
     * Create a new face without the optional fields.
     *
     * <p>The id, leftEyePosition, rightEyePosition, and mouthPosition are considered optional.
     * If the id is {@value #ID_UNSUPPORTED} then the leftEyePosition, rightEyePosition, and
     * mouthPositions are guaranteed to be {@code null}. Otherwise, each of leftEyePosition,
     * rightEyePosition, and mouthPosition may be independently null or not-null. When devices
     * report the value of key {@link CaptureResult#STATISTICS_FACE_DETECT_MODE} as
     * {@link CameraMetadata#STATISTICS_FACE_DETECT_MODE_SIMPLE} in {@link CaptureResult},
     * the face id of each face is expected to be {@value #ID_UNSUPPORTED}, the leftEyePosition,
     * rightEyePosition, and mouthPositions are expected to be {@code null} for each face.</p>
     *
     * @param bounds Bounds of the face.
     * @param score Confidence level between {@value #SCORE_MIN}-{@value #SCORE_MAX}.
     *
     * @throws IllegalArgumentException
     *             if bounds is {@code null},
     *             or if the confidence is not in the range of
     *             {@value #SCORE_MIN}-{@value #SCORE_MAX}.
     *
     * @hide
     */
    public Face(@NonNull Rect bounds, int score) {
        init(bounds, score, ID_UNSUPPORTED,
                /*leftEyePosition*/null, /*rightEyePosition*/null, /*mouthPosition*/null);
    }

    /**
     * Initialize the object (shared by constructors).
     */
    private void init(@NonNull Rect bounds, int score, int id,
            @Nullable Point leftEyePosition, @Nullable Point rightEyePosition,
            @Nullable Point mouthPosition) {
        checkNotNull("bounds", bounds);
        checkScore(score);
        checkId(id);
        if (id == ID_UNSUPPORTED) {
            checkNull("leftEyePosition", leftEyePosition);
            checkNull("rightEyePosition", rightEyePosition);
            checkNull("mouthPosition", mouthPosition);
        }
        checkFace(leftEyePosition, rightEyePosition, mouthPosition);

        mBounds = bounds;
        mScore = score;
        mId = id;
        mLeftEye = leftEyePosition;
        mRightEye = rightEyePosition;
        mMouth = mouthPosition;
    }

    /**
     * Bounds of the face.
     *
     * <p>A rectangle relative to the sensor's
     * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE}, with (0,0)
     * representing the top-left corner of the active array rectangle.</p>
     *
     * <p>There is no constraints on the Rectangle value other than it
     * is not-{@code null}.</p>
     */
    public Rect getBounds() {
        return mBounds;
    }

    /**
     * The confidence level for the detection of the face.
     *
     * <p>The range is {@value #SCORE_MIN} to {@value #SCORE_MAX}.
     * {@value #SCORE_MAX} is the highest confidence.</p>
     *
     * <p>Depending on the device, even very low-confidence faces may be
     * listed, so applications should filter out faces with low confidence,
     * depending on the use case. For a typical point-and-shoot camera
     * application that wishes to display rectangles around detected faces,
     * filtering out faces with confidence less than half of {@value #SCORE_MAX}
     * is recommended.</p>
     *
     * @see #SCORE_MAX
     * @see #SCORE_MIN
     */
    @IntRange(from = SCORE_MIN, to = SCORE_MAX)
    public int getScore() {
        return mScore;
    }

    /**
     * An unique id per face while the face is visible to the tracker.
     *
     * <p>
     * If the face leaves the field-of-view and comes back, it will get a new
     * id.</p>
     *
     * <p>This is an optional field and may not be supported on all devices.
     * If the id is {@value #ID_UNSUPPORTED} then the leftEyePosition, rightEyePosition, and
     * mouthPositions are guaranteed to be {@code null}. Otherwise, each of leftEyePosition,
     * rightEyePosition, and mouthPosition may be independently null or not-null. When devices
     * report the value of key {@link CaptureResult#STATISTICS_FACE_DETECT_MODE} as
     * {@link CameraMetadata#STATISTICS_FACE_DETECT_MODE_SIMPLE} in {@link CaptureResult},
     * the face id of each face is expected to be {@value #ID_UNSUPPORTED}.</p>
     *
     * <p>This value will either be {@value #ID_UNSUPPORTED} or
     * otherwise greater than {@code 0}.</p>
     *
     * @see #ID_UNSUPPORTED
     */
    public int getId() {
        return mId;
    }

    /**
     * The coordinates of the center of the left eye.
     *
     * <p>The coordinates are in
     * the same space as the ones for {@link #getBounds}. This is an
     * optional field and may not be supported on all devices. If not
     * supported, the value will always be set to null.
     * This value will  always be null only if {@link #getId()} returns
     * {@value #ID_UNSUPPORTED}.</p>
     *
     * @return The left eye position, or {@code null} if unknown.
     */
    public Point getLeftEyePosition() {
        return mLeftEye;
    }

    /**
     * The coordinates of the center of the right eye.
     *
     * <p>The coordinates are
     * in the same space as the ones for {@link #getBounds}.This is an
     * optional field and may not be supported on all devices. If not
     * supported, the value will always be set to null.
     * This value will  always be null only if {@link #getId()} returns
     * {@value #ID_UNSUPPORTED}.</p>
     *
     * @return The right eye position, or {@code null} if unknown.
     */
    public Point getRightEyePosition() {
        return mRightEye;
    }

    /**
     * The coordinates of the center of the mouth.
     *
     * <p>The coordinates are in
     * the same space as the ones for {@link #getBounds}. This is an optional
     * field and may not be supported on all devices. If not
     * supported, the value will always be set to null.
     * This value will  always be null only if {@link #getId()} returns
     * {@value #ID_UNSUPPORTED}.</p>
     * </p>
     *
     * @return The mouth position, or {@code null} if unknown.
     */
    public Point getMouthPosition() {
        return mMouth;
    }

    /**
     * Represent the Face as a string for debugging purposes.
     */
    @Override
    public String toString() {
        return String.format("{ bounds: %s, score: %s, id: %d, " +
                "leftEyePosition: %s, rightEyePosition: %s, mouthPosition: %s }",
                mBounds, mScore, mId, mLeftEye, mRightEye, mMouth);
    }

    private static void checkNotNull(String name, Object obj) {
        if (obj == null) {
            throw new IllegalArgumentException(name + " was required, but it was null");
        }
    }

    private static void checkNull(String name, Object obj) {
        if (obj != null) {
            throw new IllegalArgumentException(name + " was required to be null, but it wasn't");
        }
    }

    private static void checkScore(int score) {
        if (score < SCORE_MIN || score > SCORE_MAX) {
            throw new IllegalArgumentException("Confidence out of range");
        }
    }

    private static void checkId(int id) {
        if (id < 0 && id != ID_UNSUPPORTED) {
            throw new IllegalArgumentException("Id out of range");
        }
    }

    private static void checkFace(@Nullable Point leftEyePosition,
            @Nullable Point rightEyePosition, @Nullable Point mouthPosition) {
        if (leftEyePosition != null || rightEyePosition != null || mouthPosition != null) {
            if (leftEyePosition == null || rightEyePosition == null || mouthPosition == null) {
                throw new IllegalArgumentException("If any of leftEyePosition, rightEyePosition, "
                        + "or mouthPosition are non-null, all three must be non-null.");
            }
        }
    }

    /**
     * Builds a Face object.
     *
     * <p>This builder is public to allow for easier application testing by
     * creating custom object instances. It's not necessary to construct these
     * objects during normal use of the camera API.</p>
     */
    public static final class Builder {
        private long mBuilderFieldsSet = 0L;

        private static final long FIELD_BOUNDS = 1 << 1;
        private static final long FIELD_SCORE = 1 << 2;
        private static final long FIELD_ID = 1 << 3;
        private static final long FIELD_LEFT_EYE = 1 << 4;
        private static final long FIELD_RIGHT_EYE = 1 << 5;
        private static final long FIELD_MOUTH = 1 << 6;
        private static final long FIELD_BUILT = 1 << 0;

        private static final String FIELD_NAME_BOUNDS = "bounds";
        private static final String FIELD_NAME_SCORE = "score";
        private static final String FIELD_NAME_LEFT_EYE = "left eye";
        private static final String FIELD_NAME_RIGHT_EYE = "right eye";
        private static final String FIELD_NAME_MOUTH = "mouth";

        private Rect mBounds = null;
        private int mScore = 0;
        private int mId = ID_UNSUPPORTED;
        private Point mLeftEye = null;
        private Point mRightEye = null;
        private Point mMouth = null;

        public Builder() {
            // Empty
        }

        public Builder(@NonNull Face current) {
            mBounds = current.mBounds;
            mScore = current.mScore;
            mId = current.mId;
            mLeftEye = current.mLeftEye;
            mRightEye = current.mRightEye;
            mMouth = current.mMouth;
        }

        /**
         * Bounds of the face.
         *
         * <p>A rectangle relative to the sensor's
         * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE}, with (0,0)
         * representing the top-left corner of the active array rectangle.</p>
         *
         * <p>There is no constraints on the Rectangle value other than it
         * is not-{@code null}.</p>
         *
         * @param bounds Bounds of the face.
         * @return This builder.
         */
        public @NonNull Builder setBounds(@NonNull Rect bounds) {
            checkNotUsed();
            mBuilderFieldsSet |= FIELD_BOUNDS;
            mBounds = bounds;
            return this;
        }

        /**
         * The confidence level for the detection of the face.
         *
         * <p>The range is {@value #SCORE_MIN} to {@value #SCORE_MAX}.
         * {@value #SCORE_MAX} is the highest confidence.</p>
         *
         * <p>Depending on the device, even very low-confidence faces may be
         * listed, so applications should filter out faces with low confidence,
         * depending on the use case. For a typical point-and-shoot camera
         * application that wishes to display rectangles around detected faces,
         * filtering out faces with confidence less than half of {@value #SCORE_MAX}
         * is recommended.</p>
         *
         * @see #SCORE_MAX
         * @see #SCORE_MIN
         *
         * @param score Confidence level between {@value #SCORE_MIN}-{@value #SCORE_MAX}.
         * @return This builder.
         */
        public @NonNull Builder setScore(@IntRange(from = SCORE_MIN, to = SCORE_MAX) int score) {
            checkNotUsed();
            checkScore(score);
            mBuilderFieldsSet |= FIELD_SCORE;
            mScore = score;
            return this;
        }

        /**
         * An unique id per face while the face is visible to the tracker.
         *
         * <p>
         * If the face leaves the field-of-view and comes back, it will get a new
         * id.</p>
         *
         * <p>This is an optional field and may not be supported on all devices.
         * If the id is {@value #ID_UNSUPPORTED} then the leftEyePosition, rightEyePosition, and
         * mouthPositions should be {@code null}. Otherwise, each of leftEyePosition,
         * rightEyePosition, and mouthPosition may be independently null or not-null. When devices
         * report the value of key {@link CaptureResult#STATISTICS_FACE_DETECT_MODE} as
         * {@link CameraMetadata#STATISTICS_FACE_DETECT_MODE_SIMPLE} in {@link CaptureResult},
         * the face id of each face is expected to be {@value #ID_UNSUPPORTED}.</p>
         *
         * <p>This value should either be {@value #ID_UNSUPPORTED} or
         * otherwise greater than {@code 0}.</p>
         *
         * @see #ID_UNSUPPORTED
         *
         * @param id A unique ID per face visible to the tracker.
         * @return This builder.
         */
        public @NonNull Builder setId(int id) {
            checkNotUsed();
            checkId(id);
            mBuilderFieldsSet |= FIELD_ID;
            mId = id;
            return this;
        }

        /**
         * The coordinates of the center of the left eye.
         *
         * <p>The coordinates should be
         * in the same space as the ones for {@link #setBounds}. This is an
         * optional field and may not be supported on all devices. If not
         * supported, the value should always be unset or set to null.
         * This value should always be null if {@link #setId} is called with
         * {@value #ID_UNSUPPORTED}.</p>
         *
         * @param leftEyePosition The position of the left eye.
         * @return This builder.
         */
        public @NonNull Builder setLeftEyePosition(@NonNull Point leftEyePosition) {
            checkNotUsed();
            mBuilderFieldsSet |= FIELD_LEFT_EYE;
            mLeftEye = leftEyePosition;
            return this;
        }

        /**
         * The coordinates of the center of the right eye.
         *
         * <p>The coordinates should be
         * in the same space as the ones for {@link #setBounds}.This is an
         * optional field and may not be supported on all devices. If not
         * supported, the value should always be set to null.
         * This value should always be null if {@link #setId} is called with
         * {@value #ID_UNSUPPORTED}.</p>
         *
         * @param rightEyePosition The position of the right eye.
         * @return This builder.
         */
        public @NonNull Builder setRightEyePosition(@NonNull Point rightEyePosition) {
            checkNotUsed();
            mBuilderFieldsSet |= FIELD_RIGHT_EYE;
            mRightEye = rightEyePosition;
            return this;
        }

        /**
         * The coordinates of the center of the mouth.
         *
         * <p>The coordinates should be in
         * the same space as the ones for {@link #setBounds}. This is an optional
         * field and may not be supported on all devices. If not
         * supported, the value should always be set to null.
         * This value should always be null if {@link #setId} is called with
         * {@value #ID_UNSUPPORTED}.</p>
         * </p>
         *
         * @param mouthPosition The position of the mouth.
         * @return This builder.
         */
        public @NonNull Builder setMouthPosition(@NonNull Point mouthPosition) {
            checkNotUsed();
            mBuilderFieldsSet |= FIELD_MOUTH;
            mMouth = mouthPosition;
            return this;
        }

        /**
         * Returns an instance of <code>Face</code> created from the fields set
         * on this builder.
         *
         * @return A Face.
         */
        public @NonNull Face build() {
            checkNotUsed();
            checkFieldSet(FIELD_BOUNDS, FIELD_NAME_BOUNDS);
            checkFieldSet(FIELD_SCORE, FIELD_NAME_SCORE);
            if (mId == ID_UNSUPPORTED) {
                checkIdUnsupportedThenNull(mLeftEye, FIELD_NAME_LEFT_EYE);
                checkIdUnsupportedThenNull(mRightEye, FIELD_NAME_RIGHT_EYE);
                checkIdUnsupportedThenNull(mMouth, FIELD_NAME_MOUTH);
            }
            checkFace(mLeftEye, mRightEye, mMouth);

            mBuilderFieldsSet |= FIELD_BUILT;

            return new Face(mBounds, mScore, mId, mLeftEye, mRightEye, mMouth);
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & FIELD_BUILT) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }

        private void checkFieldSet(long field, String fieldName) {
            if ((mBuilderFieldsSet & field) == 0) {
                throw new IllegalStateException(
                        "Field \"" + fieldName + "\" must be set before building.");
            }
        }

        private void checkIdUnsupportedThenNull(Object obj, String fieldName) {
            if (obj != null) {
                throw new IllegalArgumentException("Field \"" + fieldName
                        + "\" must be unset or null if id is ID_UNSUPPORTED.");
            }
        }
    }
}
