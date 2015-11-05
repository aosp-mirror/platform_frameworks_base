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

import android.graphics.BlendComposite;
import android.graphics.BlendComposite.BlendingMode;
import android.graphics.PorterDuff;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffColorFilter_Delegate;
import android.graphics.PorterDuffXfermode_Delegate;

import java.awt.AlphaComposite;
import java.awt.Composite;

/**
 * Provides various utility methods for {@link PorterDuffColorFilter_Delegate} and {@link
 * PorterDuffXfermode_Delegate}.
 */
public final class PorterDuffUtility {

    private static final int MODES_COUNT = Mode.values().length;

    // Make the class non-instantiable.
    private PorterDuffUtility() {
    }

    /**
     * Convert the porterDuffMode from the framework to its corresponding enum. This defaults to
     * {@link Mode#SRC_OVER} for invalid modes.
     */
    public static Mode getPorterDuffMode(int porterDuffMode) {
        if (porterDuffMode >= 0 && porterDuffMode < MODES_COUNT) {
            return PorterDuff.intToMode(porterDuffMode);
        }
        Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                String.format("Unknown PorterDuff.Mode: %1$d", porterDuffMode), null);
        assert false;
        return Mode.SRC_OVER;
    }

    /**
     * A utility method to get the {@link Composite} that represents the filter for the given
     * PorterDuff mode and the alpha. Defaults to {@link Mode#SRC_OVER} for invalid modes.
     */
    public static Composite getComposite(Mode mode, int alpha255) {
        float alpha1 = alpha255 != 0xFF ? alpha255 / 255.f : 1.f;
        switch (mode) {
            case CLEAR:
                return AlphaComposite.getInstance(AlphaComposite.CLEAR, alpha1);
            case SRC:
                return AlphaComposite.getInstance(AlphaComposite.SRC, alpha1);
            case DST:
                return AlphaComposite.getInstance(AlphaComposite.DST, alpha1);
            case SRC_OVER:
                return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha1);
            case DST_OVER:
                return AlphaComposite.getInstance(AlphaComposite.DST_OVER, alpha1);
            case SRC_IN:
                return AlphaComposite.getInstance(AlphaComposite.SRC_IN, alpha1);
            case DST_IN:
                return AlphaComposite.getInstance(AlphaComposite.DST_IN, alpha1);
            case SRC_OUT:
                return AlphaComposite.getInstance(AlphaComposite.SRC_OUT, alpha1);
            case DST_OUT:
                return AlphaComposite.getInstance(AlphaComposite.DST_OUT, alpha1);
            case SRC_ATOP:
                return AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, alpha1);
            case DST_ATOP:
                return AlphaComposite.getInstance(AlphaComposite.DST_ATOP, alpha1);
            case XOR:
                return AlphaComposite.getInstance(AlphaComposite.XOR, alpha1);
            case DARKEN:
                return BlendComposite.getInstance(BlendingMode.DARKEN, alpha1);
            case LIGHTEN:
                return BlendComposite.getInstance(BlendingMode.LIGHTEN, alpha1);
            case MULTIPLY:
                return BlendComposite.getInstance(BlendingMode.MULTIPLY, alpha1);
            case SCREEN:
                return BlendComposite.getInstance(BlendingMode.SCREEN, alpha1);
            case ADD:
                return BlendComposite.getInstance(BlendingMode.ADD, alpha1);
            case OVERLAY:
                return BlendComposite.getInstance(BlendingMode.OVERLAY, alpha1);
            default:
                Bridge.getLog().fidelityWarning(LayoutLog.TAG_BROKEN,
                        String.format("Unsupported PorterDuff Mode: %1$s", mode.name()),
                        null, null /*data*/);

                return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha1);
        }
    }
}
