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

package com.android.settingslib.notification.modes;

import static com.google.common.base.Preconditions.checkArgument;

import android.graphics.drawable.Drawable;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Icon of a Zen Mode, already loaded from the owner's resources (if specified) or from a default.
 */
public record ZenIcon(@NonNull Key key, @NonNull Drawable drawable) {

    /**
     * Key of a Zen Mode Icon.
     *
     * <p>{@link #resPackage()} will be null if the resource belongs to the system, and thus can
     * be loaded with any {@code Context}.
     */
    public record Key(@Nullable String resPackage, @DrawableRes int resId) {

        public Key {
            checkArgument(resId != 0, "Resource id must be valid");
        }

        static Key forSystemResource(@DrawableRes int resId) {
            return new Key(null, resId);
        }
    }
}
