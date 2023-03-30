/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.WindowInsets.Type.InsetsType;

import java.util.Arrays;
import java.util.Objects;

/**
 * Insets provided by a window.
 *
 * The insets frame will by default as the window frame size. If the providers are set, the
 * calculation result based on the source size will be used as the insets frame.
 *
 * The InsetsFrameProvider should be self-contained. Nothing describing the window itself, such as
 * contentInsets, visibleInsets, etc. won't affect the insets providing to other windows when this
 * is set.
 * @hide
 */
public class InsetsFrameProvider implements Parcelable {

    /**
     * If specified in source field, the insets calculation will be based on the display frame.
     */
    public static final int SOURCE_DISPLAY = 0;

    /**
     * If specified in source field, the insets calculation will be based on the window bounds. The
     * container bounds can sometimes be different from the window frame. For example, when a task
     * bar needs the entire screen to be prepared to showing the apps, the window container can take
     * the entire display, or display area, but the window frame, as a result of the layout, will
     * stay small until it actually taking the entire display to draw their view.
     */
    public static final int SOURCE_CONTAINER_BOUNDS = 1;

    /**
     * If specified in source field, the insets calculation will be based on the window frame. This
     * is also the default value of the source.
     */
    public static final int SOURCE_FRAME = 2;

    private static final int HAS_INSETS_SIZE = 1;
    private static final int HAS_INSETS_SIZE_OVERRIDE = 2;

    private static final Rect sTmpRect = new Rect();
    private static final Rect sTmpRect2 = new Rect();

    private final IBinder mOwner;
    private final int mIndex;
    private final @InsetsType int mType;

    /**
     * The source of frame. By default, all adjustment will be based on the window frame, it
     * can be set to window bounds or display bounds instead.
     */
    private int mSource = SOURCE_FRAME;

    /**
     * The provided insets size based on the source frame. The result will be used as the insets
     * size to windows other than IME. Only one side should be set.
     *
     * For example, when the given source frame is (0, 0) - (100, 200), and the insetsSize is null,
     * the source frame will be directly used as the final insets frame. If the insetsSize is set to
     * (0, 0, 0, 50) instead, the insets frame will be a frame starting from the bottom side of the
     * source frame with height of 50, i.e., (0, 150) - (100, 200).
     */
    private Insets mInsetsSize = null;

    /**
     * If null, the size set in insetsSize will be applied to all window types. If it contains
     * element of some types, the insets reported to the window with that types will be overridden.
     */
    private InsetsSizeOverride[] mInsetsSizeOverrides = null;

    /**
     * This field, if set, is indicating the insets needs to be at least the given size inside the
     * display cutout safe area. This will be compared to the insets size calculated based on other
     * attributes, and will be applied when this is larger. This is independent of the
     * PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT in LayoutParams, as this is not going to change
     * the layout of the window, but only change the insets frame. This can be applied to insets
     * calculated based on all three source frames.
     *
     * Be cautious, this will not be in effect for the window types whose insets size is overridden.
     */
    private Insets mMinimalInsetsSizeInDisplayCutoutSafe = null;

    /**
     * Creates an InsetsFrameProvider which describes what frame an insets source should have.
     *
     * @param owner the owner of this provider. We might have multiple sources with the same type on
     *              a display, this is used to identify them.
     * @param index the index of this provider. An owner might provide multiple sources with the
     *              same type, this is used to identify them.
     *              The value must be in a range of [0, 2047].
     * @param type the {@link InsetsType}.
     * @see InsetsSource#createId(Object, int, int)
     */
    public InsetsFrameProvider(IBinder owner, @IntRange(from = 0, to = 2047) int index,
            @InsetsType int type) {
        if (index < 0 || index >= 2048) {
            throw new IllegalArgumentException();
        }

        // This throws IllegalArgumentException if the type is not valid.
        WindowInsets.Type.indexOf(type);

        mOwner = owner;
        mIndex = index;
        mType = type;
    }

    public IBinder getOwner() {
        return mOwner;
    }

    public int getIndex() {
        return mIndex;
    }

    public int getType() {
        return mType;
    }

    public InsetsFrameProvider setSource(int source) {
        mSource = source;
        return this;
    }

    public int getSource() {
        return mSource;
    }

    public InsetsFrameProvider setInsetsSize(Insets insetsSize) {
        mInsetsSize = insetsSize;
        return this;
    }

