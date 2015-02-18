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

package android.view;

import com.android.internal.util.XmlUtils;

import android.annotation.XmlRes;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * Represents an icon that can be used as a mouse pointer.
 * <p>
 * Pointer icons can be provided either by the system using system styles,
 * or by applications using bitmaps or application resources.
 * </p>
 *
 * @hide
 */
public final class PointerIcon implements Parcelable {
    private static final String TAG = "PointerIcon";

    /** Style constant: Custom icon with a user-supplied bitmap. */
    public static final int STYLE_CUSTOM = -1;

    /** Style constant: Null icon.  It has no bitmap. */
    public static final int STYLE_NULL = 0;

    /** Style constant: Arrow icon.  (Default mouse pointer) */
    public static final int STYLE_ARROW = 1000;

    /** {@hide} Style constant: Spot hover icon for touchpads. */
    public static final int STYLE_SPOT_HOVER = 2000;

    /** {@hide} Style constant: Spot touch icon for touchpads. */
    public static final int STYLE_SPOT_TOUCH = 2001;

    /** {@hide} Style constant: Spot anchor icon for touchpads. */
    public static final int STYLE_SPOT_ANCHOR = 2002;

    // OEM private styles should be defined starting at this range to avoid
    // conflicts with any system styles that may be defined in the future.
    private static final int STYLE_OEM_FIRST = 10000;

    // The default pointer icon.
    private static final int STYLE_DEFAULT = STYLE_ARROW;

    private static final PointerIcon gNullIcon = new PointerIcon(STYLE_NULL);

    private final int mStyle;
    private int mSystemIconResourceId;
    private Bitmap mBitmap;
    private float mHotSpotX;
    private float mHotSpotY;

    private PointerIcon(int style) {
        mStyle = style;
    }

    /**
     * Gets a special pointer icon that has no bitmap.
     *
     * @return The null pointer icon.
     *
     * @see #STYLE_NULL
     */
    public static PointerIcon getNullIcon() {
        return gNullIcon;
    }

    /**
     * Gets the default pointer icon.
     *
     * @param context The context.
     * @return The default pointer icon.
     *
     * @throws IllegalArgumentException if context is null.
     */
    public static PointerIcon getDefaultIcon(Context context) {
        return getSystemIcon(context, STYLE_DEFAULT);
    }

