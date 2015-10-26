/*
 * Copyright (C) 2008 Google Inc.
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

package android.util;

import junit.framework.TestCase;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * TimeUtilsTest tests the time zone guesser.
 */
public class TimeUtilsTest extends TestCase {
    public void testMainstream() throws Exception {
        String[] mainstream = new String[] {
            "America/New_York", // Eastern
            "America/Chicago", // Central
            "America/Denver", // Mountain
            "America/Los_Angeles", // Pacific
            "America/Anchorage", // Alaska
            "Pacific/Honolulu", // Hawaii, no DST
        };

        for (String name : mainstream) {
            TimeZone tz = TimeZone.getTimeZone(name);
            Calendar c = Calendar.getInstance(tz);
            TimeZone guess;

            c.set(2008, Calendar.OCTOBER, 20, 12, 00, 00);
            guess = guess(c, "us");
            assertEquals(name, guess.getID());

            c.set(2009, Calendar.JANUARY, 20, 12, 00, 00);
            guess = guess(c, "us");
            assertEquals(name, guess.getID());
        }
    }

    public void testWeird() throws Exception {
        String[] weird = new String[] {
            "America/Phoenix", // Mountain, no DST
            "America/Adak", // Same as Hawaii, but with DST
        };

        for (String name : weird) {
            TimeZone tz = TimeZone.getTimeZone(name);
            Calendar c = Calendar.getInstance(tz);
            TimeZone guess;

            c.set(2008, Calendar.OCTOBER, 20, 12, 00, 00);
            guess = guess(c, "us");
            assertEquals(name, guess.getID());
        }
    }

    public void testOld() throws Exception {
        String[] old = new String[] {
            "America/Indiana/Indianapolis", // Eastern, formerly no DST
        };

        for (String name : old) {
            TimeZone tz = TimeZone.getTimeZone(name);
            Calendar c = Calendar.getInstance(tz);
            TimeZone guess;

            c.set(2005, Calendar.OCTOBER, 20, 12, 00, 00);
            guess = guess(c, "us");
            assertEquals(name, guess.getID());
        }
    }

