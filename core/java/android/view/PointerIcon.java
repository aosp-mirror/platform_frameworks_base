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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.XmlRes;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.util.XmlUtils;

/**
 * Represents an icon that can be used as a mouse pointer.
 * <p>
 * Pointer icons can be provided either by the system using system types,
 * or by applications using bitmaps or application resources.
 * </p>
 */
public final class PointerIcon implements Parcelable {
    private static final String TAG = "PointerIcon";

    /** {@hide} Type constant: Custom icon with a user-supplied bitmap. */
    public static final int TYPE_CUSTOM = -1;

    /** Type constant: Null icon.  It has no bitmap. */
    public static final int TYPE_NULL = 0;

    /**
     * Type constant: no icons are specified. If all views uses this, then the pointer icon falls
     * back to the default type, but this is helpful to distinguish a view that explicitly wants
     * to have the default icon.
     * @hide
     */
    public static final int TYPE_NOT_SPECIFIED = 1;

    /** Type constant: Arrow icon.  (Default mouse pointer) */
    public static final int TYPE_ARROW = 1000;

    /** {@hide} Type constant: Spot hover icon for touchpads. */
    public static final int TYPE_SPOT_HOVER = 2000;

    /** {@hide} Type constant: Spot touch icon for touchpads. */
    public static final int TYPE_SPOT_TOUCH = 2001;

    /** {@hide} Type constant: Spot anchor icon for touchpads. */
    public static final int TYPE_SPOT_ANCHOR = 2002;

    // Type constants for additional predefined icons for mice.
    /** Type constant: context-menu. */
    public static final int TYPE_CONTEXT_MENU = 1001;

    /** Type constant: hand. */
    public static final int TYPE_HAND = 1002;

    /** Type constant: help. */
    public static final int TYPE_HELP = 1003;

    /** Type constant: wait. */
    public static final int TYPE_WAIT = 1004;

    /** Type constant: cell. */
    public static final int TYPE_CELL = 1006;

    /** Type constant: crosshair. */
    public static final int TYPE_CROSSHAIR = 1007;

    /** Type constant: text. */
    public static final int TYPE_TEXT = 1008;

    /** Type constant: vertical-text. */
    public static final int TYPE_VERTICAL_TEXT = 1009;

    /** Type constant: alias (indicating an alias of/shortcut to something is
      * to be created. */
    public static final int TYPE_ALIAS = 1010;

    /** Type constant: copy. */
    public static final int TYPE_COPY = 1011;

    /** Type constant: no-drop. */
    public static final int TYPE_NO_DROP = 1012;

    /** Type constant: all-scroll. */
    public static final int TYPE_ALL_SCROLL = 1013;

    /** Type constant: horizontal double arrow mainly for resizing. */
    public static final int TYPE_HORIZONTAL_DOUBLE_ARROW = 1014;

    /** Type constant: vertical double arrow mainly for resizing. */
    public static final int TYPE_VERTICAL_DOUBLE_ARROW = 1015;

    /** Type constant: diagonal double arrow -- top-right to bottom-left. */
    public static final int TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW = 1016;

    /** Type constant: diagonal double arrow -- top-left to bottom-right. */
    public static final int TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW = 1017;

    /** Type constant: zoom-in. */
    public static final int TYPE_ZOOM_IN = 1018;

    /** Type constant: zoom-out. */
    public static final int TYPE_ZOOM_OUT = 1019;

    /** Type constant: grab. */
    public static final int TYPE_GRAB = 1020;

    /** Type constant: grabbing. */
    public static final int TYPE_GRABBING = 1021;

    /** Type constant: handwriting. */
    public static final int TYPE_HANDWRITING = 1022;

    // OEM private types should be defined starting at this range to avoid
    // conflicts with any system types that may be defined in the future.
    private static final int TYPE_OEM_FIRST = 10000;

    /**
     * The default pointer icon.
     * @deprecated This is the same as using {@link #TYPE_ARROW}. Use {@link #TYPE_ARROW} to
     *     explicitly show an arrow, or use a {@code null} {@link PointerIcon} with
     *     {@link View#setPointerIcon(PointerIcon)} or
     *     {@link View#onResolvePointerIcon(MotionEvent, int)} instead to show
     *     the default pointer icon.
     */
    public static final int TYPE_DEFAULT = TYPE_ARROW;

