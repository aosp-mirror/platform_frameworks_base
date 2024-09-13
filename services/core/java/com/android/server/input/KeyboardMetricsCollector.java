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

import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_USER;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEVICE;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD;
import static android.hardware.input.KeyboardLayoutSelectionResult.LAYOUT_SELECTION_CRITERIA_DEFAULT;
import static android.hardware.input.KeyboardLayoutSelectionResult.layoutSelectionCriteriaToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.input.KeyboardLayout;
import android.hardware.input.KeyboardLayoutSelectionResult.LayoutSelectionCriteria;
import android.icu.util.ULocale;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.InputDevice;
import android.view.inputmethod.InputMethodSubtype;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.KeyboardConfiguredProto.KeyboardLayoutConfig;
import com.android.internal.os.KeyboardConfiguredProto.RepeatedKeyboardLayoutConfig;
import com.android.internal.util.FrameworkStatsLog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Collect Keyboard metrics
 */
public final class KeyboardMetricsCollector {
    private static final String TAG = "KeyboardMetricCollector";

    // To enable these logs, run: 'adb shell setprop log.tag.KeyboardMetricCollector DEBUG'
    // (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @VisibleForTesting
    static final String DEFAULT_LAYOUT_NAME = "Default";

    @VisibleForTesting
    public static final String DEFAULT_LANGUAGE_TAG = "None";

    private static final int INVALID_SYSTEMS_EVENT = FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED__KEYBOARD_SYSTEM_EVENT__UNSPECIFIED;

    /**
     * Log keyboard system shortcuts for the proto
     * {@link com.android.os.input.KeyboardSystemsEventReported}
     * defined in "stats/atoms/input/input_extension_atoms.proto"
     */
    public static void logKeyboardSystemsEventReportedAtom(@NonNull InputDevice inputDevice,
            int[] keycodes, int modifierState, int systemsEvent) {
        if (systemsEvent == INVALID_SYSTEMS_EVENT || inputDevice.isVirtual()
                || !inputDevice.isFullKeyboard()) {
            return;
        }
        FrameworkStatsLog.write(FrameworkStatsLog.KEYBOARD_SYSTEMS_EVENT_REPORTED,
                inputDevice.getVendorId(), inputDevice.getProductId(),
                systemsEvent, keycodes, modifierState, inputDevice.getDeviceBus());

        if (DEBUG) {
            Slog.d(TAG, "Logging Keyboard system event: " + modifierState + " + " + Arrays.toString(
                    keycodes) + " -> " + systemsEvent);
        }
    }

    /**
     * Function to log the KeyboardConfigured
     * {@link com.android.os.input.KeyboardConfigured} atom
     *
     * @param event {@link KeyboardConfigurationEvent} contains information about keyboard
     *              configuration. Use {@link KeyboardConfigurationEvent.Builder} to create the
     *              configuration event to log.
     */
    public static void logKeyboardConfiguredAtom(KeyboardConfigurationEvent event) {
        // Creating proto to log nested field KeyboardLayoutConfig in atom
        ProtoOutputStream proto = new ProtoOutputStream();

        for (LayoutConfiguration layoutConfiguration : event.getLayoutConfigurations()) {
            addKeyboardLayoutConfigurationToProto(proto, layoutConfiguration);
        }
        // Push the atom to Statsd
        FrameworkStatsLog.write(FrameworkStatsLog.KEYBOARD_CONFIGURED,
                event.isFirstConfiguration(), event.getVendorId(), event.getProductId(),
                proto.getBytes(), event.getDeviceBus());

        if (DEBUG) {
            Slog.d(TAG, "Logging Keyboard configuration event: " + event);
        }
    }

