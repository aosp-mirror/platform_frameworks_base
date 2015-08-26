/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.layoutlib.bridge.bars;

import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;
import com.android.layoutlib.bridge.impl.ParserFactory;
import com.android.layoutlib.bridge.impl.ResourceHelper;
import com.android.resources.Density;
import com.android.resources.LayoutDirection;
import com.android.resources.ResourceType;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Bitmap_Delegate;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;

import static android.os.Build.VERSION_CODES.LOLLIPOP;

/**
 * Base "bar" class for the window decor around the the edited layout.
 * This is basically an horizontal layout that loads a given layout on creation (it is read
 * through {@link Class#getResourceAsStream(String)}).
 *
 * The given layout should be a merge layout so that all the children belong to this class directly.
 *
 * It also provides a few utility methods to configure the content of the layout.
 */
abstract class CustomBar extends LinearLayout {


    private final int mSimulatedPlatformVersion;

    protected abstract TextView getStyleableTextView();

    protected CustomBar(BridgeContext context, int orientation, String layoutPath,
            String name, int simulatedPlatformVersion) {
        super(context);
        mSimulatedPlatformVersion = simulatedPlatformVersion;
        setOrientation(orientation);
        if (orientation == LinearLayout.HORIZONTAL) {
            setGravity(Gravity.CENTER_VERTICAL);
        } else {
            setGravity(Gravity.CENTER_HORIZONTAL);
        }

        LayoutInflater inflater = LayoutInflater.from(mContext);

        XmlPullParser parser;
        try {
            parser = ParserFactory.create(getClass().getResourceAsStream(layoutPath), name);
        } catch (XmlPullParserException e) {
            // Should not happen as the resource is bundled with the jar, and  ParserFactory should
            // have been initialized.
            throw new AssertionError(e);
        }

        BridgeXmlBlockParser bridgeParser = new BridgeXmlBlockParser(parser, context, false);

        try {
            inflater.inflate(bridgeParser, this, true);
        } finally {
            bridgeParser.ensurePopped();
        }
    }

    protected void loadIcon(int index, String iconName, Density density) {
        loadIcon(index, iconName, density, false);
    }

    protected void loadIcon(int index, String iconName, Density density, boolean isRtl) {
        View child = getChildAt(index);
        if (child instanceof ImageView) {
            ImageView imageView = (ImageView) child;

            LayoutDirection dir = isRtl ? LayoutDirection.RTL : null;
            IconLoader iconLoader = new IconLoader(iconName, density, mSimulatedPlatformVersion,
                    dir);
            InputStream stream = iconLoader.getIcon();

            if (stream != null) {
                density = iconLoader.getDensity();
                String path = iconLoader.getPath();
                // look for a cached bitmap
                Bitmap bitmap = Bridge.getCachedBitmap(path, true /*isFramework*/);
                if (bitmap == null) {
                    try {
                        bitmap = Bitmap_Delegate.createBitmap(stream, false /*isMutable*/, density);
                        Bridge.setCachedBitmap(path, bitmap, true /*isFramework*/);
                    } catch (IOException e) {
                        return;
                    }
                }

                if (bitmap != null) {
                    BitmapDrawable drawable = new BitmapDrawable(getContext().getResources(),
                            bitmap);
                    imageView.setImageDrawable(drawable);
                }
            }
        }
    }

    protected TextView setText(int index, String string, boolean reference) {
        View child = getChildAt(index);
        if (child instanceof TextView) {
            TextView textView = (TextView) child;
            setText(textView, string, reference);
            return textView;
        }

        return null;
    }

    private void setText(TextView textView, String string, boolean reference) {
        if (reference) {
            ResourceValue value = getResourceValue(string);
            if (value != null) {
                string = value.getValue();
            }
        }
        textView.setText(string);
    }

