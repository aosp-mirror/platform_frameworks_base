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

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.graphics.PorterDuff.Mode;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static com.android.layoutlib.bridge.impl.PorterDuffUtility.getAlphaCompositeRule;
import static com.android.layoutlib.bridge.impl.PorterDuffUtility.getPorterDuffMode;

/**
 * Delegate implementing the native methods of android.graphics.PorterDuffColorFilter
 *
 * Through the layoutlib_create tool, the original native methods of PorterDuffColorFilter have
 * been replaced by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original PorterDuffColorFilter class.
 *
 * Because this extends {@link ColorFilter_Delegate}, there's no need to use a
 * {@link DelegateManager}, as all the Shader classes will be added to the manager
 * owned by {@link ColorFilter_Delegate}.
 *
 * @see ColorFilter_Delegate
 *
 */
public class PorterDuffColorFilter_Delegate extends ColorFilter_Delegate {

    // ---- delegate data ----

    private final int mSrcColor;
    private final Mode mMode;
    private int mWidth;
    private int mHeight;
    private BufferedImage mImage;


    // ---- Public Helper methods ----

    @Override
    public boolean isSupported() {
        switch (mMode) {
        case CLEAR:
        case SRC:
        case SRC_IN:
        case DST_IN:
        case SRC_ATOP:
            return true;
        }

        return false;
    }

    @Override
    public String getSupportMessage() {
        return "PorterDuff Color Filter is not supported for mode: " + mMode.name() + ".";
    }

    @Override
    public void applyFilter(Graphics2D g, int width, int height) {
        createFilterImage(width, height);
        g.setComposite(getComposite());
        g.drawImage(mImage, 0, 0, null);
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static long native_CreatePorterDuffFilter(int srcColor, int porterDuffMode) {
        PorterDuffColorFilter_Delegate newDelegate =
                new PorterDuffColorFilter_Delegate(srcColor, porterDuffMode);
        return sManager.addNewDelegate(newDelegate);
    }


    // ---- Private delegate/helper methods ----

    private PorterDuffColorFilter_Delegate(int srcColor, int mode) {
        mSrcColor = srcColor;
        mMode = getPorterDuffMode(mode);
    }

    private void createFilterImage(int width, int height) {
        if (mWidth == width && mHeight == height && mImage != null) {
            return;
        }
        mWidth = width;
        mHeight = height;
        mImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = mImage.createGraphics();
        try {
            graphics.setColor(new java.awt.Color(mSrcColor, true /* hasAlpha */));
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
    }

    private AlphaComposite getComposite() {
        return AlphaComposite.getInstance(getAlphaCompositeRule(mMode),
                getAlpha() / 255f);
    }

    private int getAlpha() {
        return mSrcColor >>> 24;
    }
}
