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

package com.android.systemui.classifier;

import android.content.res.Resources;
import android.view.ViewConfiguration;

import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.phone.NotificationTapHelper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Named;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ElementsIntoSet;

/** Dagger Module for Falsing. */
@Module
public interface FalsingModule {
    String BRIGHT_LINE_GESTURE_CLASSIFERS = "bright_line_gesture_classifiers";
    String SINGLE_TAP_TOUCH_SLOP = "falsing_single_tap_touch_slop";
    String LONG_TAP_TOUCH_SLOP = "falsing_long_tap_slop";
    String DOUBLE_TAP_TOUCH_SLOP = "falsing_double_tap_touch_slop";
    String DOUBLE_TAP_TIMEOUT_MS = "falsing_double_tap_timeout_ms";

    /** */
    @Binds
    @SysUISingleton
    FalsingCollector bindsFalsingCollector(FalsingCollectorImpl impl);

    /** */
    @Provides
    @ElementsIntoSet
    @Named(BRIGHT_LINE_GESTURE_CLASSIFERS)
    static Set<FalsingClassifier> providesBrightLineGestureClassifiers(
            DistanceClassifier distanceClassifier, ProximityClassifier proximityClassifier,
            PointerCountClassifier pointerCountClassifier, TypeClassifier typeClassifier,
            DiagonalClassifier diagonalClassifier, ZigZagClassifier zigZagClassifier) {
        return new HashSet<>(Arrays.asList(
                pointerCountClassifier, typeClassifier, diagonalClassifier, distanceClassifier,
                proximityClassifier, zigZagClassifier));
    }

    /** */
    @Provides
    @Named(DOUBLE_TAP_TIMEOUT_MS)
    static long providesDoubleTapTimeoutMs() {
        return NotificationTapHelper.DOUBLE_TAP_TIMEOUT_MS;
    }

    /** */
    @Provides
    @Named(DOUBLE_TAP_TOUCH_SLOP)
    static float providesDoubleTapTouchSlop(@Main Resources resources) {
        return resources.getDimension(R.dimen.double_tap_slop);
    }

    /** */
    @Provides
    @Named(SINGLE_TAP_TOUCH_SLOP)
    static float providesSingleTapTouchSlop(ViewConfiguration viewConfiguration) {
        return viewConfiguration.getScaledTouchSlop();
    }

    /** */
    @Provides
    @Named(LONG_TAP_TOUCH_SLOP)
    static float providesLongTapTouchSlop(ViewConfiguration viewConfiguration) {
        return viewConfiguration.getScaledTouchSlop() * 1.25f;
    }
}
