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

import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

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

    private static Rect sTmpRect = new Rect();
    private static Rect sTmpRect2 = new Rect();

    /**
     * The type of insets to provide.
     */
    public @InsetsState.InternalInsetsType int type;

    /**
     * The source of frame. By default, all adjustment will be based on the window frame, it
     * can be set to window bounds or display bounds instead.
     */
    public int source = SOURCE_FRAME;

    /**
     * The provided insets size based on the source frame. The result will be used as the insets
     * size to windows other than IME. Only one side should be set.
     *
     * For example, when the given source frame is (0, 0) - (100, 200), and the insetsSize is null,
     * the source frame will be directly used as the final insets frame. If the insetsSize is set to
     * (0, 0, 0, 50) instead, the insets frame will be a frame starting from the bottom side of the
     * source frame with height of 50, i.e., (0, 150) - (100, 200).
     */
    public Insets insetsSize = null;

    /**
     * If null, the size set in insetsSize will be applied to all window types. If it contains
     * element of some types, the insets reported to the window with that types will be overridden.
     */
    public InsetsSizeOverride[] insetsSizeOverrides = null;

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
    public Insets minimalInsetsSizeInDisplayCutoutSafe = null;

    public InsetsFrameProvider(int type) {
        this(type, SOURCE_FRAME, null, null);
    }

    public InsetsFrameProvider(int type, Insets insetsSize) {
        this(type, SOURCE_FRAME, insetsSize, null);
    }

    public InsetsFrameProvider(int type, int source, Insets insetsSize,
            InsetsSizeOverride[] insetsSizeOverride) {
        this.type = type;
        this.source = source;
        this.insetsSize = insetsSize;
        this.insetsSizeOverrides = insetsSizeOverride;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(32);
        sb.append("InsetsFrameProvider: {");
        sb.append("type=").append(InsetsState.typeToString(type));
        sb.append(", source=");
        switch (source) {
            case SOURCE_DISPLAY:
                sb.append("SOURCE_DISPLAY");
                break;
            case SOURCE_CONTAINER_BOUNDS:
                sb.append("SOURCE_CONTAINER_BOUNDS Bounds");
                break;
            case SOURCE_FRAME:
                sb.append("SOURCE_FRAME");
                break;
        }
        if (insetsSize != null) {
            sb.append(", insetsSize=").append(insetsSize);
        }
        if (insetsSizeOverrides != null) {
            sb.append(", insetsSizeOverrides=").append(Arrays.toString(insetsSizeOverrides));
        }
        sb.append("}");
        return sb.toString();
    }

    public InsetsFrameProvider(Parcel in) {
        int insetsSizeModified = in.readInt();
        type = in.readInt();
        source = in.readInt();
        if ((insetsSizeModified & HAS_INSETS_SIZE) != 0) {
            insetsSize = Insets.CREATOR.createFromParcel(in);
        }
        if ((insetsSizeModified & HAS_INSETS_SIZE_OVERRIDE) != 0) {
            insetsSizeOverrides = in.createTypedArray(InsetsSizeOverride.CREATOR);
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        int insetsSizeModified = 0;
        if (insetsSize != null) {
            insetsSizeModified |= HAS_INSETS_SIZE;
        }
        if (insetsSizeOverrides != null) {
            insetsSizeModified |= HAS_INSETS_SIZE_OVERRIDE;
        }
        out.writeInt(insetsSizeModified);
        out.writeInt(type);
        out.writeInt(source);
        if (insetsSize != null) {
            insetsSize.writeToParcel(out, flags);
        }
        if (insetsSizeOverrides != null) {
            out.writeTypedArray(insetsSizeOverrides, flags);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        InsetsFrameProvider other = (InsetsFrameProvider) o;
        return type == other.type && source == other.source
                && Objects.equals(insetsSize, other.insetsSize)
                && Arrays.equals(insetsSizeOverrides, other.insetsSizeOverrides);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, source, insetsSize, Arrays.hashCode(insetsSizeOverrides));
    }

    public static final @android.annotation.NonNull Parcelable.Creator<InsetsFrameProvider>
            CREATOR = new Parcelable.Creator<InsetsFrameProvider>() {
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
            Insets displayCutoutSafeInsetsSize) {
        boolean extendByCutout = false;
        if (source == InsetsFrameProvider.SOURCE_DISPLAY) {
            inOutFrame.set(displayFrame);
        } else if (source == InsetsFrameProvider.SOURCE_CONTAINER_BOUNDS) {
            inOutFrame.set(containerBounds);
        } else {
            extendByCutout = (privateFlags & PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT) != 0;
        }
        if (insetsSize == null) {
            return;
        }
        if (displayCutoutSafeInsetsSize != null) {
            sTmpRect2.set(inOutFrame);
        }
        calculateInsetsFrame(inOutFrame, insetsSize);

        if (extendByCutout) {
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
        public final int windowType;
        public Insets insetsSize;

        protected InsetsSizeOverride(Parcel in) {
            windowType = in.readInt();
            insetsSize = in.readParcelable(null, Insets.class);
        }

        public InsetsSizeOverride(int type, Insets size) {
            windowType = type;
            insetsSize = size;
        }

        public static final Creator<InsetsSizeOverride> CREATOR =
                new Creator<InsetsSizeOverride>() {
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
            out.writeInt(windowType);
            out.writeParcelable(insetsSize, flags);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(32);
            sb.append("TypedInsetsSize: {");
            sb.append("windowType=").append(ViewDebug.intToString(
                    WindowManager.LayoutParams.class, "type", windowType));
            sb.append(", insetsSize=").append(insetsSize);
            sb.append("}");
            return sb.toString();
        }

        @Override
        public int hashCode() {
            return Objects.hash(windowType, insetsSize);
        }
    }
}

