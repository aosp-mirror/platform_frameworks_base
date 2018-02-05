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

package com.android.internal.alsa;

import android.util.Slog;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

/**
 * @hide Retrieves information from an ALSA "cards" file.
 */
public class AlsaCardsParser {
    private static final String TAG = "AlsaCardsParser";
    protected static final boolean DEBUG = false;

    private static final String kAlsaFolderPath = "/proc/asound";
    private static final String kCardsFilePath = kAlsaFolderPath + "/cards";
    private static final String kDeviceAddressPrefix = "/dev/bus/usb/";

    private static LineTokenizer mTokenizer = new LineTokenizer(" :[]");

    private ArrayList<AlsaCardRecord> mCardRecords = new ArrayList<AlsaCardRecord>();

    public static final int SCANSTATUS_NOTSCANNED = -1;
    public static final int SCANSTATUS_SUCCESS = 0;
    public static final int SCANSTATUS_FAIL = 1;
    public static final int SCANSTATUS_EMPTY = 2;
    private int mScanStatus = SCANSTATUS_NOTSCANNED;

    public class AlsaCardRecord {
        private static final String TAG = "AlsaCardRecord";
        private static final String kUsbCardKeyStr = "at usb-";

        int mCardNum = -1;
        String mField1 = "";
        String mCardName = "";
        String mCardDescription = "";

        private String mUsbDeviceAddress = null;

        public AlsaCardRecord() {}

        public int getCardNum() {
            return mCardNum;
        }

        public String getCardName() {
            return mCardName;
        }

        public String getCardDescription() {
            return mCardDescription;
        }

        public void setDeviceAddress(String usbDeviceAddress) {
            mUsbDeviceAddress = usbDeviceAddress;
        }

        private boolean parse(String line, int lineIndex) {
            int tokenIndex = 0;
            int delimIndex = 0;

            if (lineIndex == 0) {
                // line # (skip)
                tokenIndex = mTokenizer.nextToken(line, tokenIndex);
                delimIndex = mTokenizer.nextDelimiter(line, tokenIndex);

                try {
                    // mCardNum
                    mCardNum = Integer.parseInt(line.substring(tokenIndex, delimIndex));
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Failed to parse line " + lineIndex + " of " + kCardsFilePath
                        + ": " + line.substring(tokenIndex, delimIndex));
                    return false;
                }

                // mField1
                tokenIndex = mTokenizer.nextToken(line, delimIndex);
                delimIndex = mTokenizer.nextDelimiter(line, tokenIndex);
                mField1 = line.substring(tokenIndex, delimIndex);

                // mCardName
                tokenIndex = mTokenizer.nextToken(line, delimIndex);
                mCardName = line.substring(tokenIndex);

                // done
              } else if (lineIndex == 1) {
                  tokenIndex = mTokenizer.nextToken(line, 0);
                  if (tokenIndex != -1) {
                      int keyIndex = line.indexOf(kUsbCardKeyStr);
                      boolean isUsb = keyIndex != -1;
                      if (isUsb) {
                          mCardDescription = line.substring(tokenIndex, keyIndex - 1);
                      }
                  }
            }

            return true;
        }

        boolean isUsb() {
            return mUsbDeviceAddress != null;
        }

        public String textFormat() {
          return mCardName + " : " + mCardDescription + " [addr:" + mUsbDeviceAddress + "]";
        }

        public void log(int listIndex) {
            Slog.d(TAG, "" + listIndex +
                " [" + mCardNum + " " + mCardName + " : " + mCardDescription +
                " usb:" + isUsb());
        }
    }

    public AlsaCardsParser() {}

    public int scan() {
        if (DEBUG) {
            Slog.d(TAG, "AlsaCardsParser.scan()....");
        }

        mCardRecords = new ArrayList<AlsaCardRecord>();

        File cardsFile = new File(kCardsFilePath);
        try {
            FileReader reader = new FileReader(cardsFile);
            BufferedReader bufferedReader = new BufferedReader(reader);
            String line = "";
            while ((line = bufferedReader.readLine()) != null) {
                AlsaCardRecord cardRecord = new AlsaCardRecord();
                if (DEBUG) {
                    Slog.d(TAG, "  " + line);
                }
                cardRecord.parse(line, 0);

                line = bufferedReader.readLine();
                if (line == null) {
                    break;
                }
                if (DEBUG) {
                    Slog.d(TAG, "  " + line);
                }
                cardRecord.parse(line, 1);

                // scan "usbbus" file
                int cardNum = cardRecord.mCardNum;
                String cardFolderPath = kAlsaFolderPath + "/card" + cardNum;
                File usbbusFile = new File(cardFolderPath + "/usbbus");
                if (usbbusFile.exists()) {
                    // read it in
                    FileReader usbbusReader = new FileReader(usbbusFile);
                    String deviceAddress = (new BufferedReader(usbbusReader)).readLine();
                    if (deviceAddress != null) {
                        cardRecord.setDeviceAddress(kDeviceAddressPrefix + deviceAddress);
                    }
                    usbbusReader.close();
                }
                mCardRecords.add(cardRecord);
            }
            reader.close();
            if (mCardRecords.size() > 0) {
                mScanStatus = SCANSTATUS_SUCCESS;
            } else {
                mScanStatus = SCANSTATUS_EMPTY;
            }
        } catch (FileNotFoundException e) {
            mScanStatus = SCANSTATUS_FAIL;
        } catch (IOException e) {
            mScanStatus = SCANSTATUS_FAIL;
        }
        if (DEBUG) {
            Slog.d(TAG, "  status:" + mScanStatus);
        }
        return mScanStatus;
    }

    public int getScanStatus() {
        return mScanStatus;
    }

    public AlsaCardRecord findCardNumFor(String deviceAddress) {
        for(AlsaCardRecord cardRec : mCardRecords) {
            if (cardRec.isUsb() && cardRec.mUsbDeviceAddress.equals(deviceAddress)) {
                return cardRec;
            }
        }
        return null;
    }

    //
    // Logging
    //
    private void Log(String heading) {
        if (DEBUG) {
            Slog.d(TAG, heading);
            for (AlsaCardRecord cardRec : mCardRecords) {
                Slog.d(TAG, cardRec.textFormat());
            }
        }
    }

//    static private void LogDevices(String caption, ArrayList<AlsaCardRecord> deviceList) {
//        Slog.d(TAG, caption + " ----------------");
//        int listIndex = 0;
//        for (AlsaCardRecord device : deviceList) {
//            device.log(listIndex++);
//        }
//        Slog.d(TAG, caption + "----------------");
//    }
}
