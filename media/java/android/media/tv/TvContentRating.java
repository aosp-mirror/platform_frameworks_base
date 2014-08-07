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
 *     <!--tr>
 *         <td>AM_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>AR_TV</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>AU_TV</td>
 *         <td>Australian TV Classification</td>
 *     </tr>
 *     <!--tr>
 *         <td>BG_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>BR_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>CA_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>CH_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>CL_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>CO_TV</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>DE_TV</td>
 *         <td>The Germany television rating system</td>
 *     </tr>
 *     <!--tr>
 *         <td>DK_TV</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>ES_TV</td>
 *         <td>The Spanish rating system for television programs</td>
 *     </tr>
 *     <!--tr>
 *         <td>FI_TV</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>FR_TV</td>
 *         <td>The content rating system in French</td>
 *     </tr>
 *     <!--tr>
 *         <td>GR_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>HK_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>HU_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>ID_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IE_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IL_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IN_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IS_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IT_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>KH_TV</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>KR_TV</td>
 *         <td>The South Korean television rating system</td>
 *     </tr>
 *     <!--tr>
 *         <td>MV_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>MX_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>MY_TV</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>NL_TV</td>
 *         <td>The television rating system in the Netherlands</td>
 *     </tr>
 *     <!--tr>
 *         <td>NZ_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PE_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PH_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PL_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PT_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>RO_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>RU_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>RS_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>SG_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>SI_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>TH_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>TR_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>TW_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>UA_TV</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>US_TVPG</td>
 *         <td>The TV Parental Guidelines for US TV content ratings</td>
 *     </tr>
 *     <!--tr>
 *         <td>VE_TV</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>ZA_TV</td>
 *         <td></td>
 *     </tr-->
 * </table>
 *
 * <u>System defined string for {@code rating}</u>
 * <table border="0" cellspacing="0" cellpadding="0">
 *     <tr>
 *         <td>String value</td>
 *         <td>Comments</td>
 *     </tr>
 *     <!--tr>
 *         <td>AM_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>AR_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>AU_TV_CTC</td>
 *         <td>A rating string for {@code AU_TV}. The content has been assessed and approved for
 *         advertising unclassified films. Any advertising of unclassified films must display the
 *         CTC message.</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_G</td>
 *         <td>A rating string for {@code AU_TV}. The content is very mild in impact. The G
 *         classification is suitable for everyone. G products may contain classifiable elements
 *         such as language and themes that are very mild in impact. However, some G-classified
 *         films may contain content that is not of interest to children.</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_PG</td>
 *         <td>A rating string for {@code AU_TV}. The content is mild in impact. The impact of PG
 *         (Parental Guidance) classified films should be no higher than mild, but they may contain
 *         content that children find confusing or upsetting and may require the guidance or parents
 *         and guardians. They may, for example, contain classifiable elements such as language and
 *         themes that are mild in impact. It is not recommended for viewing or playing by persons
 *         under 15 without guidance from parents or guardians.</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_M</td>
 *         <td>A rating string for {@code AU_TV}. The content is moderate in impact. Films
 *         classified M (Mature) contain content of a moderate impact and are recommended for
 *         teenagers aged 15 years and over. Children under 15 may legally access this material
 *         because it is an advisory category. However, M classified films may include classifiable
 *         elements such as violence and nudity of moderate impact that are not recommended for
 *         children under 15 years. Parents and guardians may need to find out more about the film’s
 *         specific content, before deciding whether the material is suitable for their child.</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_MA16</td>
 *         <td>A rating string for {@code AU_TV}. The content is strong in impact. MA 15+ classified
 *         material contains strong content and is legally restricted to persons 15 years and over.
 *         It may contain classifiable elements such as sex scenes and drug use that are strong in
 *         impact. A person may be asked to show proof of their age before hiring or purchasing an
 *         MA 15+ film. Cinema staff may also request that the person show proof of their age before
 *         allowing them to watch an MA 15+ film. Children under the age of 15 may not legally
 *         watch, buy or hire MA 15+ classified material unless they are in the company of a parent
 *         or adult guardian. Children under 15 who go to the cinema to see an MA 15+ film must be
 *         accompanied by a parent or adult guardian for the duration of the film. The parent or
 *         adult guardian must also purchase the movie ticket for the child. The guardian must be
 *         an adult exercising parental control over the person under 15 years of age. The guardian
 *         needs to be 18 years or older.</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_R18</td>
 *         <td>A rating string for {@code AU_TV}. The content is high in impact. R 18+ classified
 *         material is restricted to adults. Such material may contain classifiable elements such as
 *         sex scenes and drug use that are high in impact. Some material classified R18+ may be
 *         offensive to sections of the adult community. A person may be asked for proof of their
 *         age before purchasing, hiring or viewing R18+ films at a retail store or cinema.</td>
 *     </tr>
 *     <tr>
 *         <td>AU_TV_X18</td>
 *         <td>A rating string for {@code AU_TV}. X 18+ films are restricted to adults. This
 *         classification is a special and legally restricted category which contains only sexually
 *         explicit content. That is, material which shows actual sexual intercourse and other
 *         sexual activity between consenting adults. X18+ films are only available for sale or hire
 *         in the ACT and the NT.</td>
 *     </tr>
 *     <!--tr>
 *         <td>BG_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>BR_TV_L</td>
 *         <td>A rating string for {@code BR_TV}. This classification applies to works which contain
 *         predominantly positive contents and which do not bring unsuitable elements subject to
 *         ratings to ages higher than 10, such as the ones listed below:
 *         <dl>
 *         <dd><b>Violence</b>: Fantasy violence; display of arms with no violence; deaths with no
 *         violence; bones and skeletons with no violence.</dd>
 *         <dd><b>Sex and Nudity</b>: Non-erotic nudity.</dd>
 *         <dd><b>Drugs</b>: Moderate or insinuated use of legal drugs.</dd>
 *         </dl>
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_10</td>
 *         <td>A rating string for {@code BR_TV}. Not recommended for ages under 10. The following
 *         contents are accepted for this age range:
 *         <dl>
 *         <dd><b>Violence</b>: Display of arms with violence; fear/tension; distress; bones and
 *         skeletons with signs of violent acts; criminal acts without violence; derogatory
 *         language.</dd>
 *         <dd><b>Sex and Nudity</b>: Educational contents about sex.</dd>
 *         <dd><b>Drugs</b>: Oral description of the use of legal drugs; discussion on the issue
 *         "drug trafficking"; medicinal use of illegal drugs.</dd>
 *         </dl>
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_12</td>
 *         <td>A rating string for {@code BR_TV}. Not recommended for ages under 12. The following
 *         contents are accepted for this age range:
 *         <dl>
 *         <dd><b>Violence</b>: Violent act; body injury; description of violence; presence of
 *         blood; victim's grief; natural or accidental death with violence; violent act against
 *         animals; exposure to danger; showing people in embarrassing or degrading situations;
 *         verbal aggression; obscenity; bullying; corpses; sexual harassment; overvaluation of the
 *         physical beauty; overvaluation of consumption.</dd>
 *         <dd><b>Sex and Nudity</b>: Veiled nudity; sexual innuendo; sexual fondling; masturbation;
 *         foul language; sex content language; sex simulation; sexual appeal.</dd>
 *         <dd><b>Drugs</b>: Use of legal drugs; inducing the use of legal drugs; irregular use of
 *         medication; mention to illegal drugs.</dd>
 *         </dl>
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_14</td>
 *         <td>A rating string for {@code BR_TV}. Not recommended for ages under 14. The following
 *         contents are accepted for this age range:
 *         <dl>
 *         <dd><b>Violence</b>: Intentional death; stigma/prejudice.</dd>
 *         <dd><b>Sex and Nudity</b>: Nudity; erotization; vulgarity; sexual intercourse;
 *         prostitution.</dd>
 *         <dd><b>Drugs</b>: Insinuation of the use of illegal drugs; verbal descriptions of the use
 *         or trafficking of illegal drugs; discussion on the "decriminalization of illegal drugs".</dd>
 *         </dl>
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_16</td>
 *         <td>A rating string for {@code BR_TV}. Not recommended for ages under 16. The following
 *         contents are accepted for this age range:
 *         <dl>
 *         <dd><b>Violence</b>: Rape; sexual exploitation; sexual coercion; torture; mutilation;
 *         suicide; gratuitous violence/banalization of violence; abortion, death penalty,
 *         euthanasia.</dd>
 *         <dd><b>Sex and Nudity</b>: Intense sexual intercourse.</dd>
 *         <dd><b>Drugs</b>: Production or trafficking of any illegal drug; use of illegal drugs;
 *         inducing the use of illegal drugs.</dd>
 *         </dl>
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>BR_TV_18</td>
 *         <td>A rating string for {@code BR_TV}. Not recommended for ages under 18. The following
 *         contents are accepted for this age range:
 *         <dl>
 *         <dd><b>Violence</b>:  Violence of high impact; exaltation, glamorization and/or
 *         incitement to violence; cruelty; hate crimes; pedophilia.</dd>
 *         <dd><b>Sex and Nudity</b>: Explicit sex; complex/strong impact sexual intercourses
 *         (incest, group sex, violent fetish and pornography overall).</dd>
 *         <dd><b>Drugs</b>:  Inciting the use of illegal drugs.</dd>
 *         </dl>
 *         </td>
 *     </tr>
 *     <!--tr>
 *         <td>CA_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>CH_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>CL_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>CO_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>DE_TV_ALL</td>
 *         <td>Without restriction. There are time schedules and certain age groups which have to be
 *         considered. {@code DE_TV_ALL} is scheduled in daytime (6:00AM – 8:00PM). However, cinema
 *         films classified with "12" may be shown during the daytime, if they are not considered
 *         harmful to younger children.</td>
 *     </tr>
 *     <tr>
 *         <td>DE_TV_12</td>
 *         <td>Suitable for 12 years and above. There are time schedules and certain age groups
 *         which have to be considered. {@code DE_TV_12} is scheduled in primetime (from 8:00PM
 *         – 10.00 p.m.).</td>
 *     </tr>
 *     <tr>
 *         <td>DE_TV_16</td>
 *         <td>Suitable for 16 years and above. There are time schedules and certain age groups
 *         which have to be considered. {@code DE_TV_16} is scheduled in late evening (from 10:00PM
 *         - 11:00PM). </td>
 *     </tr>
 *     <tr>
 *         <td>DE_TV_18</td>
 *         <td>Suitable for 18 years and above. There are time schedules and certain age groups
 *         which have to be considered. {@code DE_TV_18} is scheduled in late night (from 11:00PM
 *         - 6:00AM). </td>
 *     </tr>
 *     <!--tr>
 *         <td>DK_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>ES_TV_ALL</td>
 *         <td>A rating string for {@code ES_TV}. This rating is for programs for all ages.</td>
 *     </tr>
 *     <tr>
 *         <td>ES_TV_I</td>
 *         <td>A rating string for {@code ES_TV}. This rating is for the recommended programs
 *         especially for children.</td>
 *     </tr>
 *     <tr>
 *         <td>ES_TV_7</td>
 *         <td>A rating string for {@code ES_TV}. This rating is for programs not recommended for
 *         children under 7.</td>
 *     </tr>
 *     <tr>
 *         <td>ES_TV_13</td>
 *         <td>A rating string for {@code ES_TV}. This rating is for programs not recommended for
 *         children under 13.</td>
 *     </tr>
 *     <tr>
 *         <td>ES_TV_18</td>
 *         <td>A rating string for {@code ES_TV}. This rating is for programs not recommended for
 *         children under 18.</td>
 *     </tr>
 *     <!--tr>
 *         <td>FI_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>FR_TV_ALL</td>
 *         <td>A rating string for {@code FR_TV}. According to CSA in France, if no rating appears,
 *         the program is most likely appropriate for all ages. In Android TV, however,
 *         {@code RATING_FR_ALL} is used for handling that case.</td>
 *     </tr>
 *     <tr>
 *         <td>FR_TV_10</td>
 *         <td>A rating string for {@code FR_TV}. This rating is for programs that are not
 *         recommended for children under 10.</td>
 *     </tr>
 *     <tr>
 *         <td>FR_TV_12</td>
 *         <td>A rating string for {@code FR_TV}. This rating is for programs that are not
 *         recommended for children under 12. Programs rated this are not allowed to air before
 *         10:00 pm (Some channels and programs are subject to exception). </td>
 *     </tr>
 *     <tr>
 *         <td>FR_TV_16</td>
 *         <td>A rating string for {@code FR_TV}. This rating is for programs that are not
 *         recommended for children under 16. Programs rated this are not allowed to air before
 *         10:30 pm (Some channels and programs are subject to exception). </td>
 *     </tr>
 *     <tr>
 *         <td>FR_TV_18</td>
 *         <td>A rating string for {@code FR_TV}.  This rating is for programs that are not
 *         recommended for persons under 18. Programs rated this are allowed between midnight and
 *         5 am and only on some channels. The access to these programs is locked by a personal
 *         password.</td>
 *     </tr>
 *     <!--tr>
 *         <td>GR_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>HK_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>HU_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>ID_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IE_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IL_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IN_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IS_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>KH_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>KR_TV_ALL</td>
 *         <td>A rating string for {@code KR_TV}. This rating is for programs that are appropriate
 *         for all ages. This program usually involves programs designed for children or families.
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_7</td>
 *         <td>A rating string for {@code KR_TV}. This rating is for programs that may contain
 *         material inappropriate for children younger than 7, and parental guidance is required.
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_12</td>
 *         <td>A rating string for {@code KR_TV}. This rating is for programs that may contain
 *         material inappropriate for children younger than 12, and parental guidance is required.
 *         </td>
 *     </tr>
 *     <tr>
 *         <td>KR_TV_15</td>
 *         <td>A rating string for {@code KR_TV}. This rating is for programs that may contain
 *         material inappropriate for children younger than 15, and parental guidance is required.
 *     </tr>
 *     <tr>
 *         <td>KR_TV_19</td>
 *         <td>A rating string for {@code KR_TV}. This rating is for programs designed for adults
 *         only.</td>
 *     </tr>
 *     <!--tr>
 *         <td>MV_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>MX_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>MY_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>NL_TV_AL</td>
 *         <td>A rating string for {@code NL_TV}. This rating is for programs that are appropriate
 *         for all ages.</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_6</td>
 *         <td>A rating string for {@code NL_TV}. This rating is for programs that require parental
 *         advisory for children under 6.</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_9</td>
 *         <td>A rating string for {@code NL_TV}. This rating is for programs that require parental
 *         advisory for children under 9.</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_12</td>
 *         <td>A rating string for {@code NL_TV}. This rating is for programs that require parental
 *         advisory for children under 12.</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_16</td>
 *         <td>A rating string for {@code NL_TV}. This rating is for programs that require parental
 *         advisory for children under 16.</td>
 *     </tr>
 *     <!--tr>
 *         <td>NZ_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PE_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PH_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PL_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PT_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>RO_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>RU_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>RS_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>SG_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>SI_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>TH_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>TR_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>TW_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>UA_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>US_TVPG_TV_Y</td>
 *         <td>A rating string for {@code US_TVPG}. Programs rated this are designed to be
 *         appropriate for all children. Whether animated or live-action, the themes and elements
 *         in this program are specifically designed for a very young audience, including children
 *         from ages 2-6. This program is not expected to frighten younger children.</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_TV_Y7</td>
 *         <td>A rating string for {@code US_TVPG}. Programs rated this are designed for children
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
 *         <td>A rating string for {@code US_TVPG}. Most parents would find this program suitable
 *         for all ages. Although this rating does not signify a program designed specifically for
 *         children, most parents may let younger children watch this program unattended. It
 *         contains little or no violence, no strong language and little or no sexual dialogue or
 *         situations.</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_TV_PG</td>
 *         <td>A rating string for {@code US_TVPG}. Programs rated this contain material that
 *         parents may find unsuitable for younger children. Many parents may want to watch it with
 *         their younger children. The theme itself may call for parental guidance and/or the
 *         program may contain one or more of the following: some suggestive dialogue (
 *         {@code US_TVPG_D}), infrequent coarse language ({@code US_TVPG_L}), some sexual
 *         situations ({@code US_TVPG_S}), or moderate violence ({@code US_TVPG_V}).</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_TV_14</td>
 *         <td>A rating string for {@code US_TVPG}. Programs rated this contains some material
 *         that many parents would find unsuitable for children under 14 years of age. Parents are
 *         strongly urged to exercise greater care in monitoring this program and are cautioned
 *         against letting children under the age of 14 watch unattended. This program may contain
 *         one or more of the following: intensely suggestive dialogue ({@code US_TVPG_D}), strong
 *         coarse language ({@code US_TVPG_L}), intense sexual situations ({@code US_TVPG_S}), or
 *         intense violence ({@code US_TVPG_V}).</td>
 *     </tr>
 *     <tr>
 *         <td>US_TVPG_TV_MA</td>
 *         <td>A rating string for {@code US_TVPG}. Programs rated TV-MA are specifically
 *         designed to be viewed by adults and therefore may be unsuitable for children under 17.
 *         This program may contain one or more of the following: crude indecent language
 *         ({@code US_TVPG_L}), explicit sexual activity ({@code US_TVPG_S}), or graphic violence
 *         ({@code US_TVPG_V}).</td>
 *     </tr>
 *     <!--tr>
 *         <td>VE_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>ZA_TV_ALL</td>
 *         <td></td>
 *     </tr-->
 * </table>
 *
 * <u>System defined string for {@code subRating}</u>
 * <table border="0" cellspacing="0" cellpadding="0">
 *     <tr>
 *         <td>String value</td>
 *         <td>Comments</td>
 *     </tr>
 *     <!--tr>
 *         <td>AM_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>AR_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>BG_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>CA_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>CH_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>CL_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>CO_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>DK_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>FI_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>GR_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>HK_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>HU_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>ID_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IE_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IL_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IN_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>IS_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>KH_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>MV_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>MX_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>MY_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <tr>
 *         <td>NL_TV_V</td>
 *         <td>Violence</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_F</td>
 *         <td>Fear</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_S</td>
 *         <td>Sex</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_D</td>
 *         <td>Discrimination</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_DA</td>
 *         <td>Drugs- and alcoholabuse</td>
 *     </tr>
 *     <tr>
 *         <td>NL_TV_L</td>
 *         <td>Coarse Language</td>
 *     </tr>
 *     <!--tr>
 *         <td>NZ_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PE_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PH_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PL_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>PT_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>RO_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>RU_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>RS_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>SG_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>SI_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>TH_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>TR_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>TW_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>UA_TV_</td>
 *         <td></td>
 *     </tr-->
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
 *     <!--tr>
 *         <td>VE_TV_</td>
 *         <td></td>
 *     </tr-->
 *     <!--tr>
 *         <td>ZA_TV_</td>
 *         <td></td>
 *     </tr-->
 * </table>
 */