    /**
     * Gets a system pointer icon for the given style.
     * If style is not recognized, returns the default pointer icon.
     *
     * @param context The context.
     * @param style The pointer icon style.
     * @return The pointer icon.
     *
     * @throws IllegalArgumentException if context is null.
     */
    public static PointerIcon getSystemIcon(Context context, int style) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }

        if (style == STYLE_NULL) {
            return gNullIcon;
        }

        int styleIndex = getSystemIconStyleIndex(style);
        if (styleIndex == 0) {
            styleIndex = getSystemIconStyleIndex(STYLE_DEFAULT);
        }

        TypedArray a = context.obtainStyledAttributes(null,
                com.android.internal.R.styleable.Pointer,
                com.android.internal.R.attr.pointerStyle, 0);
        int resourceId = a.getResourceId(styleIndex, -1);
        a.recycle();

        if (resourceId == -1) {
            Log.w(TAG, "Missing theme resources for pointer icon style " + style);
            return style == STYLE_DEFAULT ? gNullIcon : getSystemIcon(context, STYLE_DEFAULT);
        }

        PointerIcon icon = new PointerIcon(style);
        if ((resourceId & 0xff000000) == 0x01000000) {
            icon.mSystemIconResourceId = resourceId;
        } else {
            icon.loadResource(context, context.getResources(), resourceId);
        }
        return icon;
    }

    /**
     * Creates a custom pointer from the given bitmap and hotspot information.
     *
     * @param bitmap The bitmap for the icon.
     * @param hotSpotX The X offset of the pointer icon hotspot in the bitmap.
     *        Must be within the [0, bitmap.getWidth()) range.
     * @param hotSpotY The Y offset of the pointer icon hotspot in the bitmap.
     *        Must be within the [0, bitmap.getHeight()) range.
     * @return A pointer icon for this bitmap.
     *
     * @throws IllegalArgumentException if bitmap is null, or if the x/y hotspot
     *         parameters are invalid.
     */
    public static PointerIcon createCustomIcon(Bitmap bitmap, float hotSpotX, float hotSpotY) {
        if (bitmap == null) {
            throw new IllegalArgumentException("bitmap must not be null");
        }
        validateHotSpot(bitmap, hotSpotX, hotSpotY);

        PointerIcon icon = new PointerIcon(STYLE_CUSTOM);
        icon.mBitmap = bitmap;
        icon.mHotSpotX = hotSpotX;
        icon.mHotSpotY = hotSpotY;
        return icon;
    }

    /**
     * Loads a custom pointer icon from an XML resource.
     * <p>
     * The XML resource should have the following form:
     * <code>
     * &lt;?xml version="1.0" encoding="utf-8"?&gt;
     * &lt;pointer-icon xmlns:android="http://schemas.android.com/apk/res/android"
     *   android:bitmap="@drawable/my_pointer_bitmap"
     *   android:hotSpotX="24"
     *   android:hotSpotY="24" /&gt;
     * </code>
     * </p>
     *
     * @param resources The resources object.
     * @param resourceId The resource id.
     * @return The pointer icon.
     *
     * @throws IllegalArgumentException if resources is null.
     * @throws Resources.NotFoundException if the resource was not found or the drawable
     * linked in the resource was not found.
     */
    public static PointerIcon loadCustomIcon(Resources resources, @XmlRes int resourceId) {
        if (resources == null) {
            throw new IllegalArgumentException("resources must not be null");
        }

        PointerIcon icon = new PointerIcon(STYLE_CUSTOM);
        icon.loadResource(null, resources, resourceId);
        return icon;
    }

    /**
     * Loads the bitmap and hotspot information for a pointer icon, if it is not already loaded.
     * Returns a pointer icon (not necessarily the same instance) with the information filled in.
     *
     * @param context The context.
     * @return The loaded pointer icon.
     *
     * @throws IllegalArgumentException if context is null.
     * @see #isLoaded()
     * @hide
     */
    public PointerIcon load(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }

        if (mSystemIconResourceId == 0 || mBitmap != null) {
            return this;
        }

        PointerIcon result = new PointerIcon(mStyle);
        result.mSystemIconResourceId = mSystemIconResourceId;
        result.loadResource(context, context.getResources(), mSystemIconResourceId);
        return result;
    }

    /**
     * Returns true if the pointer icon style is {@link #STYLE_NULL}.
     *
     * @return True if the pointer icon style is {@link #STYLE_NULL}.
     */
    public boolean isNullIcon() {
        return mStyle == STYLE_NULL;
    }

    /**
     * Returns true if the pointer icon has been loaded and its bitmap and hotspot
     * information are available.
     *
     * @return True if the pointer icon is loaded.
     * @see #load(Context)
     */
    public boolean isLoaded() {
        return mBitmap != null || mStyle == STYLE_NULL;
    }

    /**
     * Gets the style of the pointer icon.
     *
     * @return The pointer icon style.
     */
    public int getStyle() {
        return mStyle;
    }

    /**
     * Gets the bitmap of the pointer icon.
     *
     * @return The pointer icon bitmap, or null if the style is {@link #STYLE_NULL}.
     *
     * @throws IllegalStateException if the bitmap is not loaded.
     * @see #isLoaded()
     * @see #load(Context)
     */
    public Bitmap getBitmap() {
        throwIfIconIsNotLoaded();
        return mBitmap;
    }

    /**
     * Gets the X offset of the pointer icon hotspot.
     *
     * @return The hotspot X offset.
     *
     * @throws IllegalStateException if the bitmap is not loaded.
     * @see #isLoaded()
     * @see #load(Context)
     */
    public float getHotSpotX() {
        throwIfIconIsNotLoaded();
        return mHotSpotX;
    }

    /**
     * Gets the Y offset of the pointer icon hotspot.
     *
     * @return The hotspot Y offset.
     *
     * @throws IllegalStateException if the bitmap is not loaded.
     * @see #isLoaded()
     * @see #load(Context)
     */
    public float getHotSpotY() {
        throwIfIconIsNotLoaded();
        return mHotSpotY;
    }

    private void throwIfIconIsNotLoaded() {
        if (!isLoaded()) {
            throw new IllegalStateException("The icon is not loaded.");
        }
    }

    public static final Parcelable.Creator<PointerIcon> CREATOR
            = new Parcelable.Creator<PointerIcon>() {
        public PointerIcon createFromParcel(Parcel in) {
            int style = in.readInt();
            if (style == STYLE_NULL) {
                return getNullIcon();
            }

            int systemIconResourceId = in.readInt();
            if (systemIconResourceId != 0) {
                PointerIcon icon = new PointerIcon(style);
                icon.mSystemIconResourceId = systemIconResourceId;
                return icon;
            }

            Bitmap bitmap = Bitmap.CREATOR.createFromParcel(in);
            float hotSpotX = in.readFloat();
            float hotSpotY = in.readFloat();
            return PointerIcon.createCustomIcon(bitmap, hotSpotX, hotSpotY);
        }

        public PointerIcon[] newArray(int size) {
            return new PointerIcon[size];
        }
    };

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mStyle);

        if (mStyle != STYLE_NULL) {
            out.writeInt(mSystemIconResourceId);
            if (mSystemIconResourceId == 0) {
                mBitmap.writeToParcel(out, flags);
                out.writeFloat(mHotSpotX);
                out.writeFloat(mHotSpotY);
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || !(other instanceof PointerIcon)) {
            return false;
        }

        PointerIcon otherIcon = (PointerIcon) other;
        if (mStyle != otherIcon.mStyle
                || mSystemIconResourceId != otherIcon.mSystemIconResourceId) {
            return false;
        }

        if (mSystemIconResourceId == 0 && (mBitmap != otherIcon.mBitmap
                || mHotSpotX != otherIcon.mHotSpotX
                || mHotSpotY != otherIcon.mHotSpotY)) {
            return false;
        }

        return true;
    }

    private void loadResource(Context context, Resources resources, @XmlRes int resourceId) {
        final XmlResourceParser parser = resources.getXml(resourceId);
        final int bitmapRes;
        final float hotSpotX;
        final float hotSpotY;
        try {
            XmlUtils.beginDocument(parser, "pointer-icon");

            final TypedArray a = resources.obtainAttributes(
                    parser, com.android.internal.R.styleable.PointerIcon);
            bitmapRes = a.getResourceId(com.android.internal.R.styleable.PointerIcon_bitmap, 0);
            hotSpotX = a.getDimension(com.android.internal.R.styleable.PointerIcon_hotSpotX, 0);
            hotSpotY = a.getDimension(com.android.internal.R.styleable.PointerIcon_hotSpotY, 0);
            a.recycle();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Exception parsing pointer icon resource.", ex);
        } finally {
            parser.close();
        }

        if (bitmapRes == 0) {
            throw new IllegalArgumentException("<pointer-icon> is missing bitmap attribute.");
        }

        Drawable drawable;
        if (context == null) {
            drawable = resources.getDrawable(bitmapRes);
        } else {
            drawable = context.getDrawable(bitmapRes);
        }
        if (!(drawable instanceof BitmapDrawable)) {
            throw new IllegalArgumentException("<pointer-icon> bitmap attribute must "
                    + "refer to a bitmap drawable.");
        }

        // Set the properties now that we have successfully loaded the icon.
        mBitmap = ((BitmapDrawable)drawable).getBitmap();
        mHotSpotX = hotSpotX;
        mHotSpotY = hotSpotY;
    }

    private static void validateHotSpot(Bitmap bitmap, float hotSpotX, float hotSpotY) {
        if (hotSpotX < 0 || hotSpotX >= bitmap.getWidth()) {
            throw new IllegalArgumentException("x hotspot lies outside of the bitmap area");
        }
        if (hotSpotY < 0 || hotSpotY >= bitmap.getHeight()) {
            throw new IllegalArgumentException("y hotspot lies outside of the bitmap area");
        }
    }

    private static int getSystemIconStyleIndex(int style) {
        switch (style) {
            case STYLE_ARROW:
                return com.android.internal.R.styleable.Pointer_pointerIconArrow;
            case STYLE_SPOT_HOVER:
                return com.android.internal.R.styleable.Pointer_pointerIconSpotHover;
            case STYLE_SPOT_TOUCH:
                return com.android.internal.R.styleable.Pointer_pointerIconSpotTouch;
            case STYLE_SPOT_ANCHOR:
                return com.android.internal.R.styleable.Pointer_pointerIconSpotAnchor;
            default:
                return 0;
        }
    }
}
