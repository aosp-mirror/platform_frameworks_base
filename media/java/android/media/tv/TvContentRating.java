/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.tv;

import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A class representing a TV content rating.
 */
public class TvContentRating {
    private static final String TAG = "TvContentRating";

    private static final int RATING_PREFIX_LENGTH = 10;
    private static final String PREFIX_RATING_US = "RATING_US_";
    private static final String PREFIX_SUBRATING_US = "SUBRATING_US_";

    /**
     * Rating constant for TV-Y from the TV Parental Guidelines system in US. This program is
     * designed to be appropriate for all children.
     */
    public static final String RATING_US_TV_Y = PREFIX_RATING_US + "TV_Y";
    /**
     * Rating constant for TV-Y7 from the TV Parental Guidelines system in US. This program is
     * designed for children age 7 and above.
     */
    public static final String RATING_US_TV_Y7 = PREFIX_RATING_US + "TV_Y7";
    /**
     * Rating constant for TV-G from the TV Parental Guidelines system in US. Most parents would
     * find this program suitable for all ages.
     */
    public static final String RATING_US_TV_G = PREFIX_RATING_US + "TV_G";
    /**
     * Rating constant for TV-PG from the TV Parental Guidelines system in US. This program contains
     * material that parents may find unsuitable for younger children.
     */
    public static final String RATING_US_TV_PG = PREFIX_RATING_US + "TV_PG";
    /**
     * Rating constant for TV-14 from the TV Parental Guidelines system in US. This program contains
     * some material that many parents would find unsuitable for children under 14 years of age.
     */
    public static final String RATING_US_TV_14 = PREFIX_RATING_US + "TV_14";
    /**
     * Rating constant for TV-MA from the TV Parental Guidelines system in US. This program is
     * specifically designed to be viewed by adults and therefore may be unsuitable for children
     * under 17.
     */
    public static final String RATING_US_TV_MA = PREFIX_RATING_US + "TV_MA";

    /**
     * Sub-rating constant for D (Suggestive dialogue) from the TV Parental Guidelines system in US.
     */
    public static final String SUBRATING_US_D = PREFIX_SUBRATING_US + "D";
    /**
     * Sub-rating constant for L (Coarse language) from the TV Parental Guidelines system in US.
     */
    public static final String SUBRATING_US_L = PREFIX_SUBRATING_US + "L";
    /**
     * Sub-rating constant for S (Sexual content) from the TV Parental Guidelines system in US.
     */
    public static final String SUBRATING_US_S = PREFIX_SUBRATING_US + "S";
    /**
     * Sub-rating constant for V (Violence) from the TV Parental Guidelines system in US.
     */
    public static final String SUBRATING_US_V = PREFIX_SUBRATING_US + "V";
    /**
     * Sub-rating constant for FV (Fantasy violence) from the TV Parental Guidelines system in US.
     */
    public static final String SUBRATING_US_FV = PREFIX_SUBRATING_US + "FV";

    private static final String PREFIX_RATING_KR = "RATING_KR_";

    /**
     * Rating constant for 'ALL' from the South Korean television rating system. This rating is for
     * programming that is appropriate for all ages.
     */
    public static final String RATING_KR_ALL = PREFIX_RATING_KR + "ALL";
    /**
     * Rating constant for '7' from the South Korean television rating system. This rating is for
     * programming that may contain material inappropriate for children younger than 7, and parental
     * discretion should be used.
     */
    public static final String RATING_KR_7 = PREFIX_RATING_KR + "7";
    /**
     * Rating constant for '12' from the South Korean television rating system. This rating is for
     * programs that may deemed inappropriate for those younger than 12, and parental discretion
     * should be used.
     */
    public static final String RATING_KR_12 = PREFIX_RATING_KR + "12";
    /**
     * Rating constant for '15' from the South Korean television rating system. This rating is for
     * programs that contain material that may be inappropriate for children under 15, and that
     * parental discretion should be used.
     */
    public static final String RATING_KR_15 = PREFIX_RATING_KR + "15";
    /**
     * Rating constant for '19' from the South Korean television rating system. This rating is for
     * programs that are intended for adults only. 19-rated programming cannot air during the hours
     * of 7:00AM to 9:00AM, and 1:00PM to 10:00PM.
     */
    public static final String RATING_KR_19 = PREFIX_RATING_KR + "19";

    private static final String DELIMITER = "/";

    // A mapping from two-letter country code (ISO 3166-1 alpha-2) to its rating-to-sub-ratings map.
    // This is used for validating the builder parameters.
    private static final Map<String, Map<String, String[]>> sRatings
            = new HashMap<String, Map<String, String[]>>();

