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

package com.android.dream.lowlight;

import static com.android.dream.lowlight.dagger.LowLightDreamModule.LOW_LIGHT_DREAM_COMPONENT;

import android.annotation.IntDef;
import android.annotation.RequiresPermission;
import android.app.DreamManager;
import android.content.ComponentName;
import android.util.Log;

import androidx.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Maintains the ambient light mode of the environment the device is in, and sets a low light dream
 * component, if present, as the system dream when the ambient light mode is low light.
 *
 * @hide
 */
public final class LowLightDreamManager {
    private static final String TAG = "LowLightDreamManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "AMBIENT_LIGHT_MODE_" }, value = {
            AMBIENT_LIGHT_MODE_UNKNOWN,
            AMBIENT_LIGHT_MODE_REGULAR,
            AMBIENT_LIGHT_MODE_LOW_LIGHT
    })
    public @interface AmbientLightMode {}

    /**
     * Constant for ambient light mode being unknown.
     * @hide
     */
    public static final int AMBIENT_LIGHT_MODE_UNKNOWN = 0;

    /**
     * Constant for ambient light mode being regular / bright.
     * @hide
     */
    public static final int AMBIENT_LIGHT_MODE_REGULAR = 1;

    /**
     * Constant for ambient light mode being low light / dim.
     * @hide
     */
    public static final int AMBIENT_LIGHT_MODE_LOW_LIGHT = 2;

    private final DreamManager mDreamManager;

    @Nullable
    private final ComponentName mLowLightDreamComponent;

    private int mAmbientLightMode = AMBIENT_LIGHT_MODE_UNKNOWN;

    @Inject
    public LowLightDreamManager(
            DreamManager dreamManager,
            @Named(LOW_LIGHT_DREAM_COMPONENT) @Nullable ComponentName lowLightDreamComponent) {
        mDreamManager = dreamManager;
        mLowLightDreamComponent = lowLightDreamComponent;
    }

    /**
     * Sets the current ambient light mode.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.WRITE_DREAM_STATE)
    public void setAmbientLightMode(@AmbientLightMode int ambientLightMode) {
        if (mLowLightDreamComponent == null) {
            if (DEBUG) {
                Log.d(TAG, "ignore ambient light mode change because low light dream component "
                        + "is empty");
            }
            return;
        }

        if (mAmbientLightMode == ambientLightMode) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "ambient light mode changed from " + mAmbientLightMode + " to "
                    + ambientLightMode);
        }

        mAmbientLightMode = ambientLightMode;

        mDreamManager.setSystemDreamComponent(mAmbientLightMode == AMBIENT_LIGHT_MODE_LOW_LIGHT
                ? mLowLightDreamComponent : null);
    }
}
