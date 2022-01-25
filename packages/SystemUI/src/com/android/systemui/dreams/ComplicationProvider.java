/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams;

import android.annotation.IntDef;
import android.content.Context;

import com.android.settingslib.dream.DreamBackend;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * {@link ComplicationProvider} is an interface for defining entities that can supply complications
 * to show over a dream. Presentation components such as the {@link DreamOverlayService} supply
 * implementations with the necessary context for constructing such overlays.
 */
public interface ComplicationProvider {
    /**
     * The type of dream complications which can be provided by a {@link ComplicationProvider}.
     */
    @IntDef(prefix = {"COMPLICATION_TYPE_"}, flag = true, value = {
            COMPLICATION_TYPE_NONE,
            COMPLICATION_TYPE_TIME,
            COMPLICATION_TYPE_DATE,
            COMPLICATION_TYPE_WEATHER,
            COMPLICATION_TYPE_AIR_QUALITY,
            COMPLICATION_TYPE_CAST_INFO
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ComplicationType {}

    int COMPLICATION_TYPE_NONE = 0;
    int COMPLICATION_TYPE_TIME = 1;
    int COMPLICATION_TYPE_DATE = 1 << 1;
    int COMPLICATION_TYPE_WEATHER = 1 << 2;
    int COMPLICATION_TYPE_AIR_QUALITY = 1 << 3;
    int COMPLICATION_TYPE_CAST_INFO = 1 << 4;

    /**
     * Called when the {@link ComplicationHost} requests the associated complication be produced.
     *
     * @param context The {@link Context} used to construct the view.
     * @param creationCallback The callback to inform the complication has been created.
     * @param interactionCallback The callback to inform the complication has been interacted with.
     */
    void onCreateComplication(Context context, ComplicationHost.CreationCallback creationCallback,
            ComplicationHost.InteractionCallback interactionCallback);

    /**
     * Converts a {@link com.android.settingslib.dream.DreamBackend.ComplicationType} to
     * {@link ComplicationType}.
     */
    @ComplicationType
    default int convertComplicationType(@DreamBackend.ComplicationType int type) {
        switch (type) {
            case DreamBackend.COMPLICATION_TYPE_TIME:
                return COMPLICATION_TYPE_TIME;
            case DreamBackend.COMPLICATION_TYPE_DATE:
                return COMPLICATION_TYPE_DATE;
            case DreamBackend.COMPLICATION_TYPE_WEATHER:
                return COMPLICATION_TYPE_WEATHER;
            case DreamBackend.COMPLICATION_TYPE_AIR_QUALITY:
                return COMPLICATION_TYPE_AIR_QUALITY;
            case DreamBackend.COMPLICATION_TYPE_CAST_INFO:
                return COMPLICATION_TYPE_CAST_INFO;
            default:
                return COMPLICATION_TYPE_NONE;
        }
    }
}
