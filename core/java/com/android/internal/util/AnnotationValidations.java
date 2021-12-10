/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.internal.util;

import static com.android.internal.util.BitUtils.flagsUpTo;

import android.annotation.AppIdInt;
import android.annotation.ColorInt;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Size;
import android.annotation.UserIdInt;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.PackageInfoFlagsBits;
import android.content.pm.PackageManager.PermissionResult;
import android.os.UserHandle;

import java.lang.annotation.Annotation;

/**
 * Validations for common annotations, e.g. {@link IntRange}, {@link UserIdInt}, etc.
 *
 * For usability from generated {@link DataClass} code, all validations are overloads of
 * {@link #validate} with the following shape:
 * {@code
 *      <A extends Annotation> void validate(
 *              Class<A> cls, A ignored, Object value[, (String, Object)... annotationParams])
 * }
 * The ignored {@link Annotation} parameter is used to differentiate between overloads that would
 * otherwise have the same jvm signature. It's usually null at runtime.
 */
public class AnnotationValidations {
    private AnnotationValidations() {}

    public static void validate(Class<UserIdInt> annotation, UserIdInt ignored, int value) {
        if ((value != UserHandle.USER_NULL && value < -3)
                || value > Integer.MAX_VALUE / UserHandle.PER_USER_RANGE) {
            invalid(annotation, value);
        }
    }

    public static void validate(Class<AppIdInt> annotation, AppIdInt ignored, int value) {
        if (value / UserHandle.PER_USER_RANGE != 0 || value < 0) {
            invalid(annotation, value);
        }
    }

    public static void validate(Class<IntRange> annotation, IntRange ignored, int value,
            String paramName1, long param1, String paramName2, long param2) {
        validate(annotation, ignored, value, paramName1, param1);
        validate(annotation, ignored, value, paramName2, param2);
    }

    public static void validate(Class<IntRange> annotation, IntRange ignored, int value,
            String paramName, long param) {
        switch (paramName) {
            case "from":
                if (value < param) {
                    invalid(annotation, value, paramName, param);
                }
                break;
            case "to":
                if (value > param) {
                    invalid(annotation, value, paramName, param);
                }
                break;
        }
    }

    /**
     * Validate a long value with two parameters.
     */
    public static void validate(Class<IntRange> annotation, IntRange ignored, long value,
            String paramName1, long param1, String paramName2, long param2) {
        validate(annotation, ignored, value, paramName1, param1);
        validate(annotation, ignored, value, paramName2, param2);
    }

    /**
     * Validate a long value with one parameter.
     */
    public static void validate(Class<IntRange> annotation, IntRange ignored, long value,
            String paramName, long param) {
        switch (paramName) {
            case "from":
                if (value < param) {
                    invalid(annotation, value, paramName, param);
                }
                break;
            case "to":
                if (value > param) {
                    invalid(annotation, value, paramName, param);
                }
                break;
        }
    }

    public static void validate(Class<FloatRange> annotation, FloatRange ignored, float value,
            String paramName1, float param1, String paramName2, float param2) {
        validate(annotation, ignored, value, paramName1, param1);
        validate(annotation, ignored, value, paramName2, param2);
    }

    public static void validate(Class<FloatRange> annotation, FloatRange ignored, float value,
            String paramName, float param) {
        switch (paramName) {
            case "from": if (value < param) invalid(annotation, value, paramName, param); break;
            case "to": if (value > param) invalid(annotation, value, paramName, param); break;
        }
    }

    public static void validate(Class<NonNull> annotation, NonNull ignored, Object value) {
        if (value == null) {
            throw new NullPointerException();
        }
    }

    public static void validate(Class<Size> annotation, Size ignored, int value,
            String paramName1, int param1, String paramName2, int param2) {
        validate(annotation, ignored, value, paramName1, param1);
        validate(annotation, ignored, value, paramName2, param2);
    }

    public static void validate(Class<Size> annotation, Size ignored, int value,
            String paramName, int param) {
        switch (paramName) {
            case "value": {
                if (param != -1 && value != param) invalid(annotation, value, paramName, param);
            } break;
            case "min": {
                if (value < param) invalid(annotation, value, paramName, param);
            } break;
            case "max": {
                if (value > param) invalid(annotation, value, paramName, param);
            } break;
            case "multiple": {
                if (value % param != 0) invalid(annotation, value, paramName, param);
            } break;
        }
    }

    public static void validate(
            Class<PermissionResult> annotation, PermissionResult ignored, int value) {
        validateIntEnum(annotation, value, PackageManager.PERMISSION_GRANTED);
    }

    public static void validate(
            Class<PackageInfoFlagsBits> annotation, PackageInfoFlagsBits ignored, long value) {
        validateLongFlags(annotation, value,
                flagsUpTo(PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS));
    }

    public static void validate(
            Class<Intent.Flags> annotation, Intent.Flags ignored, int value) {
        validateIntFlags(annotation, value, flagsUpTo(Intent.FLAG_RECEIVER_OFFLOAD));
    }


    @Deprecated
    public static void validate(Class<? extends Annotation> annotation,
            Annotation ignored, Object value, Object... params) {}
    @Deprecated
    public static void validate(Class<? extends Annotation> annotation,
            Annotation ignored, Object value) {}
    @Deprecated
    public static void validate(Class<? extends Annotation> annotation,
            Annotation ignored, int value, Object... params) {}
    public static void validate(Class<? extends Annotation> annotation,
            Annotation ignored, int value) {
        if (("android.annotation".equals(annotation.getPackageName())
                && annotation.getSimpleName().endsWith("Res"))
                || ColorInt.class.equals(annotation)) {
            if (value < 0) {
                invalid(annotation, value);
            }
        }
    }
    public static void validate(Class<? extends Annotation> annotation,
            Annotation ignored, long value) {
        if ("android.annotation".equals(annotation.getPackageName())
                && annotation.getSimpleName().endsWith("Long")) {
            if (value < 0L) {
                invalid(annotation, value);
            }
        }
    }

    private static void validateIntEnum(
            Class<? extends Annotation> annotation, int value, int lastValid) {
        if (value > lastValid) {
            invalid(annotation, value);
        }
    }
    private static void validateIntFlags(
            Class<? extends Annotation> annotation, int value, int validBits) {
        if ((validBits & value) != validBits) {
            invalid(annotation, "0x" + Integer.toHexString(value));
        }
    }
    private static void validateLongFlags(
            Class<? extends Annotation> annotation, long value, int validBits) {
        if ((validBits & value) != validBits) {
            invalid(annotation, "0x" + Long.toHexString(value));
        }
    }
    private static void invalid(Class<? extends Annotation> annotation, Object value) {
        invalid("@" + annotation.getSimpleName(), value);
    }

    private static void invalid(Class<? extends Annotation> annotation, Object value,
            String paramName, Object param) {
        String paramPrefix = "value".equals(paramName) ? "" : paramName + " = ";
        invalid("@" + annotation.getSimpleName() + "(" + paramPrefix + param + ")", value);
    }

    private static void invalid(String valueKind, Object value) {
        throw new IllegalStateException("Invalid " + valueKind + ": " + value);
    }
}
