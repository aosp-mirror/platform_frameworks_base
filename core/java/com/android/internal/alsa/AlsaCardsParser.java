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

    private static final String kCardsFilePath = "/proc/asound/cards";

    private static LineTokenizer mTokenizer = new LineTokenizer(" :[]");

    private ArrayList<AlsaCardRecord> mCardRecords = new ArrayList<AlsaCardRecord>();

    public class AlsaCardRecord {
        private static final String TAG = "AlsaCardRecord";
        private static final String kUsbCardKeyStr = "at usb-";

        public int mCardNum = -1;
        public String mField1 = "";
        public String mCardName = "";
        public String mCardDescription = "";
        public boolean mIsUsb = false;

        public AlsaCardRecord() {}

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
                      mIsUsb = keyIndex != -1;
                      if (mIsUsb) {
                          mCardDescription = line.substring(tokenIndex, keyIndex - 1);
                      }
                  }
            }

            return true;
        }

        public String textFormat() {
          return mCardName + " : " + mCardDescription;
        }

        public void log(int listIndex) {
            Slog.d(TAG, "" + listIndex +
                " [" + mCardNum + " " + mCardName + " : " + mCardDescription +
                " usb:" + mIsUsb);
        }
    }

    public AlsaCardsParser() {}

    public void scan() {
        if (DEBUG) {
            Slog.i(TAG, "AlsaCardsParser.scan()");
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
                    Slog.i(TAG, "  " + line);
                }
                cardRecord.parse(line, 0);

                line = bufferedReader.readLine();
                if (line == null) {
                    break;
                }
                if (DEBUG) {
                    Slog.i(TAG, "  " + line);
                }
                cardRecord.parse(line, 1);

                mCardRecords.add(cardRecord);
            }
            reader.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public ArrayList<AlsaCardRecord> getScanRecords() {
        return mCardRecords;
    }

    public AlsaCardRecord getCardRecordAt(int index) {
        return mCardRecords.get(index);
    }

    public AlsaCardRecord getCardRecordFor(int cardNum) {
        for (AlsaCardRecord rec : mCardRecords) {
            if (rec.mCardNum == cardNum) {
                return rec;
            }
        }

        return null;
    }

    public int getNumCardRecords() {
        return mCardRecords.size();
    }

    public boolean isCardUsb(int cardNum) {
        for (AlsaCardRecord rec : mCardRecords) {
            if (rec.mCardNum == cardNum) {
                return rec.mIsUsb;
            }
        }

        return false;
    }

    // return -1 if none found
    public int getDefaultUsbCard() {
        // save the current list of devices
        ArrayList<AlsaCardsParser.AlsaCardRecord> prevRecs = mCardRecords;
        if (DEBUG) {
            LogDevices("Previous Devices:", prevRecs);
        }

        // get the new list of devices
        scan();
        if (DEBUG) {
            LogDevices("Current Devices:", mCardRecords);
        }

        // Calculate the difference between the old and new device list
        ArrayList<AlsaCardRecord> newRecs = getNewCardRecords(prevRecs);
        if (DEBUG) {
            LogDevices("New Devices:", newRecs);
        }

        // Choose the most-recently added EXTERNAL card
        // Check recently added devices
        for (AlsaCardRecord rec : newRecs) {
            if (DEBUG) {
                Slog.d(TAG, rec.mCardName + " card:" + rec.mCardNum + " usb:" + rec.mIsUsb);
            }
            if (rec.mIsUsb) {
                // Found it
                return rec.mCardNum;
            }
        }

        // or return the first added EXTERNAL card?
        for (AlsaCardRecord rec : prevRecs) {
            if (DEBUG) {
                Slog.d(TAG, rec.mCardName + " card:" + rec.mCardNum + " usb:" + rec.mIsUsb);
            }
            if (rec.mIsUsb) {
                return rec.mCardNum;
            }
        }

        return -1;
    }

    public int getDefaultCard() {
        // return an external card if possible
        int card = getDefaultUsbCard();
        if (DEBUG) {
            Slog.d(TAG, "getDefaultCard() default usb card:" + card);
        }

        if (card < 0 && getNumCardRecords() > 0) {
            // otherwise return the (internal) card with the highest number
            card = getCardRecordAt(getNumCardRecords() - 1).mCardNum;
        }
        if (DEBUG) {
            Slog.d(TAG, "  returns card:" + card);
        }
        return card;
    }

    static public boolean hasCardNumber(ArrayList<AlsaCardRecord> recs, int cardNum) {
        for (AlsaCardRecord cardRec : recs) {
            if (cardRec.mCardNum == cardNum) {
                return true;
            }
        }
        return false;
    }

    public ArrayList<AlsaCardRecord> getNewCardRecords(ArrayList<AlsaCardRecord> prevScanRecs) {
        ArrayList<AlsaCardRecord> newRecs = new ArrayList<AlsaCardRecord>();
        for (AlsaCardRecord rec : mCardRecords) {
            // now scan to see if this card number is in the previous scan list
            if (!hasCardNumber(prevScanRecs, rec.mCardNum)) {
                newRecs.add(rec);
            }
        }
        return newRecs;
    }

    //
    // Logging
    //
    private void Log(String heading) {
        if (DEBUG) {
            Slog.i(TAG, heading);
            for (AlsaCardRecord cardRec : mCardRecords) {
                Slog.i(TAG, cardRec.textFormat());
            }
        }
    }

    static private void LogDevices(String caption, ArrayList<AlsaCardRecord> deviceList) {
        Slog.d(TAG, caption + " ----------------");
        int listIndex = 0;
        for (AlsaCardRecord device : deviceList) {
            device.log(listIndex++);
        }
        Slog.d(TAG, "----------------");
    }
}
