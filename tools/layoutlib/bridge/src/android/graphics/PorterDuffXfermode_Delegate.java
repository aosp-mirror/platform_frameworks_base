/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.graphics;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import java.awt.AlphaComposite;
import java.awt.Composite;

/**
 * Delegate implementing the native methods of android.graphics.PorterDuffXfermode
 *
 * Through the layoutlib_create tool, the original native methods of PorterDuffXfermode have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original PorterDuffXfermode class.
 *
 * Because this extends {@link Xfermode_Delegate}, there's no need to use a
 * {@link DelegateManager}, as all the PathEffect classes will be added to the manager owned by
 * {@link Xfermode_Delegate}.
 *
 */
public class PorterDuffXfermode_Delegate extends Xfermode_Delegate {

    // ---- delegate data ----

    private final int mMode;

    // ---- Public Helper methods ----

    public PorterDuff.Mode getMode() {
        return getPorterDuffMode(mMode);
    }

    @Override
    public Composite getComposite(int alpha) {
        return getComposite(getPorterDuffMode(mMode), alpha);
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public String getSupportMessage() {
        // no message since isSupported returns true;
        return null;
    }

    public static PorterDuff.Mode getPorterDuffMode(int mode) {
        for (PorterDuff.Mode m : PorterDuff.Mode.values()) {
            if (m.nativeInt == mode) {
                return m;
            }
        }

        Bridge.getLog().error(LayoutLog.TAG_BROKEN,
                String.format("Unknown PorterDuff.Mode: %d", mode), null /*data*/);
        assert false;
        return PorterDuff.Mode.SRC_OVER;
    }

    public static Composite getComposite(PorterDuff.Mode mode, int alpha) {
        float falpha = alpha != 0xFF ? (float)alpha / 255.f : 1.f;
        switch (mode) {
            case CLEAR:
                return AlphaComposite.getInstance(AlphaComposite.CLEAR, falpha);
            case DARKEN:
                break;
            case DST:
                return AlphaComposite.getInstance(AlphaComposite.DST, falpha);
            case DST_ATOP:
                return AlphaComposite.getInstance(AlphaComposite.DST_ATOP, falpha);
            case DST_IN:
                return AlphaComposite.getInstance(AlphaComposite.DST_IN, falpha);
            case DST_OUT:
                return AlphaComposite.getInstance(AlphaComposite.DST_OUT, falpha);
            case DST_OVER:
                return AlphaComposite.getInstance(AlphaComposite.DST_OVER, falpha);
            case LIGHTEN:
                break;
            case MULTIPLY:
                break;
            case SCREEN:
                break;
            case SRC:
                return AlphaComposite.getInstance(AlphaComposite.SRC, falpha);
            case SRC_ATOP:
                return AlphaComposite.getInstance(AlphaComposite.SRC_ATOP, falpha);
            case SRC_IN:
                return AlphaComposite.getInstance(AlphaComposite.SRC_IN, falpha);
            case SRC_OUT:
                return AlphaComposite.getInstance(AlphaComposite.SRC_OUT, falpha);
            case SRC_OVER:
                return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, falpha);
            case XOR:
                return AlphaComposite.getInstance(AlphaComposite.XOR, falpha);
        }

        Bridge.getLog().fidelityWarning(LayoutLog.TAG_BROKEN,
                String.format("Unsupported PorterDuff Mode: %s", mode.name()),
                null, null /*data*/);

        return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, falpha);
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static int nativeCreateXfermode(int mode) {
        PorterDuffXfermode_Delegate newDelegate = new PorterDuffXfermode_Delegate(mode);
        return sManager.addNewDelegate(newDelegate);
    }

    // ---- Private delegate/helper methods ----

    private PorterDuffXfermode_Delegate(int mode) {
        mMode = mode;
    }
}
