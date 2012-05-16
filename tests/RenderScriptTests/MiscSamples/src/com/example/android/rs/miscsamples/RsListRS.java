/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.example.android.rs.miscsamples;

import java.io.Writer;
import java.util.Vector;

import android.content.res.Resources;
import android.renderscript.*;
import android.renderscript.ProgramStore.DepthFunc;
import android.util.Log;


public class RsListRS {

    private final int STATE_LAST_FOCUS = 1;

    private static final String[] DATA_LIST = {
    "Afghanistan", "Albania", "Algeria", "American Samoa", "Andorra",
    "Angola", "Anguilla", "Antarctica", "Antigua and Barbuda", "Argentina",
    "Armenia", "Aruba", "Australia", "Austria", "Azerbaijan",
    "Bahrain", "Bangladesh", "Barbados", "Belarus", "Belgium",
    "Belize", "Benin", "Bermuda", "Bhutan", "Bolivia",
    "Bosnia and Herzegovina", "Botswana", "Bouvet Island", "Brazil",
    "British Indian Ocean Territory", "British Virgin Islands", "Brunei", "Bulgaria",
    "Burkina Faso", "Burundi", "Cote d'Ivoire", "Cambodia", "Cameroon", "Canada", "Cape Verde",
    "Cayman Islands", "Central African Republic", "Chad", "Chile", "China",
    "Christmas Island", "Cocos (Keeling) Islands", "Colombia", "Comoros", "Congo",
    "Cook Islands", "Costa Rica", "Croatia", "Cuba", "Cyprus", "Czech Republic",
    "Democratic Republic of the Congo", "Denmark", "Djibouti", "Dominica", "Dominican Republic",
    "East Timor", "Ecuador", "Egypt", "El Salvador", "Equatorial Guinea", "Eritrea",
    "Estonia", "Ethiopia", "Faeroe Islands", "Falkland Islands", "Fiji", "Finland",
    "Former Yugoslav Republic of Macedonia", "France", "French Guiana", "French Polynesia",
    "French Southern Territories", "Gabon", "Georgia", "Germany", "Ghana", "Gibraltar",
    "Greece", "Greenland", "Grenada", "Guadeloupe", "Guam", "Guatemala", "Guinea", "Guinea-Bissau",
    "Guyana", "Haiti", "Heard Island and McDonald Islands", "Honduras", "Hong Kong", "Hungary",
    "Iceland", "India", "Indonesia", "Iran", "Iraq", "Ireland", "Israel", "Italy", "Jamaica",
    "Japan", "Jordan", "Kazakhstan", "Kenya", "Kiribati", "Kuwait", "Kyrgyzstan", "Laos",
    "Latvia", "Lebanon", "Lesotho", "Liberia", "Libya", "Liechtenstein", "Lithuania", "Luxembourg",
    "Macau", "Madagascar", "Malawi", "Malaysia", "Maldives", "Mali", "Malta", "Marshall Islands",
    "Martinique", "Mauritania", "Mauritius", "Mayotte", "Mexico", "Micronesia", "Moldova",
    "Monaco", "Mongolia", "Montserrat", "Morocco", "Mozambique", "Myanmar", "Namibia",
    "Nauru", "Nepal", "Netherlands", "Netherlands Antilles", "New Caledonia", "New Zealand",
    "Nicaragua", "Niger", "Nigeria", "Niue", "Norfolk Island", "North Korea", "Northern Marianas",
    "Norway", "Oman", "Pakistan", "Palau", "Panama", "Papua New Guinea", "Paraguay", "Peru",
    "Philippines", "Pitcairn Islands", "Poland", "Portugal", "Puerto Rico", "Qatar",
    "Reunion", "Romania", "Russia", "Rwanda", "Sqo Tome and Principe", "Saint Helena",
    "Saint Kitts and Nevis", "Saint Lucia", "Saint Pierre and Miquelon",
    "Saint Vincent and the Grenadines", "Samoa", "San Marino", "Saudi Arabia", "Senegal",
    "Seychelles", "Sierra Leone", "Singapore", "Slovakia", "Slovenia", "Solomon Islands",
    "Somalia", "South Africa", "South Georgia and the South Sandwich Islands", "South Korea",
    "Spain", "Sri Lanka", "Sudan", "Suriname", "Svalbard and Jan Mayen", "Swaziland", "Sweden",
    "Switzerland", "Syria", "Taiwan", "Tajikistan", "Tanzania", "Thailand", "The Bahamas",
    "The Gambia", "Togo", "Tokelau", "Tonga", "Trinidad and Tobago", "Tunisia", "Turkey",
    "Turkmenistan", "Turks and Caicos Islands", "Tuvalu", "Virgin Islands", "Uganda",
    "Ukraine", "United Arab Emirates", "United Kingdom",
    "United States", "United States Minor Outlying Islands", "Uruguay", "Uzbekistan",
    "Vanuatu", "Vatican City", "Venezuela", "Vietnam", "Wallis and Futuna", "Western Sahara",
    "Yemen", "Yugoslavia", "Zambia", "Zimbabwe"
    };

    public RsListRS() {
    }

    public void init(RenderScriptGL rs, Resources res) {
        mRS = rs;
        mRes = res;
        initRS();
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    private Font mItalic;

    ScriptField_ListAllocs_s mListAllocs;

    private ScriptC_rslist mScript;

    int mLastX;
    int mLastY;

    public void onActionDown(int x, int y) {
        mScript.set_gDY(0.0f);

        mLastX = x;
        mLastY = y;
    }

    public void onActionMove(int x, int y) {
        int dx = mLastX - x;
        int dy = mLastY - y;

        if (Math.abs(dy) <= 2) {
            dy = 0;
        }

        mScript.set_gDY(dy);

        mLastX = x;
        mLastY = y;
    }

    private void initRS() {

        mScript = new ScriptC_rslist(mRS, mRes, R.raw.rslist);

        mListAllocs = new ScriptField_ListAllocs_s(mRS, DATA_LIST.length);
        for (int i = 0; i < DATA_LIST.length; i ++) {
            ScriptField_ListAllocs_s.Item listElem = new ScriptField_ListAllocs_s.Item();
            listElem.text = Allocation.createFromString(mRS, DATA_LIST[i], Allocation.USAGE_SCRIPT);
            mListAllocs.set(listElem, i, false);
        }

        mListAllocs.copyAll();

        mScript.bind_gList(mListAllocs);

        mItalic = Font.create(mRS, mRes, "serif", Font.Style.BOLD_ITALIC, 8);
        mScript.set_gItalic(mItalic);

        mRS.bindRootScript(mScript);
    }
}



