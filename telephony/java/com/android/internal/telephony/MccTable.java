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
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.Arrays;

/**
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
 * This table was verified (28 Aug 2009) against
 * http://en.wikipedia.org/wiki/List_of_mobile_country_codes with the
 * only unresolved discrepancy being that this list has an extra entry
 * (461) for China.
 *
 * TODO: Complete the mappings for timezones and language/locale codes.
 *
 * The actual table data used in the Java code is generated from the
 * below Python code for efficiency.  The information is expected to
 * be static, but if changes are required, the table in the python
 * code can be modified and the trailing code run to re-generate the
 * tables that are to be used by Java.

mcc_table = [
  (202, 'gr', 2, 'Greece'),
  (204, 'nl', 2, 'Europe/Amsterdam', 'nl', 13, 'Netherlands (Kingdom of the)'),
  (206, 'be', 2, 'Belgium'),
  (208, 'fr', 2, 'Europe/Paris', 'fr', 'France'),
  (212, 'mc', 2, 'Monaco (Principality of)'),
  (213, 'ad', 2, 'Andorra (Principality of)'),
  (214, 'es', 2, 'Europe/Madrid', 'es', 'Spain'),
  (216, 'hu', 2, 'Hungary (Republic of)'),
  (218, 'ba', 2, 'Bosnia and Herzegovina'),
  (219, 'hr', 2, 'Croatia (Republic of)'),
  (220, 'rs', 2, 'Serbia and Montenegro'),
  (222, 'it', 2, 'Europe/Rome', 'it', 'Italy'),
  (225, 'va', 2, 'Europe/Rome', 'it', 'Vatican City State'),
  (226, 'ro', 2, 'Romania'),
  (228, 'ch', 2, 'Europe/Zurich', 'de', 'Switzerland (Confederation of)'),
  (230, 'cz', 2, 'Europe/Prague', 'cs', 13, 'Czech Republic'),
  (231, 'sk', 2, 'Slovak Republic'),
  (232, 'at', 2, 'Europe/Vienna', 'de', 13, 'Austria'),
  (234, 'gb', 2, 'Europe/London', 'en', 13, 'United Kingdom of Great Britain and Northern Ireland'),
  (235, 'gb', 2, 'Europe/London', 'en', 13, 'United Kingdom of Great Britain and Northern Ireland'),
  (238, 'dk', 2, 'Denmark'),
  (240, 'se', 2, 'Sweden'),
  (242, 'no', 2, 'Norway'),
  (244, 'fi', 2, 'Finland'),
  (246, 'lt', 2, 'Lithuania (Republic of)'),
  (247, 'lv', 2, 'Latvia (Republic of)'),
  (248, 'ee', 2, 'Estonia (Republic of)'),
  (250, 'ru', 2, 'Russian Federation'),
  (255, 'ua', 2, 'Ukraine'),
  (257, 'by', 2, 'Belarus (Republic of)'),
  (259, 'md', 2, 'Moldova (Republic of)'),
  (260, 'pl', 2, 'Europe/Warsaw', 'Poland (Republic of)'),
  (262, 'de', 2, 'Europe/Berlin', 'de', 13, 'Germany (Federal Republic of)'),
  (266, 'gi', 2, 'Gibraltar'),
  (268, 'pt', 2, 'Portugal'),
  (270, 'lu', 2, 'Luxembourg'),
  (272, 'ie', 2, 'Europe/Dublin', 'en', 'Ireland'),
  (274, 'is', 2, 'Iceland'),
  (276, 'al', 2, 'Albania (Republic of)'),
  (278, 'mt', 2, 'Malta'),
  (280, 'cy', 2, 'Cyprus (Republic of)'),
  (282, 'ge', 2, 'Georgia'),
  (283, 'am', 2, 'Armenia (Republic of)'),
  (284, 'bg', 2, 'Bulgaria (Republic of)'),
  (286, 'tr', 2, 'Turkey'),
  (288, 'fo', 2, 'Faroe Islands'),
  (289, 'ge', 2, 'Abkhazia (Georgia)'),
  (290, 'gl', 2, 'Greenland (Denmark)'),
  (292, 'sm', 2, 'San Marino (Republic of)'),
  (293, 'sl', 2, 'Slovenia (Republic of)'),
  (294, 'mk', 2, 'The Former Yugoslav Republic of Macedonia'),
  (295, 'li', 2, 'Liechtenstein (Principality of)'),
  (297, 'me', 2, 'Montenegro (Republic of)'),
  (302, 'ca', 3, '', '', 11, 'Canada'),
  (308, 'pm', 2, 'Saint Pierre and Miquelon (Collectivit territoriale de la Rpublique franaise)'),
  (310, 'us', 3, '', 'en', 11, 'United States of America'),
  (311, 'us', 3, '', 'en', 11, 'United States of America'),
  (312, 'us', 3, '', 'en', 11, 'United States of America'),
  (313, 'us', 3, '', 'en', 11, 'United States of America'),
  (314, 'us', 3, '', 'en', 11, 'United States of America'),
  (315, 'us', 3, '', 'en', 11, 'United States of America'),
  (316, 'us', 3, '', 'en', 11, 'United States of America'),
  (330, 'pr', 2, 'Puerto Rico'),
  (332, 'vi', 2, 'United States Virgin Islands'),
  (334, 'mx', 3, 'Mexico'),
  (338, 'jm', 3, 'Jamaica'),
  (340, 'gp', 2, 'Guadeloupe (French Department of)'),
  (342, 'bb', 3, 'Barbados'),
  (344, 'ag', 3, 'Antigua and Barbuda'),
  (346, 'ky', 3, 'Cayman Islands'),
  (348, 'vg', 3, 'British Virgin Islands'),
  (350, 'bm', 2, 'Bermuda'),
  (352, 'gd', 2, 'Grenada'),
  (354, 'ms', 2, 'Montserrat'),
  (356, 'kn', 2, 'Saint Kitts and Nevis'),
  (358, 'lc', 2, 'Saint Lucia'),
  (360, 'vc', 2, 'Saint Vincent and the Grenadines'),
  (362, 'nl', 2, 'Netherlands Antilles'),
  (363, 'aw', 2, 'Aruba'),
  (364, 'bs', 2, 'Bahamas (Commonwealth of the)'),
  (365, 'ai', 3, 'Anguilla'),
  (366, 'dm', 2, 'Dominica (Commonwealth of)'),
  (368, 'cu', 2, 'Cuba'),
  (370, 'do', 2, 'Dominican Republic'),
  (372, 'ht', 2, 'Haiti (Republic of)'),
  (374, 'tt', 2, 'Trinidad and Tobago'),
  (376, 'tc', 2, 'Turks and Caicos Islands'),
  (400, 'az', 2, 'Azerbaijani Republic'),
  (401, 'kz', 2, 'Kazakhstan (Republic of)'),
  (402, 'bt', 2, 'Bhutan (Kingdom of)'),
  (404, 'in', 2, 'India (Republic of)'),
  (405, 'in', 2, 'India (Republic of)'),
  (410, 'pk', 2, 'Pakistan (Islamic Republic of)'),
  (412, 'af', 2, 'Afghanistan'),
  (413, 'lk', 2, 'Sri Lanka (Democratic Socialist Republic of)'),
  (414, 'mm', 2, 'Myanmar (Union of)'),
  (415, 'lb', 2, 'Lebanon'),
  (416, 'jo', 2, 'Jordan (Hashemite Kingdom of)'),
  (417, 'sy', 2, 'Syrian Arab Republic'),
  (418, 'iq', 2, 'Iraq (Republic of)'),
  (419, 'kw', 2, 'Kuwait (State of)'),
  (420, 'sa', 2, 'Saudi Arabia (Kingdom of)'),
  (421, 'ye', 2, 'Yemen (Republic of)'),
  (422, 'om', 2, 'Oman (Sultanate of)'),
  (423, 'ps', 2, 'Palestine'),
  (424, 'ae', 2, 'United Arab Emirates'),
  (425, 'il', 2, 'Israel (State of)'),
  (426, 'bh', 2, 'Bahrain (Kingdom of)'),
  (427, 'qa', 2, 'Qatar (State of)'),
  (428, 'mn', 2, 'Mongolia'),
  (429, 'np', 2, 'Nepal'),
  (430, 'ae', 2, 'United Arab Emirates'),
  (431, 'ae', 2, 'United Arab Emirates'),
  (432, 'ir', 2, 'Iran (Islamic Republic of)'),
  (434, 'uz', 2, 'Uzbekistan (Republic of)'),
  (436, 'tj', 2, 'Tajikistan (Republic of)'),
  (437, 'kg', 2, 'Kyrgyz Republic'),
  (438, 'tm', 2, 'Turkmenistan'),
  (440, 'jp', 2, 'Asia/Tokyo', 'ja', 14, 'Japan'),
  (441, 'jp', 2, 'Asia/Tokyo', 'ja', 14, 'Japan'),
  (450, 'kr', 2, 'Asia/Seoul', 'ko', 13, 'Korea (Republic of)'),
  (452, 'vn', 2, 'Viet Nam (Socialist Republic of)'),
  (454, 'hk', 2, '"Hong Kong, China"'),
  (455, 'mo', 2, '"Macao, China"'),
  (456, 'kh', 2, 'Cambodia (Kingdom of)'),
  (457, 'la', 2, "Lao People's Democratic Republic"),
  (460, 'cn', 2, "Asia/Beijing", 'zh', 13, "China (People's Republic of)"),
  (461, 'cn', 2, "Asia/Beijing", 'zh', 13, "China (People's Republic of)"),
  (466, 'tw', 2, "Taiwan (Republic of China)"),
  (467, 'kp', 2, "Democratic People's Republic of Korea"),
  (470, 'bd', 2, "Bangladesh (People's Republic of)"),
  (472, 'mv', 2, 'Maldives (Republic of)'),
  (502, 'my', 2, 'Malaysia'),
  (505, 'au', 2, 'Australia/Sydney', 'en', 11, 'Australia'),
  (510, 'id', 2, 'Indonesia (Republic of)'),
  (514, 'tl', 2, 'Democratic Republic of Timor-Leste'),
  (515, 'ph', 2, 'Philippines (Republic of the)'),
  (520, 'th', 2, 'Thailand'),
  (525, 'sg', 2, 'Asia/Singapore', 'en', 11, 'Singapore (Republic of)'),
  (528, 'bn', 2, 'Brunei Darussalam'),
  (530, 'nz', 2, 'Pacific/Auckland', 'en', 'New Zealand'),
  (534, 'mp', 2, 'Northern Mariana Islands (Commonwealth of the)'),
  (535, 'gu', 2, 'Guam'),
  (536, 'nr', 2, 'Nauru (Republic of)'),
  (537, 'pg', 2, 'Papua New Guinea'),
  (539, 'to', 2, 'Tonga (Kingdom of)'),
  (540, 'sb', 2, 'Solomon Islands'),
  (541, 'vu', 2, 'Vanuatu (Republic of)'),
  (542, 'fj', 2, 'Fiji (Republic of)'),
  (543, 'wf', 2, "Wallis and Futuna (Territoire franais d'outre-mer)"),
  (544, 'as', 2, 'American Samoa'),
  (545, 'ki', 2, 'Kiribati (Republic of)'),
  (546, 'nc', 2, "New Caledonia (Territoire franais d'outre-mer)"),
  (547, 'pf', 2, "French Polynesia (Territoire franais d'outre-mer)"),
  (548, 'ck', 2, 'Cook Islands'),
  (549, 'ws', 2, 'Samoa (Independent State of)'),
  (550, 'fm', 2, 'Micronesia (Federated States of)'),
  (551, 'mh', 2, 'Marshall Islands (Republic of the)'),
  (552, 'pw', 2, 'Palau (Republic of)'),
  (602, 'eg', 2, 'Egypt (Arab Republic of)'),
  (603, 'dz', 2, "Algeria (People's Democratic Republic of)"),
  (604, 'ma', 2, 'Morocco (Kingdom of)'),
  (605, 'tn', 2, 'Tunisia'),
  (606, 'ly', 2, "Libya (Socialist People's Libyan Arab Jamahiriya)"),
  (607, 'gm', 2, 'Gambia (Republic of the)'),
  (608, 'sn', 2, 'Senegal (Republic of)'),
  (609, 'mr', 2, 'Mauritania (Islamic Republic of)'),
  (610, 'ml', 2, 'Mali (Republic of)'),
  (611, 'gn', 2, 'Guinea (Republic of)'),
  (612, 'ci', 2, "Cte d'Ivoire (Republic of)"),
  (613, 'bf', 2, 'Burkina Faso'),
  (614, 'ne', 2, 'Niger (Republic of the)'),
  (615, 'tg', 2, 'Togolese Republic'),
  (616, 'bj', 2, 'Benin (Republic of)'),
  (617, 'mu', 2, 'Mauritius (Republic of)'),
  (618, 'lr', 2, 'Liberia (Republic of)'),
  (619, 'sl', 2, 'Sierra Leone'),
  (620, 'gh', 2, 'Ghana'),
  (621, 'ng', 2, 'Nigeria (Federal Republic of)'),
  (622, 'td', 2, 'Chad (Republic of)'),
  (623, 'cf', 2, 'Central African Republic'),
  (624, 'cm', 2, 'Cameroon (Republic of)'),
  (625, 'cv', 2, 'Cape Verde (Republic of)'),
  (626, 'st', 2, 'Sao Tome and Principe (Democratic Republic of)'),
  (627, 'gq', 2, 'Equatorial Guinea (Republic of)'),
  (628, 'ga', 2, 'Gabonese Republic'),
  (629, 'cg', 2, 'Congo (Republic of the)'),
  (630, 'cg', 2, 'Democratic Republic of the Congo'),
  (631, 'ao', 2, 'Angola (Republic of)'),
  (632, 'gw', 2, 'Guinea-Bissau (Republic of)'),
  (633, 'sc', 2, 'Seychelles (Republic of)'),
  (634, 'sd', 2, 'Sudan (Republic of the)'),
  (635, 'rw', 2, 'Rwanda (Republic of)'),
  (636, 'et', 2, 'Ethiopia (Federal Democratic Republic of)'),
  (637, 'so', 2, 'Somali Democratic Republic'),
  (638, 'dj', 2, 'Djibouti (Republic of)'),
  (639, 'ke', 2, 'Kenya (Republic of)'),
  (640, 'tz', 2, 'Tanzania (United Republic of)'),
  (641, 'ug', 2, 'Uganda (Republic of)'),
  (642, 'bi', 2, 'Burundi (Republic of)'),
  (643, 'mz', 2, 'Mozambique (Republic of)'),
  (645, 'zm', 2, 'Zambia (Republic of)'),
  (646, 'mg', 2, 'Madagascar (Republic of)'),
  (647, 're', 2, 'Reunion (French Department of)'),
  (648, 'zw', 2, 'Zimbabwe (Republic of)'),
  (649, 'na', 2, 'Namibia (Republic of)'),
  (650, 'mw', 2, 'Malawi'),
  (651, 'ls', 2, 'Lesotho (Kingdom of)'),
  (652, 'bw', 2, 'Botswana (Republic of)'),
  (653, 'sz', 2, 'Swaziland (Kingdom of)'),
  (654, 'km', 2, 'Comoros (Union of the)'),
  (655, 'za', 2, 'Africa/Johannesburg', 'en', 'South Africa (Republic of)'),
  (657, 'er', 2, 'Eritrea'),
  (702, 'bz', 2, 'Belize'),
  (704, 'gt', 2, 'Guatemala (Republic of)'),
  (706, 'sv', 2, 'El Salvador (Republic of)'),
  (708, 'hn', 3, 'Honduras (Republic of)'),
  (710, 'ni', 2, 'Nicaragua'),
  (712, 'cr', 2, 'Costa Rica'),
  (714, 'pa', 2, 'Panama (Republic of)'),
  (716, 'pe', 2, 'Peru'),
  (722, 'ar', 3, 'Argentine Republic'),
  (724, 'br', 2, 'Brazil (Federative Republic of)'),
  (730, 'cl', 2, 'Chile'),
  (732, 'co', 3, 'Colombia (Republic of)'),
  (734, 've', 2, 'Venezuela (Bolivarian Republic of)'),
  (736, 'bo', 2, 'Bolivia (Republic of)'),
  (738, 'gy', 2, 'Guyana'),
  (740, 'ec', 2, 'Ecuador'),
  (742, 'gf', 2, 'French Guiana (French Department of)'),
  (744, 'py', 2, 'Paraguay (Republic of)'),
  (746, 'sr', 2, 'Suriname (Republic of)'),
  (748, 'uy', 2, 'Uruguay (Eastern Republic of)'),
  (750, 'fk', 2, 'Falkland Islands (Malvinas)')]

get_mcc = lambda elt: elt[0]
get_iso = lambda elt: elt[1]
get_sd = lambda elt: elt[2]
get_tz = lambda elt: len(elt) > 4 and elt[3] or ''
get_lang = lambda elt: len(elt) > 5 and elt[4] or ''
get_wifi = lambda elt: len(elt) > 6 and elt[5] or 0

mcc_codes = ['0x%04x' % get_mcc(elt) for elt in mcc_table]
tz_set = sorted(x for x in set(get_tz(elt) for elt in mcc_table))
lang_set = sorted(x for x in set(get_lang(elt) for elt in mcc_table))

def mk_ind_code(elt):
  iso = get_iso(elt)
  iso_code = ((ord(iso[0]) << 8) | ord(iso[1])) & 0xFFFF # 16 bits
  wifi = get_wifi(elt) & 0x000F                          #  4 bits
  sd = get_sd(elt) & 0x0003                              #  2 bits
  tz_ind = tz_set.index(get_tz(elt)) & 0x001F            #  5 bits
  lang_ind = lang_set.index(get_lang(elt)) & 0x000F      #  4 bits
  return (iso_code << 16) | (wifi << 11) | (sd << 9) | (tz_ind << 4) | lang_ind

ind_codes = ['0x%08x' % mk_ind_code(elt) for elt in mcc_table]

def fmt_list(title, l, batch_sz):
  sl = []
  for i in range(len(l) / batch_sz + (len(l) % batch_sz and 1 or 0)):
    j = i * batch_sz
    sl.append((' ' * 8) + ', '.join(l[j:j + batch_sz]))
  return '    private static final %s = {\n' % title + ',\n'.join(sl) + '\n    };\n'

def do_autogen_comment(extra_desc=[]):
  print '    /' + '**\n    * AUTO GENERATED (by the Python code above)'
  for line in extra_desc:
    print '    * %s' % line
  print '    *' + '/'

do_autogen_comment()
print fmt_list('String[] TZ_STRINGS', ['"%s"' % x for x in tz_set], 1)
do_autogen_comment()
print fmt_list('String[] LANG_STRINGS', ['"%s"' % x for x in lang_set], 10)
do_autogen_comment(['This table is a list of MCC codes.  The index in this table',
                    'of a given MCC code is the index of extra information about',
                    'that MCC in the IND_CODES table.'])
print fmt_list('short[] MCC_CODES', mcc_codes, 10)
do_autogen_comment(['The values in this table are broken down as follows (msb to lsb):',
                    '    iso country code 16 bits',
                    '    (unused)          1 bit',
                    '    wifi channel      4 bits',
                    '    smalled digit     2 bits',
                    '    default timezone  5 bits',
                    '    default language  4 bits'])
print fmt_list('int[] IND_CODES', ind_codes, 6)

def parse_ind_code(ind):
  mcc = eval(mcc_codes[ind])
  code = eval(ind_codes[ind])
  iso_lsb = int((code >> 16) & 0x00FF)
  iso_msb = int((code >> 24) & 0x00FF)
  iso = '%s%s' % (chr(iso_msb), chr(iso_lsb))
  wifi = int((code >> 11) & 0x000F)
  sd = int((code >> 9) & 0x0003)
  tz_ind = (code >> 4) & 0x001F
  lang_ind = (code >> 0) & 0x000F
  return (mcc, iso, sd, tz_set[tz_ind], lang_set[lang_ind], wifi)

fmt_str = 'mcc = %s, iso = %s, sd = %s, tz = %s, lang = %s, wifi = %s'
orig_table = [fmt_str % (get_mcc(elt), get_iso(elt), get_sd(elt),
                         get_tz(elt), get_lang(elt), get_wifi(elt))
              for elt in mcc_table]
derived_table = [fmt_str % parse_ind_code(i) for i in range(len(ind_codes))]
for i in range(len(orig_table)):
  if orig_table[i] == derived_table[i]: continue
  print 'MISMATCH ERROR : ', orig_table[i], " != ", derived_table[i]

*/

