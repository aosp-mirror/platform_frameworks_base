/*
 * Copyright (C) 2024 The Android Open Source Project
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

package androidx.window.common.layout;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.util.ArraySet;

import java.util.Objects;
import java.util.Set;

/**
 * A class that represents if a fold is part of the device.
 */
public final class DisplayFoldFeatureCommon {

    /**
     * Returns a new instance of {@link DisplayFoldFeatureCommon} based off of
     * {@link CommonFoldingFeature} and whether or not half opened is supported.
     */
    public static DisplayFoldFeatureCommon create(CommonFoldingFeature foldingFeature,
            boolean isHalfOpenedSupported) {
        @FoldType
        final int foldType;
        if (foldingFeature.getType() == CommonFoldingFeature.COMMON_TYPE_HINGE) {
            foldType = DISPLAY_FOLD_FEATURE_TYPE_HINGE;
        } else {
            foldType = DISPLAY_FOLD_FEATURE_TYPE_SCREEN_FOLD_IN;
        }

        final Set<Integer> properties = new ArraySet<>();

        if (isHalfOpenedSupported) {
            properties.add(DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED);
        }
        return new DisplayFoldFeatureCommon(foldType, properties);
    }

    /**
     * The type of fold is unknown. This is here for compatibility reasons if a new type is added,
     * and cannot be reported to an incompatible application.
     */
    public static final int DISPLAY_FOLD_FEATURE_TYPE_UNKNOWN = 0;

    /**
     * The type of fold is a physical hinge separating two display panels.
     */
    public static final int DISPLAY_FOLD_FEATURE_TYPE_HINGE = 1;

    /**
     * The type of fold is a screen that folds from 0-180.
     */
    public static final int DISPLAY_FOLD_FEATURE_TYPE_SCREEN_FOLD_IN = 2;

    /**
     * @hide
     */
    @IntDef(value = {DISPLAY_FOLD_FEATURE_TYPE_UNKNOWN, DISPLAY_FOLD_FEATURE_TYPE_HINGE,
            DISPLAY_FOLD_FEATURE_TYPE_SCREEN_FOLD_IN})
    public @interface FoldType {
    }

    /**
     * The fold supports the half opened state.
     */
    public static final int DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED = 1;

    @IntDef(value = {DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED})
    public @interface FoldProperty {
    }

    @FoldType
    private final int mType;

    private final Set<Integer> mProperties;

    /**
     * Creates an instance of [FoldDisplayFeature].
     *
     * @param type                  the type of fold, either [FoldDisplayFeature.TYPE_HINGE] or
     *                              [FoldDisplayFeature.TYPE_FOLDABLE_SCREEN]
     * @hide
     */
    public DisplayFoldFeatureCommon(@FoldType int type, @NonNull Set<Integer> properties) {
        mType = type;
        mProperties = new ArraySet<>();
        assertPropertiesAreValid(properties);
        mProperties.addAll(properties);
    }

    /**
     * Returns the type of fold that is either a hinge or a fold.
     */
    @FoldType
    public int getType() {
        return mType;
    }

    /**
     * Returns {@code true} if the fold has the given property, {@code false} otherwise.
     */
    public boolean hasProperty(@FoldProperty int property) {
        return mProperties.contains(property);
    }
    /**
     * Returns {@code true} if the fold has all the given properties, {@code false} otherwise.
     */
    public boolean hasProperties(@NonNull @FoldProperty int... properties) {
        for (int i = 0; i < properties.length; i++) {
            if (!mProperties.contains(properties[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns a copy of the set of properties.
     * @hide
     */
    public Set<Integer> getProperties() {
        return new ArraySet<>(mProperties);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DisplayFoldFeatureCommon that = (DisplayFoldFeatureCommon) o;
        return mType == that.mType && Objects.equals(mProperties, that.mProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mProperties);
    }

    @Override
    public String toString() {
        return "DisplayFoldFeatureCommon{mType=" + mType + ", mProperties=" + mProperties + '}';
    }

    private static void assertPropertiesAreValid(@NonNull Set<Integer> properties) {
        for (int property : properties) {
            if (!isProperty(property)) {
                throw new IllegalArgumentException("Property is not a valid type: " + property);
            }
        }
    }

    private static boolean isProperty(int property) {
        if (property == DISPLAY_FOLD_FEATURE_PROPERTY_SUPPORTS_HALF_OPENED) {
            return true;
        }
        return false;
    }
}