    static {
        Map<String, String[]> usRatings = new HashMap<String, String[]>();
        usRatings.put(RATING_US_TV_Y, null);
        usRatings.put(RATING_US_TV_Y7, new String[] { SUBRATING_US_FV });
        usRatings.put(RATING_US_TV_G, null);
        usRatings.put(RATING_US_TV_PG, new String[] {
                SUBRATING_US_D, SUBRATING_US_L, SUBRATING_US_S, SUBRATING_US_V });
        usRatings.put(RATING_US_TV_14, new String[] {
                SUBRATING_US_D, SUBRATING_US_L, SUBRATING_US_S, SUBRATING_US_V });
        usRatings.put(RATING_US_TV_MA, new String[] {
                SUBRATING_US_L, SUBRATING_US_S, SUBRATING_US_V });
        sRatings.put(PREFIX_RATING_US, usRatings);

        Map<String, String[]> krRatings = new HashMap<String, String[]>();
        krRatings.put(RATING_KR_ALL, null);
        krRatings.put(RATING_KR_7, null);
        krRatings.put(RATING_KR_12, null);
        krRatings.put(RATING_KR_15, null);
        krRatings.put(RATING_KR_19, null);
        sRatings.put(PREFIX_RATING_KR, krRatings);
    }

    private final String mRating;
    private final String[] mSubRatings;

    /**
     * Constructs a TvContentRating object from a given rating constant.
     *
     * @param rating The rating constant defined in this class.
     */
    public TvContentRating(String rating) {
        mRating = rating;
        mSubRatings = null;
    }

    /**
     * Constructs a TvContentRating object from a given rating and sub-rating constants.
     *
     * @param rating The rating constant defined in this class.
     * @param subRatings The String array of sub-rating constants defined in this class.
     */
    public TvContentRating(String rating, String[] subRatings) {
        mRating = rating;
        mSubRatings = subRatings;
        if (TextUtils.isEmpty(mRating)) {
            throw new IllegalArgumentException("rating cannot be null");
        }
        String prefix = "";
        if (mRating.length() > RATING_PREFIX_LENGTH) {
            prefix = mRating.substring(0, RATING_PREFIX_LENGTH);
        }
        Map<String, String[]> ratings = sRatings.get(prefix);
        if (ratings != null) {
            if (!ratings.keySet().contains(mRating)) {
                Log.w(TAG, "Unknown rating: " + mRating);
            } else if (mSubRatings != null) {
                String[] validSubRatings = ratings.get(mRating);
                if (validSubRatings == null) {
                    Log.w(TAG, "Invalid subratings: " + mSubRatings);
                } else {
                    List<String> validSubRatingList = Arrays.asList(subRatings);
                    for (String sr : mSubRatings) {
                        if (!validSubRatingList.contains(sr)) {
                            Log.w(TAG, "Invalid subrating: " + sr);
                            break;
                        }
                    }
                }
            }
        } else {
            Log.w(TAG, "Rating undefined for " + mRating);
        }
    }

    /**
     * Recovers a TvContentRating from a String that was previously created with
     * {@link #flattenToString}.
     *
     * @param ratingString The String that was returned by flattenToString().
     * @return a new TvContentRating containing the rating and sub-ratings information was encoded
     *         in {@code ratingString}.
     * @see #flattenToString
     */
    public static TvContentRating unflattenFromString(String ratingString) {
        if (TextUtils.isEmpty(ratingString)) {
            throw new IllegalArgumentException("Empty rating string");
        }
        String[] strs = ratingString.split(DELIMITER);
        if (strs.length < 1) {
            throw new IllegalArgumentException("Invalid rating string: " + ratingString);
        }
        if (strs.length > 1) {
            String[] subRatings = new String[strs.length - 1];
            System.arraycopy(strs, 1, subRatings, 0, subRatings.length);
            return new TvContentRating(strs[0], subRatings);
        }
        return new TvContentRating(strs[0]);
    }

    /**
     * @return a String that unambiguously describes both the rating and sub-rating information
     *         contained in the TvContentRating. You can later recover the TvContentRating from this
     *         string through {@link #unflattenFromString}.
     * @see #unflattenFromString
     */
    public String flattenToString() {
        StringBuffer ratingStr = new StringBuffer();
        ratingStr.append(mRating);
        if (mSubRatings != null) {
            for (String subRating : mSubRatings) {
                ratingStr.append(DELIMITER);
                ratingStr.append(subRating);
            }
        }
        return ratingStr.toString();
    }
}