    public void testWorld() throws Exception {
        String[] world = new String[] {
            "ad", "Europe/Andorra",
            "ae", "Asia/Dubai",
            "af", "Asia/Kabul",
            "ag", "America/Antigua",
            "ai", "America/Anguilla",
            "al", "Europe/Tirane",
            "am", "Asia/Yerevan",
            "an", "America/Curacao",
            "ao", "Africa/Luanda",
            "aq", "Antarctica/McMurdo",
            "aq", "Antarctica/DumontDUrville",
            "aq", "Antarctica/Casey",
            "aq", "Antarctica/Davis",
            "aq", "Antarctica/Mawson",
            "aq", "Antarctica/Syowa",
            "aq", "Antarctica/Rothera",
            "aq", "Antarctica/Palmer",
            "ar", "America/Argentina/Buenos_Aires",
            "as", "Pacific/Pago_Pago",
            "at", "Europe/Vienna",
            "au", "Australia/Sydney",
            "au", "Australia/Adelaide",
            "au", "Australia/Perth",
            "au", "Australia/Eucla",
            "aw", "America/Aruba",
            "ax", "Europe/Mariehamn",
            "az", "Asia/Baku",
            "ba", "Europe/Sarajevo",
            "bb", "America/Barbados",
            "bd", "Asia/Dhaka",
            "be", "Europe/Brussels",
            "bf", "Africa/Ouagadougou",
            "bg", "Europe/Sofia",
            "bh", "Asia/Bahrain",
            "bi", "Africa/Bujumbura",
            "bj", "Africa/Porto-Novo",
            "bm", "Atlantic/Bermuda",
            "bn", "Asia/Brunei",
            "bo", "America/La_Paz",
            "br", "America/Noronha",
            "br", "America/Sao_Paulo",
            "br", "America/Manaus",
            "bs", "America/Nassau",
            "bt", "Asia/Thimphu",
            "bw", "Africa/Gaborone",
            "by", "Europe/Minsk",
            "bz", "America/Belize",
            "ca", "America/St_Johns",
            "ca", "America/Halifax",
            "ca", "America/Toronto",
            "ca", "America/Winnipeg",
            "ca", "America/Edmonton",
            "ca", "America/Vancouver",
            "cc", "Indian/Cocos",
            "cd", "Africa/Lubumbashi",
            "cd", "Africa/Kinshasa",
            "cf", "Africa/Bangui",
            "cg", "Africa/Brazzaville",
            "ch", "Europe/Zurich",
            "ci", "Africa/Abidjan",
            "ck", "Pacific/Rarotonga",
            "cl", "America/Santiago",
            "cl", "Pacific/Easter",
            "cm", "Africa/Douala",
            "cn", "Asia/Shanghai",
            "co", "America/Bogota",
            "cr", "America/Costa_Rica",
            "cu", "America/Havana",
            "cv", "Atlantic/Cape_Verde",
            "cx", "Indian/Christmas",
            "cy", "Asia/Nicosia",
            "cz", "Europe/Prague",
            "de", "Europe/Berlin",
            "dj", "Africa/Djibouti",
            "dk", "Europe/Copenhagen",
            "dm", "America/Dominica",
            "do", "America/Santo_Domingo",
            "dz", "Africa/Algiers",
            "ec", "America/Guayaquil",
            "ec", "Pacific/Galapagos",
            "ee", "Europe/Tallinn",
            "eg", "Africa/Cairo",
            "eh", "Africa/El_Aaiun",
            "er", "Africa/Asmara",
            "es", "Europe/Madrid",
            "es", "Atlantic/Canary",
            "et", "Africa/Addis_Ababa",
            "fi", "Europe/Helsinki",
            "fj", "Pacific/Fiji",
            "fk", "Atlantic/Stanley",
            "fm", "Pacific/Ponape",
            "fm", "Pacific/Truk",
            "fo", "Atlantic/Faroe",
            "fr", "Europe/Paris",
            "ga", "Africa/Libreville",
            "gb", "Europe/London",
            "gd", "America/Grenada",
            "ge", "Asia/Tbilisi",
            "gf", "America/Cayenne",
            "gg", "Europe/Guernsey",
            "gh", "Africa/Accra",
            "gi", "Europe/Gibraltar",
            "gl", "America/Danmarkshavn",
            "gl", "America/Scoresbysund",
            "gl", "America/Godthab",
            "gl", "America/Thule",
            "gm", "Africa/Banjul",
            "gn", "Africa/Conakry",
            "gp", "America/Guadeloupe",
            "gq", "Africa/Malabo",
            "gr", "Europe/Athens",
            "gs", "Atlantic/South_Georgia",
            "gt", "America/Guatemala",
            "gu", "Pacific/Guam",
            "gw", "Africa/Bissau",
            "gy", "America/Guyana",
            "hk", "Asia/Hong_Kong",
            "hn", "America/Tegucigalpa",
            "hr", "Europe/Zagreb",
            "ht", "America/Port-au-Prince",
            "hu", "Europe/Budapest",
            "id", "Asia/Jayapura",
            "id", "Asia/Makassar",
            "id", "Asia/Jakarta",
            "ie", "Europe/Dublin",
            "il", "Asia/Jerusalem",
            "im", "Europe/Isle_of_Man",
            "in", "Asia/Calcutta",
            "io", "Indian/Chagos",
            "iq", "Asia/Baghdad",
            "ir", "Asia/Tehran",
            "is", "Atlantic/Reykjavik",
            "it", "Europe/Rome",
            "je", "Europe/Jersey",
            "jm", "America/Jamaica",
            "jo", "Asia/Amman",
            "jp", "Asia/Tokyo",
            "ke", "Africa/Nairobi",
            "kg", "Asia/Bishkek",
            "kh", "Asia/Phnom_Penh",
            "ki", "Pacific/Kiritimati",
            "ki", "Pacific/Enderbury",
            "ki", "Pacific/Tarawa",
            "km", "Indian/Comoro",
            "kn", "America/St_Kitts",
            "kp", "Asia/Pyongyang",
            "kr", "Asia/Seoul",
            "kw", "Asia/Kuwait",
            "ky", "America/Cayman",
            "kz", "Asia/Almaty",
            "kz", "Asia/Aqtau",
            "la", "Asia/Vientiane",
            "lb", "Asia/Beirut",
            "lc", "America/St_Lucia",
            "li", "Europe/Vaduz",
            "lk", "Asia/Colombo",
            "lr", "Africa/Monrovia",
            "ls", "Africa/Maseru",
            "lt", "Europe/Vilnius",
            "lu", "Europe/Luxembourg",
            "lv", "Europe/Riga",
            "ly", "Africa/Tripoli",
            "ma", "Africa/Casablanca",
            "mc", "Europe/Monaco",
            "md", "Europe/Chisinau",
            "me", "Europe/Podgorica",
            "mg", "Indian/Antananarivo",
            "mh", "Pacific/Majuro",
            "mk", "Europe/Skopje",
            "ml", "Africa/Bamako",
            "mm", "Asia/Rangoon",
            "mn", "Asia/Choibalsan",
            "mn", "Asia/Hovd",
            "mo", "Asia/Macau",
            "mp", "Pacific/Saipan",
            "mq", "America/Martinique",
            "mr", "Africa/Nouakchott",
            "ms", "America/Montserrat",
            "mt", "Europe/Malta",
            "mu", "Indian/Mauritius",
            "mv", "Indian/Maldives",
            "mw", "Africa/Blantyre",
            "mx", "America/Mexico_City",
            "mx", "America/Chihuahua",
            "mx", "America/Tijuana",
            "my", "Asia/Kuala_Lumpur",
            "mz", "Africa/Maputo",
            "na", "Africa/Windhoek",
            "nc", "Pacific/Noumea",
            "ne", "Africa/Niamey",
            "nf", "Pacific/Norfolk",
            "ng", "Africa/Lagos",
            "ni", "America/Managua",
            "nl", "Europe/Amsterdam",
            "no", "Europe/Oslo",
            "np", "Asia/Katmandu",
            "nr", "Pacific/Nauru",
            "nu", "Pacific/Niue",
            "nz", "Pacific/Auckland",
            "nz", "Pacific/Chatham",
            "om", "Asia/Muscat",
            "pa", "America/Panama",
            "pe", "America/Lima",
            "pf", "Pacific/Gambier",
            "pf", "Pacific/Marquesas",
            "pf", "Pacific/Tahiti",
            "pg", "Pacific/Port_Moresby",
            "ph", "Asia/Manila",
            "pk", "Asia/Karachi",
            "pl", "Europe/Warsaw",
            "pm", "America/Miquelon",
            "pn", "Pacific/Pitcairn",
            "pr", "America/Puerto_Rico",
            "ps", "Asia/Gaza",
            "pt", "Europe/Lisbon",
            "pt", "Atlantic/Azores",
            "pw", "Pacific/Palau",
            "py", "America/Asuncion",
            "qa", "Asia/Qatar",
            "re", "Indian/Reunion",
            "ro", "Europe/Bucharest",
            "rs", "Europe/Belgrade",
            "ru", "Asia/Kamchatka",
            "ru", "Asia/Magadan",
            "ru", "Asia/Vladivostok",
            "ru", "Asia/Yakutsk",
            "ru", "Asia/Irkutsk",
            "ru", "Asia/Krasnoyarsk",
            "ru", "Asia/Novosibirsk",
            "ru", "Asia/Yekaterinburg",
            "ru", "Europe/Samara",
            "ru", "Europe/Moscow",
            "ru", "Europe/Kaliningrad",
            "rw", "Africa/Kigali",
            "sa", "Asia/Riyadh",
            "sb", "Pacific/Guadalcanal",
            "sc", "Indian/Mahe",
            "sd", "Africa/Khartoum",
            "se", "Europe/Stockholm",
            "sg", "Asia/Singapore",
            "sh", "Atlantic/St_Helena",
            "si", "Europe/Ljubljana",
            "sj", "Arctic/Longyearbyen",
            "sk", "Europe/Bratislava",
            "sl", "Africa/Freetown",
            "sm", "Europe/San_Marino",
            "sn", "Africa/Dakar",
            "so", "Africa/Mogadishu",
            "sr", "America/Paramaribo",
            "st", "Africa/Sao_Tome",
            "sv", "America/El_Salvador",
            "sy", "Asia/Damascus",
            "sz", "Africa/Mbabane",
            "tc", "America/Grand_Turk",
            "td", "Africa/Ndjamena",
            "tf", "Indian/Kerguelen",
            "tg", "Africa/Lome",
            "th", "Asia/Bangkok",
            "tj", "Asia/Dushanbe",
            "tk", "Pacific/Fakaofo",
            "tl", "Asia/Dili",
            "tm", "Asia/Ashgabat",
            "tn", "Africa/Tunis",
            "to", "Pacific/Tongatapu",
            "tr", "Europe/Istanbul",
            "tt", "America/Port_of_Spain",
            "tv", "Pacific/Funafuti",
            "tw", "Asia/Taipei",
            "tz", "Africa/Dar_es_Salaam",
            "ua", "Europe/Kiev",
            "ug", "Africa/Kampala",
            "um", "Pacific/Wake",
            "um", "Pacific/Johnston",
            "um", "Pacific/Midway",
            "us", "America/New_York",
            "us", "America/Chicago",
            "us", "America/Denver",
            "us", "America/Los_Angeles",
            "us", "America/Anchorage",
            "us", "Pacific/Honolulu",
            "uy", "America/Montevideo",
            "uz", "Asia/Tashkent",
            "va", "Europe/Vatican",
            "vc", "America/St_Vincent",
            "ve", "America/Caracas",
            "vg", "America/Tortola",
            "vi", "America/St_Thomas",
            "vn", "Asia/Saigon",
            "vu", "Pacific/Efate",
            "wf", "Pacific/Wallis",
            "ws", "Pacific/Apia",
            "ye", "Asia/Aden",
            "yt", "Indian/Mayotte",
            "za", "Africa/Johannesburg",
            "zm", "Africa/Lusaka",
            "zw", "Africa/Harare",
        };

        for (int i = 0; i < world.length; i += 2) {
            String country = world[i];
            String name = world[i + 1];

            TimeZone tz = TimeZone.getTimeZone(name);
            Calendar c = Calendar.getInstance(tz);
            TimeZone guess;

            c.set(2009, Calendar.JULY, 20, 12, 00, 00);
            guess = guess(c, country);
            assertEquals(name, guess.getID());

            c.set(2009, Calendar.JANUARY, 20, 12, 00, 00);
            guess = guess(c, country);
            assertEquals(name, guess.getID());
        }
    }

