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
import android.annotation.SuppressLint;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;

import com.android.window.flags.Flags;

/**
 * Threshold values that are sent with
 * {@link android.view.WindowManager#registerTrustedPresentationListener(IBinder,
 * TrustedPresentationThresholds, Executor, Consumer)}
 */
@FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
public final class TrustedPresentationThresholds implements Parcelable {
    /**
     * The min alpha the {@link SurfaceControl} is required to have to be considered inside the
     * threshold.
     */
    @FloatRange(from = 0f, fromInclusive = false, to = 1f)
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    @SuppressLint("InternalField") // simple data class
    public final float minAlpha;

    /**
     * The min fraction of the SurfaceControl that was presented to the user to be considered
     * inside the threshold.
     */
    @FloatRange(from = 0f, fromInclusive = false, to = 1f)
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    @SuppressLint("InternalField") // simple data class
    public final float minFractionRendered;

    /**
     * The time in milliseconds required for the {@link SurfaceControl} to be in the threshold.
     */
    @IntRange(from = 1)
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    @SuppressLint("InternalField") // simple data class
    public final int stabilityRequirementMs;

    private void checkValid() {
        if (minAlpha <= 0 || minFractionRendered <= 0 || stabilityRequirementMs < 1) {
            throw new IllegalArgumentException(
                    "TrustedPresentationThresholds values are invalid");
        }
    }

    /**
     * Creates a new TrustedPresentationThresholds.
     *
     * @param minAlpha               The min alpha the {@link SurfaceControl} is required to
     *                               have to be considered inside the
     *                               threshold.
     * @param minFractionRendered    The min fraction of the SurfaceControl that was presented
     *                               to the user to be considered
     *                               inside the threshold.
     * @param stabilityRequirementMs The time in milliseconds required for the
     *                               {@link SurfaceControl} to be in the threshold.
     */
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public TrustedPresentationThresholds(
            @FloatRange(from = 0f, fromInclusive = false, to = 1f) float minAlpha,
            @FloatRange(from = 0f, fromInclusive = false, to = 1f) float minFractionRendered,
            @IntRange(from = 1) int stabilityRequirementMs) {
        this.minAlpha = minAlpha;
        this.minFractionRendered = minFractionRendered;
        this.stabilityRequirementMs = stabilityRequirementMs;
        checkValid();
    }

    @Override
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public String toString() {
        return "TrustedPresentationThresholds { "
                + "minAlpha = " + minAlpha + ", "
                + "minFractionRendered = " + minFractionRendered + ", "
                + "stabilityRequirementMs = " + stabilityRequirementMs
                + " }";
    }

    @Override
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeFloat(minAlpha);
        dest.writeFloat(minFractionRendered);
        dest.writeInt(stabilityRequirementMs);
    }

    @Override
    @FlaggedApi(Flags.FLAG_TRUSTED_PRESENTATION_LISTENER_FOR_WINDOW)
    public int describeContents() {
        return 0;
    }

    /**
     * @hide
     */
    TrustedPresentationThresholds(@NonNull Parcel in) {
        minAlpha = in.readFloat();
        minFractionRendered = in.readFloat();
        stabilityRequirementMs = in.readInt();

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
