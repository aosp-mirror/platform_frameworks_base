/*
 * Copyright (C) 2021 The Android Open Source Project
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

package androidx.window.common;

import static androidx.window.util.ExtensionHelper.isZero;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A representation of a folding feature for both Extension and Sidecar.
 * For Sidecar this is the same as combining {@link androidx.window.sidecar.SidecarDeviceState} and
 * {@link androidx.window.sidecar.SidecarDisplayFeature}. For Extensions this is the mirror of
 * {@link androidx.window.extensions.layout.FoldingFeature}.
 */
public final class CommonFoldingFeature {

    private static final boolean DEBUG = false;

    public static final String TAG = CommonFoldingFeature.class.getSimpleName();

    /**
     * A common type to represent a hinge where the screen is continuous.
     */
    public static final int COMMON_TYPE_FOLD = 1;

    /**
     * A common type to represent a hinge where there is a physical gap separating multiple
     * displays.
     */
    public static final int COMMON_TYPE_HINGE = 2;

    @IntDef({COMMON_TYPE_FOLD, COMMON_TYPE_HINGE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {
    }

    /**
     * A common state to represent when the state is not known. One example is if the device is
     * closed. We do not emit this value for developers but is useful for implementation reasons.
     */
    public static final int COMMON_STATE_UNKNOWN = -1;

    /**
     * A common state to represent a FLAT hinge. This is needed because the definitions in Sidecar
     * and Extensions do not match exactly.
     */
    public static final int COMMON_STATE_FLAT = 3;
    /**
     * A common state to represent a HALF_OPENED hinge. This is needed because the definitions in
     * Sidecar and Extensions do not match exactly.
     */
    public static final int COMMON_STATE_HALF_OPENED = 2;

    /**
     * The possible states for a folding hinge.
     */
    @IntDef({COMMON_STATE_UNKNOWN, COMMON_STATE_FLAT, COMMON_STATE_HALF_OPENED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {
    }

    private static final Pattern FEATURE_PATTERN =
            Pattern.compile("([a-z]+)-\\[(\\d+),(\\d+),(\\d+),(\\d+)]-?(flat|half-opened)?");

    private static final String FEATURE_TYPE_FOLD = "fold";
    private static final String FEATURE_TYPE_HINGE = "hinge";

    private static final String PATTERN_STATE_FLAT = "flat";
    private static final String PATTERN_STATE_HALF_OPENED = "half-opened";

    /**
     * Parse a {@link List} of {@link CommonFoldingFeature} from a {@link String}.
     * @param value a {@link String} representation of multiple {@link CommonFoldingFeature}
     *              separated by a ":".
     * @param hingeState a global fallback value for a {@link CommonFoldingFeature} if one is not
     *                   specified in the input.
     * @throws IllegalArgumentException if the provided string is improperly formatted or could not
     * otherwise be parsed.
     * @see #FEATURE_PATTERN
     * @return {@link List} of {@link CommonFoldingFeature}.
     */
    static List<CommonFoldingFeature> parseListFromString(@NonNull String value,
            @State int hingeState) {
        List<CommonFoldingFeature> features = new ArrayList<>();
        String[] featureStrings =  value.split(";");
        for (String featureString : featureStrings) {
            CommonFoldingFeature feature;
            try {
                feature = CommonFoldingFeature.parseFromString(featureString, hingeState);
            } catch (IllegalArgumentException e) {
                if (DEBUG) {
                    Log.w(TAG, "Failed to parse display feature: " + featureString, e);
                }
                continue;
            }
            features.add(feature);
        }
        return features;
    }

    /**
     * Parses a display feature from a string.
     *
     * @param string A {@link String} representation of a {@link CommonFoldingFeature}.
     * @param hingeState A fallback value for the {@link State} if it is not specified in the input.
     * @throws IllegalArgumentException if the provided string is improperly formatted or could not
     *                                  otherwise be parsed.
     * @return {@link CommonFoldingFeature} represented by the {@link String} value.
     * @see #FEATURE_PATTERN
     */
    @NonNull
    private static CommonFoldingFeature parseFromString(@NonNull String string,
            @State int hingeState) {
        Matcher featureMatcher = FEATURE_PATTERN.matcher(string);
        if (!featureMatcher.matches()) {
            throw new IllegalArgumentException("Malformed feature description format: " + string);
        }
        try {
            String featureType = featureMatcher.group(1);
            featureType = featureType == null ? "" : featureType;
            int type;
            switch (featureType) {
                case FEATURE_TYPE_FOLD:
                    type = COMMON_TYPE_FOLD;
                    break;
                case FEATURE_TYPE_HINGE:
                    type = COMMON_TYPE_HINGE;
                    break;
                default: {
                    throw new IllegalArgumentException("Malformed feature type: " + featureType);
                }
            }

            int left = Integer.parseInt(featureMatcher.group(2));
            int top = Integer.parseInt(featureMatcher.group(3));
            int right = Integer.parseInt(featureMatcher.group(4));
            int bottom = Integer.parseInt(featureMatcher.group(5));
            Rect featureRect = new Rect(left, top, right, bottom);
            if (isZero(featureRect)) {
                throw new IllegalArgumentException("Feature has empty bounds: " + string);
            }
            String stateString = featureMatcher.group(6);
            stateString = stateString == null ? "" : stateString;
            final int state;
            switch (stateString) {
                case PATTERN_STATE_FLAT:
                    state = COMMON_STATE_FLAT;
                    break;
                case PATTERN_STATE_HALF_OPENED:
                    state = COMMON_STATE_HALF_OPENED;
                    break;
                default:
                    state = hingeState;
                    break;
            }
            return new CommonFoldingFeature(type, state, featureRect);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed feature description: " + string, e);
        }
    }

    private final int mType;
    @Nullable
    private final int mState;
    @NonNull
    private final Rect mRect;

    CommonFoldingFeature(int type, int state, @NonNull Rect rect) {
        assertValidState(state);
        this.mType = type;
        this.mState = state;
        if (rect.width() == 0 && rect.height() == 0) {
            throw new IllegalArgumentException(
                    "Display feature rectangle cannot have zero width and height simultaneously.");
        }
        this.mRect = rect;
    }

    /** Returns the type of the feature. */
    @Type
    public int getType() {
        return mType;
    }

    /** Returns the state of the feature.*/
    @State
    public int getState() {
        return mState;
    }

    /** Returns the bounds of the feature. */
    @NonNull
    public Rect getRect() {
        return mRect;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommonFoldingFeature that = (CommonFoldingFeature) o;
        return mType == that.mType
                && Objects.equals(mState, that.mState)
                && mRect.equals(that.mRect);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mState, mRect);
    }

    private static void assertValidState(@Nullable Integer state) {
        if (state != null && state != COMMON_STATE_FLAT
                && state != COMMON_STATE_HALF_OPENED && state != COMMON_STATE_UNKNOWN) {
            throw new IllegalArgumentException("Invalid state: " + state
                    + "must be either COMMON_STATE_FLAT or COMMON_STATE_HALF_OPENED");
        }
    }
}
