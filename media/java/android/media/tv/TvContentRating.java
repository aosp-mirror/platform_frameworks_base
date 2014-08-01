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

import android.annotation.SystemApi;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A class representing a TV content rating.
 * When a TV input service provides the content rating information of a program into TV provider,
 * TvContentRating class will be used for generating the value of {@link
 * TvContract.Programs#COLUMN_CONTENT_RATING}. To create an object of {@link TvContentRating}, use
 * the {@link #createRating} method with valid arguments. The arguments could be a system defined
 * strings, or a TV input service defined strings.
 * TV input service defined strings are in an xml file defined in <code>&lt;{@link
 * android.R.styleable#TvInputService tv-input}&gt;</code> with the {@link
 * android.R.attr#contentRatingSystemXml contentRatingSystemXml} attribute by the TV input service.
 *
 * <h3> Content Rating System XML format </h3>
 * The XML file for publishing content rating system should follow the DTD bellow:
 * <p><pre class="prettyprint">
 * &lt;?xml version="1.0" encoding="UTF-8"?&gt;
 * &lt;!DOCTYPE rating-systems [
 *     &lt;!ELEMENT rating-system-definitions (rating-system-definition+)&gt;
 *     &lt;!ELEMENT rating-system-definition (
 *         (sub-rating-definition*, rating-definition, sub-rating-definition*)+, order*)&gt;
 *     &lt;!ATTLIST rating-system-definition
 *         id          ID    #REQUIRED
 *         displayName CDATA #IMPLIED
 *         description CDATA #IMPLIED
 *         country     CDATA #IMPLIED&gt;
 *     &lt;!ELEMENT sub-rating-definition EMPTY&gt;
 *     &lt;!ATTLIST sub-rating-definition
 *         id          ID    #REQUIRED
 *         displayName CDATA #IMPLIED
 *         icon        CDATA #IMPLIED
 *         description CDATA #IMPLIED&gt;
 *     &lt;!ELEMENT rating-definition (sub-rating*))&gt;
 *     &lt;!ATTLIST rating-definition
 *         id          ID    #REQUIRED
 *         displayName CDATA #IMPLIED
 *         icon        CDATA #IMPLIED
 *         description CDATA #IMPLIED&gt;
 *     &lt;!ELEMENT sub-rating EMPTY&gt;
 *     &lt;!ATTLIST sub-rating id IDREF #REQUIRED&gt;
 *     &lt;!ELEMENT order (rating, rating+)&gt;
 *     &lt;!ELEMENT rating EMPTY&gt;
 *     &lt;!ATTLIST rating id IDREF #REQUIRED&gt;
 * ]&gt;
 * </pre></p>
 *
 * <h3>System defined rating strings</h3>
 *
 * <u>System defined string for {@code domain}</u>
 * <table border="0" cellspacing="0" cellpadding="0">
 *     <tr>
 *         <td>String value</td>
 *         <td>Comments</td>
 *     </tr>
 *     <tr>
 *         <td>android.media.tv</td>
 *         <td>Used for creating system defined content ratings</td>
 *     </tr>
 * </table>
 * <u>System defined string for {@code ratingSystem}</u>
 * <table border="0" cellspacing="0" cellpadding="0">
 *     <tr>
 *         <td>String value</td>
 *         <td>Comments</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG</td>
 *         <td>The TV Parental Guidelines for US TV content ratings</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV</td>
 *         <td>The South Korean television rating system</td>
 *     </tr>
 * </table>
 *
 * <u>System defined string for {@code rating}</u>
 * <table border="0" cellspacing="0" cellpadding="0">
 *     <tr>
 *         <td>String value</td>
 *         <td>Comments</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_TV_Y</td>
 *         <td>A rating string for the US_TVPG domain. Programs rated this are designed to be
 *         appropriate for all children. Whether animated or live-action, the themes and elements
 *         in this program are specifically designed for a very young audience, including children
 *         from ages 2-6. This program is not expected to frighten younger children.</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_TV_Y7</td>
 *         <td>A rating string for the US_TVPG domain. Programs rated this are designed for children
 *         age 7 and above. It may be more appropriate for children who have acquired the
 *         developmental skills needed to distinguish between make-believe and reality. Themes and
 *         elements in this program may include mild fantasy violence or comedic violence, or may
 *         frighten children under the age of 7. Therefore, parents may wish to consider the
 *         suitability of this program for their very young children. This rating may contain
 *         fantasy violence (US_TVPG_FV) when programs are generally more intense or more combative
 *         than other programs in this category.</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_TV_G</td>
 *         <td>A rating string for the US_TVPG domain. Most parents would find this program suitable
 *         for all ages. Although this rating does not signify a program designed specifically for
 *         children, most parents may let younger children watch this program unattended. It
 *         contains little or no violence, no strong language and little or no sexual dialogue or
 *         situations.</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_TV_PG</td>
 *         <td>A rating string for the US_TVPG domain. Programs rated this contain material that
 *         parents may find unsuitable for younger children. Many parents may want to watch it with
 *         their younger children. The theme itself may call for parental guidance and/or the
 *         program may contain one or more of the following: some suggestive dialogue (US_TVPG_D),
 *         infrequent coarse language (US_TVPG_L), some sexual situations (US_TVPG_S), or moderate
 *         violence (US_TVPG_V).</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_TV_14</td>
 *         <td>A rating string for the US_TVPG domain. Programs rated this contains some material
 *         that many parents would find unsuitable for children under 14 years of age. Parents are
 *         strongly urged to exercise greater care in monitoring this program and are cautioned
 *         against letting children under the age of 14 watch unattended. This program may contain
 *         one or more of the following: intensely suggestive dialogue (US_TVPG_D), strong coarse
 *         language (US_TVPG_L), intense sexual situations (US_TVPG_S), or intense violence
 *         (US_TVPG_V).</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_TV_MA</td>
 *         <td>A rating string for the US_TVPG domain. Programs rated TV-MA are specifically
 *         designed to be viewed by adults and therefore may be unsuitable for children under 17.
 *         This program may contain one or more of the following: crude indecent language
 *         (US_TVPG_L), explicit sexual activity (US_TVPG_S), or graphic violence (US_TVPG_V).</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_ALL</td>
 *         <td>A rating string for the KR_TV domain. This rating is for programs that are
 *         appropriate for all ages. This program usually involves programs designed for children or
 *         families. This rating does not have an icon.</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_7</td>
 *         <td>A rating string for the KR_TV domain. This rating is for programs that may contain
 *         material inappropriate for children younger than 7, and parental discretion should be
 *         used. Some cartoon programs not deemed strictly as "educational", and films rated "G" or
 *         "PG" in North America may fall into the 7 category.</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_12</td>
 *         <td>A rating string for the KR_TV domain. This rating is for programs that may deemed
 *         inappropriate for those younger than 12, and parental discretion should be used. Usually
 *         used for animations that have stronger themes or violence then those designed for
 *         children, or for reality shows that have mild violence, themes, or language.</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_15</td>
 *         <td>A rating string for the KR_TV domain. This rating is for programs that contain
 *         material that may be inappropriate for children under 15, and that parental discretion
 *         should be used. Examples include most dramas, and talk shows on OTA (over-the-air) TV
 *         (KBS, MBC, SBS), and many American TV shows/dramas on Cable TV channels like OCN and
 *         OnStyle. The programs that have this rating may include moderate or strong adult themes,
 *         language, sexual inference, and violence. As with the TV-14 rating in North America, this
 *         rating is commonly applied to live events where the occurrence of inappropriate dialogue
 *         is unpredictable. Since 2007, this rating is the most used rating for TV.</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_19</td>
 *         <td>A rating string for the KR_TV domain. This rating is for programs that are intended
 *         for adults only. 19-rated programs cannot air during the hours of 7:00AM to 9:00AM, and
 *         1:00PM to 10:00PM. Programs that receive this rating will almost certainly have adult
 *         themes, sexual situations, frequent use of strong language and disturbing scenes of
 *         violence.</td>
 *     </tr>
 * </table>
 *
 * <u>System defined string for {@code subRating}</u>
 * <table border="0" cellspacing="0" cellpadding="0">
 *     <tr>
 *         <td>String value</td>
 *         <td>Comments</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_D</td>
 *         <td>Suggestive dialogue (Not used with US_TVPG_TV_MA)</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_L</td>
 *         <td>Coarse language</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_S</td>
 *         <td>Sexual content</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_V</td>
 *         <td>Violence</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_FV</td>
 *         <td>Fantasy violence (exclusive to US_TVPG_TV_Y7)</td>
 *     </tr>
 * </table>
 */
public final class TvContentRating {
    private static final String TAG = "TvContentRating";

    /** @hide */
    public static final Uri SYSTEM_CONTENT_RATING_SYSTEM_XML = Uri.parse(
            "android.resource://system/" + com.android.internal.R.xml.tv_content_rating_systems);

    // TODO: Consider to use other DELIMITER. In some countries such as India may use this delimiter
    // in the main ratings.
    private static final String DELIMITER = "/";

    private final String mDomain;
    private final String mCountryCode;
    private final String mRatingSystem;
    private final String mRating;
    private final String[] mSubRatings;

    /**
     * Creates a TvContentRating object.
     *
     * @param domain The domain name.
     * @param countryCode The country code in ISO 3166-2 format or {@code null}.
     * @param ratingSystem The rating system id.
     * @param rating The content rating string.
     * @param subRatings The string array of sub-ratings.
     * @return A TvContentRating object, or null if creation failed.
     */
    public static TvContentRating createRating(String domain, String countryCode,
            String ratingSystem, String rating, String... subRatings) {
        if (TextUtils.isEmpty(domain)) {
            throw new IllegalArgumentException("domain cannot be empty");
        }
        if (TextUtils.isEmpty(rating)) {
            throw new IllegalArgumentException("rating cannot be empty");
        }
        return new TvContentRating(domain, countryCode, ratingSystem, rating, subRatings);
    }

    /**
     * Recovers a TvContentRating from a String that was previously created with
     * {@link #flattenToString}.
     *
     * @param ratingString The String that was returned by flattenToString().
     * @return a new TvContentRating containing the domain, countryCode, rating system, rating and
     *         sub-ratings information was encoded in {@code ratingString}.
     * @see #flattenToString
     */
    public static TvContentRating unflattenFromString(String ratingString) {
        if (TextUtils.isEmpty(ratingString)) {
            throw new IllegalArgumentException("ratingString cannot be empty");
        }
        String[] strs = ratingString.split(DELIMITER);
        if (strs.length < 4) {
            throw new IllegalArgumentException("Invalid rating string: " + ratingString);
        }
        if (strs.length > 4) {
            String[] subRatings = new String[strs.length - 4];
            System.arraycopy(strs, 4, subRatings, 0, subRatings.length);
            return new TvContentRating(strs[0], strs[1], strs[2], strs[3], subRatings);
        }
        return new TvContentRating(strs[0], strs[1], strs[2], strs[3], null);
    }

    /**
     * Constructs a TvContentRating object from a given rating and sub-rating constants.
     *
     * @param rating The rating constant defined in this class.
     * @param subRatings The String array of sub-rating constants defined in this class.
     */
    private TvContentRating(String domain, String countryCode,
            String ratingSystem, String rating, String[] subRatings) {
        mDomain = domain;
        mCountryCode = countryCode;
        mRatingSystem = ratingSystem;
        mRating = rating;
        mSubRatings = subRatings;
    }

    /**
     * Returns the domain.
     */
    public String getDomain() {
        return mDomain;
    }

    /**
     * Returns the country code in ISO 3166-2 format or {@code null}.
     */
    public String getCountry() {
        return mCountryCode;
    }

    /**
     * Returns the rating system id.
     */
    public String getRatingSystem() {
        return mRatingSystem;
    }

    /**
     * Returns the main rating.
     */
    public String getMainRating() {
        return mRating;
    }

    /**
     * Returns the unmodifiable {@code List} of sub-rating strings.
     */
    public List<String> getSubRatings() {
        if (mSubRatings == null) {
            return null;
        }
        return Collections.unmodifiableList(Arrays.asList(mSubRatings));
    }


    /**
     * Returns a String that unambiguously describes both the rating and sub-rating information
     * contained in the TvContentRating. You can later recover the TvContentRating from this string
     * through {@link #unflattenFromString}.
     *
     * @return a new String holding rating/sub-rating information, which can later be stored in the
     *         database and settings.
     * @see #unflattenFromString
     */
    public String flattenToString() {
        StringBuilder builder = new StringBuilder();
        builder.append(mDomain);
        builder.append(DELIMITER);
        builder.append(mCountryCode);
        builder.append(DELIMITER);
        builder.append(mRatingSystem);
        builder.append(DELIMITER);
        builder.append(mRating);
        if (mSubRatings != null) {
            for (String subRating : mSubRatings) {
                builder.append(DELIMITER);
                builder.append(subRating);
            }
        }
        return builder.toString();
    }

    /**
     * Returns true if this rating has the same main rating as the specified rating and when this
     * rating's sub-ratings contain the other's.
     * <p>
     * For example, a TvContentRating object that represents TV-PG with S(Sexual content) and
     * V(Violence) contains TV-PG, TV-PG/S, TV-PG/V and itself.
     * </p>
     *
     * @param rating The {@link TvContentRating} to check.
     * @return {@code true} if this object contains {@code rating}, {@code false} otherwise.
     * @hide
     */
    @SystemApi
    public final boolean contains(TvContentRating rating) {
        if (rating == null) {
            throw new IllegalArgumentException("rating cannot be null");
        }
        if (!rating.getMainRating().equals(mRating)) {
            return false;
        }
        List<String> subRatings = getSubRatings();
        List<String> subRatingsOther = rating.getSubRatings();
        if (subRatings == null && subRatingsOther == null) {
            return true;
        } else if (subRatings == null && subRatingsOther != null) {
            return false;
        } else if (subRatings != null && subRatingsOther == null) {
            return true;
        } else {
            return subRatings.containsAll(subRatingsOther);
        }
    }
}
