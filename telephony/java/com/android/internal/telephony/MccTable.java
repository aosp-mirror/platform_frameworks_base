/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.content.Context;
import android.content.res.Configuration;
import android.net.wifi.WifiManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import libcore.icu.TimeZones;

/**
 * Mobile Country Code
 *
 * {@hide}
 */
public final class MccTable
{
    static final String LOG_TAG = "MccTable";

    static ArrayList<MccEntry> table;

    static class MccEntry implements Comparable<MccEntry>
    {
        int mcc;
        String iso;
        int smallestDigitsMnc;
        String language;

        MccEntry(int mnc, String iso, int smallestDigitsMCC) {
            this(mnc, iso, smallestDigitsMCC, null);
        }

        MccEntry(int mnc, String iso, int smallestDigitsMCC, String language) {
            this.mcc = mnc;
            this.iso = iso;
            this.smallestDigitsMnc = smallestDigitsMCC;
            this.language = language;
        }


        public int compareTo(MccEntry o)
        {
            return mcc - o.mcc;
        }
    }

    private static MccEntry
    entryForMcc(int mcc)
    {
        int index;

        MccEntry m;

        m = new MccEntry(mcc, null, 0);

        index = Collections.binarySearch(table, m);

        if (index < 0) {
            return null;
        } else {
            return table.get(index);
        }
    }

    /**
     * Returns a default time zone ID for the given MCC.
     * @param mcc Mobile Country Code
     * @return default TimeZone ID, or null if not specified
     */
    public static String defaultTimeZoneForMcc(int mcc) {
        MccEntry entry;

        entry = entryForMcc(mcc);
        if (entry == null || entry.iso == null) {
            return null;
        } else {
            Locale locale;
            if (entry.language == null) {
                locale = new Locale(entry.iso);
            } else {
                locale = new Locale(entry.language, entry.iso);
            }
            String[] tz = TimeZones.forLocale(locale);
            if (tz.length == 0) return null;
            return tz[0];
        }
    }

    /**
     * Given a GSM Mobile Country Code, returns
     * an ISO two-character country code if available.
     * Returns "" if unavailable.
     */
    public static String
    countryCodeForMcc(int mcc)
    {
        MccEntry entry;

        entry = entryForMcc(mcc);

        if (entry == null) {
            return "";
        } else {
            return entry.iso;
        }
    }

    /**
     * Given a GSM Mobile Country Code, returns
     * an ISO 2-3 character language code if available.
     * Returns null if unavailable.
     */
    public static String defaultLanguageForMcc(int mcc) {
        MccEntry entry;

        entry = entryForMcc(mcc);

        if (entry == null) {
            return null;
        } else {
            return entry.language;
        }
    }

    /**
     * Given a GSM Mobile Country Code, returns
     * the smallest number of digits that M if available.
     * Returns 2 if unavailable.
     */
    public static int
    smallestDigitsMccForMnc(int mcc)
    {
        MccEntry entry;

        entry = entryForMcc(mcc);

        if (entry == null) {
            return 2;
        } else {
            return entry.smallestDigitsMnc;
        }
    }

    /**
     * Updates MCC and MNC device configuration information for application retrieving
     * correct version of resources.  If either MCC or MNC is 0, they will be ignored (not set).
     * @param phone PhoneBae to act on.
     * @param mccmnc truncated imsi with just the MCC and MNC - MNC assumed to be from 4th to end
     */
    public static void updateMccMncConfiguration(PhoneBase phone, String mccmnc) {
        if (!TextUtils.isEmpty(mccmnc)) {
            int mcc, mnc;

            try {
                mcc = Integer.parseInt(mccmnc.substring(0,3));
                mnc = Integer.parseInt(mccmnc.substring(3));
            } catch (NumberFormatException e) {
                Log.e(LOG_TAG, "Error parsing IMSI");
                return;
            }

            Log.d(LOG_TAG, "updateMccMncConfiguration: mcc=" + mcc + ", mnc=" + mnc);

            if (mcc != 0) {
                setTimezoneFromMccIfNeeded(phone, mcc);
                setLocaleFromMccIfNeeded(phone, mcc);
                setWifiCountryCodeFromMcc(phone, mcc);
            }
            try {
                Configuration config = ActivityManagerNative.getDefault().getConfiguration();
                if (mcc != 0) {
                    config.mcc = mcc;
                }
                if (mnc != 0) {
                    config.mnc = mnc;
                }
                ActivityManagerNative.getDefault().updateConfiguration(config);
            } catch (RemoteException e) {
                Log.e(LOG_TAG, "Can't update configuration", e);
            }
        }
    }