    public Insets getInsetsSize() {
        return mInsetsSize;
    }

    public InsetsFrameProvider setInsetsSizeOverrides(InsetsSizeOverride[] insetsSizeOverrides) {
        mInsetsSizeOverrides = insetsSizeOverrides;
        return this;
    }

    public InsetsSizeOverride[] getInsetsSizeOverrides() {
        return mInsetsSizeOverrides;
    }

    public InsetsFrameProvider setMinimalInsetsSizeInDisplayCutoutSafe(
            Insets minimalInsetsSizeInDisplayCutoutSafe) {
        mMinimalInsetsSizeInDisplayCutoutSafe = minimalInsetsSizeInDisplayCutoutSafe;
        return this;
    }

    public Insets getMinimalInsetsSizeInDisplayCutoutSafe() {
        return mMinimalInsetsSizeInDisplayCutoutSafe;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("InsetsFrameProvider: {");
        sb.append("owner=").append(mOwner);
        sb.append(", index=").append(mIndex);
        sb.append(", type=").append(WindowInsets.Type.toString(mType));
        sb.append(", source=").append(sourceToString(mSource));
        if (mInsetsSize != null) {
            sb.append(", insetsSize=").append(mInsetsSize);
        }
        if (mInsetsSizeOverrides != null) {
            sb.append(", insetsSizeOverrides=").append(Arrays.toString(mInsetsSizeOverrides));
        }
        sb.append("}");
        return sb.toString();
    }

    private static String sourceToString(int source) {
        switch (source) {
            case SOURCE_DISPLAY:
                return "DISPLAY";
            case SOURCE_CONTAINER_BOUNDS:
                return "CONTAINER_BOUNDS";
            case SOURCE_FRAME:
                return "FRAME";
        }
        return "UNDEFINED";
    }

    public InsetsFrameProvider(Parcel in) {
        mOwner = in.readStrongBinder();
        mIndex = in.readInt();
        mType = in.readInt();
        int insetsSizeModified = in.readInt();
        mSource = in.readInt();
        if ((insetsSizeModified & HAS_INSETS_SIZE) != 0) {
            mInsetsSize = Insets.CREATOR.createFromParcel(in);
        }
        if ((insetsSizeModified & HAS_INSETS_SIZE_OVERRIDE) != 0) {
            mInsetsSizeOverrides = in.createTypedArray(InsetsSizeOverride.CREATOR);
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(mOwner);
        out.writeInt(mIndex);
        out.writeInt(mType);
        int insetsSizeModified = 0;
        if (mInsetsSize != null) {
            insetsSizeModified |= HAS_INSETS_SIZE;
        }
        if (mInsetsSizeOverrides != null) {
            insetsSizeModified |= HAS_INSETS_SIZE_OVERRIDE;
        }
        out.writeInt(insetsSizeModified);
        out.writeInt(mSource);
        if (mInsetsSize != null) {
            mInsetsSize.writeToParcel(out, flags);
        }
        if (mInsetsSizeOverrides != null) {
            out.writeTypedArray(mInsetsSizeOverrides, flags);
        }
    }

    public boolean idEquals(InsetsFrameProvider o) {
        return Objects.equals(mOwner, o.mOwner) && mIndex == o.mIndex && mType == o.mType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final InsetsFrameProvider other = (InsetsFrameProvider) o;
        return Objects.equals(mOwner, other.mOwner) && mIndex == other.mIndex
                && mType == other.mType && mSource == other.mSource
                && Objects.equals(mInsetsSize, other.mInsetsSize)
                && Arrays.equals(mInsetsSizeOverrides, other.mInsetsSizeOverrides);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mOwner, mIndex, mType, mSource, mInsetsSize,
                Arrays.hashCode(mInsetsSizeOverrides));
    }

    public static final @NonNull Parcelable.Creator<InsetsFrameProvider> CREATOR =
            new Parcelable.Creator<>() {
                @Override
                public InsetsFrameProvider createFromParcel(Parcel in) {
                    return new InsetsFrameProvider(in);
                }

                @Override
                public InsetsFrameProvider[] newArray(int size) {
                    return new InsetsFrameProvider[size];
                }
            };

