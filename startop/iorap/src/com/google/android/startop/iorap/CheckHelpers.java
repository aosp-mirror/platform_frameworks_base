/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.google.android.startop.iorap;

/**
 * Convenience short-hand to throw {@link IllegalAccessException} when the arguments
 * are out-of-range.
 */
public class CheckHelpers {
    /** @throws IllegalAccessException if {@param type} is not in {@code [0..maxValue]} */
    public static void checkTypeInRange(int type, int maxValue) {
        if (type < 0) {
            throw new IllegalArgumentException(
                    String.format("type must be non-negative (value=%d)", type));
        }
        if (type > maxValue) {
            throw new IllegalArgumentException(
                    String.format("type out of range (value=%d, max=%d)", type, maxValue));
        }
    }

    /** @throws IllegalAccessException if {@param state} is not in {@code [0..maxValue]} */
    public static void checkStateInRange(int state, int maxValue) {
        if (state < 0) {
            throw new IllegalArgumentException(
                    String.format("state must be non-negative (value=%d)", state));
        }
        if (state > maxValue) {
            throw new IllegalArgumentException(
                    String.format("state out of range (value=%d, max=%d)", state, maxValue));
        }
    }
}
