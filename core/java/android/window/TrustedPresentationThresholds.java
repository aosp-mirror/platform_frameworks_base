/*
 * Copyright 2023 The Android Open Source Project
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

package android.window;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.android.window.flags.Flags;

import java.util.Objects;

/**
 * Threshold values that are sent with
 * {@link android.view.WindowManager#registerTrustedPresentationListener(IBinder,
 * TrustedPresentationThresholds, Executor, Consumer)}
 */
@FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
public final class TrustedPresentationThresholds implements Parcelable {
    /**
     * The min alpha the Window is required to have to be considered inside the
     * threshold.
     */
    @FloatRange(from = 0f, fromInclusive = false, to = 1f)
    private final float mMinAlpha;

    /**
     * The min fraction of the Window that was presented to the user to be considered
     * inside the threshold.
     */
    @FloatRange(from = 0f, fromInclusive = false, to = 1f)
    private final float mMinFractionRendered;

    /**
     * The time in milliseconds required for the Window to be in the threshold.
     */
    @IntRange(from = 1)
    private final int mStabilityRequirementMs;

    /**
     * The min alpha the Window is required to have to be considered inside the
     * threshold.
     */
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public @FloatRange(from = 0f, fromInclusive = false, to = 1f) float getMinAlpha() {
        return mMinAlpha;
    }

    /**
     * The min fraction of the Window that was presented to the user to be considered
     * inside the threshold.
     */
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public @FloatRange(from = 0f, fromInclusive = false, to = 1f) float getMinFractionRendered() {
        return mMinFractionRendered;
    }

    /**
     * The time in milliseconds required for the Window to be in the threshold.
     */
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public @IntRange(from = 1) int getStabilityRequirementMillis() {
        return mStabilityRequirementMs;
    }

    private void checkValid() {
        if (mMinAlpha <= 0 || mMinFractionRendered <= 0 || mStabilityRequirementMs < 1) {
            throw new IllegalArgumentException(
                    "TrustedPresentationThresholds values are invalid");
        }
    }

    /**
     * Creates a new TrustedPresentationThresholds.
     *
     * @param minAlpha               The min alpha the Window is required to
     *                               have to be considered inside the
     *                               threshold.
     * @param minFractionRendered    The min fraction of the Window that was presented
     *                               to the user to be considered
     *                               inside the threshold.
     * @param stabilityRequirementMs The time in milliseconds required for the
     *                               Window to be in the threshold.
     */
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public TrustedPresentationThresholds(
            @FloatRange(from = 0f, fromInclusive = false, to = 1f) float minAlpha,
            @FloatRange(from = 0f, fromInclusive = false, to = 1f) float minFractionRendered,
            @IntRange(from = 1) int stabilityRequirementMs) {
        this.mMinAlpha = minAlpha;
        this.mMinFractionRendered = minFractionRendered;
        this.mStabilityRequirementMs = stabilityRequirementMs;
        checkValid();
    }

    @Override
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public String toString() {
        return "TrustedPresentationThresholds { "
                + "minAlpha = " + mMinAlpha + ", "
                + "minFractionRendered = " + mMinFractionRendered + ", "
                + "stabilityRequirementMs = " + mStabilityRequirementMs
                + " }";
    }

    @Override
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeFloat(mMinAlpha);
        dest.writeFloat(mMinFractionRendered);
        dest.writeInt(mStabilityRequirementMs);
    }

    @Override
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public int describeContents() {
        return 0;
    }


    @Override
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public int hashCode() {
        return Objects.hash(mMinAlpha, mMinFractionRendered, mStabilityRequirementMs);
    }

    @Override
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TrustedPresentationThresholds that)) {
            return false;
        }
        return mMinAlpha == that.mMinAlpha
                && mMinFractionRendered == that.mMinFractionRendered
                && mStabilityRequirementMs == that.mStabilityRequirementMs;
    }

    /**
     * @hide
     */
    TrustedPresentationThresholds(@NonNull Parcel in) {
        mMinAlpha = in.readFloat();
        mMinFractionRendered = in.readFloat();
        mStabilityRequirementMs = in.readInt();

        checkValid();
    }

    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public static final @NonNull Creator<TrustedPresentationThresholds> CREATOR =
            new Creator<TrustedPresentationThresholds>() {
                @Override
                @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
                public TrustedPresentationThresholds[] newArray(int size) {
                    return new TrustedPresentationThresholds[size];
                }

                @Override
                @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
                public TrustedPresentationThresholds createFromParcel(@NonNull Parcel in) {
                    return new TrustedPresentationThresholds(in);
                }
            };
}