public final class TvContentRating {
    /** @hide */
    public static final Uri SYSTEM_CONTENT_RATING_SYSTEM_XML = Uri.parse(
            "android.resource://system/" + com.android.internal.R.xml.tv_content_rating_systems);

    // TODO: Consider to use other DELIMITER. In some countries such as India may use this delimiter
    // in the main ratings.
    private static final String DELIMITER = "/";

    private final String mDomain;
    private final String mRatingSystem;
    private final String mRating;
    private final String[] mSubRatings;

    /**
     * Creates a TvContentRating object.
     *
     * @param domain The domain name.
     * @param ratingSystem The rating system id.
     * @param rating The content rating string.
     * @param subRatings The string array of sub-ratings.
     * @return A TvContentRating object, or null if creation failed.
     */
    public static TvContentRating createRating(String domain, String ratingSystem,
            String rating, String... subRatings) {
        if (TextUtils.isEmpty(domain)) {
            throw new IllegalArgumentException("domain cannot be empty");
        }
        if (TextUtils.isEmpty(rating)) {
            throw new IllegalArgumentException("rating cannot be empty");
        }
        return new TvContentRating(domain, ratingSystem, rating, subRatings);
    }

    /**
     * Recovers a TvContentRating from a String that was previously created with
     * {@link #flattenToString}.
     *
     * @param ratingString The String that was returned by flattenToString().
     * @return a new TvContentRating containing the domain, rating system, rating and
     *         sub-ratings information was encoded in {@code ratingString}.
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
     * @param domain The domain name.
     * @param ratingSystem The rating system id.
     * @param rating The content rating string.
     * @param subRatings The String array of sub-rating constants defined in this class.
     */
    private TvContentRating(
            String domain, String ratingSystem, String rating, String[] subRatings) {
        mDomain = domain;
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
