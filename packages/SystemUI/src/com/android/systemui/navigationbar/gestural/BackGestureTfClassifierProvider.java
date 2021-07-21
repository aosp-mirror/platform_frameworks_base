/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.systemui.navigationbar.gestural;

import android.content.res.AssetManager;

import java.util.HashMap;
import java.util.Map;

/**
 * This class can be overridden by a vendor-specific sys UI implementation,
 * in order to provide classification models for the Back Gesture.
 */
public class BackGestureTfClassifierProvider {
    private static final String TAG = "BackGestureTfClassifierProvider";

    /**
     * Default implementation that returns an empty map.
     * This method is overridden in vendor-specific Sys UI implementation.
     *
     * @param am       An AssetManager to get the vocab file.
    */
    public Map<String, Integer> loadVocab(AssetManager am) {
        return new HashMap<String, Integer>();
    }

    /**
     * This method is overridden in vendor-specific Sys UI implementation.
     *
     * @param featuresVector   List of input features.
     *
    */
    public float predict(Object[] featuresVector) {
        return -1;
    }

    /**
     * Interpreter owns resources. This method releases the resources after
     * use to avoid memory leak.
     * This method is overridden in vendor-specific Sys UI implementation.
     *
     */
    public void release() {}

    /**
     * Returns whether to use the ML model for Back Gesture.
     * This method is overridden in vendor-specific Sys UI implementation.
     *
     */
    public boolean isActive() {
        return false;
    }
}
