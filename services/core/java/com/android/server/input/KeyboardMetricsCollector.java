/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.input;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.hardware.input.KeyboardLayout;
import android.util.proto.ProtoOutputStream;
import android.view.InputDevice;

import com.android.internal.os.KeyboardConfiguredProto.KeyboardLayoutConfig;
import com.android.internal.os.KeyboardConfiguredProto.RepeatedKeyboardLayoutConfig;
import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.util.List;

/**
 * Collect Keyboard metrics
 */
public final class KeyboardMetricsCollector {
    private static final String TAG = "KeyboardMetricCollector";

    /**
     * Log keyboard system shortcuts for the proto
     * {@link com.android.os.input.KeyboardSystemsEventReported}
     * defined in "stats/atoms/input/input_extension_atoms.proto"
     */
    public static void logKeyboardSystemsEventReportedAtom(InputDevice inputDevice,
            int keyboardSystemEvent, int[] keyCode, int modifierState) {
        int vendor_id = inputDevice.getVendorId();
        int product_id = inputDevice.getProductId();
        FrameworkStatsLog.write(FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED,
                vendor_id, product_id, keyboardSystemEvent, keyCode, modifierState);
    }

    /**
     * Function to log the KeyboardConfigured
     * {@link com.android.os.input.KeyboardConfigured} atom
     *
     * @param inputDevice Input device
     * @param keyboardLayoutConfigurations List of keyboard configurations
     * @param isFirstTimeConfiguration Whether keyboard is configured for the first time
     */
    public static void logKeyboardConfiguredAtom(InputDevice inputDevice,
            List<KeyboardLayoutConfiguration> keyboardLayoutConfigurations,
            boolean isFirstTimeConfiguration) {
        int vendor_id = inputDevice.getVendorId();
        int product_id = inputDevice.getProductId();

        // Creating proto to log nested field KeyboardLayoutConfig in atom
        ProtoOutputStream proto = new ProtoOutputStream();

        for (KeyboardLayoutConfiguration keyboardLayoutConfiguration :
                keyboardLayoutConfigurations) {
            addKeyboardLayoutConfigurationToProto(proto, keyboardLayoutConfiguration);
        }
        // Push the atom to Statsd
        FrameworkStatsLog.write(FrameworkStatsLog.KEYBOARD_CONFIGURED,
                isFirstTimeConfiguration, vendor_id, product_id, proto.getBytes());
    }

    /**
     * Populate the KeyboardLayoutConfig proto which is a repeated proto
     * in the RepeatedKeyboardLayoutConfig proto with values from the
     * {@link KeyboardLayoutConfiguration} class
     * The proto definitions can be found at:
     * "frameworks/proto_logging/stats/atoms/input/input_extension_atoms.proto"
     *
     * @param proto Representing the nested proto RepeatedKeyboardLayoutConfig
     * @param keyboardLayoutConfiguration Class containing the fields for populating the
     * KeyboardLayoutConfig proto
     */
    private static void addKeyboardLayoutConfigurationToProto(ProtoOutputStream proto,
            KeyboardLayoutConfiguration keyboardLayoutConfiguration) {
        // Start a new KeyboardLayoutConfig proto.
        long keyboardLayoutConfigToken = proto.start(
                RepeatedKeyboardLayoutConfig.KEYBOARD_LAYOUT_CONFIG);
        proto.write(KeyboardLayoutConfig.KEYBOARD_LANGUAGE_TAG,
                keyboardLayoutConfiguration.getKeyboardLanguageTag());
        proto.write(KeyboardLayoutConfig.KEYBOARD_LAYOUT_TYPE,
                keyboardLayoutConfiguration.getKeyboardLayoutType());
        proto.write(KeyboardLayoutConfig.KEYBOARD_LAYOUT_NAME,
                keyboardLayoutConfiguration.getKeyboardLayoutName());
        proto.write(KeyboardLayoutConfig.LAYOUT_SELECTION_CRITERIA,
                keyboardLayoutConfiguration.getLayoutSelectionCriteria());
        proto.end(keyboardLayoutConfigToken);
    }

    /**
     * Java class representing the proto KeyboardLayoutConfig defined in
     * "frameworks/proto_logging/stats/atoms/input/input_extension_atoms.proto"
     *
     * @see com.android.os.input.KeyboardConfigured
     */
    public static class KeyboardLayoutConfiguration {
        // KeyboardLayoutType in "frameworks/base/core/res/res/values/attrs.xml"
        // contains mapping for enums to int
        int mKeyboardLayoutType;
        String mKeyboardLanguageTag;
        KeyboardLayout mKeyboardLayout;
        @LayoutSelectionCriteria int mLayoutSelectionCriteria;

        @Retention(SOURCE)
        @IntDef(prefix = { "LAYOUT_SELECTION_CRITERIA_" }, value = {
                LAYOUT_SELECTION_CRITERIA_USER,
                LAYOUT_SELECTION_CRITERIA_DEVICE,
                LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD
        })
        public @interface LayoutSelectionCriteria {}

        /** Manual selection by user */
        public static final int LAYOUT_SELECTION_CRITERIA_USER = 0;

        /** Auto-detection based on device provided language tag and layout type */
        public static final int LAYOUT_SELECTION_CRITERIA_DEVICE = 1;

        /** Auto-detection based on IME provided language tag and layout type */
        public static final int LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD = 2;

        KeyboardLayoutConfiguration(int keyboardLayoutType,
                String keyboardLanguageTag,
                KeyboardLayout keyboardLayout,
                @LayoutSelectionCriteria int layoutSelectionCriteria) {
            mKeyboardLayoutType = keyboardLayoutType;
            mKeyboardLanguageTag = keyboardLanguageTag;
            mKeyboardLayout = keyboardLayout;
            mLayoutSelectionCriteria = layoutSelectionCriteria;
        }
        int getKeyboardLayoutType() {
            return mKeyboardLayoutType;
        }

        String getKeyboardLanguageTag() {
            return mKeyboardLanguageTag;
        }

        String getKeyboardLayoutName() {
            return mKeyboardLayout.getLabel();
        }

        @LayoutSelectionCriteria int getLayoutSelectionCriteria() {
            return mLayoutSelectionCriteria;
        }
    }
}

