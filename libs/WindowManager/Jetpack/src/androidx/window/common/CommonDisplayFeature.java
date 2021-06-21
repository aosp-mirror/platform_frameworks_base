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

import android.annotation.Nullable;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Wrapper for both Extension and Sidecar versions of DisplayFeature. */
final class CommonDisplayFeature implements DisplayFeature {
    private static final Pattern FEATURE_PATTERN =
            Pattern.compile("([a-z]+)-\\[(\\d+),(\\d+),(\\d+),(\\d+)]");

    private static final String FEATURE_TYPE_FOLD = "fold";
    private static final String FEATURE_TYPE_HINGE = "hinge";

    // TODO(b/183049815): Support feature strings that include the state of the feature.
    /**
     * Parses a display feature from a string.
     *
     * @throws IllegalArgumentException if the provided string is improperly formatted or could not
     * otherwise be parsed.
     *
     * @see #FEATURE_PATTERN
     */
    @NonNull
    static CommonDisplayFeature parseFromString(@NonNull String string) {
        Matcher featureMatcher = FEATURE_PATTERN.matcher(string);
        if (!featureMatcher.matches()) {
            throw new IllegalArgumentException("Malformed feature description format: " + string);
        }
        try {
            String featureType = featureMatcher.group(1);
            int type;
            switch (featureType) {
                case FEATURE_TYPE_FOLD:
                    type = 1 /* TYPE_FOLD */;
                    break;
                case FEATURE_TYPE_HINGE:
                    type = 2 /* TYPE_HINGE */;
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

            return new CommonDisplayFeature(type, null, featureRect);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed feature description: " + string, e);
        }
    }

    private final int mType;
    @Nullable
    private final Integer mState;
    @NonNull
    private final Rect mRect;

    CommonDisplayFeature(int type, @Nullable Integer state, @NonNull Rect rect) {
        this.mType = type;
        this.mState = state;
        if (rect.width() == 0 && rect.height() == 0) {
            throw new IllegalArgumentException(
                    "Display feature rectangle cannot have zero width and height simultaneously.");
        }
        this.mRect = rect;
    }

    public int getType() {
        return mType;
    }

    /** Returns the state of the feature, or {@code null} if the feature has no state. */
    @Nullable
    public Integer getState() {
        return mState;
    }

    @NonNull
    public Rect getRect() {
        return mRect;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CommonDisplayFeature that = (CommonDisplayFeature) o;
        return mType == that.mType
                && Objects.equals(mState, that.mState)
                && mRect.equals(that.mRect);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mState, mRect);
    }
}
