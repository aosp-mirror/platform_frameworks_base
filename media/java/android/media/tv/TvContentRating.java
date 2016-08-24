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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.text.TextUtils;

import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A class representing a TV content rating. When a TV input service inserts the content rating
 * information on a program into the database, this class can be used to generate the formatted
 * string for
 * {@link TvContract.Programs#COLUMN_CONTENT_RATING TvContract.Programs.COLUMN_CONTENT_RATING}.
 * To create a {@code TvContentRating} object, use the
 * {@link #createRating TvContentRating.createRating} method with valid rating system string
 * constants.
 *
 * <p>It is possible for an application to define its own content rating system by supplying a
 * content rating system definition XML resource (see example below) and declaring a broadcast
 * receiver that filters {@link TvInputManager#ACTION_QUERY_CONTENT_RATING_SYSTEMS} in its manifest.
 *
 * <h3> Example: Rating system definition for the TV Parental Guidelines</h3>
 * The following XML example shows how the TV Parental Guidelines in the United States can be
 * defined:
 * <p><pre class="prettyprint">
 * {@literal
 * <rating-system-definitions xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:versionCode="1">
 *     <rating-system-definition android:name="US_TV"
 *         android:country="US"
 *         android:description="@string/description_us_tv">
 *         <sub-rating-definition android:name="US_TV_D"
 *             android:title="D"
 *             android:description="@string/description_us_tv_d" />
 *         <sub-rating-definition android:name="US_TV_L"
 *             android:title="L"
 *             android:description="@string/description_us_tv_l" />
 *         <sub-rating-definition android:name="US_TV_S"
 *             android:title="S"
 *             android:description="@string/description_us_tv_s" />
 *         <sub-rating-definition android:name="US_TV_V"
 *             android:title="V"
 *             android:description="@string/description_us_tv_v" />
 *         <sub-rating-definition android:name="US_TV_FV"
 *             android:title="FV"
 *             android:description="@string/description_us_tv_fv" />
 *
 *         <rating-definition android:name="US_TV_Y"
 *             android:title="TV-Y"
 *             android:description="@string/description_us_tv_y"
 *             android:icon="@drawable/icon_us_tv_y"
 *             android:contentAgeHint="0" />
 *         <rating-definition android:name="US_TV_Y7"
 *             android:title="TV-Y7"
 *             android:description="@string/description_us_tv_y7"
 *             android:icon="@drawable/icon_us_tv_y7"
 *             android:contentAgeHint="7">
 *             <sub-rating android:name="US_TV_FV" />
 *         </rating-definition>
 *         <rating-definition android:name="US_TV_G"
 *             android:title="TV-G"
 *             android:description="@string/description_us_tv_g"
 *             android:icon="@drawable/icon_us_tv_g"
 *             android:contentAgeHint="0" />
 *         <rating-definition android:name="US_TV_PG"
 *             android:title="TV-PG"
 *             android:description="@string/description_us_tv_pg"
 *             android:icon="@drawable/icon_us_tv_pg"
 *             android:contentAgeHint="14">
 *             <sub-rating android:name="US_TV_D" />
 *             <sub-rating android:name="US_TV_L" />
 *             <sub-rating android:name="US_TV_S" />
 *             <sub-rating android:name="US_TV_V" />
 *         </rating-definition>
 *         <rating-definition android:name="US_TV_14"
 *             android:title="TV-14"
 *             android:description="@string/description_us_tv_14"
 *             android:icon="@drawable/icon_us_tv_14"
 *             android:contentAgeHint="14">
 *             <sub-rating android:name="US_TV_D" />
 *             <sub-rating android:name="US_TV_L" />
 *             <sub-rating android:name="US_TV_S" />
 *             <sub-rating android:name="US_TV_V" />
 *         </rating-definition>
 *         <rating-definition android:name="US_TV_MA"
 *             android:title="TV-MA"
 *             android:description="@string/description_us_tv_ma"
 *             android:icon="@drawable/icon_us_tv_ma"
 *             android:contentAgeHint="17">
 *             <sub-rating android:name="US_TV_L" />
 *             <sub-rating android:name="US_TV_S" />
 *             <sub-rating android:name="US_TV_V" />
 *         </rating-definition>
 *         <rating-order>
 *             <rating android:name="US_TV_Y" />
 *             <rating android:name="US_TV_Y7" />
 *         </rating-order>
 *         <rating-order>
 *             <rating android:name="US_TV_G" />
 *             <rating android:name="US_TV_PG" />
 *             <rating android:name="US_TV_14" />
 *             <rating android:name="US_TV_MA" />
 *         </rating-order>
 *     </rating-system-definition>
 * </rating-system-definitions>}</pre>
 *
 * <h3>System defined rating strings</h3>
 * The following strings are defined by the system to provide a standard way to create
 * {@code TvContentRating} objects.
 *
 * <p>For example, to create an object that represents TV-PG rating with suggestive dialogue and
 * coarse language from the TV Parental Guidelines in the United States, one can use the following
 * code snippet:
 *
 * <pre>
 * TvContentRating rating = TvContentRating.createRating(
 *         "com.android.tv",
 *         "US_TV",
 *         "US_TV_PG",
 *         "US_TV_D", "US_TV_L");
 * </pre>
 * <h4>System defined string for domains</h4>
 * <table>
 *     <tr>
 *         <th>Constant Value</th>
 *         <th>Description</th>
 *     </tr>
 *     <tr>
 *         <td>com.android.tv</td>
 *         <td>Used for creating system defined content ratings</td>
 *     </tr>
 * </table>
 *
 * <h4>System defined strings for rating systems</h4>
 * <table>
 *     <tr>
 *         <th>Constant Value</th>
 *         <th>Description</th>
 *     </tr>
 *     <tr>
 *         <td>AR_TV</td>
 *         <td>TV content rating system for Argentina</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV</td>
 *         <td>TV content rating system for Australia</td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV</td>
 *         <td>TV content rating system for Brazil</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_EN</td>
 *         <td>TV content rating system for Canada (English)</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_FR</td>
 *         <td>TV content rating system for Canada (French)</td>
 *     </tr>
 *     <tr>
 *         <td>DVB</td>
 *         <td>DVB content rating system</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB</td>
 *         <td>DVB content rating system for Spain</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB</td>
 *         <td>DVB content rating system for France</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB</td>
 *         <td>ISDB content rating system</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV</td>
 *         <td>TV content rating system for South Korea</td>
 *     </tr>
 *     <tr>
 *         <td>SG_TV</td>
 *         <td>TV content rating system for Singapore</td>
 *     </tr>
 *     <tr>
 *         <td>US_MV</td>
 *         <td>Movie content rating system for the United States</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV</td>
 *         <td>TV content rating system for the United States</td>
 *     </tr>
 * </table>
 *
 * <h4>System defined strings for ratings</h4>
 * <table>
 *     <tr>
 *         <th>Rating System</th>
 *         <th>Constant Value</th>
 *         <th>Description</th>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="4">AR_TV</td>
 *         <td>AR_TV_ATP</td>
 *         <td>Suitable for all audiences. Programs may contain mild violence, language and mature
 *         situations</td>
 *     </tr>
 *     <tr>
 *         <td>AR_TV_SAM_13</td>
 *         <td>Suitable for ages 13 and up. Programs may contain mild to moderate language and mild
 *         violence and sexual references</td>
 *     </tr>
 *     <tr>
 *         <td>AR_TV_SAM_16</td>
 *         <td>Suitable for ages 16 and up. Programs may contain more intensive violence and coarse
 *         language, partial nudity and moderate sexual references</td>
 *     </tr>
 *     <tr>
 *         <td>AR_TV_SAM_18</td>
 *         <td>Suitable for mature audiences only. Programs contain strong violence, coarse language
 *         and explicit sexual references</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="8">AU_TV</td>
 *         <td>AU_TV_P</td>
 *         <td>Recommended for younger children aged between 2 and 11 years</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_C</td>
 *         <td>Recommended for older children aged between 5 and 14 years</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_G</td>
 *         <td>Recommended for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_PG</td>
 *         <td>Parental guidance is recommended for young viewers under 15</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_M</td>
 *         <td>Recommended for mature audiences aged 15 years and over</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_MA</td>
 *         <td>Not suitable for children and teens under 15, due to sexual descriptions, course
 *         language, adult themes or drug use</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_AV</td>
 *         <td>Not suitable for children and teens under 15. This category is used specifically for
 *         violent programs</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_R</td>
 *         <td>Not for children under 18. Content may include graphic violence, sexual situations,
 *         coarse language and explicit drug use</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">BR_TV</td>
 *         <td>BR_TV_L</td>
 *         <td>Content is suitable for all audiences</td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_10</td>
 *         <td>Content suitable for viewers over the age of 10</td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_12</td>
 *         <td>Content suitable for viewers over the age of 12</td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_14</td>
 *         <td>Content suitable for viewers over the age of 14</td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_16</td>
 *         <td>Content suitable for viewers over the age of 16</td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_18</td>
 *         <td>Content suitable for viewers over the age of 18</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="7">CA_TV_EN</td>
 *         <td>CA_TV_EN_EXEMPT</td>
 *         <td>Exempt from ratings</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_EN_C</td>
 *         <td>Suitable for children ages 2&#8211;7</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_EN_C8</td>
 *         <td>Suitable for children ages 8 and older</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_EN_G</td>
 *         <td>Suitable for the entire family</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_EN_PG</td>
 *         <td>May contain moderate violence, profanity, nudity, and sexual references</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_EN_14</td>
 *         <td>Intended for viewers ages 14 and older</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_EN_18</td>
 *         <td>Intended for viewers ages 18 and older</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">CA_TV_FR</td>
 *         <td>CA_TV_FR_E</td>
 *         <td>Exempt from ratings</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_FR_G</td>
 *         <td>Appropriate for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_FR_8</td>
 *         <td>Appropriate for children 8</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_FR_13</td>
 *         <td>Suitable for children 13</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_FR_16</td>
 *         <td>Recommended for children over the age of 16</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_FR_18</td>
 *         <td>Only to be viewed by adults</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="15">DVB</td>
 *         <td>DVB_4</td>
 *         <td>Recommended for ages 4 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_5</td>
 *         <td>Recommended for ages 5 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_6</td>
 *         <td>Recommended for ages 6 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_7</td>
 *         <td>Recommended for ages 7 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_8</td>
 *         <td>Recommended for ages 8 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_9</td>
 *         <td>Recommended for ages 9 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_10</td>
 *         <td>Recommended for ages 10 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_11</td>
 *         <td>Recommended for ages 11 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_12</td>
 *         <td>Recommended for ages 12 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_13</td>
 *         <td>Recommended for ages 13 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_14</td>
 *         <td>Recommended for ages 14 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_15</td>
 *         <td>Recommended for ages 15 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_16</td>
 *         <td>Recommended for ages 16 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_17</td>
 *         <td>Recommended for ages 17 and over</td>
 *     </tr>
 *     <tr>
 *         <td>DVB_18</td>
 *         <td>Recommended for ages 18 and over</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="18">ES_DVB</td>
 *         <td>ES_DVB_ALL</td>
 *         <td>Recommended for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_C</td>
 *         <td>Recommended for children</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_X</td>
 *         <td>Recommended for adults</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_4</td>
 *         <td>Recommended for ages 4 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_5</td>
 *         <td>Recommended for ages 5 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_6</td>
 *         <td>Recommended for ages 6 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_7</td>
 *         <td>Recommended for ages 7 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_8</td>
 *         <td>Recommended for ages 8 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_9</td>
 *         <td>Recommended for ages 9 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_10</td>
 *         <td>Recommended for ages 10 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_11</td>
 *         <td>Recommended for ages 11 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_12</td>
 *         <td>Recommended for ages 12 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_13</td>
 *         <td>Recommended for ages 13 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_14</td>
 *         <td>Recommended for ages 14 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_15</td>
 *         <td>Recommended for ages 15 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_16</td>
 *         <td>Recommended for ages 16 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_17</td>
 *         <td>Recommended for ages 17 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ES_DVB_18</td>
 *         <td>Recommended for ages 18 and over</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="16">FR_DVB</td>
 *         <td>FR_DVB_U</td>
 *         <td>Recommended for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_4</td>
 *         <td>Recommended for ages 4 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_5</td>
 *         <td>Recommended for ages 5 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_6</td>
 *         <td>Recommended for ages 6 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_7</td>
 *         <td>Recommended for ages 7 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_8</td>
 *         <td>Recommended for ages 8 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_9</td>
 *         <td>Recommended for ages 9 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_10</td>
 *         <td>Recommended for ages 10 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_11</td>
 *         <td>Recommended for ages 11 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_12</td>
 *         <td>Recommended for ages 12 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_13</td>
 *         <td>Recommended for ages 13 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_14</td>
 *         <td>Recommended for ages 14 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_15</td>
 *         <td>Recommended for ages 15 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_16</td>
 *         <td>Recommended for ages 16 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_17</td>
 *         <td>Recommended for ages 17 and over</td>
 *     </tr>
 *     <tr>
 *         <td>FR_DVB_18</td>
 *         <td>Recommended for ages 18 and over</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="17">ISDB</td>
 *         <td>ISDB_4</td>
 *         <td>Recommended for ages 4 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_5</td>
 *         <td>Recommended for ages 5 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_6</td>
 *         <td>Recommended for ages 6 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_7</td>
 *         <td>Recommended for ages 7 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_8</td>
 *         <td>Recommended for ages 8 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_9</td>
 *         <td>Recommended for ages 9 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_10</td>
 *         <td>Recommended for ages 10 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_11</td>
 *         <td>Recommended for ages 11 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_12</td>
 *         <td>Recommended for ages 12 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_13</td>
 *         <td>Recommended for ages 13 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_14</td>
 *         <td>Recommended for ages 14 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_15</td>
 *         <td>Recommended for ages 15 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_16</td>
 *         <td>Recommended for ages 16 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_17</td>
 *         <td>Recommended for ages 17 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_18</td>
 *         <td>Recommended for ages 18 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_19</td>
 *         <td>Recommended for ages 19 and over</td>
 *     </tr>
 *     <tr>
 *         <td>ISDB_20</td>
 *         <td>Recommended for ages 20 and over</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">KR_TV</td>
 *         <td>KR_TV_ALL</td>
 *         <td>Appropriate for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_7</td>
 *         <td>May contain material inappropriate for children younger than 7, and parental
 *         discretion should be used</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_12</td>
 *         <td>May deemed inappropriate for those younger than 12, and parental discretion should be
 *         used</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_15</td>
 *         <td>May be inappropriate for children under 15, and that parental discretion should be
 *         used</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_19</td>
 *         <td>For adults only</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">SG_TV</td>
 *         <td>SG_TV_G</td>
 *         <td>Suitable for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>SG_TV_PG</td>
 *         <td>Suitable for all but parents should guide their young</td>
 *     </tr>
 *     <tr>
 *         <td>SG_TV_PG13</td>
 *         <td>Suitable for persons aged 13 and above but parental guidance is advised for children
 *         below 13</td>
 *     </tr>
 *     <tr>
 *         <td>SG_TV_NC16</td>
 *         <td>Suitable for persons aged 16 and above</td>
 *     </tr>
 *     <tr>
 *         <td>SG_TV_M18</td>
 *         <td>Suitable for persons aged 18 and above</td>
 *     </tr>
 *     <tr>
 *         <td>SG_TV_R21</td>
 *         <td>Suitable for adults aged 21 and above</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">US_MV</td>
 *         <td>US_MV_G</td>
 *         <td>General audiences</td>
 *     </tr>
 *     <tr>
 *         <td>US_MV_PG</td>
 *         <td>Parental guidance suggested</td>
 *     </tr>
 *     <tr>
 *         <td>US_MV_PG13</td>
 *         <td>Parents strongly cautioned</td>
 *     </tr>
 *     <tr>
 *         <td>US_MV_R</td>
 *         <td>Restricted, under 17 requires accompanying parent or adult guardian</td>
 *     </tr>
 *     <tr>
 *         <td>US_MV_NC17</td>
 *         <td>No one 17 and under admitted</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">US_TV</td>
 *         <td>US_TV_Y</td>
 *         <td>This program is designed to be appropriate for all children</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_Y7</td>
 *         <td>This program is designed for children age 7 and above</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_G</td>
 *         <td>Most parents would find this program suitable for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_PG</td>
 *         <td>This program contains material that parents may find unsuitable for younger children
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_14</td>
 *         <td>This program contains some material that many parents would find unsuitable for
 *         children under 14 years of age</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_MA</td>
 *         <td>This program is specifically designed to be viewed by adults and therefore may be
 *         unsuitable for children under 17</td>
 *     </tr>
 * </table>
 *
 * <h4>System defined strings for sub-ratings</h4>
 * <table>
 *     <tr>
 *         <th>Rating System</th>
 *         <th>Constant Value</th>
 *         <th>Description</th>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="3">BR_TV</td>
 *         <td>BR_TV_D</td>
 *         <td>Drugs<br/>Applicable to BR_TV_L, BR_TV_10, BR_TV_12, BR_TV_14, BR_TV_16, and BR_TV_18
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_S</td>
 *         <td>Sex<br/>Applicable to BR_TV_L, BR_TV_10, BR_TV_12, BR_TV_14, BR_TV_16, and BR_TV_18
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_V</td>
 *         <td>Violence<br/>Applicable to BR_TV_L, BR_TV_10, BR_TV_12, BR_TV_14, BR_TV_16, and
 *         BR_TV_18</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">US_TV</td>
 *         <td>US_TV_D</td>
 *         <td>Suggestive dialogue (Usually means talks about sex)<br/>Applicable to US_TV_PG, and
 *         US_TV_14</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_L</td>
 *         <td>Coarse language<br/>Applicable to US_TV_PG, US_TV_14, and US_TV_MA</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_S</td>
 *         <td>Sexual content<br/>Applicable to US_TV_PG, US_TV_14, and US_TV_MA</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_V</td>
 *         <td>Violence<br/>Applicable to US_TV_PG, US_TV_14, and US_TV_MA</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_FV</td>
 *         <td>Fantasy violence (Children's programming only)<br/>Applicable to US_TV_Y7</td>
 *     </tr>
 * </table>
 */
public final class TvContentRating {
    // TODO: Consider to use other DELIMITER. In some countries such as India may use this delimiter
    // in the main ratings.
    private static final String DELIMITER = "/";

    private final String mDomain;
    private final String mRatingSystem;
    private final String mRating;
    private final String[] mSubRatings;
    private final int mHashCode;

    /**
     * Rating constant denoting unrated content. Used to handle the case where the content rating
     * information is missing.
     *
     * <p>TV input services can call {@link TvInputManager#isRatingBlocked} with this constant to
     * determine whether they should block unrated content. The subsequent call to
     * {@link TvInputService.Session#notifyContentBlocked} with the same constant notifies
     * applications that the current program content is blocked by parental controls.
     */
    public static final TvContentRating UNRATED = new TvContentRating("null", "null", "null", null);

    /**
     * Creates a {@code TvContentRating} object with predefined content rating strings.
     *
     * @param domain The domain string. For example, "com.android.tv".
     * @param ratingSystem The rating system string. For example, "US_TV".
     * @param rating The content rating string. For example, "US_TV_PG".
     * @param subRatings The sub-rating strings. For example, "US_TV_D" and "US_TV_L".
     * @return A {@code TvContentRating} object.
     * @throws IllegalArgumentException If {@code domain}, {@code ratingSystem} or {@code rating} is
     *             {@code null}.
     */
    public static TvContentRating createRating(String domain, String ratingSystem,
            String rating, String... subRatings) {
        if (TextUtils.isEmpty(domain)) {
            throw new IllegalArgumentException("domain cannot be empty");
        }
        if (TextUtils.isEmpty(ratingSystem)) {
            throw new IllegalArgumentException("ratingSystem cannot be empty");
        }
        if (TextUtils.isEmpty(rating)) {
            throw new IllegalArgumentException("rating cannot be empty");
        }
        return new TvContentRating(domain, ratingSystem, rating, subRatings);
    }

    /**
     * Recovers a {@code TvContentRating} object from the string that was previously created from
     * {@link #flattenToString}.
     *
     * @param ratingString The string returned by {@link #flattenToString}.
     * @return the {@code TvContentRating} object containing the domain, rating system, rating and
     *         sub-ratings information encoded in {@code ratingString}.
     * @see #flattenToString
     */
    public static TvContentRating unflattenFromString(String ratingString) {
        if (TextUtils.isEmpty(ratingString)) {
            throw new IllegalArgumentException("ratingString cannot be empty");
        }
        String[] strs = ratingString.split(DELIMITER);
        if (strs.length < 3) {
            throw new IllegalArgumentException("Invalid rating string: " + ratingString);
        }
        if (strs.length > 3) {
            String[] subRatings = new String[strs.length - 3];
            System.arraycopy(strs, 3, subRatings, 0, subRatings.length);
            return new TvContentRating(strs[0], strs[1], strs[2], subRatings);
        }
        return new TvContentRating(strs[0], strs[1], strs[2], null);
    }

    /**
     * Constructs a TvContentRating object from a given rating and sub-rating constants.
     *
     * @param domain The string for domain of the content rating system such as "com.android.tv".
     * @param ratingSystem The rating system string such as "US_TV".
     * @param rating The content rating string such as "US_TV_PG".
     * @param subRatings The sub-rating strings such as "US_TV_D" and "US_TV_L".
     */
    private TvContentRating(
            String domain, String ratingSystem, String rating, String[] subRatings) {
        mDomain = domain;
        mRatingSystem = ratingSystem;
        mRating = rating;
        if (subRatings == null || subRatings.length == 0) {
            mSubRatings = null;
        } else {
            Arrays.sort(subRatings);
            mSubRatings = subRatings;
        }
        mHashCode = 31 * Objects.hash(mDomain, mRating) + Arrays.hashCode(mSubRatings);
    }

    /**
     * Returns the domain of this {@code TvContentRating} object.
     */
    public String getDomain() {
        return mDomain;
    }

    /**
     * Returns the rating system of this {@code TvContentRating} object.
     */
    public String getRatingSystem() {
        return mRatingSystem;
    }

    /**
     * Returns the main rating of this {@code TvContentRating} object.
     */
    public String getMainRating() {
        return mRating;
    }

    /**
     * Returns the unmodifiable sub-rating string {@link List} of this {@code TvContentRating}
     * object.
     */
    public List<String> getSubRatings() {
        if (mSubRatings == null) {
            return null;
        }
        return Collections.unmodifiableList(Arrays.asList(mSubRatings));
    }

    /**
     * Returns a string that unambiguously describes the rating information contained in a
     * {@code TvContentRating} object. One can later recover the object from this string through
     * {@link #unflattenFromString}.
     *
     * @return a string containing the rating information, which can later be stored in the
     *         database.
     * @see #unflattenFromString
     */
    public String flattenToString() {
        StringBuilder builder = new StringBuilder();
        builder.append(mDomain);
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
     * Returns {@code true} if this rating has the same main rating as the specified rating and when
     * this rating's sub-ratings contain the other's.
     *
     * <p>For example, a {@code TvContentRating} object that represents TV-PG with
     * S(Sexual content) and V(Violence) contains TV-PG, TV-PG/S, TV-PG/V and itself.
     *
     * @param rating The {@link TvContentRating} to check.
     * @return {@code true} if this object contains {@code rating}, {@code false} otherwise.
     */
    public final boolean contains(@NonNull TvContentRating rating) {
        Preconditions.checkNotNull(rating);
        if (!rating.getMainRating().equals(mRating)) {
            return false;
        }
        if (!rating.getDomain().equals(mDomain) ||
                !rating.getRatingSystem().equals(mRatingSystem) ||
                !rating.getMainRating().equals(mRating)) {
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

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TvContentRating)) {
            return false;
        }
        TvContentRating other = (TvContentRating) obj;
        if (mHashCode != other.mHashCode) {
            return false;
        }
        if (!TextUtils.equals(mDomain, other.mDomain)) {
            return false;
        }
        if (!TextUtils.equals(mRatingSystem, other.mRatingSystem)) {
            return false;
        }
        if (!TextUtils.equals(mRating, other.mRating)) {
            return false;
        }
        return Arrays.equals(mSubRatings, other.mSubRatings);
    }

    @Override
    public int hashCode() {
        return mHashCode;
    }
}
