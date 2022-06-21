/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.testing.screenshot;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.UiAutomation;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.ColorInt;
import androidx.annotation.ColorRes;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.test.espresso.Espresso;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.IdlingResource;

import org.json.JSONObject;
import org.junit.function.ThrowingRunnable;

import java.util.HashMap;
import java.util.Map;

/*
 * Note: This file was forked from
 * google3/third_party/java_src/android_libs/material_components/screenshot_tests/java/android/
 * support/design/scuba/color/DynamicColorsTestUtils.java.
 */

/** Utility that helps change the dynamic system colors for testing. */
@RequiresApi(32)
public class DynamicColorsTestUtils {

    private static final String TAG = DynamicColorsTestUtils.class.getSimpleName();

    private static final String THEME_CUSTOMIZATION_KEY = "theme_customization_overlay_packages";
    private static final String THEME_CUSTOMIZATION_SYSTEM_PALETTE_KEY =
            "android.theme.customization.system_palette";

    private static final int ORANGE_SYSTEM_SEED_COLOR = 0xA66800;
    private static final int ORANGE_EXPECTED_SYSTEM_ACCENT1_600_COLOR = -8235756;

    private DynamicColorsTestUtils() {
    }

    /**
     * Update system dynamic colors (e.g., android.R.color.system_accent1_600) based on an orange
     * seed color, and then wait for the change to propagate to the app by comparing
     * android.R.color.system_accent1_600 to the expected orange value.
     */
    public static void updateSystemColorsToOrange() {
        updateSystemColors(ORANGE_SYSTEM_SEED_COLOR, ORANGE_EXPECTED_SYSTEM_ACCENT1_600_COLOR);
    }

    /**
     * Update system dynamic colors (e.g., android.R.color.system_accent1_600) based on the provided
     * {@code seedColor}, and then wait for the change to propagate to the app by comparing
     * android.R.color.system_accent1_600 to {@code expectedSystemAccent1600}.
     */
    public static void updateSystemColors(
            @ColorInt int seedColor, @ColorInt int expectedSystemAccent1600) {
        Context context = getInstrumentation().getTargetContext();

        int actualSystemAccent1600 =
                ContextCompat.getColor(context, android.R.color.system_accent1_600);

        if (expectedSystemAccent1600 == actualSystemAccent1600) {
            String expectedColorString = Integer.toHexString(expectedSystemAccent1600);
            Log.d(
                    TAG,
                    "Skipped updating system colors since system_accent1_600 is already equal to "
                            + "expected: "
                            + expectedColorString);
            return;
        }

        updateSystemColors(seedColor);
    }

    /**
     * Update system dynamic colors (e.g., android.R.color.system_accent1_600) based on the provided
     * {@code seedColor}, and then wait for the change to propagate to the app by checking
     * android.R.color.system_accent1_600 for any change.
     */
    public static void updateSystemColors(@ColorInt int seedColor) {
        Context context = getInstrumentation().getTargetContext();

        // Initialize system color idling resource with original system_accent1_600 value.
        ColorChangeIdlingResource systemColorIdlingResource =
                new ColorChangeIdlingResource(context, android.R.color.system_accent1_600);

        // Update system theme color setting to trigger fabricated resource overlay.
        runWithShellPermissionIdentity(
                () ->
                        Settings.Secure.putString(
                                context.getContentResolver(),
                                THEME_CUSTOMIZATION_KEY,
                                buildThemeCustomizationString(seedColor)));

        // Wait for system color update to propagate to app.
        IdlingRegistry idlingRegistry = IdlingRegistry.getInstance();
        idlingRegistry.register(systemColorIdlingResource);
        Espresso.onIdle();
        idlingRegistry.unregister(systemColorIdlingResource);

        Log.d(TAG,
                Settings.Secure.getString(context.getContentResolver(), THEME_CUSTOMIZATION_KEY));
    }

    private static String buildThemeCustomizationString(@ColorInt int seedColor) {
        String seedColorHex = Integer.toHexString(seedColor);
        Map<String, String> themeCustomizationMap = new HashMap<>();
        themeCustomizationMap.put(THEME_CUSTOMIZATION_SYSTEM_PALETTE_KEY, seedColorHex);
        return new JSONObject(themeCustomizationMap).toString();
    }

    private static void runWithShellPermissionIdentity(@NonNull ThrowingRunnable runnable) {
        UiAutomation uiAutomation = getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity();
        try {
            runnable.run();
        } catch (Throwable e) {
            throw new RuntimeException(e);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    private static class ColorChangeIdlingResource implements IdlingResource {

        private final Context mContext;
        private final int mColorResId;
        private final int mInitialColorInt;

        private ResourceCallback mResourceCallback;
        private boolean mIdleNow;

        ColorChangeIdlingResource(Context context, @ColorRes int colorResId) {
            this.mContext = context;
            this.mColorResId = colorResId;
            this.mInitialColorInt = ContextCompat.getColor(context, colorResId);
        }

        @Override
        public String getName() {
            return ColorChangeIdlingResource.class.getName();
        }

        @Override
        public boolean isIdleNow() {
            if (mIdleNow) {
                return true;
            }

            int currentColorInt = ContextCompat.getColor(mContext, mColorResId);

            String initialColorString = Integer.toHexString(mInitialColorInt);
            String currentColorString = Integer.toHexString(currentColorInt);
            Log.d(TAG, String.format("Initial=%s, Current=%s", initialColorString,
                    currentColorString));

            mIdleNow = currentColorInt != mInitialColorInt;
            Log.d(TAG, String.format("idleNow=%b", mIdleNow));

            if (mIdleNow) {
                mResourceCallback.onTransitionToIdle();
            }
            return mIdleNow;
        }

        @Override
        public void registerIdleTransitionCallback(ResourceCallback resourceCallback) {
            this.mResourceCallback = resourceCallback;
        }
    }
}
