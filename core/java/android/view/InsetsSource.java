/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.InsetsSourceProto.FRAME;
import static android.view.InsetsSourceProto.TYPE;
import static android.view.InsetsSourceProto.VISIBLE;
import static android.view.InsetsSourceProto.VISIBLE_FRAME;
import static android.view.ViewRootImpl.CAPTION_ON_SHELL;
import static android.view.WindowInsets.Type.ime;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.proto.ProtoOutputStream;
import android.view.WindowInsets.Type.InsetsType;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * Represents the state of a single entity generating insets for clients.
 * @hide
 */
public class InsetsSource implements Parcelable {

    /** The insets source ID of IME */
    public static final int ID_IME = createId(null, 0, ime());

    /**
     * An unique integer to identify this source across processes.
     */
    private final int mId;

    private final @InsetsType int mType;

    /** Frame of the source in screen coordinate space */
    private final Rect mFrame;
    private @Nullable Rect mVisibleFrame;

    private boolean mVisible;
    private boolean mInsetsRoundedCornerFrame;

    private final Rect mTmpFrame = new Rect();

    public InsetsSource(int id, @InsetsType int type) {
        mId = id;
        mType = type;
        mFrame = new Rect();
        mVisible = (WindowInsets.Type.defaultVisible() & type) != 0;
    }

    public InsetsSource(InsetsSource other) {
        mId = other.mId;
        mType = other.mType;
        mFrame = new Rect(other.mFrame);
        mVisible = other.mVisible;
        mVisibleFrame = other.mVisibleFrame != null
                ? new Rect(other.mVisibleFrame)
                : null;
        mInsetsRoundedCornerFrame = other.mInsetsRoundedCornerFrame;
    }

    public void set(InsetsSource other) {
        mFrame.set(other.mFrame);
        mVisible = other.mVisible;
        mVisibleFrame = other.mVisibleFrame != null
                ? new Rect(other.mVisibleFrame)
                : null;
        mInsetsRoundedCornerFrame = other.mInsetsRoundedCornerFrame;
    }

    public InsetsSource setFrame(int left, int top, int right, int bottom) {
        mFrame.set(left, top, right, bottom);
        return this;
    }

    public InsetsSource setFrame(Rect frame) {
        mFrame.set(frame);
        return this;
    }

    public InsetsSource setVisibleFrame(@Nullable Rect visibleFrame) {
        mVisibleFrame = visibleFrame != null ? new Rect(visibleFrame) : null;
        return this;
    }

    public InsetsSource setVisible(boolean visible) {
        mVisible = visible;
        return this;
    }

    public int getId() {
        return mId;
    }

    public @InsetsType int getType() {
        return mType;
    }

    public Rect getFrame() {
        return mFrame;
    }

    public @Nullable Rect getVisibleFrame() {
        return mVisibleFrame;
    }

    public boolean isVisible() {
        return mVisible;
    }

    boolean isUserControllable() {
        // If mVisibleFrame is null, it will be the same area as mFrame.
        return mVisibleFrame == null || !mVisibleFrame.isEmpty();
    }

    public boolean insetsRoundedCornerFrame() {
        return mInsetsRoundedCornerFrame;
    }

    public InsetsSource setInsetsRoundedCornerFrame(boolean insetsRoundedCornerFrame) {
        mInsetsRoundedCornerFrame = insetsRoundedCornerFrame;
        return this;
    }

    /**
     * Calculates the insets this source will cause to a client window.
     *
     * @param relativeFrame The frame to calculate the insets relative to.
     * @param ignoreVisibility If true, always reports back insets even if source isn't visible.
     * @return The resulting insets. The contract is that only one side will be occupied by a
     *         source.
     */
    public Insets calculateInsets(Rect relativeFrame, boolean ignoreVisibility) {
        return calculateInsets(relativeFrame, mFrame, ignoreVisibility);
    }

    /**
     * Like {@link #calculateInsets(Rect, boolean)}, but will return visible insets.
     */
    public Insets calculateVisibleInsets(Rect relativeFrame) {
        return calculateInsets(relativeFrame, mVisibleFrame != null ? mVisibleFrame : mFrame,
                false /* ignoreVisibility */);
    }

    private Insets calculateInsets(Rect relativeFrame, Rect frame, boolean ignoreVisibility) {
        if (!ignoreVisibility && !mVisible) {
            return Insets.NONE;
        }
        // During drag-move and drag-resizing, the caption insets position may not get updated
        // before the app frame get updated. To layout the app content correctly during drag events,
        // we always return the insets with the corresponding height covering the top.
        if (!CAPTION_ON_SHELL && getType() == WindowInsets.Type.captionBar()) {
            return Insets.of(0, frame.height(), 0, 0);
        }
        // Checks for whether there is shared edge with insets for 0-width/height window.
        final boolean hasIntersection = relativeFrame.isEmpty()
                ? getIntersection(frame, relativeFrame, mTmpFrame)
                : mTmpFrame.setIntersect(frame, relativeFrame);
        if (!hasIntersection) {
            return Insets.NONE;
        }

        // TODO: Currently, non-floating IME always intersects at bottom due to issues with cutout.
        // However, we should let the policy decide from the server.
        if (getType() == WindowInsets.Type.ime()) {
            return Insets.of(0, 0, 0, mTmpFrame.height());
        }

        // Intersecting at top/bottom
        if (mTmpFrame.width() == relativeFrame.width()) {
            if (mTmpFrame.top == relativeFrame.top) {
                return Insets.of(0, mTmpFrame.height(), 0, 0);
            } else if (mTmpFrame.bottom == relativeFrame.bottom) {
                return Insets.of(0, 0, 0, mTmpFrame.height());
            }
            // TODO: remove when insets are shell-customizable.
            // This is a hack that says "if this is a top-inset (eg statusbar), always apply it
            // to the top". It is used when adjusting primary split for IME.
            if (mTmpFrame.top == 0) {
                return Insets.of(0, mTmpFrame.height(), 0, 0);
            }
        }
        // Intersecting at left/right
        else if (mTmpFrame.height() == relativeFrame.height()) {
            if (mTmpFrame.left == relativeFrame.left) {
                return Insets.of(mTmpFrame.width(), 0, 0, 0);
            } else if (mTmpFrame.right == relativeFrame.right) {
                return Insets.of(0, 0, mTmpFrame.width(), 0);
            }
        }
        return Insets.NONE;
    }