    /**
     * If the timezone is not already set, set it based on the MCC of the SIM.
     * @param phone PhoneBase to act on (get context from).
     * @param mcc Mobile Country Code of the SIM or SIM-like entity (build prop on CDMA)
     */
    private static void setTimezoneFromMccIfNeeded(PhoneBase phone, int mcc) {
        String timezone = SystemProperties.get(ServiceStateTracker.TIMEZONE_PROPERTY);
        if (timezone == null || timezone.length() == 0) {
            String zoneId = defaultTimeZoneForMcc(mcc);
            if (zoneId != null && zoneId.length() > 0) {
                Context context = phone.getContext();
                // Set time zone based on MCC
                AlarmManager alarm =
                        (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                alarm.setTimeZone(zoneId);
                Log.d(LOG_TAG, "timezone set to "+zoneId);
            }
        }
    }

    /**
     * If the locale is not already set, set it based on the MCC of the SIM.
     * @param phone PhoneBase to act on.
     * @param mcc Mobile Country Code of the SIM or SIM-like entity (build prop on CDMA)
     */
    private static void setLocaleFromMccIfNeeded(PhoneBase phone, int mcc) {
        String language = MccTable.defaultLanguageForMcc(mcc);
        String country = MccTable.countryCodeForMcc(mcc);

        Log.d(LOG_TAG, "locale set to "+language+"_"+country);
        phone.setSystemLocale(language, country, true);
    }

    /**
     * If the number of allowed wifi channels has not been set, set it based on
     * the MCC of the SIM.
     * @param phone PhoneBase to act on (get context from).
     * @param mcc Mobile Country Code of the SIM or SIM-like entity (build prop on CDMA)
     */
    private static void setWifiCountryCodeFromMcc(PhoneBase phone, int mcc) {
        String country = MccTable.countryCodeForMcc(mcc);
        if (!country.isEmpty()) {
            Context context = phone.getContext();
            Log.d(LOG_TAG, "WIFI_COUNTRY_CODE set to " + country);
            WifiManager wM = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            //persist
            wM.setCountryCode(country, true);
        }
    }

    static {
        table = new ArrayList<MccEntry>(240);


        /*
         * The table below is built from two resources:
         *
         * 1) ITU "Mobile Network Code (MNC) for the international
         *   identification plan for mobile terminals and mobile users"
         *   which is available as an annex to the ITU operational bulletin
         *   available here: http://www.itu.int/itu-t/bulletin/annex.html
         *
         * 2) The ISO 3166 country codes list, available here:
         *    http://www.iso.org/iso/en/prods-services/iso3166ma/02iso-3166-code-lists/index.html
         *
         * This table has not been verified.
         *
         */

		table.add(new MccEntry(202,"gr",2));	//Greece
		table.add(new MccEntry(204,"nl",2,"nl"));	//Netherlands (Kingdom of the)
		table.add(new MccEntry(206,"be",2));	//Belgium
		table.add(new MccEntry(208,"fr",2,"fr"));	//France
		table.add(new MccEntry(212,"mc",2));	//Monaco (Principality of)
		table.add(new MccEntry(213,"ad",2));	//Andorra (Principality of)
		table.add(new MccEntry(214,"es",2,"es"));	//Spain
		table.add(new MccEntry(216,"hu",2));	//Hungary (Republic of)
		table.add(new MccEntry(218,"ba",2));	//Bosnia and Herzegovina
		table.add(new MccEntry(219,"hr",2));	//Croatia (Republic of)
		table.add(new MccEntry(220,"rs",2));	//Serbia and Montenegro
		table.add(new MccEntry(222,"it",2,"it"));	//Italy
		table.add(new MccEntry(225,"va",2,"it"));	//Vatican City State
		table.add(new MccEntry(226,"ro",2));	//Romania
		table.add(new MccEntry(228,"ch",2,"de"));	//Switzerland (Confederation of)
		table.add(new MccEntry(230,"cz",2,"cs"));	//Czech Republic
		table.add(new MccEntry(231,"sk",2));	//Slovak Republic
		table.add(new MccEntry(232,"at",2,"de"));	//Austria
		table.add(new MccEntry(234,"gb",2,"en"));	//United Kingdom of Great Britain and Northern Ireland
		table.add(new MccEntry(235,"gb",2,"en"));	//United Kingdom of Great Britain and Northern Ireland
		table.add(new MccEntry(238,"dk",2));	//Denmark
		table.add(new MccEntry(240,"se",2));	//Sweden
		table.add(new MccEntry(242,"no",2));	//Norway
		table.add(new MccEntry(244,"fi",2));	//Finland
		table.add(new MccEntry(246,"lt",2));	//Lithuania (Republic of)
		table.add(new MccEntry(247,"lv",2));	//Latvia (Republic of)
		table.add(new MccEntry(248,"ee",2));	//Estonia (Republic of)
		table.add(new MccEntry(250,"ru",2));	//Russian Federation
		table.add(new MccEntry(255,"ua",2));	//Ukraine
		table.add(new MccEntry(257,"by",2));	//Belarus (Republic of)
		table.add(new MccEntry(259,"md",2));	//Moldova (Republic of)
		table.add(new MccEntry(260,"pl",2));	//Poland (Republic of)
		table.add(new MccEntry(262,"de",2,"de"));	//Germany (Federal Republic of)
		table.add(new MccEntry(266,"gi",2));	//Gibraltar
		table.add(new MccEntry(268,"pt",2));	//Portugal
		table.add(new MccEntry(270,"lu",2));	//Luxembourg
		table.add(new MccEntry(272,"ie",2,"en"));	//Ireland
		table.add(new MccEntry(274,"is",2));	//Iceland
		table.add(new MccEntry(276,"al",2));	//Albania (Republic of)
		table.add(new MccEntry(278,"mt",2));	//Malta
		table.add(new MccEntry(280,"cy",2));	//Cyprus (Republic of)
		table.add(new MccEntry(282,"ge",2));	//Georgia
		table.add(new MccEntry(283,"am",2));	//Armenia (Republic of)
		table.add(new MccEntry(284,"bg",2));	//Bulgaria (Republic of)
		table.add(new MccEntry(286,"tr",2));	//Turkey
		table.add(new MccEntry(288,"fo",2));	//Faroe Islands
                table.add(new MccEntry(289,"ge",2));    //Abkhazia (Georgia)
		table.add(new MccEntry(290,"gl",2));	//Greenland (Denmark)
		table.add(new MccEntry(292,"sm",2));	//San Marino (Republic of)
		table.add(new MccEntry(293,"si",2));	//Slovenia (Republic of)
                table.add(new MccEntry(294,"mk",2));   //The Former Yugoslav Republic of Macedonia
		table.add(new MccEntry(295,"li",2));	//Liechtenstein (Principality of)
                table.add(new MccEntry(297,"me",2));    //Montenegro (Republic of)
		table.add(new MccEntry(302,"ca",3,""));	//Canada
		table.add(new MccEntry(308,"pm",2));	//Saint Pierre and Miquelon (Collectivit territoriale de la Rpublique franaise)
		table.add(new MccEntry(310,"us",3,"en"));	//United States of America
		table.add(new MccEntry(311,"us",3,"en"));	//United States of America
		table.add(new MccEntry(312,"us",3,"en"));	//United States of America
		table.add(new MccEntry(313,"us",3,"en"));	//United States of America
		table.add(new MccEntry(314,"us",3,"en"));	//United States of America
		table.add(new MccEntry(315,"us",3,"en"));	//United States of America
		table.add(new MccEntry(316,"us",3,"en"));	//United States of America
		table.add(new MccEntry(330,"pr",2));	//Puerto Rico
		table.add(new MccEntry(332,"vi",2));	//United States Virgin Islands
		table.add(new MccEntry(334,"mx",3));	//Mexico
		table.add(new MccEntry(338,"jm",3));	//Jamaica
		table.add(new MccEntry(340,"gp",2));	//Guadeloupe (French Department of)
		table.add(new MccEntry(342,"bb",3));	//Barbados
		table.add(new MccEntry(344,"ag",3));	//Antigua and Barbuda
		table.add(new MccEntry(346,"ky",3));	//Cayman Islands
		table.add(new MccEntry(348,"vg",3));	//British Virgin Islands
		table.add(new MccEntry(350,"bm",2));	//Bermuda
		table.add(new MccEntry(352,"gd",2));	//Grenada
		table.add(new MccEntry(354,"ms",2));	//Montserrat
		table.add(new MccEntry(356,"kn",2));	//Saint Kitts and Nevis
		table.add(new MccEntry(358,"lc",2));	//Saint Lucia
		table.add(new MccEntry(360,"vc",2));	//Saint Vincent and the Grenadines
		table.add(new MccEntry(362,"nl",2));	//Netherlands Antilles
		table.add(new MccEntry(363,"aw",2));	//Aruba
		table.add(new MccEntry(364,"bs",2));	//Bahamas (Commonwealth of the)
		table.add(new MccEntry(365,"ai",3));	//Anguilla
		table.add(new MccEntry(366,"dm",2));	//Dominica (Commonwealth of)
		table.add(new MccEntry(368,"cu",2));	//Cuba
		table.add(new MccEntry(370,"do",2));	//Dominican Republic
		table.add(new MccEntry(372,"ht",2));	//Haiti (Republic of)
		table.add(new MccEntry(374,"tt",2));	//Trinidad and Tobago
		table.add(new MccEntry(376,"tc",2));	//Turks and Caicos Islands
		table.add(new MccEntry(400,"az",2));	//Azerbaijani Republic
		table.add(new MccEntry(401,"kz",2));	//Kazakhstan (Republic of)
		table.add(new MccEntry(402,"bt",2));	//Bhutan (Kingdom of)
		table.add(new MccEntry(404,"in",2));	//India (Republic of)
		table.add(new MccEntry(405,"in",2));	//India (Republic of)
		table.add(new MccEntry(410,"pk",2));	//Pakistan (Islamic Republic of)
		table.add(new MccEntry(412,"af",2));	//Afghanistan
		table.add(new MccEntry(413,"lk",2));	//Sri Lanka (Democratic Socialist Republic of)
		table.add(new MccEntry(414,"mm",2));	//Myanmar (Union of)
		table.add(new MccEntry(415,"lb",2));	//Lebanon
		table.add(new MccEntry(416,"jo",2));	//Jordan (Hashemite Kingdom of)
		table.add(new MccEntry(417,"sy",2));	//Syrian Arab Republic
		table.add(new MccEntry(418,"iq",2));	//Iraq (Republic of)
		table.add(new MccEntry(419,"kw",2));	//Kuwait (State of)
		table.add(new MccEntry(420,"sa",2));	//Saudi Arabia (Kingdom of)
		table.add(new MccEntry(421,"ye",2));	//Yemen (Republic of)
		table.add(new MccEntry(422,"om",2));	//Oman (Sultanate of)
                table.add(new MccEntry(423,"ps",2));    //Palestine
		table.add(new MccEntry(424,"ae",2));	//United Arab Emirates
		table.add(new MccEntry(425,"il",2));	//Israel (State of)
		table.add(new MccEntry(426,"bh",2));	//Bahrain (Kingdom of)
		table.add(new MccEntry(427,"qa",2));	//Qatar (State of)
		table.add(new MccEntry(428,"mn",2));	//Mongolia
		table.add(new MccEntry(429,"np",2));	//Nepal
		table.add(new MccEntry(430,"ae",2));	//United Arab Emirates
		table.add(new MccEntry(431,"ae",2));	//United Arab Emirates
		table.add(new MccEntry(432,"ir",2));	//Iran (Islamic Republic of)
		table.add(new MccEntry(434,"uz",2));	//Uzbekistan (Republic of)
		table.add(new MccEntry(436,"tj",2));	//Tajikistan (Republic of)
		table.add(new MccEntry(437,"kg",2));	//Kyrgyz Republic
		table.add(new MccEntry(438,"tm",2));	//Turkmenistan
		table.add(new MccEntry(440,"jp",2,"ja"));	//Japan
		table.add(new MccEntry(441,"jp",2,"ja"));	//Japan
		table.add(new MccEntry(450,"kr",2,"ko"));	//Korea (Republic of)
		table.add(new MccEntry(452,"vn",2));	//Viet Nam (Socialist Republic of)
		table.add(new MccEntry(454,"hk",2));	//"Hong Kong, China"
		table.add(new MccEntry(455,"mo",2));	//"Macao, China"
		table.add(new MccEntry(456,"kh",2));	//Cambodia (Kingdom of)
		table.add(new MccEntry(457,"la",2));	//Lao People's Democratic Republic
		table.add(new MccEntry(460,"cn",2,"zh"));	//China (People's Republic of)
		table.add(new MccEntry(461,"cn",2,"zh"));	//China (People's Republic of)
		table.add(new MccEntry(466,"tw",2));	//"Taiwan, China"
		table.add(new MccEntry(467,"kp",2));	//Democratic People's Republic of Korea
		table.add(new MccEntry(470,"bd",2));	//Bangladesh (People's Republic of)
		table.add(new MccEntry(472,"mv",2));	//Maldives (Republic of)
		table.add(new MccEntry(502,"my",2));	//Malaysia
		table.add(new MccEntry(505,"au",2,"en"));	//Australia
		table.add(new MccEntry(510,"id",2));	//Indonesia (Republic of)
		table.add(new MccEntry(514,"tl",2));	//Democratic Republic of Timor-Leste
		table.add(new MccEntry(515,"ph",2));	//Philippines (Republic of the)
		table.add(new MccEntry(520,"th",2));	//Thailand
		table.add(new MccEntry(525,"sg",2,"en"));	//Singapore (Republic of)
		table.add(new MccEntry(528,"bn",2));	//Brunei Darussalam
		table.add(new MccEntry(530,"nz",2, "en"));	//New Zealand
		table.add(new MccEntry(534,"mp",2));	//Northern Mariana Islands (Commonwealth of the)
		table.add(new MccEntry(535,"gu",2));	//Guam
		table.add(new MccEntry(536,"nr",2));	//Nauru (Republic of)
		table.add(new MccEntry(537,"pg",2));	//Papua New Guinea
		table.add(new MccEntry(539,"to",2));	//Tonga (Kingdom of)
		table.add(new MccEntry(540,"sb",2));	//Solomon Islands
		table.add(new MccEntry(541,"vu",2));	//Vanuatu (Republic of)
		table.add(new MccEntry(542,"fj",2));	//Fiji (Republic of)
		table.add(new MccEntry(543,"wf",2));	//Wallis and Futuna (Territoire franais d'outre-mer)
		table.add(new MccEntry(544,"as",2));	//American Samoa
		table.add(new MccEntry(545,"ki",2));	//Kiribati (Republic of)
		table.add(new MccEntry(546,"nc",2));	//New Caledonia (Territoire franais d'outre-mer)
		table.add(new MccEntry(547,"pf",2));	//French Polynesia (Territoire franais d'outre-mer)
		table.add(new MccEntry(548,"ck",2));	//Cook Islands
		table.add(new MccEntry(549,"ws",2));	//Samoa (Independent State of)
		table.add(new MccEntry(550,"fm",2));	//Micronesia (Federated States of)
		table.add(new MccEntry(551,"mh",2));	//Marshall Islands (Republic of the)
		table.add(new MccEntry(552,"pw",2));	//Palau (Republic of)
		table.add(new MccEntry(602,"eg",2));	//Egypt (Arab Republic of)
		table.add(new MccEntry(603,"dz",2));	//Algeria (People's Democratic Republic of)
		table.add(new MccEntry(604,"ma",2));	//Morocco (Kingdom of)
		table.add(new MccEntry(605,"tn",2));	//Tunisia
		table.add(new MccEntry(606,"ly",2));	//Libya (Socialist People's Libyan Arab Jamahiriya)
		table.add(new MccEntry(607,"gm",2));	//Gambia (Republic of the)
		table.add(new MccEntry(608,"sn",2));	//Senegal (Republic of)
		table.add(new MccEntry(609,"mr",2));	//Mauritania (Islamic Republic of)
		table.add(new MccEntry(610,"ml",2));	//Mali (Republic of)
		table.add(new MccEntry(611,"gn",2));	//Guinea (Republic of)
		table.add(new MccEntry(612,"ci",2));	//Cte d'Ivoire (Republic of)
		table.add(new MccEntry(613,"bf",2));	//Burkina Faso
		table.add(new MccEntry(614,"ne",2));	//Niger (Republic of the)
		table.add(new MccEntry(615,"tg",2));	//Togolese Republic
		table.add(new MccEntry(616,"bj",2));	//Benin (Republic of)
		table.add(new MccEntry(617,"mu",2));	//Mauritius (Republic of)
		table.add(new MccEntry(618,"lr",2));	//Liberia (Republic of)
		table.add(new MccEntry(619,"sl",2));	//Sierra Leone
		table.add(new MccEntry(620,"gh",2));	//Ghana
		table.add(new MccEntry(621,"ng",2));	//Nigeria (Federal Republic of)
		table.add(new MccEntry(622,"td",2));	//Chad (Republic of)
		table.add(new MccEntry(623,"cf",2));	//Central African Republic
		table.add(new MccEntry(624,"cm",2));	//Cameroon (Republic of)
		table.add(new MccEntry(625,"cv",2));	//Cape Verde (Republic of)
		table.add(new MccEntry(626,"st",2));	//Sao Tome and Principe (Democratic Republic of)
		table.add(new MccEntry(627,"gq",2));	//Equatorial Guinea (Republic of)
		table.add(new MccEntry(628,"ga",2));	//Gabonese Republic
		table.add(new MccEntry(629,"cg",2));	//Congo (Republic of the)
		table.add(new MccEntry(630,"cg",2));	//Democratic Republic of the Congo
		table.add(new MccEntry(631,"ao",2));	//Angola (Republic of)
		table.add(new MccEntry(632,"gw",2));	//Guinea-Bissau (Republic of)
		table.add(new MccEntry(633,"sc",2));	//Seychelles (Republic of)
		table.add(new MccEntry(634,"sd",2));	//Sudan (Republic of the)
		table.add(new MccEntry(635,"rw",2));	//Rwanda (Republic of)
		table.add(new MccEntry(636,"et",2));	//Ethiopia (Federal Democratic Republic of)
		table.add(new MccEntry(637,"so",2));	//Somali Democratic Republic
		table.add(new MccEntry(638,"dj",2));	//Djibouti (Republic of)
		table.add(new MccEntry(639,"ke",2));	//Kenya (Republic of)
		table.add(new MccEntry(640,"tz",2));	//Tanzania (United Republic of)
		table.add(new MccEntry(641,"ug",2));	//Uganda (Republic of)
		table.add(new MccEntry(642,"bi",2));	//Burundi (Republic of)
		table.add(new MccEntry(643,"mz",2));	//Mozambique (Republic of)
		table.add(new MccEntry(645,"zm",2));	//Zambia (Republic of)
		table.add(new MccEntry(646,"mg",2));	//Madagascar (Republic of)
		table.add(new MccEntry(647,"re",2));	//Reunion (French Department of)
		table.add(new MccEntry(648,"zw",2));	//Zimbabwe (Republic of)
		table.add(new MccEntry(649,"na",2));	//Namibia (Republic of)
		table.add(new MccEntry(650,"mw",2));	//Malawi
		table.add(new MccEntry(651,"ls",2));	//Lesotho (Kingdom of)
		table.add(new MccEntry(652,"bw",2));	//Botswana (Republic of)
		table.add(new MccEntry(653,"sz",2));	//Swaziland (Kingdom of)
		table.add(new MccEntry(654,"km",2));	//Comoros (Union of the)
		table.add(new MccEntry(655,"za",2,"en"));	//South Africa (Republic of)
		table.add(new MccEntry(657,"er",2));	//Eritrea
		table.add(new MccEntry(702,"bz",2));	//Belize
		table.add(new MccEntry(704,"gt",2));	//Guatemala (Republic of)
		table.add(new MccEntry(706,"sv",2));	//El Salvador (Republic of)
		table.add(new MccEntry(708,"hn",3));	//Honduras (Republic of)
		table.add(new MccEntry(710,"ni",2));	//Nicaragua
		table.add(new MccEntry(712,"cr",2));	//Costa Rica
		table.add(new MccEntry(714,"pa",2));	//Panama (Republic of)
		table.add(new MccEntry(716,"pe",2));	//Peru
		table.add(new MccEntry(722,"ar",3));	//Argentine Republic
		table.add(new MccEntry(724,"br",2));	//Brazil (Federative Republic of)
		table.add(new MccEntry(730,"cl",2));	//Chile
		table.add(new MccEntry(732,"co",3));	//Colombia (Republic of)
		table.add(new MccEntry(734,"ve",2));	//Venezuela (Bolivarian Republic of)
		table.add(new MccEntry(736,"bo",2));	//Bolivia (Republic of)
		table.add(new MccEntry(738,"gy",2));	//Guyana
		table.add(new MccEntry(740,"ec",2));	//Ecuador
		table.add(new MccEntry(742,"gf",2));	//French Guiana (French Department of)
		table.add(new MccEntry(744,"py",2));	//Paraguay (Republic of)
		table.add(new MccEntry(746,"sr",2));	//Suriname (Republic of)
		table.add(new MccEntry(748,"uy",2));	//Uruguay (Eastern Republic of)
		table.add(new MccEntry(750,"fk",2));	//Falkland Islands (Malvinas)
        //table.add(new MccEntry(901,"",2));	//"International Mobile, shared code"

        Collections.sort(table);
    }
}
