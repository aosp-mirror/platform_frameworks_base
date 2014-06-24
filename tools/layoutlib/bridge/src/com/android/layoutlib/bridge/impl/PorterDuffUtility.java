/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;

import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffColorFilter_Delegate;
import android.graphics.PorterDuffXfermode_Delegate;

import java.awt.AlphaComposite;

/**
 * Provides various utility methods for {@link PorterDuffColorFilter_Delegate} and {@link
 * PorterDuffXfermode_Delegate}.
 */
public final class PorterDuffUtility {

    // Make the class non-instantiable.
    private PorterDuffUtility() {
    }

    /**
     * Convert the porterDuffMode from the framework to its corresponding enum. This defaults to
     * {@link Mode#SRC_OVER} for invalid modes.
     */
    public static Mode getPorterDuffMode(int porterDuffMode) {
        Mode[] values = Mode.values();
        if (porterDuffMode >= 0 && porterDuffMode < values.length) {
            return values[porterDuffMode];
        }
        Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                String.format("Unknown PorterDuff.Mode: %1$d", porterDuffMode), null /*data*/);
        assert false;
        return Mode.SRC_OVER;
    }

    /**
     * A utility method to convert the porterDuffMode to an int to be used as a rule for {@link
     * AlphaComposite}. If {@code AlphaComposite} doesn't support the mode, -1 is returned.
     */
    public static int getAlphaCompositeRule(Mode porterDuffMode) {
        switch (porterDuffMode) {
            case CLEAR:
                return AlphaComposite.CLEAR;
            case DARKEN:
                break;
            case DST:
                return AlphaComposite.DST;
            case DST_ATOP:
                return AlphaComposite.DST_ATOP;
            case DST_IN:
                return AlphaComposite.DST_IN;
            case DST_OUT:
                return AlphaComposite.DST_OUT;
            case DST_OVER:
                return AlphaComposite.DST_OVER;
            case LIGHTEN:
                break;
            case MULTIPLY:
                break;
            case SCREEN:
                break;
            case SRC:
                return AlphaComposite.SRC;
            case SRC_ATOP:
                return AlphaComposite.SRC_ATOP;
            case SRC_IN:
                return AlphaComposite.SRC_IN;
            case SRC_OUT:
                return AlphaComposite.SRC_OUT;
            case SRC_OVER:
                return AlphaComposite.SRC_OVER;
            case XOR:
                return AlphaComposite.XOR;
        }
        // This is an unsupported mode.
        return -1;

    }
}
