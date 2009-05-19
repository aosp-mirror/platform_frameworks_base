/*
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

package com.android.internal.telephony.cdma;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.Message;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;

import com.android.internal.util.XmlUtils;

import java.util.HashMap;

/**
 * TODO(Teleca): Please as some comments on how this class is to
 * be used. We've removed Handler as a base class and instead
 * recommend that child classes add a Handler as a member if its
 * needed.
 */


public final class EriManager {

    class EriFile {
        public static final int MAX_ERI_ENTRIES = 30;

        public int mVersionNumber;                      // File version number
        public int mNumberOfEriEntries;                 // Number of entries
        public int mEriFileType;                        // Eri Phase 0/1
        //public int mNumberOfIconImages;               // reserved for future use
        //public int mIconImageType;                    // reserved for future use
        public String[] mCallPromptId;                  // reserved for future use
        public HashMap<Integer, EriInfo> mRoamIndTable; // Roaming Indicator Table

        public EriFile() {
            this.mVersionNumber = -1;
            this.mNumberOfEriEntries = 0;
            this.mEriFileType = -1;
            this.mCallPromptId = new String[] { "", "", "" };
            this.mRoamIndTable = new HashMap<Integer, EriInfo>();
        }

    }

    static final String LOG_TAG = "CDMA";

    public static final int ERI_FROM_XML          = 0;
    public static final int ERI_FROM_FILE_SYSTEM  = 1;
    public static final int ERI_FROM_MODEM        = 2;

    private PhoneBase mPhone;
    private Context mContext;
    private int mEriFileSource = ERI_FROM_XML;
    private boolean isEriFileLoaded = false;
    private EriFile mEriFile;

    public EriManager(PhoneBase phone, Context context, int eriFileSource) {
        this.mPhone = phone;
        this.mContext = context;
        this.mEriFileSource = eriFileSource;
        this.mEriFile = new EriFile();
    }

    public void dispose() {
        mEriFile = new EriFile();
        isEriFileLoaded = false;
    }


    public void loadEriFile() {
        switch (mEriFileSource) {
        case ERI_FROM_MODEM:
            loadEriFileFromModem();
            break;

        case ERI_FROM_FILE_SYSTEM:
            loadEriFileFromFileSystem();
            break;

        case ERI_FROM_XML:
        default:
            loadEriFileFromXml();
            break;
        }
    }

    /**
     * Load the ERI file from the MODEM through chipset specific RIL_REQUEST_OEM_HOOK
     *
     * In this case the ERI file can be updated from the Phone Support Tool available
     * from the Chipset vendor
     */
    private void loadEriFileFromModem() {
        // NOT IMPLEMENTED, Chipset vendor/Operator specific
    }

    /**
     * Load the ERI file from a File System file
     *
     * In this case the a Phone Support Tool to update the ERI file must be provided
     * to the Operator
     */
    private void loadEriFileFromFileSystem() {
        // NOT IMPLEMENTED, Chipset vendor/Operator specific
    }

    /**
     * Load the ERI file from the application framework resources encoded in XML
     *
     */
    private void loadEriFileFromXml() {
        Resources r = mContext.getResources();
        XmlResourceParser parser = r.getXml(com.android.internal.R.xml.eri);
        try {
            XmlUtils.beginDocument(parser, "EriFile");
            mEriFile.mVersionNumber = Integer.parseInt(
                    parser.getAttributeValue(null, "VersionNumber"));
            mEriFile.mNumberOfEriEntries = Integer.parseInt(
                    parser.getAttributeValue(null, "NumberOfEriEntries"));
            mEriFile.mEriFileType = Integer.parseInt(
                    parser.getAttributeValue(null, "EriFileType"));

            int parsedEriEntries = 0;
            while(true) {
                XmlUtils.nextElement(parser);
                String name = parser.getName();
                if (name == null) {
                    if (parsedEriEntries != mEriFile.mNumberOfEriEntries)
                        Log.e(LOG_TAG, "Error Parsing ERI file: " +  mEriFile.mNumberOfEriEntries
                                + " defined, " + parsedEriEntries + " parsed!");
                    break;
                } else if (name.equals("CallPromptId")) {
                    int id = Integer.parseInt(parser.getAttributeValue(null, "Id"));
                    String text = parser.getAttributeValue(null, "CallPromptText");
                    if (id >= 0 && id <= 2) {
                        mEriFile.mCallPromptId[id] = text;
                    } else {
                        Log.e(LOG_TAG, "Error Parsing ERI file: found" + id + " CallPromptId");
                    }

                } else if (name.equals("EriInfo")) {
                    int roamingIndicator = Integer.parseInt(
                            parser.getAttributeValue(null, "RoamingIndicator"));
                    int iconIndex = Integer.parseInt(parser.getAttributeValue(null, "IconIndex"));
                    int iconMode = Integer.parseInt(parser.getAttributeValue(null, "IconMode"));
                    String eriText = parser.getAttributeValue(null, "EriText");
                    int callPromptId = Integer.parseInt(
                            parser.getAttributeValue(null, "CallPromptId"));
                    int alertId = Integer.parseInt(parser.getAttributeValue(null, "AlertId"));
                    parsedEriEntries++;
                    mEriFile.mRoamIndTable.put(roamingIndicator, new EriInfo (roamingIndicator,
                            iconIndex, iconMode, eriText, callPromptId, alertId));
                }
            }

            isEriFileLoaded = true;

        } catch (Exception e) {
            Log.e(LOG_TAG, "Got exception while loading ERI file.", e);
        } finally {
            parser.close();
        }
    }

    /**
     * Returns the version of the ERI file
     *
     */
    public int getEriFileVersion() {
        return mEriFile.mVersionNumber;
    }

    /**
     * Returns the number of ERI entries parsed
     *
     */
    public int getEriNumberOfEntries() {
        return mEriFile.mNumberOfEriEntries;
    }

    /**
     * Returns the ERI file type value ( 0 for Phase 0, 1 for Phase 1)
     *
     */
    public int getEriFileType() {
        return mEriFile.mEriFileType;
    }

    /**
     * Returns if the ERI file has been loaded
     *
     */
    public boolean isEriFileLoaded() {
        return isEriFileLoaded;
    }

    /**
     * Returns the EriInfo record associated with roamingIndicator
     * or null if the entry is not found
     */
    public EriInfo getEriInfo(int roamingIndicator) {
        if (mEriFile.mRoamIndTable.containsKey(roamingIndicator)) {
            return mEriFile.mRoamIndTable.get(roamingIndicator);
        } else {
            return null;
        }
    }
}