    protected void setStyle(String themeEntryName) {

        BridgeContext bridgeContext = getContext();
        RenderResources res = bridgeContext.getRenderResources();

        ResourceValue value = res.findItemInTheme(themeEntryName, true /*isFrameworkAttr*/);
        value = res.resolveResValue(value);

        if (!(value instanceof StyleResourceValue)) {
            return;
        }

        StyleResourceValue style = (StyleResourceValue) value;

        // get the background
        ResourceValue backgroundValue = res.findItemInStyle(style, "background",
                true /*isFrameworkAttr*/);
        backgroundValue = res.resolveResValue(backgroundValue);
        if (backgroundValue != null) {
            Drawable d = ResourceHelper.getDrawable(backgroundValue, bridgeContext);
            if (d != null) {
                setBackground(d);
            }
        }

        TextView textView = getStyleableTextView();
        if (textView != null) {
            // get the text style
            ResourceValue textStyleValue = res.findItemInStyle(style, "titleTextStyle",
                    true /*isFrameworkAttr*/);
            textStyleValue = res.resolveResValue(textStyleValue);
            if (textStyleValue instanceof StyleResourceValue) {
                StyleResourceValue textStyle = (StyleResourceValue) textStyleValue;

                ResourceValue textSize = res.findItemInStyle(textStyle, "textSize",
                        true /*isFrameworkAttr*/);
                textSize = res.resolveResValue(textSize);

                if (textSize != null) {
                    TypedValue out = new TypedValue();
                    if (ResourceHelper.parseFloatAttribute("textSize", textSize.getValue(), out,
                            true /*requireUnit*/)) {
                        textView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                                out.getDimension(bridgeContext.getResources().getDisplayMetrics()));
                    }
                }


                ResourceValue textColor = res.findItemInStyle(textStyle, "textColor",
                        true);
                textColor = res.resolveResValue(textColor);
                if (textColor != null) {
                    ColorStateList stateList = ResourceHelper.getColorStateList(
                            textColor, bridgeContext);
                    if (stateList != null) {
                        textView.setTextColor(stateList);
                    }
                }
            }
        }
    }

    @Override
    public BridgeContext getContext() {
        return (BridgeContext) mContext;
    }

    /**
     * Find the background color for this bar from the theme attributes. Only relevant to StatusBar
     * and NavigationBar.
     * <p/>
     * Returns 0 if not found.
     *
     * @param colorAttrName the attribute name for the background color
     * @param translucentAttrName the attribute name for the translucency property of the bar.
     *
     * @throws NumberFormatException if color resolved to an invalid string.
     */
    protected int getBarColor(@NonNull String colorAttrName, @NonNull String translucentAttrName) {
        if (!Config.isGreaterOrEqual(mSimulatedPlatformVersion, LOLLIPOP)) {
            return 0;
        }
        RenderResources renderResources = getContext().getRenderResources();
        // First check if the bar is translucent.
        boolean translucent = ResourceHelper.getBooleanThemeValue(renderResources,
                translucentAttrName, true, false);
        if (translucent) {
            // Keep in sync with R.color.system_bar_background_semi_transparent from system ui.
            return 0x66000000;  // 40% black.
        }
        boolean transparent = ResourceHelper.getBooleanThemeValue(renderResources,
                "windowDrawsSystemBarBackgrounds", true, false);
        if (transparent) {
            return getColor(renderResources, colorAttrName);
        }
        return 0;
    }

    private static int getColor(RenderResources renderResources, String attr) {
        // From ?attr/foo to @color/bar. This is most likely an ItemResourceValue.
        ResourceValue resource = renderResources.findItemInTheme(attr, true);
        // Form @color/bar to the #AARRGGBB
        resource = renderResources.resolveResValue(resource);
        if (resource != null) {
            ResourceType type = resource.getResourceType();
            if (type == null || type == ResourceType.COLOR) {
                // if no type is specified, the value may have been specified directly in the style
                // file, rather than referencing a color resource value.
                try {
                    return ResourceHelper.getColor(resource.getValue());
                } catch (NumberFormatException e) {
                    // Conversion failed.
                    Bridge.getLog().warning(LayoutLog.TAG_RESOURCES_FORMAT,
                            "Theme attribute @android:" + attr +
                                    " does not reference a color, instead is '" +
                                    resource.getValue() + "'.", resource);
                }
            }
        }
        return 0;
    }

    private ResourceValue getResourceValue(String reference) {
        RenderResources res = getContext().getRenderResources();

        // find the resource
        ResourceValue value = res.findResValue(reference, false);

        // resolve it if needed
        return res.resolveResValue(value);
    }
}
