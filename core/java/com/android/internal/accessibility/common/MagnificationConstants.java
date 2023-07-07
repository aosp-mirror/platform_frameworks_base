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

package com.android.internal.accessibility.common;

/**
 * Collection of common constants for accessibility shortcut.
 */
public final class MagnificationConstants {
    private MagnificationConstants() {}

    /**
     * The min value for the magnification persisted scale. We assume if the scale is lower than
     * the min value, there will be no obvious magnification effect.
     */
    public static final float PERSISTED_SCALE_MIN_VALUE = 1.3f;
}