    /**
     * Populate the KeyboardLayoutConfig proto which is a repeated proto
     * in the RepeatedKeyboardLayoutConfig proto with values from the
     * {@link LayoutConfiguration} class
     * The proto definitions can be found at:
     * "frameworks/proto_logging/stats/atoms/input/input_extension_atoms.proto"
     *
     * @param proto Representing the nested proto RepeatedKeyboardLayoutConfig
     * @param layoutConfiguration Class containing the fields for populating the
     * KeyboardLayoutConfig proto
     */
    private static void addKeyboardLayoutConfigurationToProto(ProtoOutputStream proto,
            LayoutConfiguration layoutConfiguration) {
        // Start a new KeyboardLayoutConfig proto.
        long keyboardLayoutConfigToken = proto.start(
                RepeatedKeyboardLayoutConfig.KEYBOARD_LAYOUT_CONFIG);
        proto.write(KeyboardLayoutConfig.KEYBOARD_LANGUAGE_TAG,
                layoutConfiguration.keyboardLanguageTag);
        proto.write(KeyboardLayoutConfig.KEYBOARD_LAYOUT_TYPE,
                layoutConfiguration.keyboardLayoutType);
        proto.write(KeyboardLayoutConfig.KEYBOARD_LAYOUT_NAME,
                layoutConfiguration.keyboardLayoutName);
        proto.write(KeyboardLayoutConfig.LAYOUT_SELECTION_CRITERIA,
                layoutConfiguration.layoutSelectionCriteria);
        proto.write(KeyboardLayoutConfig.IME_LANGUAGE_TAG,
                layoutConfiguration.imeLanguageTag);
        proto.write(KeyboardLayoutConfig.IME_LAYOUT_TYPE,
                layoutConfiguration.imeLayoutType);
        proto.end(keyboardLayoutConfigToken);
    }

    /**
     * Class representing the proto KeyboardLayoutConfig defined in
     * "frameworks/proto_logging/stats/atoms/input/input_extension_atoms.proto
     *
     * @see com.android.os.input.KeyboardConfigured
     */
    public static class KeyboardConfigurationEvent {

        private final InputDevice mInputDevice;
        private final boolean mIsFirstConfiguration;
        private final List<LayoutConfiguration> mLayoutConfigurations;

        private KeyboardConfigurationEvent(InputDevice inputDevice, boolean isFirstConfiguration,
                List<LayoutConfiguration> layoutConfigurations) {
            mInputDevice = inputDevice;
            mIsFirstConfiguration = isFirstConfiguration;
            mLayoutConfigurations = layoutConfigurations;
        }

        public int getVendorId() {
            return mInputDevice.getVendorId();
        }

        public int getProductId() {
            return mInputDevice.getProductId();
        }

        public int getDeviceBus() {
            return mInputDevice.getDeviceBus();
        }

        public boolean isFirstConfiguration() {
            return mIsFirstConfiguration;
        }

        public List<LayoutConfiguration> getLayoutConfigurations() {
            return mLayoutConfigurations;
        }

        @Override
        public String toString() {
            return "InputDevice = {VendorId = " + Integer.toHexString(getVendorId())
                    + ", ProductId = " + Integer.toHexString(getProductId())
                    + ", Device Bus = " + Integer.toHexString(getDeviceBus())
                    + "}, isFirstConfiguration = " + mIsFirstConfiguration
                    + ", LayoutConfigurations = " + mLayoutConfigurations;
        }

        /**
         * Builder class to help create {@link KeyboardConfigurationEvent}.
         */
        public static class Builder {
            @NonNull
            private final InputDevice mInputDevice;
            private boolean mIsFirstConfiguration;
            private final List<InputMethodSubtype> mImeSubtypeList = new ArrayList<>();
            private final List<String> mSelectedLayoutList = new ArrayList<>();
            private final List<Integer> mLayoutSelectionCriteriaList = new ArrayList<>();

            public Builder(@NonNull InputDevice inputDevice) {
                Objects.requireNonNull(inputDevice, "InputDevice provided should not be null");
                mInputDevice = inputDevice;
            }

            /**
             * Set whether this is the first time this keyboard is configured.
             */
            public Builder setIsFirstTimeConfiguration(boolean isFirstTimeConfiguration) {
                mIsFirstConfiguration = isFirstTimeConfiguration;
                return this;
            }

            /**
             * Adds keyboard layout configuration info for a particular IME subtype language
             */
            public Builder addLayoutSelection(@NonNull InputMethodSubtype imeSubtype,
                    @Nullable String selectedLayout,
                    @LayoutSelectionCriteria int layoutSelectionCriteria) {
                Objects.requireNonNull(imeSubtype, "IME subtype provided should not be null");
                if (!isValidSelectionCriteria(layoutSelectionCriteria)) {
                    throw new IllegalStateException("Invalid layout selection criteria");
                }
                mImeSubtypeList.add(imeSubtype);
                mSelectedLayoutList.add(selectedLayout);
                mLayoutSelectionCriteriaList.add(layoutSelectionCriteria);
                return this;
            }

