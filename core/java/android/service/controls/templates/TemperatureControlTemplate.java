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

package android.service.controls.templates;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Bundle;
import android.service.controls.Control;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A template for a temperature related {@link Control} that supports multiple modes.
 *
 * Both the current mode and the active mode for the control can be specified. The combination of
 * the {@link Control#getDeviceType} and the current and active mode will determine colors and
 * transitions for the UI element.
 */
public final class TemperatureControlTemplate extends ControlTemplate {

    private static final String TAG = "ThermostatTemplate";

    private static final @TemplateType int TYPE = TYPE_TEMPERATURE;
    private static final String KEY_TEMPLATE = "key_template";
    private static final String KEY_CURRENT_MODE = "key_current_mode";
    private static final String KEY_CURRENT_ACTIVE_MODE = "key_current_active_mode";
    private static final String KEY_MODES = "key_modes";

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            MODE_UNKNOWN,
            MODE_OFF,
            MODE_HEAT,
            MODE_COOL,
            MODE_HEAT_COOL,
            MODE_ECO
    })
    public @interface Mode {}

    private static final int NUM_MODES = 6;

    /**
     * Use when the current or active mode of the device is not known
     */
    public static final @Mode int MODE_UNKNOWN = 0;

    /**
     * Indicates that the current or active mode of the device is off.
     */
    public static final @Mode int MODE_OFF = 1;

    /**
     * Indicates that the current or active mode of the device is set to heat.
     */
    public static final @Mode int MODE_HEAT = 2;

    /**
     * Indicates that the current or active mode of the device is set to cool.
     */
    public static final @Mode int MODE_COOL = 3;

    /**
     * Indicates that the current or active mode of the device is set to heat-cool.
     */
    public static final @Mode int MODE_HEAT_COOL = 4;

    /**
     * Indicates that the current or active mode of the device is set to eco.
     */
    public static final @Mode int MODE_ECO = 5;

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            FLAG_MODE_OFF,
            FLAG_MODE_HEAT,
            FLAG_MODE_COOL,
            FLAG_MODE_HEAT_COOL,
            FLAG_MODE_ECO
    })
    public @interface ModeFlag {}

    /**
     * Flag to indicate that the device supports off mode.
     */
    public static final int FLAG_MODE_OFF = 1 << MODE_OFF;

    /**
     * Flag to indicate that the device supports heat mode.
     */
    public static final int FLAG_MODE_HEAT = 1 << MODE_HEAT;

    /**
     * Flag to indicate that the device supports cool mode.
     */
    public static final int FLAG_MODE_COOL = 1 << MODE_COOL;

    /**
     * Flag to indicate that the device supports heat-cool mode.
     */
    public static final int FLAG_MODE_HEAT_COOL = 1 << MODE_HEAT_COOL;

    /**
     * Flag to indicate that the device supports eco mode.
     */
    public static final int FLAG_MODE_ECO = 1 << MODE_ECO;
    private static final int ALL_FLAGS =
            FLAG_MODE_OFF |
                    FLAG_MODE_HEAT |
                    FLAG_MODE_COOL |
                    FLAG_MODE_HEAT_COOL |
                    FLAG_MODE_ECO;

    private static final int[] modeToFlag = new int[]{
            0,
            FLAG_MODE_OFF,
            FLAG_MODE_HEAT,
            FLAG_MODE_COOL,
            FLAG_MODE_HEAT_COOL,
            FLAG_MODE_ECO
    };

    private final @NonNull ControlTemplate mTemplate;
    private final @Mode int mCurrentMode;
    private final @Mode int mCurrentActiveMode;
    private final @ModeFlag int mModes;

    /**
     * Construct a new {@link TemperatureControlTemplate}.
     *
     * The current and active mode have to be among the ones supported by the flags.
     *
     * @param templateId the identifier for this template object
     * @param controlTemplate a template to use for interaction with the user
     * @param currentMode the current mode for the {@link Control}
     * @param currentActiveMode the current active mode for the {@link Control}
     * @param modesFlag a flag representing the available modes for the {@link Control}
     * @throws IllegalArgumentException if the parameters passed do not make a valid template.
     */
    public TemperatureControlTemplate(@NonNull String templateId,
            @NonNull ControlTemplate controlTemplate,
            @Mode int currentMode,
            @Mode int currentActiveMode,
            @ModeFlag int modesFlag) {
        super(templateId);
        Preconditions.checkNotNull(controlTemplate);
        mTemplate = controlTemplate;

        if (currentMode < 0 || currentMode >= NUM_MODES) {
            Log.e(TAG, "Invalid current mode:" + currentMode);
            mCurrentMode = MODE_UNKNOWN;
        } else {
            mCurrentMode = currentMode;
        }

        if (currentActiveMode < 0 || currentActiveMode >= NUM_MODES) {
            Log.e(TAG, "Invalid current active mode:" + currentActiveMode);
            mCurrentActiveMode = MODE_UNKNOWN;
        } else {
            mCurrentActiveMode = currentActiveMode;
        }

        mModes = modesFlag & ALL_FLAGS;
        if (mCurrentMode != MODE_UNKNOWN && (modeToFlag[mCurrentMode] & mModes) == 0) {
            throw new IllegalArgumentException("Mode " + mCurrentMode + " not supported in flag.");
        }
        if (mCurrentActiveMode != MODE_UNKNOWN && (modeToFlag[mCurrentActiveMode] & mModes) == 0) {
            throw new IllegalArgumentException(
                    "Mode " + currentActiveMode + " not supported in flag.");
        }
    }

    /**
     * @param b
     * @hide
     */
    TemperatureControlTemplate(@NonNull Bundle b) {
        super(b);
        mTemplate = ControlTemplate.createTemplateFromBundle(b.getBundle(KEY_TEMPLATE));
        mCurrentMode = b.getInt(KEY_CURRENT_MODE);
        mCurrentActiveMode = b.getInt(KEY_CURRENT_ACTIVE_MODE);
        mModes = b.getInt(KEY_MODES);
    }

    /**
     * @return
     * @hide
     */
    @Override
    @NonNull
    Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putBundle(KEY_TEMPLATE, mTemplate.getDataBundle());
        b.putInt(KEY_CURRENT_MODE, mCurrentMode);
        b.putInt(KEY_CURRENT_ACTIVE_MODE, mCurrentActiveMode);
        b.putInt(KEY_MODES, mModes);
        return b;
    }

    @NonNull
    public ControlTemplate getTemplate() {
        return mTemplate;
    }

    public int getCurrentMode() {
        return mCurrentMode;
    }

    public int getCurrentActiveMode() {
        return mCurrentActiveMode;
    }

    public int getModes() {
        return mModes;
    }

    /**
     * @return {@link ControlTemplate#TYPE_TEMPERATURE}
     */
    @Override
    public int getTemplateType() {
        return TYPE;
    }
}