    /**
     * Outputs the intersection of two rectangles. The shared edges will also be counted in the
     * intersection.
     *
     * @param a The first rectangle being intersected with.
     * @param b The second rectangle being intersected with.
     * @param out The rectangle which represents the intersection.
     * @return {@code true} if there is any intersection.
     */
    private static boolean getIntersection(@NonNull Rect a, @NonNull Rect b, @NonNull Rect out) {
        if (a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom) {
            out.left = Math.max(a.left, b.left);
            out.top = Math.max(a.top, b.top);
            out.right = Math.min(a.right, b.right);
            out.bottom = Math.min(a.bottom, b.bottom);
            return true;
        }
        out.setEmpty();
        return false;
    }

    /**
     * Creates an identifier of an {@link InsetsSource}.
     *
     * @param owner An object owned by the owner. Only the owner can modify its own sources.
     * @param index An owner may have multiple sources with the same type. For example, the system
     *              server might have multiple display cutout sources. This is used to identify
     *              which one is which. The value must be in a range of [0, 2047].
     * @param type The {@link WindowInsets.Type.InsetsType type} of the source.
     * @return a unique integer as the identifier.
     */
    public static int createId(Object owner, @IntRange(from = 0, to = 2047) int index,
            @InsetsType int type) {
        if (index < 0 || index >= 2048) {
            throw new IllegalArgumentException();
        }
        // owner takes top 16 bits;
        // index takes 11 bits since the 6th bit;
        // type takes bottom 5 bits.
        return (((owner != null ? owner.hashCode() : 1) % (1 << 16)) << 16)
                + (index << 5)
                + WindowInsets.Type.indexOf(type);
    }

    /**
     * Export the state of {@link InsetsSource} into a protocol buffer output stream.
     *
     * @param proto   Stream to write the state to
     * @param fieldId FieldId of InsetsSource as defined in the parent message
     */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(TYPE, WindowInsets.Type.toString(mType));
        mFrame.dumpDebug(proto, FRAME);
        if (mVisibleFrame != null) {
            mVisibleFrame.dumpDebug(proto, VISIBLE_FRAME);
        }
        proto.write(VISIBLE, mVisible);
        proto.end(token);
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("InsetsSource id="); pw.print(Integer.toHexString(mId));
        pw.print(" type="); pw.print(WindowInsets.Type.toString(mType));
        pw.print(" frame="); pw.print(mFrame.toShortString());
        if (mVisibleFrame != null) {
            pw.print(" visibleFrame="); pw.print(mVisibleFrame.toShortString());
        }
        pw.print(" visible="); pw.print(mVisible);
        pw.print(" insetsRoundedCornerFrame="); pw.print(mInsetsRoundedCornerFrame);
        pw.println();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return equals(o, false);
    }

    /**
     * @param excludeInvisibleImeFrames If {@link WindowInsets.Type#ime()} frames should be ignored
     *                                  when IME is not visible.
     */
    public boolean equals(@Nullable Object o, boolean excludeInvisibleImeFrames) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        InsetsSource that = (InsetsSource) o;

        if (mId != that.mId) return false;
        if (mType != that.mType) return false;
        if (mVisible != that.mVisible) return false;
        if (excludeInvisibleImeFrames && !mVisible && mType == WindowInsets.Type.ime()) return true;
        if (!Objects.equals(mVisibleFrame, that.mVisibleFrame)) return false;
        if (mInsetsRoundedCornerFrame != that.mInsetsRoundedCornerFrame) return false;
        return mFrame.equals(that.mFrame);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mType, mFrame, mVisibleFrame, mVisible, mInsetsRoundedCornerFrame);
    }

    public InsetsSource(Parcel in) {
        mId = in.readInt();
        mType = in.readInt();
        mFrame = Rect.CREATOR.createFromParcel(in);
        if (in.readInt() != 0) {
            mVisibleFrame = Rect.CREATOR.createFromParcel(in);
        } else {
            mVisibleFrame = null;
        }
        mVisible = in.readBoolean();
        mInsetsRoundedCornerFrame = in.readBoolean();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mType);
        mFrame.writeToParcel(dest, 0);
        if (mVisibleFrame != null) {
            dest.writeInt(1);
            mVisibleFrame.writeToParcel(dest, 0);
        } else {
            dest.writeInt(0);
        }
        dest.writeBoolean(mVisible);
        dest.writeBoolean(mInsetsRoundedCornerFrame);
    }

    @Override
    public String toString() {
        return "InsetsSource: {" + Integer.toHexString(mId)
                + " mType=" + WindowInsets.Type.toString(mType)
                + " mFrame=" + mFrame.toShortString()
                + " mVisible=" + mVisible
                + (mInsetsRoundedCornerFrame ? " insetsRoundedCornerFrame" : "")
                + "}";
    }

    public static final @NonNull Creator<InsetsSource> CREATOR = new Creator<>() {

        public InsetsSource createFromParcel(Parcel in) {
            return new InsetsSource(in);
        }

        public InsetsSource[] newArray(int size) {
            return new InsetsSource[size];
        }
    };
}
