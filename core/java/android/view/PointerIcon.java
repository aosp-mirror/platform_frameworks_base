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

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
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
import android.os.PointerIconType;
import android.util.Log;
import android.util.SparseArray;
import android.view.flags.Flags;

import androidx.annotation.VisibleForTesting;

import com.android.internal.util.XmlUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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
    public static final int TYPE_CUSTOM = PointerIconType.CUSTOM;

    /** Type constant: Null icon.  It has no bitmap. */
    public static final int TYPE_NULL = PointerIconType.TYPE_NULL;

    /**
     * Type constant: no icons are specified. If all views uses this, then the pointer icon falls
     * back to the default type, but this is helpful to distinguish a view that explicitly wants
     * to have the default icon.
     * @hide
     */
    public static final int TYPE_NOT_SPECIFIED = PointerIconType.NOT_SPECIFIED;

    /** Type constant: Arrow icon.  (Default mouse pointer) */
    public static final int TYPE_ARROW = PointerIconType.ARROW;

    /** {@hide} Type constant: Spot hover icon for touchpads. */
    public static final int TYPE_SPOT_HOVER = PointerIconType.SPOT_HOVER;

    /** {@hide} Type constant: Spot touch icon for touchpads. */
    public static final int TYPE_SPOT_TOUCH = PointerIconType.SPOT_TOUCH;

    /** {@hide} Type constant: Spot anchor icon for touchpads. */
    public static final int TYPE_SPOT_ANCHOR = PointerIconType.SPOT_ANCHOR;

    // Type constants for additional predefined icons for mice.
    /** Type constant: context-menu. */
    public static final int TYPE_CONTEXT_MENU = PointerIconType.CONTEXT_MENU;

    /** Type constant: hand. */
    public static final int TYPE_HAND = PointerIconType.HAND;

    /** Type constant: help. */
    public static final int TYPE_HELP = PointerIconType.HELP;

    /** Type constant: wait. */
    public static final int TYPE_WAIT = PointerIconType.WAIT;

    /** Type constant: cell. */
    public static final int TYPE_CELL = PointerIconType.CELL;

    /** Type constant: crosshair. */
    public static final int TYPE_CROSSHAIR = PointerIconType.CROSSHAIR;

    /** Type constant: text. */
    public static final int TYPE_TEXT = PointerIconType.TEXT;

    /** Type constant: vertical-text. */
    public static final int TYPE_VERTICAL_TEXT = PointerIconType.VERTICAL_TEXT;

    /** Type constant: alias (indicating an alias of/shortcut to something is
      * to be created. */
    public static final int TYPE_ALIAS = PointerIconType.ALIAS;

    /** Type constant: copy. */
    public static final int TYPE_COPY = PointerIconType.COPY;

    /** Type constant: no-drop. */
    public static final int TYPE_NO_DROP = PointerIconType.NO_DROP;

    /** Type constant: all-scroll. */
    public static final int TYPE_ALL_SCROLL = PointerIconType.ALL_SCROLL;

    /** Type constant: horizontal double arrow mainly for resizing. */
    public static final int TYPE_HORIZONTAL_DOUBLE_ARROW = PointerIconType.HORIZONTAL_DOUBLE_ARROW;

    /** Type constant: vertical double arrow mainly for resizing. */
    public static final int TYPE_VERTICAL_DOUBLE_ARROW = PointerIconType.VERTICAL_DOUBLE_ARROW;

    /** Type constant: diagonal double arrow -- top-right to bottom-left. */
    public static final int TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW = 1016;

    /** Type constant: diagonal double arrow -- top-left to bottom-right. */
    public static final int TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW = 1017;

    /** Type constant: zoom-in. */
    public static final int TYPE_ZOOM_IN = PointerIconType.ZOOM_IN;

    /** Type constant: zoom-out. */
    public static final int TYPE_ZOOM_OUT = PointerIconType.ZOOM_OUT;

    /** Type constant: grab. */
    public static final int TYPE_GRAB = PointerIconType.GRAB;

    /** Type constant: grabbing. */
    public static final int TYPE_GRABBING = PointerIconType.GRABBING;

    /** Type constant: handwriting. */
    public static final int TYPE_HANDWRITING = PointerIconType.HANDWRITING;

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

    /** @hide */
    @IntDef(prefix = {"POINTER_ICON_VECTOR_STYLE_FILL_"}, value = {
            POINTER_ICON_VECTOR_STYLE_FILL_BLACK,
            POINTER_ICON_VECTOR_STYLE_FILL_GREEN,
            POINTER_ICON_VECTOR_STYLE_FILL_RED,
            POINTER_ICON_VECTOR_STYLE_FILL_PINK,
            POINTER_ICON_VECTOR_STYLE_FILL_BLUE,
            POINTER_ICON_VECTOR_STYLE_FILL_PURPLE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PointerIconVectorStyleFill {}

    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_FILL_BLACK = 0;
    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_FILL_GREEN = 1;
    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_FILL_RED = 2;
    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_FILL_PINK = 3;
    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_FILL_BLUE = 4;
    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_FILL_PURPLE = 5;

    // If adding a PointerIconVectorStyleFill, update END value for {@link SystemSettingsValidators}
    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_FILL_BEGIN =
            POINTER_ICON_VECTOR_STYLE_FILL_BLACK;
    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_FILL_END =
            POINTER_ICON_VECTOR_STYLE_FILL_PURPLE;

    /** @hide */
    @IntDef(prefix = {"POINTER_ICON_VECTOR_STYLE_STROKE_"}, value = {
            POINTER_ICON_VECTOR_STYLE_STROKE_WHITE,
            POINTER_ICON_VECTOR_STYLE_STROKE_BLACK,
            POINTER_ICON_VECTOR_STYLE_STROKE_NONE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PointerIconVectorStyleStroke {}

    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_STROKE_WHITE = 0;
    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_STROKE_BLACK = 1;
    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_STROKE_NONE = 2;

    // If adding PointerIconVectorStyleStroke, update END value for {@link SystemSettingsValidators}
    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_STROKE_BEGIN =
            POINTER_ICON_VECTOR_STYLE_STROKE_WHITE;
    /** @hide */ public static final int POINTER_ICON_VECTOR_STYLE_STROKE_END =
            POINTER_ICON_VECTOR_STYLE_STROKE_NONE;

    /** @hide */ public static final float DEFAULT_POINTER_SCALE = 1f;
    /** @hide */ public static final float LARGE_POINTER_SCALE = 2.5f;

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
            boolean useLargeIcons, float pointerScale) {
        if (type == TYPE_NOT_SPECIFIED) {
            throw new IllegalStateException("Cannot load icon for type TYPE_NOT_SPECIFIED");
        }

        if (type == TYPE_CUSTOM) {
            throw new IllegalArgumentException("Custom icons must be loaded when they're created");
        }

        int typeIndex = getSystemIconTypeIndex(type);
        if (typeIndex < 0) {
            typeIndex = getSystemIconTypeIndex(TYPE_DEFAULT);
        }

        final int defStyle;
        if (android.view.flags.Flags.enableVectorCursorA11ySettings()) {
            defStyle = com.android.internal.R.style.VectorPointer;
        } else {
            // TODO(b/346358375): Remove useLargeIcons and the legacy pointer styles when
            //  enableVectorCursorA11ySetting is rolled out.
            if (useLargeIcons) {
                defStyle = com.android.internal.R.style.LargePointer;
            } else if (android.view.flags.Flags.enableVectorCursors()) {
                defStyle = com.android.internal.R.style.VectorPointer;
            } else {
                defStyle = com.android.internal.R.style.Pointer;
            }
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
                    : getLoadedSystemIcon(context, TYPE_DEFAULT, useLargeIcons, pointerScale);
        }

        final PointerIcon icon = new PointerIcon(type);
        icon.loadResource(context.getResources(), resourceId, context.getTheme(), pointerScale);
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
        validateHotSpot(bitmap, hotSpotX, hotSpotY, false /* isScaled */);

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
        icon.loadResource(resources, resourceId, null, DEFAULT_POINTER_SCALE);
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
            VectorDrawable vectorDrawable, float pointerScale) {
        // Ensure we pass the display metrics into the Bitmap constructor so that it is initialized
        // with the correct density.
        Bitmap bitmap = Bitmap.createBitmap(resources.getDisplayMetrics(),
                (int) (vectorDrawable.getIntrinsicWidth() * pointerScale),
                (int) (vectorDrawable.getIntrinsicHeight() * pointerScale),
                Bitmap.Config.ARGB_8888, true /* hasAlpha */);
        Canvas canvas = new Canvas(bitmap);
        vectorDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        vectorDrawable.draw(canvas);
        return new BitmapDrawable(resources, bitmap);
    }

    private void loadResource(@NonNull Resources resources, @XmlRes int resourceId,
            @Nullable Resources.Theme theme, float pointerScale) {
        final XmlResourceParser parser = resources.getXml(resourceId);
        final int bitmapRes;
        final float hotSpotX;
        final float hotSpotY;
        try {
            XmlUtils.beginDocument(parser, "pointer-icon");

            final TypedArray a = resources.obtainAttributes(
                    parser, com.android.internal.R.styleable.PointerIcon);
            bitmapRes = a.getResourceId(com.android.internal.R.styleable.PointerIcon_bitmap, 0);
            hotSpotX = a.getDimension(com.android.internal.R.styleable.PointerIcon_hotSpotX, 0)
                    * pointerScale;
            hotSpotY = a.getDimension(com.android.internal.R.styleable.PointerIcon_hotSpotY, 0)
                    * pointerScale;
            a.recycle();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Exception parsing pointer icon resource.", ex);
        } finally {
            parser.close();
        }

        if (bitmapRes == 0) {
            throw new IllegalArgumentException("<pointer-icon> is missing bitmap attribute.");
        }

        Drawable drawable = resources.getDrawable(bitmapRes, theme);
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
                final boolean isVectorAnimation = drawable instanceof VectorDrawable;
                mDrawNativeDropShadow = isVectorAnimation;
                for (int i = 1; i < frames; ++i) {
                    Drawable drawableFrame = animationDrawable.getFrame(i);
                    if (!(drawableFrame instanceof BitmapDrawable)
                            && !(drawableFrame instanceof VectorDrawable)) {
                        throw new IllegalArgumentException("Frame of an animated pointer icon "
                                + "must refer to a bitmap drawable or vector drawable.");
                    }
                    if (isVectorAnimation != (drawableFrame instanceof VectorDrawable)) {
                        throw new IllegalArgumentException("The drawable of the " + i + "-th frame "
                                + "is a different type from the others. All frames should be the "
                                + "same type.");
                    }
                    if (isVectorAnimation) {
                        drawableFrame = getBitmapDrawableFromVectorDrawable(resources,
                                (VectorDrawable) drawableFrame, pointerScale);
                    }
                    mBitmapFrames[i - 1] = getBitmapFromDrawable((BitmapDrawable) drawableFrame);
                }
            }
        }
        if (drawable instanceof VectorDrawable) {
            mDrawNativeDropShadow = true;
            drawable = getBitmapDrawableFromVectorDrawable(resources, (VectorDrawable) drawable,
                    pointerScale);
        }
        if (!(drawable instanceof BitmapDrawable)) {
            throw new IllegalArgumentException("<pointer-icon> bitmap attribute must "
                    + "refer to a bitmap drawable.");
        }

        BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
        final Bitmap bitmap = getBitmapFromDrawable(bitmapDrawable);
        // The bitmap and hotspot are loaded from the context, which means it is implicitly scaled
        // to the current display density, so treat this as a scaled icon when verifying hotspot.
        validateHotSpot(bitmap, hotSpotX, hotSpotY, true /* isScaled */);
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

    private static void validateHotSpot(Bitmap bitmap, float hotSpotX, float hotSpotY,
            boolean isScaled) {
        // Be more lenient when checking the hotspot for scaled icons to account for the restriction
        // that bitmaps must have an integer size.
        if (hotSpotX < 0 || (isScaled ? (int) hotSpotX > bitmap.getWidth()
                : hotSpotX >= bitmap.getWidth())) {
            throw new IllegalArgumentException("x hotspot lies outside of the bitmap area");
        }
        if (hotSpotY < 0 || (isScaled ? (int) hotSpotY > bitmap.getHeight()
                : hotSpotY >= bitmap.getHeight())) {
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
                return -1;
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

    /**
     * Convert fill style constant to resource ID.
     *
     * @hide
     */
    public static int vectorFillStyleToResource(@PointerIconVectorStyleFill int fillStyle) {
        return switch (fillStyle) {
            case POINTER_ICON_VECTOR_STYLE_FILL_BLACK ->
                    com.android.internal.R.style.PointerIconVectorStyleFillBlack;
            case POINTER_ICON_VECTOR_STYLE_FILL_GREEN ->
                    com.android.internal.R.style.PointerIconVectorStyleFillGreen;
            case POINTER_ICON_VECTOR_STYLE_FILL_RED ->
                    com.android.internal.R.style.PointerIconVectorStyleFillRed;
            case POINTER_ICON_VECTOR_STYLE_FILL_PINK ->
                    com.android.internal.R.style.PointerIconVectorStyleFillPink;
            case POINTER_ICON_VECTOR_STYLE_FILL_BLUE ->
                    com.android.internal.R.style.PointerIconVectorStyleFillBlue;
            case POINTER_ICON_VECTOR_STYLE_FILL_PURPLE ->
                    com.android.internal.R.style.PointerIconVectorStyleFillPurple;
            default -> com.android.internal.R.style.PointerIconVectorStyleFillBlack;
        };
    }

    /**
     * Convert stroke style constant to resource ID.
     *
     * @hide
     */
    public static int vectorStrokeStyleToResource(@PointerIconVectorStyleStroke int strokeStyle) {
        return switch (strokeStyle) {
            case POINTER_ICON_VECTOR_STYLE_STROKE_BLACK ->
                    com.android.internal.R.style.PointerIconVectorStyleStrokeBlack;
            case POINTER_ICON_VECTOR_STYLE_STROKE_WHITE ->
                    com.android.internal.R.style.PointerIconVectorStyleStrokeWhite;
            case POINTER_ICON_VECTOR_STYLE_STROKE_NONE ->
                    com.android.internal.R.style.PointerIconVectorStyleStrokeNone;
            default -> com.android.internal.R.style.PointerIconVectorStyleStrokeWhite;
        };
    }

    /**
     * Sets whether drop shadow will draw in the native code.
     *
     * @hide
     */
    @TestApi
    @FlaggedApi(Flags.FLAG_ENABLE_VECTOR_CURSORS)
    public void setDrawNativeDropShadow(boolean drawNativeDropShadow) {
        mDrawNativeDropShadow = drawNativeDropShadow;
    }

    /**
     * Gets the PointerIcon's bitmap.
     *
     * @hide
     */
    @VisibleForTesting
    public Bitmap getBitmap() {
        return mBitmap;
    }
}
