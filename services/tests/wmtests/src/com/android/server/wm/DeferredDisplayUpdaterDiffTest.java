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

package com.android.server.wm;

import static android.hardware.display.DeviceProductInfo.CONNECTION_TO_SINK_UNKNOWN;
import static android.view.RoundedCorner.POSITION_TOP_LEFT;
import static android.view.RoundedCorners.NO_ROUNDED_CORNERS;

import static com.android.server.wm.DeferredDisplayUpdater.DEFERRABLE_FIELDS;
import static com.android.server.wm.DeferredDisplayUpdater.DIFF_NOT_WM_DEFERRABLE;
import static com.android.server.wm.DeferredDisplayUpdater.DIFF_WM_DEFERRABLE;
import static com.android.server.wm.DeferredDisplayUpdater.calculateDisplayInfoDiff;
import static com.android.server.wm.utils.DisplayInfoOverrides.copyDisplayInfoFields;

import static com.google.common.truth.Truth.assertWithMessage;

import android.annotation.NonNull;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.display.DeviceProductInfo;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.DisplayShape;
import android.view.RoundedCorner;
import android.view.RoundedCorners;
import android.view.SurfaceControl.RefreshRateRange;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Build/Install/Run:
 * atest WmTests:DeferredDisplayUpdaterDiffTest
 */
@SmallTest
@Presubmit
public class DeferredDisplayUpdaterDiffTest {

    private static final Set<String> IGNORED_FIELDS = new HashSet<>(Arrays.asList(
            "name" // human-readable name is ignored in equals() checks
    ));

    private static final DisplayInfo EMPTY = new DisplayInfo();

    @Test
    public void testCalculateDisplayInfoDiff_allDifferent_returnsChanges() {
        final DisplayInfo first = new DisplayInfo();
        final DisplayInfo second = new DisplayInfo();
        makeAllFieldsDifferent(first, second);

        int diff = calculateDisplayInfoDiff(first, second);

        assertWithMessage("Expected to receive a non-zero difference when "
                + "there are changes in all fields of DisplayInfo\n"
                + "Make sure that you have updated calculateDisplayInfoDiff function after "
                + "changing DisplayInfo fields").that(diff).isGreaterThan(0);
    }

    @Test
    public void testCalculateDisplayInfoDiff_forEveryDifferentField_returnsChanges() {
        generateWithSingleDifferentField((first, second, field) -> {
            int diff = calculateDisplayInfoDiff(first, second);

            assertWithMessage("Expected to receive a non-zero difference when "
                    + "there are changes in " + field + "\n"
                    + "Make sure that you have updated calculateDisplayInfoDiff function after "
                    + "changing DisplayInfo fields").that(diff).isGreaterThan(0);
        });
    }

    @Test
    public void testCalculateDisplayInfoDiff_forEveryDifferentField_returnsMatchingChange() {
        generateWithSingleDifferentField((first, second, field) -> {
            boolean hasDeferrableFieldChange = hasDeferrableFieldChange(first, second);
            int expectedDiff =
                    hasDeferrableFieldChange ? DIFF_WM_DEFERRABLE : DIFF_NOT_WM_DEFERRABLE;

            int diff = calculateDisplayInfoDiff(first, second);

            assertWithMessage("Expected to have diff = " + expectedDiff
                    + ", for field = " + field + "\n"
                    + "Make sure that you have updated calculateDisplayInfoDiff function after "
                    + "changing DisplayInfo fields").that(
                    diff).isEqualTo(expectedDiff);
        });
    }