/**
 * Mobile Country Code
 *
 * {@hide}
 */
public final class MccTable
{
    /**
     * AUTO GENERATED (by the Python code above)
     */
    private static final String[] TZ_STRINGS = {
        "",
        "Africa/Johannesburg",
        "Asia/Beijing",
        "Asia/Seoul",
        "Asia/Singapore",
        "Asia/Tokyo",
        "Australia/Sydney",
        "Europe/Amsterdam",
        "Europe/Berlin",
        "Europe/Dublin",
        "Europe/London",
        "Europe/Madrid",
        "Europe/Paris",
        "Europe/Prague",
        "Europe/Rome",
        "Europe/Vienna",
        "Europe/Warsaw",
        "Europe/Zurich",
        "Pacific/Auckland"
    };

    /**
     * AUTO GENERATED (by the Python code above)
     */
    private static final String[] LANG_STRINGS = {
        "", "cs", "de", "en", "es", "fr", "it", "ja", "ko", "nl",
        "zh"
    };

    /**
     * AUTO GENERATED (by the Python code above)
     * This table is a list of MCC codes.  The index in this table
     * of a given MCC code is the index of extra information about
     * that MCC in the IND_CODES table.
     */
    private static final short[] MCC_CODES = {
        0x00ca, 0x00cc, 0x00ce, 0x00d0, 0x00d4, 0x00d5, 0x00d6, 0x00d8, 0x00da, 0x00db,
        0x00dc, 0x00de, 0x00e1, 0x00e2, 0x00e4, 0x00e6, 0x00e7, 0x00e8, 0x00ea, 0x00eb,
        0x00ee, 0x00f0, 0x00f2, 0x00f4, 0x00f6, 0x00f7, 0x00f8, 0x00fa, 0x00ff, 0x0101,
        0x0103, 0x0104, 0x0106, 0x010a, 0x010c, 0x010e, 0x0110, 0x0112, 0x0114, 0x0116,
        0x0118, 0x011a, 0x011b, 0x011c, 0x011e, 0x0120, 0x0121, 0x0122, 0x0124, 0x0125,
        0x0126, 0x0127, 0x0129, 0x012e, 0x0134, 0x0136, 0x0137, 0x0138, 0x0139, 0x013a,
        0x013b, 0x013c, 0x014a, 0x014c, 0x014e, 0x0152, 0x0154, 0x0156, 0x0158, 0x015a,
        0x015c, 0x015e, 0x0160, 0x0162, 0x0164, 0x0166, 0x0168, 0x016a, 0x016b, 0x016c,
        0x016d, 0x016e, 0x0170, 0x0172, 0x0174, 0x0176, 0x0178, 0x0190, 0x0191, 0x0192,
        0x0194, 0x0195, 0x019a, 0x019c, 0x019d, 0x019e, 0x019f, 0x01a0, 0x01a1, 0x01a2,
        0x01a3, 0x01a4, 0x01a5, 0x01a6, 0x01a7, 0x01a8, 0x01a9, 0x01aa, 0x01ab, 0x01ac,
        0x01ad, 0x01ae, 0x01af, 0x01b0, 0x01b2, 0x01b4, 0x01b5, 0x01b6, 0x01b8, 0x01b9,
        0x01c2, 0x01c4, 0x01c6, 0x01c7, 0x01c8, 0x01c9, 0x01cc, 0x01cd, 0x01d2, 0x01d3,
        0x01d6, 0x01d8, 0x01f6, 0x01f9, 0x01fe, 0x0202, 0x0203, 0x0208, 0x020d, 0x0210,
        0x0212, 0x0216, 0x0217, 0x0218, 0x0219, 0x021b, 0x021c, 0x021d, 0x021e, 0x021f,
        0x0220, 0x0221, 0x0222, 0x0223, 0x0224, 0x0225, 0x0226, 0x0227, 0x0228, 0x025a,
        0x025b, 0x025c, 0x025d, 0x025e, 0x025f, 0x0260, 0x0261, 0x0262, 0x0263, 0x0264,
        0x0265, 0x0266, 0x0267, 0x0268, 0x0269, 0x026a, 0x026b, 0x026c, 0x026d, 0x026e,
        0x026f, 0x0270, 0x0271, 0x0272, 0x0273, 0x0274, 0x0275, 0x0276, 0x0277, 0x0278,
        0x0279, 0x027a, 0x027b, 0x027c, 0x027d, 0x027e, 0x027f, 0x0280, 0x0281, 0x0282,
        0x0283, 0x0285, 0x0286, 0x0287, 0x0288, 0x0289, 0x028a, 0x028b, 0x028c, 0x028d,
        0x028e, 0x028f, 0x0291, 0x02be, 0x02c0, 0x02c2, 0x02c4, 0x02c6, 0x02c8, 0x02ca,
        0x02cc, 0x02d2, 0x02d4, 0x02da, 0x02dc, 0x02de, 0x02e0, 0x02e2, 0x02e4, 0x02e6,
        0x02e8, 0x02ea, 0x02ec, 0x02ee
    };