    // A cache of the system icons used by the app, used to avoid creating a new PointerIcon object
    // every time we need to resolve the icon (i.e. on each input event).
    private static final SparseArray<PointerIcon> SYSTEM_ICONS = new SparseArray<>();

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private final int mType;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private Bitmap mBitmap;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private float mHotSpotX;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private float mHotSpotY;
    // The bitmaps for the additional frame of animated pointer icon. Note that the first frame
    // will be stored in mBitmap.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private Bitmap mBitmapFrames[];
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private int mDurationPerFrame;
    @SuppressWarnings("unused")
    private boolean mDrawNativeDropShadow;

    private PointerIcon(int type) {
        mType = type;
    }

    /**
     * Gets a system pointer icon for the given type.
     *
     * @param context The context.
     * @param type The pointer icon type.
     * @return The pointer icon.
     *
     * @throws IllegalArgumentException if context is null.
     */
    public static @NonNull PointerIcon getSystemIcon(@NonNull Context context, int type) {
        if (context == null) {
            // We no longer use the context to resolve the system icon resource here, because the
            // system will use its own context to do the type-to-resource resolution and cache it
            // for use across different apps. Therefore, apps cannot customize the resource of a
            // system icon. To avoid changing the public API, we keep the context parameter
            // requirement.
            throw new IllegalArgumentException("context must not be null");
        }
        return getSystemIcon(type);
    }

    private static @NonNull PointerIcon getSystemIcon(int type) {
        if (type == TYPE_CUSTOM) {
            throw new IllegalArgumentException("cannot get system icon for TYPE_CUSTOM");
        }
        PointerIcon icon = SYSTEM_ICONS.get(type);
        if (icon == null) {
            icon = new PointerIcon(type);
            SYSTEM_ICONS.put(type, icon);
        }
        return icon;
    }

    /**
     * Get a system icon with its resources loaded.
     * This should only be used by the system for drawing icons to the screen.
     * @hide
     */
    public static @NonNull PointerIcon getLoadedSystemIcon(@NonNull Context context, int type,
            boolean useLargeIcons) {
        if (type == TYPE_NOT_SPECIFIED) {
            throw new IllegalStateException("Cannot load icon for type TYPE_NOT_SPECIFIED");
        }

        if (type == TYPE_CUSTOM) {
            throw new IllegalArgumentException("Custom icons must be loaded when they're created");
        }

        int typeIndex = getSystemIconTypeIndex(type);
        if (typeIndex == 0) {
            typeIndex = getSystemIconTypeIndex(TYPE_DEFAULT);
        }

        final int defStyle;
        // TODO(b/305193969): Use scaled vectors when large icons are requested.
        if (useLargeIcons) {
            defStyle = com.android.internal.R.style.LargePointer;
        } else if (android.view.flags.Flags.enableVectorCursors()) {
            defStyle = com.android.internal.R.style.VectorPointer;
        } else {
            defStyle = com.android.internal.R.style.Pointer;
        }
        TypedArray a = context.obtainStyledAttributes(null,
                com.android.internal.R.styleable.Pointer,
                0, defStyle);
        int resourceId = a.getResourceId(typeIndex, -1);
        a.recycle();

        if (resourceId == -1) {
            Log.w(TAG, "Missing theme resources for pointer icon type " + type);
            return type == TYPE_DEFAULT
                    ? getSystemIcon(TYPE_NULL)
                    : getLoadedSystemIcon(context, TYPE_DEFAULT, useLargeIcons);
        }

        final PointerIcon icon = new PointerIcon(type);
        icon.loadResource(context.getResources(), resourceId);
        return icon;
    }

    private boolean isLoaded() {
        return mBitmap != null && mHotSpotX >= 0 && mHotSpotX < mBitmap.getWidth() && mHotSpotY >= 0
                && mHotSpotY < mBitmap.getHeight();
    }

