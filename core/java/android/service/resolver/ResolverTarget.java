/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.service.resolver;

import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import java.util.Map;

/**
 * A ResolverTarget contains features by which an app or option will be ranked, in
 * {@link ResolverRankerService}.
 * @hide
 */
@SystemApi
public final class ResolverTarget implements Parcelable {
    private static final String TAG = "ResolverTarget";

    /**
     * a float score for recency of last use.
     */
    private float mRecencyScore;

    /**
     * a float score for total time spent.
     */
    private float mTimeSpentScore;

    /**
     * a float score for number of launches.
     */
    private float mLaunchScore;

    /**
     * a float score for number of selected.
     */
    private float mChooserScore;

    /**
     * a float score for the probability to be selected.
     */
    private float mSelectProbability;

    // constructor for the class.
    public ResolverTarget() {}

    ResolverTarget(Parcel in) {
        mRecencyScore = in.readFloat();
        mTimeSpentScore = in.readFloat();
        mLaunchScore = in.readFloat();
        mChooserScore = in.readFloat();
        mSelectProbability = in.readFloat();
    }

    /**
     * Gets the score for how recently the target was used in the foreground.
     *
     * @return a float score whose range is [0, 1]. The higher the score is, the more recently the
     * target was used.
     */
    public float getRecencyScore() {
        return mRecencyScore;
    }

    /**
     * Sets the score for how recently the target was used in the foreground.
     *
     * @param recencyScore a float score whose range is [0, 1]. The higher the score is, the more
     *                     recently the target was used.
     */
    public void setRecencyScore(float recencyScore) {
        this.mRecencyScore = recencyScore;
    }

    /**
     * Gets the score for how long the target has been used in the foreground.
     *
     * @return a float score whose range is [0, 1]. The higher the score is, the longer the target
     * has been used for.
     */
    public float getTimeSpentScore() {
        return mTimeSpentScore;
    }

    /**
     * Sets the score for how long the target has been used in the foreground.
     *
     * @param timeSpentScore a float score whose range is [0, 1]. The higher the score is, the
     *                       longer the target has been used for.
     */
    public void setTimeSpentScore(float timeSpentScore) {
        this.mTimeSpentScore = timeSpentScore;
    }

    /**
     * Gets the score for how many times the target has been launched to the foreground.
     *
     * @return a float score whose range is [0, 1]. The higher the score is, the more times the
     * target has been launched.
     */
    public float getLaunchScore() {
        return mLaunchScore;
    }

    /**
     * Sets the score for how many times the target has been launched to the foreground.
     *
     * @param launchScore a float score whose range is [0, 1]. The higher the score is, the more
     *                    times the target has been launched.
     */
    public void setLaunchScore(float launchScore) {
        this.mLaunchScore = launchScore;
    }

    /**
     * Gets the score for how many times the target has been selected by the user to share the same
     * types of content.
     *
     * @return a float score whose range is [0, 1]. The higher the score is, the
     * more times the target has been selected by the user to share the same types of content for.
     */
    public float getChooserScore() {
        return mChooserScore;
    }

    /**
     * Sets the score for how many times the target has been selected by the user to share the same
     * types of content.
     *
     * @param chooserScore a float score whose range is [0, 1]. The higher the score is, the more
     *                     times the target has been selected by the user to share the same types
     *                     of content for.
     */
    public void setChooserScore(float chooserScore) {
        this.mChooserScore = chooserScore;
    }

    /**
     * Gets the probability of how likely this target will be selected by the user.
     *
     * @return a float score whose range is [0, 1]. The higher the score is, the more likely the
     * user is going to select this target.
     */
    public float getSelectProbability() {
        return mSelectProbability;
    }

    /**
     * Sets the probability for how like this target will be selected by the user.
     *
     * @param selectProbability a float score whose range is [0, 1]. The higher the score is, the
     *                          more likely tht user is going to select this target.
     */
    public void setSelectProbability(float selectProbability) {
        this.mSelectProbability = selectProbability;
    }

    // serialize the class to a string.
    @Override
    public String toString() {
        return "ResolverTarget{"
                + mRecencyScore + ", "
                + mTimeSpentScore + ", "
                + mLaunchScore + ", "
                + mChooserScore + ", "
                + mSelectProbability + "}";
    }

    // describes the kinds of special objects contained in this Parcelable instance's marshaled
    // representation.
    @Override
    public int describeContents() {
        return 0;
    }

    // flattens this object in to a Parcel.
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeFloat(mRecencyScore);
        dest.writeFloat(mTimeSpentScore);
        dest.writeFloat(mLaunchScore);
        dest.writeFloat(mChooserScore);
        dest.writeFloat(mSelectProbability);
    }

    // creator definition for the class.
    public static final @android.annotation.NonNull Creator<ResolverTarget> CREATOR
            = new Creator<ResolverTarget>() {
        @Override
        public ResolverTarget createFromParcel(Parcel source) {
            return new ResolverTarget(source);
        }

        @Override
        public ResolverTarget[] newArray(int size) {
            return new ResolverTarget[size];
        }
    };
}