    /**
     * AUTO GENERATED (by the Python code above)
     * The values in this table are broken down as follows (msb to lsb):
     *     iso country code 16 bits
     *     (unused)          1 bit
     *     wifi channel      4 bits
     *     smalled digit     2 bits
     *     default timezone  5 bits
     *     default language  4 bits
     */
    private static final int[] IND_CODES = {
        0x67720400, 0x6e6c6c79, 0x62650400, 0x667204c5, 0x6d630400, 0x61640400,
        0x657304b4, 0x68750400, 0x62610400, 0x68720400, 0x72730400, 0x697404e6,
        0x766104e6, 0x726f0400, 0x63680512, 0x637a6cd1, 0x736b0400, 0x61746cf2,
        0x67626ca3, 0x67626ca3, 0x646b0400, 0x73650400, 0x6e6f0400, 0x66690400,
        0x6c740400, 0x6c760400, 0x65650400, 0x72750400, 0x75610400, 0x62790400,
        0x6d640400, 0x706c0500, 0x64656c82, 0x67690400, 0x70740400, 0x6c750400,
        0x69650493, 0x69730400, 0x616c0400, 0x6d740400, 0x63790400, 0x67650400,
        0x616d0400, 0x62670400, 0x74720400, 0x666f0400, 0x67650400, 0x676c0400,
        0x736d0400, 0x736c0400, 0x6d6b0400, 0x6c690400, 0x6d650400, 0x63615e00,
        0x706d0400, 0x75735e03, 0x75735e03, 0x75735e03, 0x75735e03, 0x75735e03,
        0x75735e03, 0x75735e03, 0x70720400, 0x76690400, 0x6d780600, 0x6a6d0600,
        0x67700400, 0x62620600, 0x61670600, 0x6b790600, 0x76670600, 0x626d0400,
        0x67640400, 0x6d730400, 0x6b6e0400, 0x6c630400, 0x76630400, 0x6e6c0400,
        0x61770400, 0x62730400, 0x61690600, 0x646d0400, 0x63750400, 0x646f0400,
        0x68740400, 0x74740400, 0x74630400, 0x617a0400, 0x6b7a0400, 0x62740400,
        0x696e0400, 0x696e0400, 0x706b0400, 0x61660400, 0x6c6b0400, 0x6d6d0400,
        0x6c620400, 0x6a6f0400, 0x73790400, 0x69710400, 0x6b770400, 0x73610400,
        0x79650400, 0x6f6d0400, 0x70730400, 0x61650400, 0x696c0400, 0x62680400,
        0x71610400, 0x6d6e0400, 0x6e700400, 0x61650400, 0x61650400, 0x69720400,
        0x757a0400, 0x746a0400, 0x6b670400, 0x746d0400, 0x6a707457, 0x6a707457,
        0x6b726c38, 0x766e0400, 0x686b0400, 0x6d6f0400, 0x6b680400, 0x6c610400,
        0x636e6c2a, 0x636e6c2a, 0x74770400, 0x6b700400, 0x62640400, 0x6d760400,
        0x6d790400, 0x61755c63, 0x69640400, 0x746c0400, 0x70680400, 0x74680400,
        0x73675c43, 0x626e0400, 0x6e7a0523, 0x6d700400, 0x67750400, 0x6e720400,
        0x70670400, 0x746f0400, 0x73620400, 0x76750400, 0x666a0400, 0x77660400,
        0x61730400, 0x6b690400, 0x6e630400, 0x70660400, 0x636b0400, 0x77730400,
        0x666d0400, 0x6d680400, 0x70770400, 0x65670400, 0x647a0400, 0x6d610400,
        0x746e0400, 0x6c790400, 0x676d0400, 0x736e0400, 0x6d720400, 0x6d6c0400,
        0x676e0400, 0x63690400, 0x62660400, 0x6e650400, 0x74670400, 0x626a0400,
        0x6d750400, 0x6c720400, 0x736c0400, 0x67680400, 0x6e670400, 0x74640400,
        0x63660400, 0x636d0400, 0x63760400, 0x73740400, 0x67710400, 0x67610400,
        0x63670400, 0x63670400, 0x616f0400, 0x67770400, 0x73630400, 0x73640400,
        0x72770400, 0x65740400, 0x736f0400, 0x646a0400, 0x6b650400, 0x747a0400,
        0x75670400, 0x62690400, 0x6d7a0400, 0x7a6d0400, 0x6d670400, 0x72650400,
        0x7a770400, 0x6e610400, 0x6d770400, 0x6c730400, 0x62770400, 0x737a0400,
        0x6b6d0400, 0x7a610413, 0x65720400, 0x627a0400, 0x67740400, 0x73760400,
        0x686e0600, 0x6e690400, 0x63720400, 0x70610400, 0x70650400, 0x61720600,
        0x62720400, 0x636c0400, 0x636f0600, 0x76650400, 0x626f0400, 0x67790400,
        0x65630400, 0x67660400, 0x70790400, 0x73720400, 0x75790400, 0x666b0400
    };

