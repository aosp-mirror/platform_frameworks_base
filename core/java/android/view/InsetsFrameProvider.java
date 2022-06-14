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

import android.graphics.Insets;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Insets provided by a window.
 *
 * The insets frame will by default as the window frame size. If the providers are set, the
 * calculation result based on the source size will be used as the insets frame.
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
    private static final int HAS_IME_INSETS_SIZE = 2;

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
     * The provided frame based on the source frame. The result will be used as the insets
     * size to IME window. Only one side should be set.
     */
    public Insets imeInsetsSize = null;

    public InsetsFrameProvider(int type) {
        this(type, SOURCE_FRAME, null, null);
    }

    public InsetsFrameProvider(int type, Insets insetsSize) {
        this(type, SOURCE_FRAME, insetsSize, insetsSize);
    }

    public InsetsFrameProvider(int type, Insets insetsSize, Insets imeInsetsSize) {
        this(type, SOURCE_FRAME, insetsSize, imeInsetsSize);
    }

    public InsetsFrameProvider(int type, int source, Insets insetsSize,
            Insets imeInsetsSize) {
        this.type = type;
        this.source = source;
        this.insetsSize = insetsSize;
        this.imeInsetsSize = imeInsetsSize;
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
        if (imeInsetsSize != null) {
            sb.append(", imeInsetsSize=").append(imeInsetsSize);
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
        if ((insetsSizeModified & HAS_IME_INSETS_SIZE) != 0) {
            imeInsetsSize = Insets.CREATOR.createFromParcel(in);
        }
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        int insetsSizeModified = 0;
        if (insetsSize != null) {
            insetsSizeModified |= HAS_INSETS_SIZE;
        }
        if (imeInsetsSize != null) {
            insetsSizeModified |= HAS_IME_INSETS_SIZE;
        }
        out.writeInt(insetsSizeModified);
        out.writeInt(type);
        out.writeInt(source);
        if (insetsSize != null) {
            insetsSize.writeToParcel(out, flags);
        }
        if (imeInsetsSize != null) {
            imeInsetsSize.writeToParcel(out, flags);
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
                && Objects.equals(imeInsetsSize, other.imeInsetsSize);
    }

    @Override
    public int hashCode() {
        int result = type;
        result = 31 * result + source;
        result = 31 * result + (insetsSize != null ? insetsSize.hashCode() : 0);
        result = 31 * result + (imeInsetsSize != null ? imeInsetsSize.hashCode() : 0);
        return result;
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
}