    public void testWorldWeird() throws Exception {
        String[] world = new String[] {
            // Distinguisable from Sydney only when DST not in effect
            "au", "Australia/Lord_Howe",
        };

        for (int i = 0; i < world.length; i += 2) {
            String country = world[i];
            String name = world[i + 1];

            TimeZone tz = TimeZone.getTimeZone(name);
            Calendar c = Calendar.getInstance(tz);
            TimeZone guess;

            c.set(2009, Calendar.JULY, 20, 12, 00, 00);
            guess = guess(c, country);
            assertEquals(name, guess.getID());
        }
    }

    private static TimeZone guess(Calendar c, String country) {
        return TimeUtils.getTimeZone(c.get(c.ZONE_OFFSET) + c.get(c.DST_OFFSET),
                                     c.get(c.DST_OFFSET) != 0,
                                     c.getTimeInMillis(),
                                     country);
    }

    public void testFormatDuration() {
        assertFormatDuration("0", 0);
        assertFormatDuration("-1ms", -1);
        assertFormatDuration("+1ms", 1);
        assertFormatDuration("+10ms", 10);
        assertFormatDuration("+100ms", 100);
        assertFormatDuration("+101ms", 101);
        assertFormatDuration("+330ms", 330);
        assertFormatDuration("+1s0ms", 1000);
        assertFormatDuration("+1s330ms", 1330);
        assertFormatDuration("+10s24ms", 10024);
        assertFormatDuration("+1m0s30ms", 60030);
        assertFormatDuration("+1h0m0s30ms", 3600030);
        assertFormatDuration("+1d0h0m0s30ms", 86400030);
    }

    public void testFormatHugeDuration() {
        assertFormatDuration("+15542d1h11m11s555ms", 1342833071555L);
        assertFormatDuration("-15542d1h11m11s555ms", -1342833071555L);
    }

    private void assertFormatDuration(String expected, long duration) {
        StringBuilder sb = new StringBuilder();
        TimeUtils.formatDuration(duration, sb);
        assertEquals("formatDuration(" + duration + ")", expected, sb.toString());
    }
}
