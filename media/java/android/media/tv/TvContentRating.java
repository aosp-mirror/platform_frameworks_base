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
import android.text.TextUtils;

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
 * <p>
 * It is possible for a TV input to define its own content rating system by supplying a content
 * rating system definition XML resource (see example below) and having the
 * {@link android.R.attr#tvContentRatingDescription tvContentRatingDescription} attribute in
 * {@link TvInputService#SERVICE_META_DATA} of the TV input point to it.
 * </p>
 * <h3> Example: Rating system definition for the TV Parental Guidelines</h3>
 * The following XML example shows how the TV Parental Guidelines in the United States can be
 * defined:
 * <p><pre class="prettyprint">
 * {@literal
 * <rating-system-definitions xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:versionCode="1">
 *     <!-- TV content rating system for US TV -->
 *     <rating-system-definition android:name="US_TV"
 *         android:title="US-TV"
 *         android:description="@string/description_ustv"
 *         android:country="US">
 *         <sub-rating-definition android:name="US_TV_D"
 *             android:title="D"
 *             android:description="@string/description_ustv_d" />
 *         <sub-rating-definition android:name="US_TV_L"
 *             android:title="L"
 *             android:description="@string/description_ustv_l" />
 *         <sub-rating-definition android:name="US_TV_S"
 *             android:title="S"
 *             android:description="@string/description_ustv_s" />
 *         <sub-rating-definition android:name="US_TV_V"
 *             android:title="V"
 *             android:description="@string/description_ustv_v" />
 *         <sub-rating-definition android:name="US_TV_FV"
 *             android:title="FV"
 *             android:description="@string/description_ustv_fv" />
 *
 *         <rating-definition android:name="US_TV_Y"
 *             android:title="TV-Y"
 *             android:description="@string/description_ustv_y"
 *             android:ageHint="0" />
 *         <rating-definition android:name="US_TV_Y7"
 *             android:title="TV-Y7"
 *             android:description="@string/description_ustv_y7"
 *             android:ageHint="7">
 *             <sub-rating android:name="US_TV_FV" />
 *         </rating-definition>
 *         <rating-definition android:name="US_TV_G"
 *             android:title="TV-G"
 *             android:description="@string/description_ustv_g"
 *             android:ageHint="0" />
 *         <rating-definition android:name="US_TV_PG"
 *             android:title="TV-PG"
 *             android:description="@string/description_ustv_pg"
 *             android:ageHint="14">
 *             <sub-rating android:name="US_TV_D" />
 *             <sub-rating android:name="US_TV_L" />
 *             <sub-rating android:name="US_TV_S" />
 *             <sub-rating android:name="US_TV_V" />
 *         </rating-definition>
 *         <rating-definition android:name="US_TV_14"
 *             android:title="TV-14"
 *             android:description="@string/description_ustv_14"
 *             android:ageHint="14">
 *             <sub-rating android:name="US_TV_D" />
 *             <sub-rating android:name="US_TV_L" />
 *             <sub-rating android:name="US_TV_S" />
 *             <sub-rating android:name="US_TV_V" />
 *         </rating-definition>
 *         <rating-definition android:name="US_TV_MA"
 *             android:title="TV-MA"
 *             android:description="@string/description_ustv_ma"
 *             android:ageHint="17">
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
 * </rating-system-definitions>}</pre></p>
 *
 * <h3>System defined rating strings</h3>
 * The following strings are defined by the system to provide a standard way to create
 * {@code TvContentRating} objects.
 * <p>For example, to create an object that represents TV-PG rating with suggestive dialogue and
 * coarse language from the TV Parental Guidelines in the United States, one can use the following
 * code snippet:
 * </p>
 * <pre>
 * String rating = TvContentRating.createRating(
 *         "com.android.tv",
 *         "US_TV",
 *         "US_TV_PG",
 *         "US_TV_D", "US_TV_L");
 * </pre>
 * <h4>System defined string for domains</h4>
 * <table>
 *     <tr>
 *         <th>Constant Value</th>
 *         <th>Comment</th>
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
 *         <th>Comment</th>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_RS</td>
 *         <td>Range specific TV content rating system strings for Armenia</td>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_AS</td>
 *         <td>Age specific TV content rating system strings for Armenia</td>
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
 *         <td>BG_TV</td>
 *         <td>TV content rating system for Bulgaria</td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV</td>
 *         <td>TV content rating system for Brazil</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV</td>
 *         <td>TV content rating system for Canada</td>
 *     </tr>
 *     <tr>
 *         <td>CH_TV</td>
 *         <td>TV content rating system for Switzerland</td>
 *     </tr>
 *     <tr>
 *         <td>CL_TV</td>
 *         <td>TV content rating system for Chile</td>
 *     </tr>
 *     <tr>
 *         <td>DE_TV</td>
 *         <td>TV content rating system for Germany</td>
 *     </tr>
 *     <tr>
 *         <td>DK_TV</td>
 *         <td>TV content rating system for Denmark</td>
 *     </tr>
 *     <tr>
 *         <td>ES_TV</td>
 *         <td>TV content rating system for Spain</td>
 *     </tr>
 *     <tr>
 *         <td>FI_TV</td>
 *         <td>TV content rating system for Finland</td>
 *     </tr>
 *     <tr>
 *         <td>FR_TV</td>
 *         <td>TV content rating system for France</td>
 *     </tr>
 *     <tr>
 *         <td>GR_TV</td>
 *         <td>TV content rating system for Greece</td>
 *     </tr>
 *     <tr>
 *         <td>HK_TV</td>
 *         <td>TV content rating system for Hong Kong</td>
 *     </tr>
 *     <tr>
 *         <td>HU_TV</td>
 *         <td>TV content rating system for Hungary</td>
 *     </tr>
 *     <tr>
 *         <td>ID_TV</td>
 *         <td>TV content rating system for Indonesia</td>
 *     </tr>
 *     <tr>
 *         <td>IE_TV</td>
 *         <td>TV content rating system for Ireland</td>
 *     </tr>
 *     <tr>
 *         <td>IL_TV</td>
 *         <td>TV content rating system for Israel</td>
 *     </tr>
 *     <tr>
 *         <td>IN_TV</td>
 *         <td>TV content rating system for India</td>
 *     </tr>
 *     <tr>
 *         <td>IS_TV</td>
 *         <td>TV content rating system for Iceland</td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV</td>
 *         <td>TV content rating system for South Korea</td>
 *     </tr>
 *     <tr>
 *         <td>MV_TV</td>
 *         <td>TV content rating system for Maldives</td>
 *     </tr>
 *     <tr>
 *         <td>MX_TV</td>
 *         <td>TV content rating system for Mexico</td>
 *     </tr>
 *     <tr>
 *         <td>MY_TV</td>
 *         <td>TV content rating system for Malaysia</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV</td>
 *         <td>TV content rating system for Netherlands</td>
 *     </tr>
 *     <tr>
 *         <td>NZ_FTV</td>
 *         <td>TV content rating system for free-to-air channels in New Zealand</td>
 *     </tr>
 *     <tr>
 *         <td>NZ_PTV</td>
 *         <td>TV content rating system for Pay TV channels in New Zealand</td>
 *     </tr>
 *     <tr>
 *         <td>PE_TV</td>
 *         <td>TV content rating system for some Peruvian channels in Peru</td>
 *     </tr>
 *     <tr>
 *         <td>PE_ATV</td>
 *         <td>TV content rating system for America Television in Peru that uses its own rating
 *         system</td>
 *     </tr>
 *     <tr>
 *         <td>PH_TV</td>
 *         <td>TV content rating system for Philippines</td>
 *     </tr>
 *     <tr>
 *         <td>PL_TV</td>
 *         <td>TV content rating system for Poland</td>
 *     </tr>
 *     <tr>
 *         <td>PT_TV</td>
 *         <td>TV content rating system for Portugal</td>
 *     </tr>
 *     <tr>
 *         <td>RO_TV</td>
 *         <td>TV content rating system for Romania</td>
 *     </tr>
 *     <tr>
 *         <td>RU_TV</td>
 *         <td>TV content rating system for Russia</td>
 *     </tr>
 *     <tr>
 *         <td>RS_TV</td>
 *         <td>TV content rating system for Serbia</td>
 *     </tr>
 *     <tr>
 *         <td>SG_FTV</td>
 *         <td>TV content rating system for Singapore</td>
 *     </tr>
 *     <tr>
 *         <td>SG_PTV</td>
 *         <td>TV content rating system for Singapore</td>
 *     </tr>
 *     <tr>
 *         <td>SI_TV</td>
 *         <td>TV content rating system for Slovenia</td>
 *     </tr>
 *     <tr>
 *         <td>TH_TV</td>
 *         <td>TV content rating system for Thailand</td>
 *     </tr>
 *     <tr>
 *         <td>TR_TV</td>
 *         <td>TV content rating system for Turkey</td>
 *     </tr>
 *     <tr>
 *         <td>TW_TV</td>
 *         <td>TV content rating system for Taiwan</td>
 *     </tr>
 *     <tr>
 *         <td>UA_TV</td>
 *         <td>TV content rating system for Ukraine</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV</td>
 *         <td>TV content rating system for the United States</td>
 *     </tr>
 *     <tr>
 *         <td>VE_TV</td>
 *         <td>TV content rating system for Venezuela</td>
 *     </tr>
 *     <tr>
 *         <td>ZA_TV</td>
 *         <td>TV content rating system for South Africa</td>
 *     </tr>
 * </table>
 *
 * <h4>System defined strings for ratings</h4>
 * <table>
 *     <tr>
 *         <th>Rating System</th>
 *         <th>Constant Value</th>
 *         <th>Comment</th>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">AM_TV_RS</td>
 *         <td>AM_TV_RS_Y</td>
 *         <td>Suitable for ages 2-11</td>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_RS_Y7</td>
 *         <td>Suitable for ages 7-16</td>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_RS_GA</td>
 *         <td>Suitable for general audiences</td>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_RS_TW</td>
 *         <td>Suitable for teens ages 9 and up</td>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_RS_T</td>
 *         <td>Suitable for teens ages 12 and up</td>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_RS_A</td>
 *         <td>Suitable only for adults ages 18 and up</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">AM_TV_AS</td>
 *         <td>AM_TV_AS_EC</td>
 *         <td>Suitable for ages 2 and up</td>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_AS_E</td>
 *         <td>Suitable for ages 5 and up</td>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_AS_E9</td>
 *         <td>Suitable for ages 9 and up</td>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_AS_T</td>
 *         <td>Suitable for ages 12 and up</td>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_AS_M</td>
 *         <td>Suitable for ages 16 and up</td>
 *     </tr>
 *     <tr>
 *         <td>AM_TV_AS_AO</td>
 *         <td>Suitable for ages 17 and up</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="4">AR_TV</td>
 *         <td>AR_TV_ALL</td>
 *         <td>Suitable for all audiences. Programs may contain mild violence, language and mature
 *         situations</td>
 *     </tr>
 *     <tr>
 *         <td>AR_TV_13</td>
 *         <td>Suitable for ages 13 and up. Programs may contain mild to moderate language and mild
 *         violence and sexual references</td>
 *     </tr>
 *     <tr>
 *         <td>AR_TV_16</td>
 *         <td>Suitable for ages 16 and up. Programs may contain more intensive violence and coarse
 *         language, partial nudity and moderate sexual references</td>
 *     </tr>
 *     <tr>
 *         <td>AR_TV_18</td>
 *         <td>Suitable for mature audiences only. Programs contain strong violence, coarse language
 *         and explicit sexual references</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="7">AU_TV</td>
 *         <td>AU_TV_CTC</td>
 *         <td>This has advertising approval, but is not yet classified</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_G</td>
 *         <td>The content is very mild in impact, and suitable for everyone</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_PG</td>
 *         <td>The content is mild in impact, but it may contain content that children find
 *         confusing or upsetting and may require the guidance or parents and guardians</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_M</td>
 *         <td>The content is moderate in impact, and it is recommended for teenagers aged 15 years
 *         and over</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_MA15</td>
 *         <td>The content is strong in impact, and it is legally restricted to persons 15 years and
 *         over</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_R18</td>
 *         <td>The content is high in impact, and it is restricted to adults</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_X18</td>
 *         <td>The content is restricted to adults. This classification is a special and legally
 *         restricted category which contains only sexually explicit content</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">BG_TV</td>
 *         <td>BG_TV_A</td>
 *         <td>Recommended to children. When the film confirms the ideals of humanism or popularizes
 *         the national and world cultures or contributes to upbringing children</td>
 *     </tr>
 *     <tr>
 *         <td>BG_TV_B</td>
 *         <td>No restrictive recommendations from the Committee. When the film is in no way
 *         contrary to the universal rules of morality in this country, has no restrictive
 *         recommendations from the Committee and does not fall in rating A</td>
 *     </tr>
 *     <tr>
 *         <td>BG_TV_C</td>
 *         <td>No persons under the age of 12 are admitted unless accompanied by an adult. When the
 *         film contains certain erotic scenes or scenes with drinking, taking drugs or stimulants
 *         or a few scenes of violence</td>
 *     </tr>
 *     <tr>
 *         <td>BG_TV_D</td>
 *         <td>No persons under the age of 16 are admitted. When the film contains quite a number of
 *         erotic scenes or scenes with drinking, taking drugs or stimulants or a considerable
 *         number of scenes showing violence</td>
 *     </tr>
 *     <tr>
 *         <td>BG_TV_X</td>
 *         <td>No persons under the age of 18 are admitted. When the film is naturalistically erotic
 *         or shows violence in an ostentatious manner</td>
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
 *         <td valign="top" rowspan="7">CA_TV</td>
 *         <td>CA_TV_EXEMPT</td>
 *         <td>Shows which are exempt from ratings (such as news and sports programming) will not
 *         display an on-screen rating at all</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_C</td>
 *         <td>Programming suitable for children ages of 2-7 years. No profanity or sexual content
 *         of any level allowed. Contains little violence</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_C8</td>
 *         <td>Suitable for children ages 8+. Low level violence and fantasy horror is allowed. No
 *         foul language is allowed, but occasional "socially offensive and discriminatory" language
 *         is allowed if in the context of the story. No sexual content of any level allowed</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_G</td>
 *         <td>Suitable for general audiences. Programming suitable for the entire family with mild
 *         violence, and mild profanity and/or censored language</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_PG</td>
 *         <td>Parental guidance. Moderate violence and moderate profanity is allowed, as is brief
 *         nudity and sexual references if important to the context of the story</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_14</td>
 *         <td>Programming intended for viewers ages 14 and older. May contain strong violence and
 *         strong profanity, and depictions of sexual activity as long as they are within the
 *         context of a story</td>
 *     </tr>
 *     <tr>
 *         <td>CA_TV_18</td>
 *         <td>Programming intended for viewers ages 18 and older. May contain explicit violence and
 *         sexual activity</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="2">CH_TV</td>
 *         <td>CH_TV_ALL</td>
 *         <td>This program is suitable for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>CH_TV_RED</td>
 *         <td>This program contains scenes that may hurt sensitive people, therefore the red symbol
 *         will be displayed</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="7">CL_TV</td>
 *         <td>CL_TV_I</td>
 *         <td>Programs suitable for all children</td>
 *     </tr>
 *     <tr>
 *         <td>CL_TV_I7</td>
 *         <td>Programs recommended for children ages 7 or older</td>
 *     </tr>
 *     <tr>
 *         <td>CL_TV_I10</td>
 *         <td>Programs recommended for children ages 10 or older</td>
 *     </tr>
 *     <tr>
 *         <td>CL_TV_I12</td>
 *         <td>Programs recommended for children and teens ages 12 or older</td>
 *     </tr>
 *     <tr>
 *         <td>CL_TV_F</td>
 *         <td>Programs suitable for a general audience, with content appropriate for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>CL_TV_R</td>
 *         <td>Programs may content not suitable for children not accompanied by an adult</td>
 *     </tr>
 *     <tr>
 *         <td>CL_TV_A</td>
 *         <td>Programs suitable for adult audiences only (ages 18 or older), may contain coarse
 *         language, and sexual or explicit situations</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="4">DE_TV</td>
 *         <td>DE_TV_ALL</td>
 *         <td>The program is suitable for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>DE_TV_12</td>
 *         <td>The program is not suitable for viewers under the age of 12</td>
 *     </tr>
 *     <tr>
 *         <td>DE_TV_16</td>
 *         <td>The program is not suitable for viewers under the age of 16</td>
 *     </tr>
 *     <tr>
 *         <td>DE_TV_18</td>
 *         <td>The program is not suitable for viewers under the age of 18</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="4">DK_TV</td>
 *         <td>DK_TV_G</td>
 *         <td>programs suitable for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>DK_TV_Y</td>
 *         <td>programs suitable children accompanied by an adult</td>
 *     </tr>
 *     <tr>
 *         <td>DK_TV_R</td>
 *         <td>programs containing material with more intensive content</td>
 *     </tr>
 *     <tr>
 *         <td>DK_TV_B</td>
 *         <td>programs containing explicit content and strictly for adults only</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="7">ES_TV</td>
 *         <td>ES_TV_TP</td>
 *         <td>Recommended for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>ES_TV_I</td>
 *         <td>Specially recommended for preschoolers and kids</td>
 *     </tr>
 *     <tr>
 *         <td>ES_TV_7</td>
 *         <td>Recommended for people older than 7 years old</td>
 *     </tr>
 *     <tr>
 *         <td>ES_TV_7I</td>
 *         <td>Recommended for kids older than 7 years old</td>
 *     </tr>
 *     <tr>
 *         <td>ES_TV_12</td>
 *         <td>Recommended for people older than 12 years old</td>
 *     </tr>
 *     <tr>
 *         <td>ES_TV_16</td>
 *         <td>Recommended for people older than 16 years old</td>
 *     </tr>
 *     <tr>
 *         <td>ES_TV_18</td>
 *         <td>Recommended for people older than 18 years old</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">FI_TV</td>
 *         <td>FI_TV_S</td>
 *         <td>Allowed at all times</td>
 *     </tr>
 *     <tr>
 *         <td>FI_TV_K7</td>
 *         <td>Not recommended for children under 7</td>
 *     </tr>
 *     <tr>
 *         <td>FI_TV_K12</td>
 *         <td>Not recommended for children under 12</td>
 *     </tr>
 *     <tr>
 *         <td>FI_TV_K16</td>
 *         <td>Not recommended for children under 16</td>
 *     </tr>
 *     <tr>
 *         <td>FI_TV_K18</td>
 *         <td>Not recommended for children under 18</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">FR_TV</td>
 *         <td>FR_TV_ALL</td>
 *         <td>Appropriate for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>FR_TV_10</td>
 *         <td>Not recommended for children under 10</td>
 *     </tr>
 *     <tr>
 *         <td>FR_TV_12</td>
 *         <td>Not recommended for children under 12</td>
 *     </tr>
 *     <tr>
 *         <td>FR_TV_16</td>
 *         <td>Not recommended for children under 16</td>
 *     </tr>
 *     <tr>
 *         <td>FR_TV_18</td>
 *         <td>Not recommended for persons under 18</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">GR_TV</td>
 *         <td>GR_TV_all</td>
 *         <td>Suitable for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>GR_TV_10</td>
 *         <td>Parental consent suggested</td>
 *     </tr>
 *     <tr>
 *         <td>GR_TV_12</td>
 *         <td>Required parental consent</td>
 *     </tr>
 *     <tr>
 *         <td>GR_TV_15</td>
 *         <td>Suitable for minors over the age of 15</td>
 *     </tr>
 *     <tr>
 *         <td>GR_TV_18</td>
 *         <td>Suitable only for adults profanity before midnight is punishable by fine, except when
 *         used in the context of the program</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="3">HK_TV</td>
 *         <td>HK_TV_G</td>
 *         <td>For general audiences</td>
 *     </tr>
 *     <tr>
 *         <td>HK_TV_PG</td>
 *         <td>Programs are unsuitable for children, parental guidance is recommended</td>
 *     </tr>
 *     <tr>
 *         <td>HK_TV_M</td>
 *         <td>Programs are recommended only for adult viewers above the age of 18</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">HU_TV</td>
 *         <td>HU_TV_U</td>
 *         <td>Programs can be viewed by any age</td>
 *     </tr>
 *     <tr>
 *         <td>HU_TV_CF</td>
 *         <td>Programs recommended for children. It is an optional rating, there is no obligation
 *         for broadcasters to indicate it</td>
 *     </tr>
 *     <tr>
 *         <td>HU_TV_6</td>
 *         <td>Programs not recommended for children below the age of 6, may not contain any
 *         violence or sexual content</td>
 *     </tr>
 *     <tr>
 *         <td>HU_TV_12</td>
 *         <td>Programs not recommended for children below the age of 12, may contain light sexual
 *         content or explicit language</td>
 *     </tr>
 *     <tr>
 *         <td>HU_TV_16</td>
 *         <td>Programs not recommended for teens and children below the age of 16, may contain more
 *         intensive violence and sexual content</td>
 *     </tr>
 *     <tr>
 *         <td>HU_TV_18</td>
 *         <td>The program is recommended only for adult viewers (for ages 18 and up), may contain
 *         explicit violence and explicit sexual content</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="8">ID_TV</td>
 *         <td>ID_TV_P</td>
 *         <td>Suitable for children from ages 2 through 11</td>
 *     </tr>
 *     <tr>
 *         <td>ID_TV_A</td>
 *         <td>Suitable for teens and children from ages 7 through 16</td>
 *     </tr>
 *     <tr>
 *         <td>ID_TV_A_BO</td>
 *         <td>Suitable for children ages 5 through 10, with parental guidance or permission</td>
 *     </tr>
 *     <tr>
 *         <td>ID_TV_SU</td>
 *         <td>Suitable for general audiences</td>
 *     </tr>
 *     <tr>
 *         <td>ID_TV_BO</td>
 *         <td>Parental guidance suggested for ages 5 and under</td>
 *     </tr>
 *     <tr>
 *         <td>ID_TV_R</td>
 *         <td>Suitable for teens from ages 13 through 17</td>
 *     </tr>
 *     <tr>
 *         <td>ID_TV_R_BO</td>
 *         <td>Suitable for teens with parental guidance or permission</td>
 *     </tr>
 *     <tr>
 *         <td>ID_TV_D</td>
 *         <td>Suitable for viewers over 18 and older only</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">IE_TV</td>
 *         <td>IE_TV_GA</td>
 *         <td>Suitable for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>IE_TV_Ch</td>
 *         <td>Suitable for children ages 5 to 10, may contain comedic violence or action fantasy
 *         violence</td>
 *     </tr>
 *     <tr>
 *         <td>IE_TV_YA</td>
 *         <td>Suitable for adolescent audiences, may contain thematic elements that would appeal to
 *         teenagers</td>
 *     </tr>
 *     <tr>
 *         <td>IE_TV_PS</td>
 *         <td>Suitable for more mature viewers, more mature themes may be present</td>
 *     </tr>
 *     <tr>
 *         <td>IE_TV_MA</td>
 *         <td>Most restrictive classification, allowing for heavy subject matter and coarse
 *         language</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">IL_TV</td>
 *         <td>IL_TV_G</td>
 *         <td>General audience; anyone, regardless of age, can view the program, usually news and
 *         children's programming</td>
 *     </tr>
 *     <tr>
 *         <td>IL_TV_12</td>
 *         <td>Suitable for teens and children ages 12 and over, no child under 12 are permitted to
 *         view the program</td>
 *     </tr>
 *     <tr>
 *         <td>IL_TV_15</td>
 *         <td>Suitable for teens ages 15 and over, no child under 15 may view the programme</td>
 *     </tr>
 *     <tr>
 *         <td>IL_TV_18</td>
 *         <td>Suitable for adults only, no minors may view the programme</td>
 *     </tr>
 *     <tr>
 *         <td>IL_TV_E</td>
 *         <td>Exempt from classification</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="4">IN_TV</td>
 *         <td>IN_TV_U</td>
 *         <td>Unrestricted public exhibition</td>
 *     </tr>
 *     <tr>
 *         <td>IN_TV_U/A</td>
 *         <td>Unrestricted public exhibition, but with a caution regarding parental guidance to
 *         those under 12 years of age</td>
 *     </tr>
 *     <tr>
 *         <td>IN_TV_A</td>
 *         <td>Public exhibition restricted to adults 18 years of age and older only</td>
 *     </tr>
 *     <tr>
 *         <td>IN_TV_S</td>
 *         <td>Public exhibition restricted to members of any profession or any class of persons
 *         </td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="7">IS_TV</td>
 *         <td>IS_TV_L</td>
 *         <td>Programs suitable for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>IS_TV_7</td>
 *         <td>Programs suitable for ages 7 and older</td>
 *     </tr>
 *     <tr>
 *         <td>IS_TV_10</td>
 *         <td>Programs suitable for ages 10 and older</td>
 *     </tr>
 *     <tr>
 *         <td>IS_TV_12</td>
 *         <td>Programs suitable for ages 12 and older</td>
 *     </tr>
 *     <tr>
 *         <td>IS_TV_14</td>
 *         <td>Programs suitable for ages 14 and older</td>
 *     </tr>
 *     <tr>
 *         <td>IS_TV_16</td>
 *         <td>Programs suitable for ages 16 and older</td>
 *     </tr>
 *     <tr>
 *         <td>IS_TV_18</td>
 *         <td>Programs suitable for ages 18 and older</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">KR_TV</td>
 *         <td>KR_TV_All</td>
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
 *         <td valign="top" rowspan="9">MV_TV</td>
 *         <td>MV_TV_Y</td>
 *         <td>Young children</td>
 *     </tr>
 *     <tr>
 *         <td>MV_TV_G</td>
 *         <td>General viewing for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>MV_TV_PG</td>
 *         <td>Parental guidance is required unaccompanied children</td>
 *     </tr>
 *     <tr>
 *         <td>MV_TV_PG-12</td>
 *         <td>Parental guidance is required for children under the age of 12</td>
 *     </tr>
 *     <tr>
 *         <td>MV_TV_12</td>
 *         <td>Teens and children aged 12 and older may watch, otherwise restricted</td>
 *     </tr>
 *     <tr>
 *         <td>MV_TV_15</td>
 *         <td>Restricted to viewers aged 15 and above</td>
 *     </tr>
 *     <tr>
 *         <td>MV_TV_18</td>
 *         <td>Restricted to viewers aged 18 and above</td>
 *     </tr>
 *     <tr>
 *         <td>MV_TV_21</td>
 *         <td>Restricted to viewers aged 21 and above</td>
 *     </tr>
 *     <tr>
 *         <td>MV_TV_X</td>
 *         <td>Most restrictive classification, only adults ages 25 and above may view</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">MX_TV</td>
 *         <td>MX_TV_A</td>
 *         <td>Appropriate for all ages, parental guidance is recommended for children under 7 years
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>MX_TV_B</td>
 *         <td>Designed for ages 12 and older, may contain some sexual situations, mild violence,
 *         and mild language</td>
 *     </tr>
 *     <tr>
 *         <td>MX_TV_B-15</td>
 *         <td>Designed for ages 15 and up, slightly more intensive than the 'A' and 'B' ratings
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>MX_TV_C</td>
 *         <td>Designed to be viewed by adults aged 18 or older only, generally more intensive
 *         content</td>
 *     </tr>
 *     <tr>
 *         <td>MX_TV_D</td>
 *         <td>Designed to be viewed only by mature adults (at least 21 years of age and over),
 *         contains extreme content matter</td>
 *     </tr>
 *     <tr>
 *         <td>MX_TV_RC</td>
 *         <td>Banned from public television in Mexico</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="3">MY_TV</td>
 *         <td>MY_TV_U</td>
 *         <td>General viewing for all ages, can be broadcast anytime</td>
 *     </tr>
 *     <tr>
 *         <td>MY_TV_P13</td>
 *         <td>For viewers ages 13 and above, children under 13 needs parental guidance, can be
 *         broadcast anytime, but some elements may only be broadcast at night</td>
 *     </tr>
 *     <tr>
 *         <td>MY_TV_18</td>
 *         <td>For viewers ages 18 and above only</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">NL_TV</td>
 *         <td>NL_TV_AL</td>
 *         <td>All Ages</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_6</td>
 *         <td>Parental advisory for children under 6</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_9</td>
 *         <td>Parental advisory for children under 9</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_12</td>
 *         <td>Parental advisory for children under 12</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_16</td>
 *         <td>Parental advisory for children under 16</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="3">NZ_FTV</td>
 *         <td>NZ_FTV_G</td>
 *         <td>These exclude material likely to harm children under 14 and can screen at any time.
 *         Programmes may not necessarily be designed for younger viewers, but must not contain
 *         material likely to cause them undue distress or discomfort</td>
 *     </tr>
 *     <tr>
 *         <td>NZ_FTV_PGR</td>
 *         <td>Programmes more suited to more mature viewers. These are not necessarily unsuitable
 *         for children, but viewer discretion is advised, and parents and guardians are encouraged
 *         to supervise younger viewers</td>
 *     </tr>
 *     <tr>
 *         <td>NZ_FTV_AO</td>
 *         <td>Contain material of an adult nature handled in such a way that it is unsuitable for
 *         children</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">NZ_PTV</td>
 *         <td>NZ_PTV_G</td>
 *         <td>suitable for general audiences</td>
 *     </tr>
 *     <tr>
 *         <td>NZ_PTV_PG</td>
 *         <td>Parental guidance recommended for under 10</td>
 *     </tr>
 *     <tr>
 *         <td>NZ_PTV_M</td>
 *         <td>Suitable for mature audiences 13 and up</td>
 *     </tr>
 *     <tr>
 *         <td>NZ_PTV_16</td>
 *         <td>Suitable for viewers 16 and up</td>
 *     </tr>
 *     <tr>
 *         <td>NZ_PTV_18</td>
 *         <td>Suitable for viewers 18 and up</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="3">PE_TV</td>
 *         <td>PE_TV_A</td>
 *         <td>Suitable for all audiences</td>
 *     </tr>
 *     <tr>
 *         <td>PE_TV_14</td>
 *         <td>Suitable for people aged 14 and above only</td>
 *     </tr>
 *     <tr>
 *         <td>PE_TV_18</td>
 *         <td>Suitable for people aged 18 and above only</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="4">PE_ATV</td>
 *         <td>PE_ATV_GP</td>
 *         <td>General audience</td>
 *     </tr>
 *     <tr>
 *         <td>PE_ATV_PG</td>
 *         <td>Parental guidance required for under 6</td>
 *     </tr>
 *     <tr>
 *         <td>PE_ATV_14</td>
 *         <td>Suitable for people aged 14 and above only</td>
 *     </tr>
 *     <tr>
 *         <td>PE_ATV_18</td>
 *         <td>Suitable for people aged 18 and above only</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="3">PH_TV</td>
 *         <td>PH_TV_G</td>
 *         <td>Suitable for all public viewers</td>
 *     </tr>
 *     <tr>
 *         <td>PH_TV_PG</td>
 *         <td>Programmes rated PG may contain scenes or other content that are unsuitable for
 *         children without the guidance of a parent</td>
 *     </tr>
 *     <tr>
 *         <td>PH_TV_SPG</td>
 *         <td>Contains mature themes or moderate to intense violence, which may be deemed unfit for
 *         children to watch without strict parental supervision</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">PL_TV</td>
 *         <td>PL_TV_G</td>
 *         <td>Positive or neutral view of the world, little to no violence, non-sexual love, and no
 *         sexual content</td>
 *     </tr>
 *     <tr>
 *         <td>PL_TV_7</td>
 *         <td>Age 7 and above. May additionally contain some mild language, bloodless violence, and
 *         a more negative view of the world</td>
 *     </tr>
 *     <tr>
 *         <td>PL_TV_12</td>
 *         <td>Age 12 and above. May contain some foul language, some violence, and some sexual
 *         content</td>
 *     </tr>
 *     <tr>
 *         <td>PL_TV_16</td>
 *         <td>Age 16 and above. Deviant social behaviour, world filled with violence and sexuality,
 *         simplified picture of adulthood, display of physical force, especially in controversial
 *         social context, immoral behaviour without ethic dilemma, putting the blame on the victim,
 *         excessive concentration on material possessions</td>
 *     </tr>
 *     <tr>
 *         <td>PL_TV_18</td>
 *         <td>Age 18 and above. One-sided display of the joys of adult life without showing
 *         responsibilities, social justification of violent behaviour, excessive vulgarity, use of
 *         racial slurs and social stereotypes, explicit sexual content, praise of aggression or
 *         vulgarity</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="4">PT_TV</td>
 *         <td>PT_TV_T</td>
 *         <td>Suitable for all</td>
 *     </tr>
 *     <tr>
 *         <td>PT_TV_10</td>
 *         <td>May not be suitable for children under 10, parental guidance advised</td>
 *     </tr>
 *     <tr>
 *         <td>PT_TV_12</td>
 *         <td>May not be suitable for children under 12, parental guidance advised</td>
 *     </tr>
 *     <tr>
 *         <td>PT_TV_16</td>
 *         <td>Not suitable for children under 16</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">RO_TV</td>
 *         <td>RO_TV_Y</td>
 *         <td>Young Ages</td>
 *     </tr>
 *     <tr>
 *         <td>RO_TV_G</td>
 *         <td>General Exhibition</td>
 *     </tr>
 *     <tr>
 *         <td>RO_TV_AP</td>
 *         <td>Parental guidance is recommended for children below the age of 12</td>
 *     </tr>
 *     <tr>
 *         <td>RO_TV_12</td>
 *         <td>Forbidden for children under 12 years of age</td>
 *     </tr>
 *     <tr>
 *         <td>RO_TV_15</td>
 *         <td>Forbidden for children under 15 years of age</td>
 *     </tr>
 *     <tr>
 *         <td>RO_TV_18</td>
 *         <td>Forbidden for children under 18 years of age</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">RU_TV</td>
 *         <td>RU_TV_0</td>
 *         <td>Can be watched by Any Age</td>
 *     </tr>
 *     <tr>
 *         <td>RU_TV_6</td>
 *         <td>Only kids the age of 6 or older can watch</td>
 *     </tr>
 *     <tr>
 *         <td>RU_TV_12</td>
 *         <td>Only kids the age of 12 or older can watch</td>
 *     </tr>
 *     <tr>
 *         <td>RU_TV_16</td>
 *         <td>Only teens the age of 16 or older can watch</td>
 *     </tr>
 *     <tr>
 *         <td>RU_TV_18</td>
 *         <td>Restricted to children ONLY people 18 or older</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="7">RS_TV</td>
 *         <td>RS_TV_G</td>
 *         <td>Program suitable for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>RS_TV_12</td>
 *         <td>Program not suitable for children under the age of 12</td>
 *     </tr>
 *     <tr>
 *         <td>RS_TV_14</td>
 *         <td>Program not suitable for children/teens under the age of 14</td>
 *     </tr>
 *     <tr>
 *         <td>RS_TV_15</td>
 *         <td>Program not suitable for children/teens under the age of 15</td>
 *     </tr>
 *     <tr>
 *         <td>RS_TV_16</td>
 *         <td>Program not suitable for children/teens under the age of 16</td>
 *     </tr>
 *     <tr>
 *         <td>RS_TV_17</td>
 *         <td>Program not suitable for children/teens under the age of 17</td>
 *     </tr>
 *     <tr>
 *         <td>RS_TV_18</td>
 *         <td>Program not suitable for minors under the age of 18</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="2">SG_FTV</td>
 *         <td>SG_FTV_PG</td>
 *         <td>Suitable for most but parents should guide their young</td>
 *     </tr>
 *     <tr>
 *         <td>SG_FTV_PG13</td>
 *         <td>Parental Guidance Strongly Cautioned - Suitable for 13 And Above</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="2">SG_PTV</td>
 *         <td>SG_PTV_NC16</td>
 *         <td>No Children Under 16</td>
 *     </tr>
 *     <tr>
 *         <td>SG_PTV_M18</td>
 *         <td>Nobody under age 18 is admitted</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="4">SI_TV</td>
 *         <td>SI_TV_VS</td>
 *         <td>Parental guidance suggested (for children under 6)</td>
 *     </tr>
 *     <tr>
 *         <td>SI_TV_12</td>
 *         <td>Content suitable for teens over 12 years</td>
 *     </tr>
 *     <tr>
 *         <td>SI_TV_15</td>
 *         <td>Content suitable for teens over 15 years</td>
 *     </tr>
 *     <tr>
 *         <td>SI_TV_AD</td>
 *         <td>Content exclusively for adults</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">TH_TV</td>
 *         <td>TH_TV_P</td>
 *         <td>Content suitable for primary school aged children</td>
 *     </tr>
 *     <tr>
 *         <td>TH_TV_C</td>
 *         <td>Content suitable for children between 6-12 years old</td>
 *     </tr>
 *     <tr>
 *         <td>TH_TV_G</td>
 *         <td>Content suitable for general audiences</td>
 *     </tr>
 *     <tr>
 *         <td>TH_TV_PG13</td>
 *         <td>Content suitable for people aged 13 and above, but can be watched by those who are
 *         under the recommended age if parental guidance is provided</td>
 *     </tr>
 *     <tr>
 *         <td>TH_TV_PG18</td>
 *         <td>Content suitable for people aged above 18 years old; those who are younger that 18
 *         must be provided with parental guidance</td>
 *     </tr>
 *     <tr>
 *         <td>TH_TV_A</td>
 *         <td>Content unsuitable for children and youngsters</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="4">TR_TV</td>
 *         <td>TR_TV_G</td>
 *         <td>General audience. Suitable for all ages</td>
 *     </tr>
 *     <tr>
 *         <td>TR_TV_7</td>
 *         <td>Suitable for ages 7 and over</td>
 *     </tr>
 *     <tr>
 *         <td>TR_TV_13</td>
 *         <td>Suitable for ages 13 and over</td>
 *     </tr>
 *     <tr>
 *         <td>TR_TV_18</td>
 *         <td>Suitable for ages 13 and over</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="4">TW_TV</td>
 *         <td>TW_TV_G</td>
 *         <td>For all ages</td>
 *     </tr>
 *     <tr>
 *         <td>TW_TV_P</td>
 *         <td>Not suitable for children under 6 years old. People aged 6 but under 12 require
 *         guidance from accompanying adults to watch</td>
 *     </tr>
 *     <tr>
 *         <td>TW_TV_PG</td>
 *         <td>Not suitable for people under 12 years of age. Parental guidance is required for
 *         people aged 12 but under 18</td>
 *     </tr>
 *     <tr>
 *         <td>TW_TV_R</td>
 *         <td>For adults only and people under 18 years of age must not watch</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="3">UA_TV</td>
 *         <td>UA_TV_G</td>
 *         <td>This program does not have age restrictions</td>
 *     </tr>
 *     <tr>
 *         <td>UA_TV_Y</td>
 *         <td>Children must view this program with parents. In it program there are fragments,
 *         which unsuitable for children</td>
 *     </tr>
 *     <tr>
 *         <td>UA_TV_R</td>
 *         <td>This program is only for adult viewers. In it there are scenes with nudity, drug use,
 *         or violence</td>
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
 *     <tr>
 *         <td valign="top" rowspan="3">VE_TV</td>
 *         <td>VE_TV_TU</td>
 *         <td>For all ages</td>
 *     </tr>
 *     <tr>
 *         <td>VE_TV_SU</td>
 *         <td>Parental guidance for young viewers</td>
 *     </tr>
 *     <tr>
 *         <td>VE_TV_A</td>
 *         <td>Mature viewers</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">ZA_TV</td>
 *         <td>ZA_TV_F</td>
 *         <td>This is a program/film that does not contain any obscenity, and is suitable for
 *         family viewing. A logo must be displayed in the corner of the screen for 30 seconds after
 *         each commercial break</td>
 *     </tr>
 *     <tr>
 *         <td>ZA_TV_PG</td>
 *         <td>Children under 6 may watch this program/film, but must be accompanied by an adult.
 *         This program contains an adult related theme, which might include very mild language,
 *         violence and sexual innuendo. A logo must be displayed in the corner of the screen for
 *         one minute after each commercial break</td>
 *     </tr>
 *     <tr>
 *         <td>ZA_TV_13</td>
 *         <td>Children under 13 are prohibited from watching this program/film. This program
 *         contains mild language, violence and sexual innuendo. A logo must be displayed in the
 *         corner of the screen for two minutes after each commercial break</td>
 *     </tr>
 *     <tr>
 *         <td>ZA_TV_16</td>
 *         <td>Children under 16 are prohibited from watching this program/film. It contains
 *         moderate violence, language, and some sexual situations. In the case of television, this
 *         program may only be broadcast after 9pm-4:30am. A logo must be displayed in the corner of
 *         the screen for five minutes after each commercial break. A full-screen warning must be
 *         issued before the start of the program. If the program is longer than an hour, a warning
 *         must be displayed every half an hour</td>
 *     </tr>
 *     <tr>
 *         <td>ZA_TV_18</td>
 *         <td>Children under 18 are prohibited from watching this program/film. It contains extreme
 *         violence, language and/or graphic sexual content. In the case of television, this program
 *         may only be broadcast from 10pm-4:30am. A logo must be displayed in the corner of the
 *         screen for the duration of the program. A full-screen warning must be issued before the
 *         start of the program and after each commercial break</td>
 *     </tr>
 *     <tr>
 *         <td>ZA_TV_R18</td>
 *         <td>This is reserved for films of an extreme sexual nature (pornography). R18 films may
 *         only be distributed in the form of video and DVD in a controlled environment (e.g. Adult
 *         Shops). No public viewing of this film may take place. R18 films may not be broadcast on
 *         television and in cinemas</td>
 *     </tr>
 * </table>
 *
 * <h4>System defined strings for sub-ratings</h4>
 * <table>
 *     <tr>
 *         <th>Rating System</th>
 *         <th>Constant Value</th>
 *         <th>Comment</th>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">NL_TV</td>
 *         <td>NL_TV_V</td>
 *         <td>Violence<br/>Applicable to NL_TV_AL, NL_TV_6, NL_TV_9, NL_TV_12, NL_TV_16</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_F</td>
 *         <td>Scary or Disturbing ContentViolence<br/>Applicable to NL_TV_AL, NL_TV_6, NL_TV_9,
 *         NL_TV_12, NL_TV_16</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_S</td>
 *         <td>Sexual Content<br/>Applicable to NL_TV_AL, NL_TV_6, NL_TV_9, NL_TV_12, NL_TV_16</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_D</td>
 *         <td>Discrimination<br/>Applicable to NL_TV_AL, NL_TV_6, NL_TV_9, NL_TV_12, NL_TV_16</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_DA</td>
 *         <td>Drug and/or Alcohol abuse<br/>Applicable to NL_TV_AL, NL_TV_6, NL_TV_9, NL_TV_12,
 *         NL_TV_16</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_L</td>
 *         <td>Bad Language<br/>Applicable to NL_TV_AL, NL_TV_6, NL_TV_9, NL_TV_12, NL_TV_16</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="4">NZ_PTV</td>
 *         <td>NZ_PTV_C</td>
 *         <td>Content may offend<br/>Applicable to NZ_PTV_PG, NZ_PTV_M, NZ_PTV_16, NZ_PTV_18</td>
 *     </tr>
 *     <tr>
 *         <td>NZ_PTV_V</td>
 *         <td>Violence<br/>Applicable to NZ_PTV_PG, NZ_PTV_M, NZ_PTV_16, NZ_PTV_18</td>
 *     </tr>
 *     <tr>
 *         <td>NZ_PTV_L</td>
 *         <td>Language<br/>Applicable to NZ_PTV_PG, NZ_PTV_M, NZ_PTV_16, NZ_PTV_18</td>
 *     </tr>
 *     <tr>
 *         <td>NZ_PTV_S</td>
 *         <td>Sexual content<br/>Applicable to NZ_PTV_PG, NZ_PTV_M, NZ_PTV_16, NZ_PTV_18</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="5">US_TV</td>
 *         <td>US_TV_D</td>
 *         <td>Suggestive dialogue (Usually means talks about sex)<br/>Applicable to US_TV_PG,
 *         US_TV_14, US_TV</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_L</td>
 *         <td>Coarse language<br/>Applicable to US_TV_PG, US_TV_14</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_S</td>
 *         <td>Sexual content<br/>Applicable to US_TV_PG, US_TV_14, US_TV_MA</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_V</td>
 *         <td>Violence<br/>Applicable to US_TV_PG, US_TV_14, US_TV_MA</td>
 *     </tr>
 *     <tr>
 *         <td>US_TV_FV</td>
 *         <td>Fantasy violence (Children's programming only)<br/>Applicable to US_TV_Y7</td>
 *     </tr>
 *     <tr>
 *         <td valign="top" rowspan="6">ZA_TV</td>
 *         <td>ZA_TV_D</td>
 *         <td>Drug<br/>Applicable to ZA_TV_F, ZA_TV_PG, ZA_TV_13, ZA_TV_16, ZA_TV_18, ZA_TV_R18
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>ZA_TV_V</td>
 *         <td>Violence<br/>Applicable to ZA_TV_F, ZA_TV_PG, ZA_TV_13, ZA_TV_16, ZA_TV_18, ZA_TV_R18
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>ZA_TV_N</td>
 *         <td>Nudity<br/>Applicable to ZA_TV_F, ZA_TV_PG, ZA_TV_13, ZA_TV_16, ZA_TV_18, ZA_TV_R18
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>ZA_TV_P</td>
 *         <td>Prejudice<br/>Applicable to ZA_TV_F, ZA_TV_PG, ZA_TV_13, ZA_TV_16, ZA_TV_18,
 *         ZA_TV_R18</td>
 *     </tr>
 *     <tr>
 *         <td>ZA_TV_S</td>
 *         <td>Sex<br/>Applicable to ZA_TV_F, ZA_TV_PG, ZA_TV_13, ZA_TV_16, ZA_TV_18, ZA_TV_R18</td>
 *     </tr>
 *     <tr>
 *         <td>ZA_TV_L</td>
 *         <td>Language<br/>Applicable to ZA_TV_F, ZA_TV_PG, ZA_TV_13, ZA_TV_16, ZA_TV_18, ZA_TV_R18
 *         </td>
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
     * <p>
     * For example, a {@code TvContentRating} object that represents TV-PG with S(Sexual content)
     * and V(Violence) contains TV-PG, TV-PG/S, TV-PG/V and itself.
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