    /**
     * Creates a custom pointer icon from the given bitmap and hotspot information.
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
    public static @NonNull PointerIcon create(@NonNull Bitmap bitmap, float hotSpotX,
            float hotSpotY) {
        if (bitmap == null) {
            throw new IllegalArgumentException("bitmap must not be null");
        }
        validateHotSpot(bitmap, hotSpotX, hotSpotY);

        PointerIcon icon = new PointerIcon(TYPE_CUSTOM);
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
    public static @NonNull PointerIcon load(@NonNull Resources resources, @XmlRes int resourceId) {
        if (resources == null) {
            throw new IllegalArgumentException("resources must not be null");
        }

        PointerIcon icon = new PointerIcon(TYPE_CUSTOM);
        icon.loadResource(resources, resourceId);
        return icon;
    }

    /** @hide */
    public int getType() {
        return mType;
    }

    public static final @NonNull Parcelable.Creator<PointerIcon> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public PointerIcon createFromParcel(Parcel in) {
                    final int type = in.readInt();
                    if (type != TYPE_CUSTOM) {
                        return getSystemIcon(type);
                    }
                    final PointerIcon icon =
                            PointerIcon.create(
                                    Bitmap.CREATOR.createFromParcel(in),
                                    in.readFloat(),
                                    in.readFloat());
                    icon.mDrawNativeDropShadow = in.readBoolean();
                    return icon;
                }

