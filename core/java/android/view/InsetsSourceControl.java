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

import static android.graphics.PointProto.X;
import static android.graphics.PointProto.Y;
import static android.view.InsetsSourceControlProto.LEASH;
import static android.view.InsetsSourceControlProto.POSITION;
import static android.view.InsetsSourceControlProto.TYPE;

import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Point;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsState.InternalInsetsType;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Represents a parcelable object to allow controlling a single {@link InsetsSource}.
 * @hide
 */
public class InsetsSourceControl implements Parcelable {

    private final @InternalInsetsType int mType;
    private final @Nullable SurfaceControl mLeash;
    private final boolean mInitiallyVisible;
    private final Point mSurfacePosition;

    // This is used while playing an insets animation regardless of the relative frame. This would
    // be the insets received by the bounds of its source window.
    private Insets mInsetsHint;

    private boolean mSkipAnimationOnce;
    private int mParcelableFlags;

    public InsetsSourceControl(@InternalInsetsType int type, @Nullable SurfaceControl leash,
            boolean initiallyVisible, Point surfacePosition, Insets insetsHint) {
        mType = type;
        mLeash = leash;
        mInitiallyVisible = initiallyVisible;
        mSurfacePosition = surfacePosition;
        mInsetsHint = insetsHint;
    }

    public InsetsSourceControl(InsetsSourceControl other) {
        mType = other.mType;
        if (other.mLeash != null) {
            mLeash = new SurfaceControl(other.mLeash, "InsetsSourceControl");
        } else {
            mLeash = null;
        }
        mInitiallyVisible = other.mInitiallyVisible;
        mSurfacePosition = new Point(other.mSurfacePosition);
        mInsetsHint = other.mInsetsHint;
        mSkipAnimationOnce = other.getAndClearSkipAnimationOnce();
    }

    public InsetsSourceControl(Parcel in) {
        mType = in.readInt();
        mLeash = in.readTypedObject(SurfaceControl.CREATOR);
        mInitiallyVisible = in.readBoolean();
        mSurfacePosition = in.readTypedObject(Point.CREATOR);
        mInsetsHint = in.readTypedObject(Insets.CREATOR);
        mSkipAnimationOnce = in.readBoolean();
    }

    public int getType() {
        return mType;
    }

    /**
     * Gets the leash for controlling insets source. If the system is controlling the insets source,
     * for example, transient bars, the client will receive fake controls without leash in it.
     *
     * @return the leash.
     */
    public @Nullable SurfaceControl getLeash() {
        return mLeash;
    }

    public boolean isInitiallyVisible() {
        return mInitiallyVisible;
    }

    public boolean setSurfacePosition(int left, int top) {
        if (mSurfacePosition.equals(left, top)) {
            return false;
        }
        mSurfacePosition.set(left, top);
        return true;
    }

    public Point getSurfacePosition() {
        return mSurfacePosition;
    }

    public void setInsetsHint(Insets insets) {
        mInsetsHint = insets;
    }

    public void setInsetsHint(int left, int top, int right, int bottom) {
        mInsetsHint = Insets.of(left, top, right, bottom);
    }

    public Insets getInsetsHint() {
        return mInsetsHint;
    }

    public void setSkipAnimationOnce(boolean skipAnimation) {
        mSkipAnimationOnce = skipAnimation;
    }

    /**
     * Get the state whether the current control needs to skip animation or not.
     *
     * Note that this is a one-time check that the state is only valid and can be called when
     * {@link InsetsController#applyAnimation} to check if the current control can skip animation
     * at this time, and then will clear the state value.
     */
    public boolean getAndClearSkipAnimationOnce() {
        final boolean result = mSkipAnimationOnce;
        mSkipAnimationOnce = false;
        return result;
    }

    public void setParcelableFlags(int parcelableFlags) {
        mParcelableFlags = parcelableFlags;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeTypedObject(mLeash, mParcelableFlags);
        dest.writeBoolean(mInitiallyVisible);
        dest.writeTypedObject(mSurfacePosition, mParcelableFlags);
        dest.writeTypedObject(mInsetsHint, mParcelableFlags);
        dest.writeBoolean(mSkipAnimationOnce);
    }

    public void release(Consumer<SurfaceControl> surfaceReleaseConsumer) {
        if (mLeash != null) {
            surfaceReleaseConsumer.accept(mLeash);
        }
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final InsetsSourceControl that = (InsetsSourceControl) o;
        final SurfaceControl thatLeash = that.mLeash;
        return mType == that.mType
                && ((mLeash == thatLeash)
                        || (mLeash != null && thatLeash != null && mLeash.isSameSurface(thatLeash)))
                && mInitiallyVisible == that.mInitiallyVisible
                && mSurfacePosition.equals(that.mSurfacePosition)
                && mInsetsHint.equals(that.mInsetsHint)
                && mSkipAnimationOnce == that.mSkipAnimationOnce;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mLeash, mInitiallyVisible, mSurfacePosition, mInsetsHint,
                mSkipAnimationOnce);
    }

    @Override
    public String toString() {
        return "InsetsSourceControl: {"
                + "type=" + InsetsState.typeToString(mType)
                + ", mSurfacePosition=" + mSurfacePosition
                + ", mInsetsHint=" + mInsetsHint
                + "}";
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("InsetsSourceControl type="); pw.print(InsetsState.typeToString(mType));
        pw.print(" mLeash="); pw.print(mLeash);
        pw.print(" mInitiallyVisible="); pw.print(mInitiallyVisible);
        pw.print(" mSurfacePosition="); pw.print(mSurfacePosition);
        pw.print(" mInsetsHint="); pw.print(mInsetsHint);
        pw.print(" mSkipAnimationOnce="); pw.print(mSkipAnimationOnce);
        pw.println();
    }

    public static final @android.annotation.NonNull Creator<InsetsSourceControl> CREATOR
            = new Creator<InsetsSourceControl>() {
        public InsetsSourceControl createFromParcel(Parcel in) {
            return new InsetsSourceControl(in);
        }

        public InsetsSourceControl[] newArray(int size) {
            return new InsetsSourceControl[size];
        }
    };

    /**
     * Export the state of {@link InsetsSourceControl} into a protocol buffer output stream.
     *
     * @param proto   Stream to write the state to
     * @param fieldId FieldId of InsetsSource as defined in the parent message
     */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(TYPE, InsetsState.typeToString(mType));

        final long surfaceToken = proto.start(POSITION);
        proto.write(X, mSurfacePosition.x);
        proto.write(Y, mSurfacePosition.y);
        proto.end(surfaceToken);

        if (mLeash != null) {
            mLeash.dumpDebug(proto, LEASH);
        }
        proto.end(token);
    }
}
