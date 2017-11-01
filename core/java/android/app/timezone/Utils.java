/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.app.timezone;

/**
 * Shared code for android.app.timezone classes.
 */
final class Utils {
    private Utils() {}

    static int validateVersion(String type, int version) {
        if (version < 0 || version > 999) {
            throw new IllegalArgumentException("Invalid " + type + " version=" + version);
        }
        return version;
    }

    static String validateRulesVersion(String type, String rulesVersion) {
        validateNotNull(type, rulesVersion);

        if (rulesVersion.isEmpty()) {
            throw new IllegalArgumentException(type + " must not be empty");
        }
        return rulesVersion;
    }

    /** Validates that {@code object} is not null. Always returns {@code object}. */
    static <T> T validateNotNull(String type, T object) {
        if (object == null) {
            throw new NullPointerException(type + " == null");
        }
        return object;
    }

    /**
     * If {@code requireNotNull} is {@code true} calls {@link #validateNotNull(String, Object)},
     * and {@link #validateNull(String, Object)} otherwise. Returns {@code object}.
     */
    static <T> T validateConditionalNull(boolean requireNotNull, String type, T object) {
        if (requireNotNull) {
            return validateNotNull(type, object);
        } else {
            return validateNull(type, object);
        }
    }

    /** Validates that {@code object} is null. Always returns null. */
    static <T> T validateNull(String type, T object) {
        if (object != null) {
            throw new IllegalArgumentException(type + " != null");
        }
        return null;
    }
}
