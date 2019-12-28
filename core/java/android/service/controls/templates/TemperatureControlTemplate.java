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
import android.os.Parcel;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
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
    public static final @Mode int MODE_UNKNOWN = 0;

    public static final @Mode int MODE_OFF = 1;

    public static final @Mode int MODE_HEAT = 2;

    public static final @Mode int MODE_COOL = 3;

    public static final @Mode int MODE_HEAT_COOL = 4;

    public static final @Mode int MODE_ECO = 5;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            FLAG_MODE_OFF,
            FLAG_MODE_HEAT,
            FLAG_MODE_COOL,
            FLAG_MODE_HEAT_COOL,
            FLAG_MODE_ECO
    })
    public @interface ModeFlag {}

    public static final int FLAG_MODE_OFF = 1 << MODE_OFF;
    public static final int FLAG_MODE_HEAT = 1 << MODE_HEAT;
    public static final int FLAG_MODE_COOL = 1 << MODE_COOL;
    public static final int FLAG_MODE_HEAT_COOL = 1 << MODE_HEAT_COOL;
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

    TemperatureControlTemplate(@NonNull Bundle b) {
        super(b);
        mTemplate = b.getParcelable(KEY_TEMPLATE);
        mCurrentMode = b.getInt(KEY_CURRENT_MODE);
        mCurrentActiveMode = b.getInt(KEY_CURRENT_ACTIVE_MODE);
        mModes = b.getInt(KEY_MODES);
    }

    @Override
    protected Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putParcelable(KEY_TEMPLATE, mTemplate);
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

    @Override
    public int getTemplateType() {
        return TYPE;
    }

    public static final Creator<TemperatureControlTemplate> CREATOR = new Creator<TemperatureControlTemplate>() {
        @Override
        public TemperatureControlTemplate createFromParcel(Parcel source) {
            int type = source.readInt();
            verifyType(type, TYPE);
            return new TemperatureControlTemplate(source.readBundle());
        }

        @Override
        public TemperatureControlTemplate[] newArray(int size) {
            return new TemperatureControlTemplate[size];
        }
    };
}