                @Override
                public PointerIcon[] newArray(int size) {
                    return new PointerIcon[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(mType);
        if (mType != TYPE_CUSTOM) {
            // When parceling a non-custom icon type, do not write the icon bitmap into the parcel
            // because it can be re-loaded from resources after un-parceling.
            return;
        }

        if (!isLoaded()) {
            throw new IllegalStateException("Custom icon should be loaded upon creation");
        }
        mBitmap.writeToParcel(out, flags);
        out.writeFloat(mHotSpotX);
        out.writeFloat(mHotSpotY);
        out.writeBoolean(mDrawNativeDropShadow);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (this == other) {
            return true;
        }

        if (other == null || !(other instanceof PointerIcon)) {
            return false;
        }

        PointerIcon otherIcon = (PointerIcon) other;
        if (mType != otherIcon.mType) {
            return false;
        }

        if (mBitmap != otherIcon.mBitmap
                || mHotSpotX != otherIcon.mHotSpotX
                || mHotSpotY != otherIcon.mHotSpotY) {
            return false;
        }

        return true;
    }

    /**
     *  Get the Bitmap from the Drawable.
     *
     *  If the Bitmap needed to be scaled up to account for density, BitmapDrawable
     *  handles this at draw time. But this class doesn't actually draw the Bitmap;
     *  it is just a holder for native code to access its SkBitmap. So this needs to
     *  get a version that is scaled to account for density.
     */
    private Bitmap getBitmapFromDrawable(BitmapDrawable bitmapDrawable) {
        Bitmap bitmap = bitmapDrawable.getBitmap();
        final int scaledWidth  = bitmapDrawable.getIntrinsicWidth();
        final int scaledHeight = bitmapDrawable.getIntrinsicHeight();
        if (scaledWidth == bitmap.getWidth() && scaledHeight == bitmap.getHeight()) {
            return bitmap;
        }

        Rect src = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        RectF dst = new RectF(0, 0, scaledWidth, scaledHeight);

        Bitmap scaled = Bitmap.createBitmap(scaledWidth, scaledHeight, bitmap.getConfig());
        Canvas canvas = new Canvas(scaled);
        Paint paint = new Paint();
        paint.setFilterBitmap(true);
        canvas.drawBitmap(bitmap, src, dst, paint);
        return scaled;
    }

    private BitmapDrawable getBitmapDrawableFromVectorDrawable(Resources resources,
            VectorDrawable vectorDrawable) {
        Bitmap bitmap = Bitmap.createBitmap(vectorDrawable.getIntrinsicWidth(),
                vectorDrawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        vectorDrawable.draw(canvas);
        return new BitmapDrawable(resources, bitmap);
    }

    private void loadResource(@NonNull Resources resources, @XmlRes int resourceId) {
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

        Drawable drawable = resources.getDrawable(bitmapRes);
        if (drawable instanceof AnimationDrawable) {
            // Extract animation frame bitmaps.
            final AnimationDrawable animationDrawable = (AnimationDrawable) drawable;
            final int frames = animationDrawable.getNumberOfFrames();
            drawable = animationDrawable.getFrame(0);
            if (frames == 1) {
                Log.w(TAG, "Animation icon with single frame -- simply treating the first "
                        + "frame as a normal bitmap icon.");
            } else {
                // Assumes they have the exact duration.
                mDurationPerFrame = animationDrawable.getDuration(0);
                mBitmapFrames = new Bitmap[frames - 1];
                final int width = drawable.getIntrinsicWidth();
                final int height = drawable.getIntrinsicHeight();
                for (int i = 1; i < frames; ++i) {
                    Drawable drawableFrame = animationDrawable.getFrame(i);
                    if (!(drawableFrame instanceof BitmapDrawable)) {
                        throw new IllegalArgumentException("Frame of an animated pointer icon "
                                + "must refer to a bitmap drawable.");
                    }
                    if (drawableFrame.getIntrinsicWidth() != width ||
                        drawableFrame.getIntrinsicHeight() != height) {
                        throw new IllegalArgumentException("The bitmap size of " + i + "-th frame "
                                + "is different. All frames should have the exact same size and "
                                + "share the same hotspot.");
                    }
                    BitmapDrawable bitmapDrawableFrame = (BitmapDrawable) drawableFrame;
                    mBitmapFrames[i - 1] = getBitmapFromDrawable(bitmapDrawableFrame);
                }
            }
        }
        if (drawable instanceof VectorDrawable) {
            mDrawNativeDropShadow = true;
            drawable = getBitmapDrawableFromVectorDrawable(resources, (VectorDrawable) drawable);
        }
        if (!(drawable instanceof BitmapDrawable)) {
            throw new IllegalArgumentException("<pointer-icon> bitmap attribute must "
                    + "refer to a bitmap drawable.");
        }

        BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
        final Bitmap bitmap = getBitmapFromDrawable(bitmapDrawable);
        validateHotSpot(bitmap, hotSpotX, hotSpotY);
        // Set the properties now that we have successfully loaded the icon.
        mBitmap = bitmap;
        mHotSpotX = hotSpotX;
        mHotSpotY = hotSpotY;
        assert isLoaded();
    }

    @Override
    public String toString() {
        return "PointerIcon{type=" + typeToString(mType)
                + ", hotspotX=" + mHotSpotX + ", hotspotY=" + mHotSpotY + "}";
    }

    private static void validateHotSpot(Bitmap bitmap, float hotSpotX, float hotSpotY) {
        if (hotSpotX < 0 || hotSpotX >= bitmap.getWidth()) {
            throw new IllegalArgumentException("x hotspot lies outside of the bitmap area");
        }
        if (hotSpotY < 0 || hotSpotY >= bitmap.getHeight()) {
            throw new IllegalArgumentException("y hotspot lies outside of the bitmap area");
        }
    }

    private static int getSystemIconTypeIndex(int type) {
        switch (type) {
            case TYPE_ARROW:
                return com.android.internal.R.styleable.Pointer_pointerIconArrow;
            case TYPE_SPOT_HOVER:
                return com.android.internal.R.styleable.Pointer_pointerIconSpotHover;
            case TYPE_SPOT_TOUCH:
                return com.android.internal.R.styleable.Pointer_pointerIconSpotTouch;
            case TYPE_SPOT_ANCHOR:
                return com.android.internal.R.styleable.Pointer_pointerIconSpotAnchor;
            case TYPE_HAND:
                return com.android.internal.R.styleable.Pointer_pointerIconHand;
            case TYPE_CONTEXT_MENU:
                return com.android.internal.R.styleable.Pointer_pointerIconContextMenu;
            case TYPE_HELP:
                return com.android.internal.R.styleable.Pointer_pointerIconHelp;
            case TYPE_WAIT:
                return com.android.internal.R.styleable.Pointer_pointerIconWait;
            case TYPE_CELL:
                return com.android.internal.R.styleable.Pointer_pointerIconCell;
            case TYPE_CROSSHAIR:
                return com.android.internal.R.styleable.Pointer_pointerIconCrosshair;
            case TYPE_TEXT:
                return com.android.internal.R.styleable.Pointer_pointerIconText;
            case TYPE_VERTICAL_TEXT:
                return com.android.internal.R.styleable.Pointer_pointerIconVerticalText;
            case TYPE_ALIAS:
                return com.android.internal.R.styleable.Pointer_pointerIconAlias;
            case TYPE_COPY:
                return com.android.internal.R.styleable.Pointer_pointerIconCopy;
            case TYPE_ALL_SCROLL:
                return com.android.internal.R.styleable.Pointer_pointerIconAllScroll;
            case TYPE_NO_DROP:
                return com.android.internal.R.styleable.Pointer_pointerIconNodrop;
            case TYPE_HORIZONTAL_DOUBLE_ARROW:
                return com.android.internal.R.styleable.Pointer_pointerIconHorizontalDoubleArrow;
            case TYPE_VERTICAL_DOUBLE_ARROW:
                return com.android.internal.R.styleable.Pointer_pointerIconVerticalDoubleArrow;
            case TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW:
                return com.android.internal.R.styleable.
                        Pointer_pointerIconTopRightDiagonalDoubleArrow;
            case TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW:
                return com.android.internal.R.styleable.
                        Pointer_pointerIconTopLeftDiagonalDoubleArrow;
            case TYPE_ZOOM_IN:
                return com.android.internal.R.styleable.Pointer_pointerIconZoomIn;
            case TYPE_ZOOM_OUT:
                return com.android.internal.R.styleable.Pointer_pointerIconZoomOut;
            case TYPE_GRAB:
                return com.android.internal.R.styleable.Pointer_pointerIconGrab;
            case TYPE_GRABBING:
                return com.android.internal.R.styleable.Pointer_pointerIconGrabbing;
            case TYPE_HANDWRITING:
                return com.android.internal.R.styleable.Pointer_pointerIconHandwriting;
            default:
                return 0;
        }
    }

    /**
     * Convert type constant to string.
     * @hide
     */
    public static String typeToString(int type) {
        switch (type) {
            case TYPE_CUSTOM: return "CUSTOM";
            case TYPE_NULL: return "NULL";
            case TYPE_NOT_SPECIFIED: return "NOT_SPECIFIED";
            case TYPE_ARROW: return "ARROW";
            case TYPE_SPOT_HOVER: return "SPOT_HOVER";
            case TYPE_SPOT_TOUCH: return "SPOT_TOUCH";
            case TYPE_SPOT_ANCHOR: return "SPOT_ANCHOR";
            case TYPE_CONTEXT_MENU: return "CONTEXT_MENU";
            case TYPE_HAND: return "HAND";
            case TYPE_HELP: return "HELP";
            case TYPE_WAIT: return "WAIT";
            case TYPE_CELL: return "CELL";
            case TYPE_CROSSHAIR: return "CROSSHAIR";
            case TYPE_TEXT: return "TEXT";
            case TYPE_VERTICAL_TEXT: return "VERTICAL_TEXT";
            case TYPE_ALIAS: return "ALIAS";
            case TYPE_COPY: return "COPY";
            case TYPE_NO_DROP: return "NO_DROP";
            case TYPE_ALL_SCROLL: return "ALL_SCROLL";
            case TYPE_HORIZONTAL_DOUBLE_ARROW: return "HORIZONTAL_DOUBLE_ARROW";
            case TYPE_VERTICAL_DOUBLE_ARROW: return "VERTICAL_DOUBLE_ARROW";
            case TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW: return "TOP_RIGHT_DIAGONAL_DOUBLE_ARROW";
            case TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW: return "TOP_LEFT_DIAGONAL_DOUBLE_ARROW";
            case TYPE_ZOOM_IN: return "ZOOM_IN";
            case TYPE_ZOOM_OUT: return "ZOOM_OUT";
            case TYPE_GRAB: return "GRAB";
            case TYPE_GRABBING: return "GRABBING";
            case TYPE_HANDWRITING: return "HANDWRITING";
            default: return Integer.toString(type);
        }
    }
}
