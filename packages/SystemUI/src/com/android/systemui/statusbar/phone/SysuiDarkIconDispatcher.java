/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.graphics.Rect;

import com.android.systemui.Dumpable;
import com.android.systemui.plugins.DarkIconDispatcher;

import java.util.ArrayList;
import java.util.Collection;

import kotlinx.coroutines.flow.StateFlow;

/**
 * Dispatches events to {@link DarkReceiver}s about changes in darkness, tint area
 * and dark intensity.
 */
public interface SysuiDarkIconDispatcher extends DarkIconDispatcher, Dumpable {

    /**
     * @return LightBarTransitionsController
     */
    LightBarTransitionsController getTransitionsController();

    /**
     * Flow equivalent of registering {@link DarkReceiver} using
     * {@link DarkIconDispatcher#addDarkReceiver(DarkReceiver)}
     */
    StateFlow<DarkChange> darkChangeFlow();

    /** Model for {@link #darkChangeFlow()} */
    class DarkChange {

        public static final DarkChange EMPTY =
                new DarkChange(new ArrayList<>(), /* darkIntensity= */ 0f, DEFAULT_ICON_TINT);

        public DarkChange(Collection<Rect> areas, float darkIntensity, int tint) {
            this.areas = areas;
            this.darkIntensity = darkIntensity;
            this.tint = tint;
        }

        public final Collection<Rect> areas;
        public final float darkIntensity;
        public final int tint;
    }
}