    /**
     * Sets each field of the objects to different values using reflection
     */
    private static void makeAllFieldsDifferent(@NonNull DisplayInfo first,
            @NonNull DisplayInfo second) {
        forEachDisplayInfoField(field -> {
            try {
                setDifferentFieldValues(first, second, field);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static boolean hasDeferrableFieldChange(@NonNull DisplayInfo first,
            @NonNull DisplayInfo second) {
        final DisplayInfo firstDeferrableFieldsOnly = new DisplayInfo();
        final DisplayInfo secondDeferrableFieldsOnly = new DisplayInfo();

        copyDisplayInfoFields(/* out= */ firstDeferrableFieldsOnly, /* base= */
                EMPTY, /* override= */ first, DEFERRABLE_FIELDS);
        copyDisplayInfoFields(/* out= */ secondDeferrableFieldsOnly, /* base= */
                EMPTY, /* override= */ second, DEFERRABLE_FIELDS);

        return !firstDeferrableFieldsOnly.equals(secondDeferrableFieldsOnly);
    }

    /**
     * Creates pairs of DisplayInfos where only one field is different, the callback is called for
     * each field
     */
    private static void generateWithSingleDifferentField(DisplayInfoConsumer consumer) {
        forEachDisplayInfoField(field -> {
            final DisplayInfo first = new DisplayInfo();
            final DisplayInfo second = new DisplayInfo();

            try {
                setDifferentFieldValues(first, second, field);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }

            consumer.consume(first, second, field);
        });
    }

    private static void setDifferentFieldValues(@NonNull DisplayInfo first,
            @NonNull DisplayInfo second,
            @NonNull Field field) throws IllegalAccessException {
        final Class<?> type = field.getType();
        if (type.equals(int.class)) {
            field.setInt(first, 1);
            field.setInt(second, 2);
        } else if (type.equals(double.class)) {
            field.setDouble(first, 1.0);
            field.setDouble(second, 2.0);
        } else if (type.equals(short.class)) {
            field.setShort(first, (short) 1);
            field.setShort(second, (short) 2);
        } else if (type.equals(long.class)) {
            field.setLong(first, 1L);
            field.setLong(second, 2L);
        } else if (type.equals(char.class)) {
            field.setChar(first, 'a');
            field.setChar(second, 'b');
        } else if (type.equals(byte.class)) {
            field.setByte(first, (byte) 1);
            field.setByte(second, (byte) 2);
        } else if (type.equals(float.class)) {
            field.setFloat(first, 1.0f);
            field.setFloat(second, 2.0f);
        } else if (type == boolean.class) {
            field.setBoolean(first, true);
            field.setBoolean(second, false);
        } else if (type.equals(String.class)) {
            field.set(first, "one");
            field.set(second, "two");
        } else if (type.equals(DisplayAddress.class)) {
            field.set(first, DisplayAddress.fromPhysicalDisplayId(0));
            field.set(second, DisplayAddress.fromPhysicalDisplayId(1));
        } else if (type.equals(DeviceProductInfo.class)) {
            field.set(first, new DeviceProductInfo("name", "pnp_id", "product_id1", 2023,
                    CONNECTION_TO_SINK_UNKNOWN));
            field.set(second, new DeviceProductInfo("name", "pnp_id", "product_id2", 2023,
                    CONNECTION_TO_SINK_UNKNOWN));
        } else if (type.equals(DisplayCutout.class)) {
            field.set(first,
                    new DisplayCutout(Insets.NONE, new Rect(0, 0, 100, 100), null, null,
                            null));
            field.set(second,
                    new DisplayCutout(Insets.NONE, new Rect(0, 0, 200, 200), null, null,
                            null));
        } else if (type.equals(RoundedCorners.class)) {
            field.set(first, NO_ROUNDED_CORNERS);

            final RoundedCorners other = new RoundedCorners(NO_ROUNDED_CORNERS);
            other.setRoundedCorner(POSITION_TOP_LEFT,
                    new RoundedCorner(POSITION_TOP_LEFT, 1, 2, 3));
            field.set(second, other);
        } else if (type.equals(DisplayShape.class)) {
            field.set(first, DisplayShape.createDefaultDisplayShape(100, 200, false));
            field.set(second, DisplayShape.createDefaultDisplayShape(50, 100, false));
        } else if (type.equals(RefreshRateRange.class)) {
            field.set(first, new RefreshRateRange(0, 100));
            field.set(second, new RefreshRateRange(20, 80));
        } else if (type.equals(Display.HdrCapabilities.class)) {
            field.set(first, new Display.HdrCapabilities(new int[]{0}, 100, 50, 25));
            field.set(second, new Display.HdrCapabilities(new int[]{1}, 100, 50, 25));
        } else if (type.equals(SparseArray.class)
                && ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0].equals(
                RefreshRateRange.class)) {
            final SparseArray<RefreshRateRange> array1 = new SparseArray<>();
            array1.set(0, new RefreshRateRange(0, 100));
            final SparseArray<RefreshRateRange> array2 = new SparseArray<>();
            array2.set(0, new RefreshRateRange(20, 80));
            field.set(first, array1);
            field.set(second, array2);
        } else if (type.isArray() && type.getComponentType().equals(int.class)) {
            field.set(first, new int[]{0});
            field.set(second, new int[]{1});
        } else if (type.isArray() && type.getComponentType().equals(Display.Mode.class)) {
            field.set(first, new Display.Mode[]{new Display.Mode(100, 200, 300)});
            field.set(second, new Display.Mode[]{new Display.Mode(10, 20, 30)});
        } else {
            throw new IllegalArgumentException("Field " + field
                    + " is not supported by this test, please add implementation of setting "
                    + "different values for this field");
        }
    }

    private interface DisplayInfoConsumer {
        void consume(DisplayInfo first, DisplayInfo second, Field field);
    }

    /**
     * Iterates over every non-static field of DisplayInfo class except IGNORED_FIELDS
     */
    private static void forEachDisplayInfoField(Consumer<Field> consumer) {
        for (Field field : DisplayInfo.class.getDeclaredFields()) {
            field.setAccessible(true);

            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            if (IGNORED_FIELDS.contains(field.getName())) {
                continue;
            }

            consumer.accept(field);
        }
    }
}
