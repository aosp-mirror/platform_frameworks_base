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

package android.syncml.pim.vcard;

import android.syncml.pim.VDataBuilder;
import android.syncml.pim.VParser;
import android.util.Config;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.IOException;

public class VCardParser {

    // TODO: fix this.
    VCardParser_V21 mParser = null;

    public final static String VERSION_VCARD21 = "vcard2.1";

    public final static String VERSION_VCARD30 = "vcard3.0";

    final public static int VERSION_VCARD21_INT = 1;

    final public static int VERSION_VCARD30_INT = 2;

    String mVersion = null;

    static final private String TAG = "VCardParser";

    public VCardParser() {
    }

    /**
     * If version not given. Search from vcard string of the VERSION property.
     * Then instance mParser to appropriate parser.
     *
     * @param vcardStr
     *            the content of vcard data
     */
    private void judgeVersion(String vcardStr) {
        if (mVersion == null) {// auto judge
            int verIdx = vcardStr.indexOf("\nVERSION:");
            if (verIdx == -1) // if not have VERSION, v2.1 default
                mVersion = VERSION_VCARD21;
            else {
                String verStr = vcardStr.substring(verIdx, vcardStr.indexOf(
                        "\n", verIdx + 1));
                if (verStr.indexOf("2.1") > 0)
                    mVersion = VERSION_VCARD21;
                else if (verStr.indexOf("3.0") > 0)
                    mVersion = VERSION_VCARD30;
                else
                    mVersion = VERSION_VCARD21;
            }
        }
        if (mVersion.equals(VERSION_VCARD21))
            mParser = new VCardParser_V21();
        if (mVersion.equals(VERSION_VCARD30))
            mParser = new VCardParser_V30();
    }

    /**
     * To make sure the vcard string has proper wrap character
     *
     * @param vcardStr
     *            the string to be checked
     * @return string after verified
     */
    private String verifyVCard(String vcardStr) {
        this.judgeVersion(vcardStr);
        // -- indent line:
        vcardStr = vcardStr.replaceAll("\r\n", "\n");
        String[] strlist = vcardStr.split("\n");
        StringBuilder v21str = new StringBuilder("");
        for (int i = 0; i < strlist.length; i++) {
            if (strlist[i].indexOf(":") < 0) {
                if (strlist[i].length() == 0 && strlist[i + 1].indexOf(":") > 0)
                    v21str.append(strlist[i]).append("\r\n");
                else
                    v21str.append(" ").append(strlist[i]).append("\r\n");
            } else
                v21str.append(strlist[i]).append("\r\n");
        }
        return v21str.toString();
    }

    /**
     * Set current version
     *
     * @param version
     *            the new version
     */
    private void setVersion(String version) {
        this.mVersion = version;
    }

    /**
     * Parse the given vcard string
     *
     * @param vcardStr
     *            to content to be parsed
     * @param builder
     *            the data builder to hold data
     * @return true if the string is successfully parsed, else return false
     * @throws VCardException
     * @throws IOException
     */
    public boolean parse(String vcardStr, VDataBuilder builder)
            throws VCardException, IOException {

        vcardStr = this.verifyVCard(vcardStr);

        boolean isSuccess = mParser.parse(new ByteArrayInputStream(vcardStr
                .getBytes()), "US-ASCII", builder);
        if (!isSuccess) {
            if (mVersion.equals(VERSION_VCARD21)) {
                if (Config.LOGD)
                    Log.d(TAG, "Parse failed for vCard 2.1 parser."
                            + " Try to use 3.0 parser.");

                this.setVersion(VERSION_VCARD30);

                return this.parse(vcardStr, builder);
            }
            throw new VCardException("parse failed.(even use 3.0 parser)");
        }
        return true;
    }
}