    static final String LOG_TAG = "MccTable";

    /**
     * Given a Mobile Country Code, returns a default time zone ID
     * if available.  Returns null if unavailable.
     */
    public static String defaultTimeZoneForMcc(int mcc) {
        int index = Arrays.binarySearch(MCC_CODES, (short)mcc);
        if (index < 0) {
            return null;
        }
        int indCode = IND_CODES[index];
        int tzInd = (indCode >>> 4) & 0x001F;
        String tz = TZ_STRINGS[tzInd];
        if (tz == "") {
            return null;
        }
        return tz;
    }

    /**
     * Given a Mobile Country Code, returns an ISO two-character
     * country code if available.  Returns "" if unavailable.
     */
    public static String countryCodeForMcc(int mcc) {
        int index = Arrays.binarySearch(MCC_CODES, (short)mcc);
        if (index < 0) {
            return "";
        }
        int indCode = IND_CODES[index];
        byte[] iso = {(byte)((indCode >>> 24) & 0x00FF), (byte)((indCode >>> 16) & 0x00FF)};
        return new String(iso);
    }

    /**
     * Given a GSM Mobile Country Code, returns an ISO 2-3 character
     * language code if available.  Returns null if unavailable.
     */
    public static String defaultLanguageForMcc(int mcc) {
        int index = Arrays.binarySearch(MCC_CODES, (short)mcc);
        if (index < 0) {
            return null;
        }
        int indCode = IND_CODES[index];
        int langInd = indCode & 0x000F;
        String lang = LANG_STRINGS[langInd];
        if (lang == "") {
            return null;
        }
        return lang;
    }