    public static void calculateInsetsFrame(Rect displayFrame, Rect containerBounds,
            Rect displayCutoutSafe, Rect inOutFrame, int source, Insets insetsSize,
            @WindowManager.LayoutParams.PrivateFlags int privateFlags,
            Insets displayCutoutSafeInsetsSize, Rect givenContentInsets) {
        boolean extendByCutout = false;
        if (source == InsetsFrameProvider.SOURCE_DISPLAY) {
            inOutFrame.set(displayFrame);
        } else if (source == InsetsFrameProvider.SOURCE_CONTAINER_BOUNDS) {
            inOutFrame.set(containerBounds);
        } else {
            extendByCutout = (privateFlags & PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT) != 0;
            if (givenContentInsets != null) {
                inOutFrame.inset(givenContentInsets);
            }
        }
        if (displayCutoutSafeInsetsSize != null) {
            sTmpRect2.set(inOutFrame);
        }
        if (insetsSize != null) {
            calculateInsetsFrame(inOutFrame, insetsSize);
        }

        if (extendByCutout && insetsSize != null) {
            // Only extend if the insets size is not null. Otherwise, the frame has already been
            // extended by the display cutout during layout process.
            WindowLayout.extendFrameByCutout(displayCutoutSafe, displayFrame, inOutFrame, sTmpRect);
        }

        if (displayCutoutSafeInsetsSize != null) {
            // The insets is at least with the given size within the display cutout safe area.
            // Calculate the smallest size.
            calculateInsetsFrame(sTmpRect2, displayCutoutSafeInsetsSize);
            WindowLayout.extendFrameByCutout(displayCutoutSafe, displayFrame, sTmpRect2, sTmpRect);
            // If it's larger than previous calculation, use it.
            if (sTmpRect2.contains(inOutFrame)) {
                inOutFrame.set(sTmpRect2);
            }
        }
    }

    /**
     * Calculate the insets frame given the insets size and the source frame.
     * @param inOutFrame the source frame.
     * @param insetsSize the insets size. Only the first non-zero value will be taken.
     */
    private static void calculateInsetsFrame(Rect inOutFrame, Insets insetsSize) {
        // Only one side of the provider shall be applied. Check in the order of left - top -
        // right - bottom, only the first non-zero value will be applied.
        if (insetsSize.left != 0) {
            inOutFrame.right = inOutFrame.left + insetsSize.left;
        } else if (insetsSize.top != 0) {
            inOutFrame.bottom = inOutFrame.top + insetsSize.top;
        } else if (insetsSize.right != 0) {
            inOutFrame.left = inOutFrame.right - insetsSize.right;
        } else if (insetsSize.bottom != 0) {
            inOutFrame.top = inOutFrame.bottom - insetsSize.bottom;
        } else {
            inOutFrame.setEmpty();
        }
    }

    /**
     * Class to describe the insets size to be provided to window with specific window type. If not
     * used, same insets size will be sent as instructed in the insetsSize and source.
     *
     * If the insetsSize of given type is set to {@code null}, the insets source frame will be used
     * directly for that window type.
     */
    public static class InsetsSizeOverride implements Parcelable {

        private final int mWindowType;
        private final Insets mInsetsSize;

        protected InsetsSizeOverride(Parcel in) {
            mWindowType = in.readInt();
            mInsetsSize = in.readParcelable(null, Insets.class);
        }

        public InsetsSizeOverride(int windowType, Insets insetsSize) {
            mWindowType = windowType;
            mInsetsSize = insetsSize;
        }
        public int getWindowType() {
            return mWindowType;
        }

        public Insets getInsetsSize() {
            return mInsetsSize;
        }

        public static final Creator<InsetsSizeOverride> CREATOR = new Creator<>() {
            @Override
            public InsetsSizeOverride createFromParcel(Parcel in) {
                return new InsetsSizeOverride(in);
            }

            @Override
            public InsetsSizeOverride[] newArray(int size) {
                return new InsetsSizeOverride[size];
            }
        };

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeInt(mWindowType);
            out.writeParcelable(mInsetsSize, flags);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(32);
            sb.append("TypedInsetsSize: {");
            sb.append("windowType=").append(ViewDebug.intToString(
                    WindowManager.LayoutParams.class, "type", mWindowType));
            sb.append(", insetsSize=").append(mInsetsSize);
            sb.append("}");
            return sb.toString();
        }

        @Override
        public int hashCode() {
            return Objects.hash(mWindowType, mInsetsSize);
        }
    }
}

