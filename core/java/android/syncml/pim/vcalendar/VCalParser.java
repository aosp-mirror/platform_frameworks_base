/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.syncml.pim.vcalendar;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import android.util.Config;
import android.util.Log;

import android.syncml.pim.VDataBuilder;
import android.syncml.pim.VParser;

public class VCalParser{

    private final static String TAG = "VCalParser";

    public final static String VERSION_VCALENDAR10 = "vcalendar1.0";
    public final static String VERSION_VCALENDAR20 = "vcalendar2.0";

    private VParser mParser = null;
    private String mVersion = null;

    public VCalParser() {
    }

    public boolean parse(String vcalendarStr, VDataBuilder builder)
            throws VCalException {

        vcalendarStr = verifyVCal(vcalendarStr);
        try{
            boolean isSuccess = mParser.parse(
                    new ByteArrayInputStream(vcalendarStr.getBytes()),
                    "US-ASCII", builder);

            if (!isSuccess) {
                if (mVersion.equals(VERSION_VCALENDAR10)) {
                    if(Config.LOGD)
                        Log.d(TAG, "Parse failed for vCal 1.0 parser."
                            + " Try to use 2.0 parser.");
                    mVersion = VERSION_VCALENDAR20;
                    return parse(vcalendarStr, builder);
                }else
                    throw new VCalException("parse failed.(even use 2.0 parser)");
            }
        }catch (IOException e){
            throw new VCalException(e.getMessage());
        }
        return true;
    }

    /**
     * Verify vCalendar string, and initialize mVersion according to it.
     * */
    private String verifyVCal(String vcalStr) {

        //Version check
        judgeVersion(vcalStr);

        vcalStr = vcalStr.replaceAll("\r\n", "\n");
        String[] strlist = vcalStr.split("\n");

        StringBuilder replacedStr = new StringBuilder();

        for (int i = 0; i < strlist.length; i++) {
            if (strlist[i].indexOf(":") < 0) {
                if (strlist[i].length() == 0 && strlist[i + 1].indexOf(":") > 0)
                    replacedStr.append(strlist[i]).append("\r\n");
                else
                    replacedStr.append(" ").append(strlist[i]).append("\r\n");
            } else
                replacedStr.append(strlist[i]).append("\r\n");
        }
        if(Config.LOGD)Log.d(TAG, "After verify:\r\n" + replacedStr.toString());

        return replacedStr.toString();
    }

    /**
     * If version not given. Search from vcal string of the VERSION property.
     * Then instance mParser to appropriate parser.
     */
    private void judgeVersion(String vcalStr) {

        if (mVersion == null) {
            int versionIdx = vcalStr.indexOf("\nVERSION:");

            mVersion = VERSION_VCALENDAR10;

            if (versionIdx != -1){
                String versionStr = vcalStr.substring(
                        versionIdx, vcalStr.indexOf("\n", versionIdx + 1));
                if (versionStr.indexOf("2.0") > 0)
                    mVersion = VERSION_VCALENDAR20;
            }
        }
        if (mVersion.equals(VERSION_VCALENDAR10))
            mParser = new VCalParser_V10();
        if (mVersion.equals(VERSION_VCALENDAR20))
            mParser = new VCalParser_V20();
    }
}

