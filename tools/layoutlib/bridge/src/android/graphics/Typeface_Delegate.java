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
import com.android.layoutlib.bridge.impl.FontLoader;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.content.res.AssetManager;

import java.awt.Font;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Delegate implementing the native methods of android.graphics.Typeface
 *
 * Through the layoutlib_create tool, the original native methods of Typeface have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original Typeface class.
 *
 * @see DelegateManager
 *
 */
public final class Typeface_Delegate {

    private static final String SYSTEM_FONTS = "/system/fonts/";

    // ---- delegate manager ----
    private static final DelegateManager<Typeface_Delegate> sManager =
            new DelegateManager<Typeface_Delegate>(Typeface_Delegate.class);

    // ---- delegate helper data ----
    private static final String DEFAULT_FAMILY = "sans-serif";

    private static FontLoader sFontLoader;
    private static final List<Typeface_Delegate> sPostInitDelegate =
            new ArrayList<Typeface_Delegate>();

    // ---- delegate data ----

    private final String mFamily;
    private int mStyle;
    private List<Font> mFonts;


    // ---- Public Helper methods ----

    public static synchronized void init(FontLoader fontLoader) {
        sFontLoader = fontLoader;

        for (Typeface_Delegate delegate : sPostInitDelegate) {
            delegate.init();
        }
        sPostInitDelegate.clear();
    }

    public static Typeface_Delegate getDelegate(int nativeTypeface) {
        return sManager.getDelegate(nativeTypeface);
    }

    public static List<Font> getFonts(Typeface typeface) {
        return getFonts(typeface.native_instance);
    }

    public static List<Font> getFonts(int native_int) {
        Typeface_Delegate delegate = sManager.getDelegate(native_int);
        if (delegate == null) {
            return null;
        }

        return delegate.getFonts();
    }

    public List<Font> getFonts() {
        return mFonts;
    }

    // ---- native methods ----

    @LayoutlibDelegate
    /*package*/ static synchronized int nativeCreate(String familyName, int style) {
        if (familyName == null) {
            familyName = DEFAULT_FAMILY;
        }

        Typeface_Delegate newDelegate = new Typeface_Delegate(familyName, style);
        if (sFontLoader != null) {
            newDelegate.init();
        } else {
            // font loader has not been initialized yet, add the delegate to a list of delegates
            // to init when the font loader is initialized.
            // There won't be any rendering before this happens anyway.
            sPostInitDelegate.add(newDelegate);
        }

        return sManager.addNewDelegate(newDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static synchronized int nativeCreateFromTypeface(int native_instance, int style) {
        Typeface_Delegate delegate = sManager.getDelegate(native_instance);
        if (delegate == null) {
            return 0;
        }

        Typeface_Delegate newDelegate = new Typeface_Delegate(delegate.mFamily, style);
        if (sFontLoader != null) {
            newDelegate.init();
        } else {
            // font loader has not been initialized yet, add the delegate to a list of delegates
            // to init when the font loader is initialized.
            // There won't be any rendering before this happens anyway.
            sPostInitDelegate.add(newDelegate);
        }

        return sManager.addNewDelegate(newDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static synchronized int nativeCreateFromAsset(AssetManager mgr, String path) {
        Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                "Typeface.createFromAsset() is not supported.", null /*throwable*/, null /*data*/);
        return 0;
    }

    @LayoutlibDelegate
    /*package*/ static synchronized int nativeCreateFromFile(String path) {
        if (path.startsWith(SYSTEM_FONTS) ) {
            String relativePath = path.substring(SYSTEM_FONTS.length());
            File f = new File(sFontLoader.getOsFontsLocation(), relativePath);

            try {
                Font font = Font.createFont(Font.TRUETYPE_FONT, f);
                if (font != null) {
                    Typeface_Delegate newDelegate = new Typeface_Delegate(font);
                    return sManager.addNewDelegate(newDelegate);
                }
            } catch (Exception e) {
                Bridge.getLog().fidelityWarning(LayoutLog.TAG_BROKEN,
                        String.format("Unable to load font %1$s", relativePath),
                            null /*throwable*/, null /*data*/);
            }
        } else {
            Bridge.getLog().fidelityWarning(LayoutLog.TAG_UNSUPPORTED,
                    "Typeface.createFromFile() can only work with platform fonts located in " +
                        SYSTEM_FONTS,
                    null /*throwable*/, null /*data*/);
        }


        // return a copy of the base font
        return nativeCreate(null, 0);
    }

    @LayoutlibDelegate
    /*package*/ static void nativeUnref(int native_instance) {
        sManager.removeJavaReferenceFor(native_instance);
    }

    @LayoutlibDelegate
    /*package*/ static int nativeGetStyle(int native_instance) {
        Typeface_Delegate delegate = sManager.getDelegate(native_instance);
        if (delegate == null) {
            return 0;
        }

        return delegate.mStyle;
    }

    @LayoutlibDelegate
    /*package*/ static void setGammaForText(float blackGamma, float whiteGamma) {
        // This is for device testing only: pass
    }

    // ---- Private delegate/helper methods ----

    private Typeface_Delegate(String family, int style) {
        mFamily = family;
        mStyle = style;
    }

    private Typeface_Delegate(Font font) {
        mFamily = font.getFamily();
        mStyle = Typeface.NORMAL;

        mFonts = sFontLoader.getFallbackFonts(mStyle);

        // insert the font glyph first.
        mFonts.add(0, font);
    }

    private void init() {
        mFonts = sFontLoader.getFont(mFamily, mStyle);
    }
}