    /**
     * Given a GSM Mobile Country Code, returns the corresponding
     * smallest number of digits field.  Returns 2 if unavailable.
     */
    public static int smallestDigitsMccForMnc(int mcc) {
        int index = Arrays.binarySearch(MCC_CODES, (short)mcc);
        if (index < 0) {
            return 2;
        }
        int indCode = IND_CODES[index];
        int smDig = (indCode >>> 9) & 0x0003;
        return smDig;
    }

    /**
     * Given a GSM Mobile Country Code, returns the number of wifi
     * channels allowed in that country.  Returns 0 if unavailable.
     */
    public static int wifiChannelsForMcc(int mcc) {
        int index = Arrays.binarySearch(MCC_CODES, (short)mcc);
        if (index < 0) {
            return 0;
        }
        int indCode = IND_CODES[index];
        int wifi = (indCode >>> 11) & 0x000F;
        return wifi;
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
                setWifiChannelsFromMcc(phone, mcc);
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
        phone.setSystemLocale(language, country);
    }

    /**
     * If the number of allowed wifi channels has not been set, set it based on
     * the MCC of the SIM.
     * @param phone PhoneBase to act on (get context from).
     * @param mcc Mobile Country Code of the SIM or SIM-like entity (build prop on CDMA)
     */
    private static void setWifiChannelsFromMcc(PhoneBase phone, int mcc) {
        int wifiChannels = MccTable.wifiChannelsForMcc(mcc);
        if (wifiChannels != 0) {
            Context context = phone.getContext();
            Log.d(LOG_TAG, "WIFI_NUM_ALLOWED_CHANNELS set to " + wifiChannels);
            WifiManager wM = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            //persist
            wM.setNumAllowedChannels(wifiChannels, true);
        }
    }
}
