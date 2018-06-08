/*
 * Copyright 2018 The Android Open Source Project
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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.IntDef;
import android.media.update.ApiLoader;
import android.media.update.Rating2Provider;
import android.os.Bundle;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 * A class to encapsulate rating information used as content metadata.
 * A rating is defined by its rating style (see {@link #RATING_HEART},
 * {@link #RATING_THUMB_UP_DOWN}, {@link #RATING_3_STARS}, {@link #RATING_4_STARS},
 * {@link #RATING_5_STARS} or {@link #RATING_PERCENTAGE}) and the actual rating value (which may
 * be defined as "unrated"), both of which are defined when the rating instance is constructed
 * through one of the factory methods.
 */
// New version of Rating with following change
//   - Don't implement Parcelable for updatable support.
public final class Rating2 {
    /**
     * @hide
     */
    @IntDef({RATING_NONE, RATING_HEART, RATING_THUMB_UP_DOWN, RATING_3_STARS, RATING_4_STARS,
            RATING_5_STARS, RATING_PERCENTAGE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Style {}

    /**
     * @hide
     */
    @IntDef({RATING_3_STARS, RATING_4_STARS, RATING_5_STARS})
    @Retention(RetentionPolicy.SOURCE)
    public @interface StarStyle {}

    /**
     * Indicates a rating style is not supported. A Rating2 will never have this
     * type, but can be used by other classes to indicate they do not support
     * Rating2.
     */
    public final static int RATING_NONE = 0;

    /**
     * A rating style with a single degree of rating, "heart" vs "no heart". Can be used to
     * indicate the content referred to is a favorite (or not).
     */
    public final static int RATING_HEART = 1;

    /**
     * A rating style for "thumb up" vs "thumb down".
     */
    public final static int RATING_THUMB_UP_DOWN = 2;

    /**
     * A rating style with 0 to 3 stars.
     */
    public final static int RATING_3_STARS = 3;

    /**
     * A rating style with 0 to 4 stars.
     */
    public final static int RATING_4_STARS = 4;

    /**
     * A rating style with 0 to 5 stars.
     */
    public final static int RATING_5_STARS = 5;

    /**
     * A rating style expressed as a percentage.
     */
    public final static int RATING_PERCENTAGE = 6;

    private final Rating2Provider mProvider;

    /**
     * @hide
     */
    public Rating2(@NonNull Rating2Provider provider) {
        mProvider = provider;
    }

    @Override
    public String toString() {
        return mProvider.toString_impl();
    }

    /**
     * @hide
     */
    public Rating2Provider getProvider() {
        return mProvider;
    }

    @Override
    public boolean equals(Object obj) {
        return mProvider.equals_impl(obj);
    }

    @Override
    public int hashCode() {
        return mProvider.hashCode_impl();
    }

    /**
     * Create an instance from bundle object, previoulsy created by {@link #toBundle()}
     *
     * @param bundle bundle
     * @return new Rating2 instance or {@code null} for error
     */
    public static Rating2 fromBundle(@Nullable Bundle bundle) {
        return ApiLoader.getProvider().fromBundle_Rating2(bundle);
    }

    /**
     * Return bundle for this object to share across the process.
     * @return bundle of this object
     */
    public Bundle toBundle() {
        return mProvider.toBundle_impl();
    }

    /**
     * Return a Rating2 instance with no rating.
     * Create and return a new Rating2 instance with no rating known for the given
     * rating style.
     * @param ratingStyle one of {@link #RATING_HEART}, {@link #RATING_THUMB_UP_DOWN},
     *    {@link #RATING_3_STARS}, {@link #RATING_4_STARS}, {@link #RATING_5_STARS},
     *    or {@link #RATING_PERCENTAGE}.
     * @return null if an invalid rating style is passed, a new Rating2 instance otherwise.
     */
    public static @Nullable Rating2 newUnratedRating(@Style int ratingStyle) {
        return ApiLoader.getProvider().newUnratedRating_Rating2(ratingStyle);
    }

    /**
     * Return a Rating2 instance with a heart-based rating.
     * Create and return a new Rating2 instance with a rating style of {@link #RATING_HEART},
     * and a heart-based rating.
     * @param hasHeart true for a "heart selected" rating, false for "heart unselected".
     * @return a new Rating2 instance.
     */
    public static @Nullable Rating2 newHeartRating(boolean hasHeart) {
        return ApiLoader.getProvider().newHeartRating_Rating2(hasHeart);
    }

    /**
     * Return a Rating2 instance with a thumb-based rating.
     * Create and return a new Rating2 instance with a {@link #RATING_THUMB_UP_DOWN}
     * rating style, and a "thumb up" or "thumb down" rating.
     * @param thumbIsUp true for a "thumb up" rating, false for "thumb down".
     * @return a new Rating2 instance.
     */
    public static @Nullable Rating2 newThumbRating(boolean thumbIsUp) {
        return ApiLoader.getProvider().newThumbRating_Rating2(thumbIsUp);
    }

    /**
     * Return a Rating2 instance with a star-based rating.
     * Create and return a new Rating2 instance with one of the star-base rating styles
     * and the given integer or fractional number of stars. Non integer values can for instance
     * be used to represent an average rating value, which might not be an integer number of stars.
     * @param starRatingStyle one of {@link #RATING_3_STARS}, {@link #RATING_4_STARS},
     *     {@link #RATING_5_STARS}.
     * @param starRating a number ranging from 0.0f to 3.0f, 4.0f or 5.0f according to
     *     the rating style.
     * @return null if the rating style is invalid, or the rating is out of range,
     *     a new Rating2 instance otherwise.
     */
    public static @Nullable Rating2 newStarRating(
            @StarStyle int starRatingStyle, float starRating) {
        return ApiLoader.getProvider().newStarRating_Rating2(starRatingStyle, starRating);
    }

    /**
     * Return a Rating2 instance with a percentage-based rating.
     * Create and return a new Rating2 instance with a {@link #RATING_PERCENTAGE}
     * rating style, and a rating of the given percentage.
     * @param percent the value of the rating
     * @return null if the rating is out of range, a new Rating2 instance otherwise.
     */
    public static @Nullable Rating2 newPercentageRating(float percent) {
        return ApiLoader.getProvider().newPercentageRating_Rating2(percent);
    }

    /**
     * Return whether there is a rating value available.
     * @return true if the instance was not created with {@link #newUnratedRating(int)}.
     */
    public boolean isRated() {
        return mProvider.isRated_impl();
    }

    /**
     * Return the rating style.
     * @return one of {@link #RATING_HEART}, {@link #RATING_THUMB_UP_DOWN},
     *    {@link #RATING_3_STARS}, {@link #RATING_4_STARS}, {@link #RATING_5_STARS},
     *    or {@link #RATING_PERCENTAGE}.
     */
    public @Style int getRatingStyle() {
        return mProvider.getRatingStyle_impl();
    }

    /**
     * Return whether the rating is "heart selected".
     * @return true if the rating is "heart selected", false if the rating is "heart unselected",
     *    if the rating style is not {@link #RATING_HEART} or if it is unrated.
     */
    public boolean hasHeart() {
        return mProvider.hasHeart_impl();
    }

    /**
     * Return whether the rating is "thumb up".
     * @return true if the rating is "thumb up", false if the rating is "thumb down",
     *    if the rating style is not {@link #RATING_THUMB_UP_DOWN} or if it is unrated.
     */
    public boolean isThumbUp() {
        return mProvider.isThumbUp_impl();
    }

    /**
     * Return the star-based rating value.
     * @return a rating value greater or equal to 0.0f, or a negative value if the rating style is
     *    not star-based, or if it is unrated.
     */
    public float getStarRating() {
        return mProvider.getStarRating_impl();
    }

    /**
     * Return the percentage-based rating value.
     * @return a rating value greater or equal to 0.0f, or a negative value if the rating style is
     *    not percentage-based, or if it is unrated.
     */
    public float getPercentRating() {
        return mProvider.getPercentRating_impl();
    }
}