            /**
             * Creates {@link KeyboardConfigurationEvent} from the provided information
             */
            public KeyboardConfigurationEvent build() {
                int size = mImeSubtypeList.size();
                if (size == 0) {
                    throw new IllegalStateException("Should have at least one configuration");
                }
                List<LayoutConfiguration> configurationList = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    @LayoutSelectionCriteria int layoutSelectionCriteria =
                            mLayoutSelectionCriteriaList.get(i);
                    InputMethodSubtype imeSubtype = mImeSubtypeList.get(i);
                    String keyboardLanguageTag = mInputDevice.getKeyboardLanguageTag();
                    keyboardLanguageTag = TextUtils.isEmpty(keyboardLanguageTag)
                            ? DEFAULT_LANGUAGE_TAG : keyboardLanguageTag;
                    int keyboardLayoutType = KeyboardLayout.LayoutType.getLayoutTypeEnumValue(
                            mInputDevice.getKeyboardLayoutType());

                    ULocale pkLocale = imeSubtype.getPhysicalKeyboardHintLanguageTag();
                    String imeLanguageTag = pkLocale != null ? pkLocale.toLanguageTag()
                            : imeSubtype.getCanonicalizedLanguageTag();
                    imeLanguageTag = TextUtils.isEmpty(imeLanguageTag) ? DEFAULT_LANGUAGE_TAG
                            : imeLanguageTag;
                    int imeLayoutType = KeyboardLayout.LayoutType.getLayoutTypeEnumValue(
                            imeSubtype.getPhysicalKeyboardHintLayoutType());

                    // Sanitize null values
                    String keyboardLayoutName = mSelectedLayoutList.get(i) == null
                            ? DEFAULT_LAYOUT_NAME
                            : mSelectedLayoutList.get(i);

                    configurationList.add(
                            new LayoutConfiguration(keyboardLayoutType, keyboardLanguageTag,
                                    keyboardLayoutName, layoutSelectionCriteria,
                                    imeLayoutType, imeLanguageTag));
                }
                return new KeyboardConfigurationEvent(mInputDevice, mIsFirstConfiguration,
                        configurationList);
            }
        }
    }

    @VisibleForTesting
    static class LayoutConfiguration {
        // This should match enum values defined in "frameworks/base/core/res/res/values/attrs.xml"
        public final int keyboardLayoutType;
        public final String keyboardLanguageTag;
        public final String keyboardLayoutName;
        @LayoutSelectionCriteria
        public final int layoutSelectionCriteria;
        public final int imeLayoutType;
        public final String imeLanguageTag;

        private LayoutConfiguration(int keyboardLayoutType, String keyboardLanguageTag,
                String keyboardLayoutName, @LayoutSelectionCriteria int layoutSelectionCriteria,
                int imeLayoutType, String imeLanguageTag) {
            this.keyboardLayoutType = keyboardLayoutType;
            this.keyboardLanguageTag = keyboardLanguageTag;
            this.keyboardLayoutName = keyboardLayoutName;
            this.layoutSelectionCriteria = layoutSelectionCriteria;
            this.imeLayoutType = imeLayoutType;
            this.imeLanguageTag = imeLanguageTag;
        }

        @Override
        public String toString() {
            return "{keyboardLanguageTag = " + keyboardLanguageTag
                    + " keyboardLayoutType = "
                    + KeyboardLayout.LayoutType.getLayoutNameFromValue(keyboardLayoutType)
                    + " keyboardLayoutName = " + keyboardLayoutName
                    + " layoutSelectionCriteria = "
                    + layoutSelectionCriteriaToString(layoutSelectionCriteria)
                    + " imeLanguageTag = " + imeLanguageTag
                    + " imeLayoutType = " + KeyboardLayout.LayoutType.getLayoutNameFromValue(
                    imeLayoutType)
                    + "}";
        }
    }

    private static boolean isValidSelectionCriteria(int layoutSelectionCriteria) {
        return layoutSelectionCriteria == LAYOUT_SELECTION_CRITERIA_USER
                || layoutSelectionCriteria == LAYOUT_SELECTION_CRITERIA_DEVICE
                || layoutSelectionCriteria == LAYOUT_SELECTION_CRITERIA_VIRTUAL_KEYBOARD
                || layoutSelectionCriteria == LAYOUT_SELECTION_CRITERIA_DEFAULT;
    }
}
