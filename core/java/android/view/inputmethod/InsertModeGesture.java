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

package android.view.inputmethod;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.graphics.PointF;
import android.os.CancellationSignal;
import android.os.CancellationSignalBeamer;
import android.os.Parcel;
import android.os.Parcelable;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * A sub-class of {@link HandwritingGesture} for starting an insert mode which inserts a space in
 * the editor to let users hand write freely at the designated insertion point.
 * This class holds the information required for insertion of text in
 * toolkit widgets like {@link TextView}.
 *
 * Once InsertMode gesture is started, it continues until IME calls
 * {@link CancellationSignal#cancel()} and toolkit can receive cancel using
 * {@link CancellationSignal#setOnCancelListener(CancellationSignal.OnCancelListener)} obtained from
 * {@link #getCancellationSignal()}.
 */
public final class InsertModeGesture extends CancellableHandwritingGesture implements Parcelable {

    private PointF mPoint;

    private InsertModeGesture(PointF point, String fallbackText,
            CancellationSignal cancellationSignal) {
        mType = GESTURE_TYPE_INSERT_MODE;
        mPoint = point;
        mFallbackText = fallbackText;
        mCancellationSignal = cancellationSignal;
    }

    private InsertModeGesture(final Parcel source) {
        mType = GESTURE_TYPE_INSERT_MODE;
        mFallbackText = source.readString8();
        mPoint = source.readTypedObject(PointF.CREATOR);
        mCancellationSignalToken = source.readStrongBinder();
    }

    /**
     * Returns the {@link CancellationSignal} associated with finishing this gesture.
     * Once InsertMode gesture is started, it continues until IME calls
     * {@link CancellationSignal#cancel()} and toolkit can receive cancel using
     * {@link CancellationSignal#setOnCancelListener(CancellationSignal.OnCancelListener)}.
     */
    @Override
    @NonNull
    public CancellationSignal getCancellationSignal() {
        return mCancellationSignal;
    }

    /**
     * Returns the insertion point {@link PointF} (in screen coordinates) where space will be
     * created for additional text to be inserted.
     */
    @NonNull
    public PointF getInsertionPoint() {
        return mPoint;
    }

    /**
     * Builder for {@link InsertModeGesture}. This class is not designed to be thread-safe.
     */
    public static final class Builder {
        private PointF mPoint;
        private String mFallbackText;
        // TODO(b/254727073): implement CancellationSignal
        private CancellationSignal mCancellationSignal;

        /**
         * Sets the insertion point (in screen coordinates) where space will be created for
         * additional text to be inserted.
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setInsertionPoint(@NonNull PointF point) {
            mPoint = point;
            return this;
        }

        /**
         * Sets the {@link CancellationSignal} used to cancel the ongoing gesture.
         * @param cancellationSignal signal to cancel an ongoing gesture.
         */
        @NonNull
        @SuppressLint("MissingGetterMatchingBuilder")
        public Builder setCancellationSignal(@NonNull CancellationSignal cancellationSignal) {
            mCancellationSignal = cancellationSignal;
            return this;
        }

        /**
         * Set fallback text that will be committed at current cursor position if there is no
         * applicable text beneath the area of gesture.
         * @param fallbackText text to set
         */
        @NonNull
        public Builder setFallbackText(@Nullable String fallbackText) {
            mFallbackText = fallbackText;
            return this;
        }

        /**
         * Returns {@link InsertModeGesture} using parameters in this
         * {@link InsertModeGesture.Builder}.
         * @throws IllegalArgumentException if one or more positional parameters are not specified.
         */
        @NonNull
        public InsertModeGesture build() {
            if (mPoint == null) {
                throw new IllegalArgumentException("Insertion point must be set.");
            } else if (mCancellationSignal == null) {
                throw new IllegalArgumentException("CancellationSignal must be set.");
            }
            return new InsertModeGesture(mPoint, mFallbackText, mCancellationSignal);
        }
    }

    /**
     * Used to make this class parcelable.
     */
    @NonNull
    public static final Creator<InsertModeGesture> CREATOR = new Creator<>() {
        @Override
        public InsertModeGesture createFromParcel(Parcel source) {
            return new InsertModeGesture(source);
        }

        @Override
        public InsertModeGesture[] newArray(int size) {
            return new InsertModeGesture[size];
        }
    };

    @Override
    public int hashCode() {
        return Objects.hash(mPoint, mFallbackText);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InsertModeGesture)) return false;

        InsertModeGesture that = (InsertModeGesture) o;

        if (!Objects.equals(mFallbackText, that.mFallbackText)) return false;
        return Objects.equals(mPoint, that.mPoint);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Used to package this object into a {@link Parcel}.
     *
     * @param dest The {@link Parcel} to be written.
     * @param flags The flags used for parceling.
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mFallbackText);
        dest.writeTypedObject(mPoint, flags);
        dest.writeStrongBinder(CancellationSignalBeamer.Sender.beamFromScope(mCancellationSignal));
    }
}
