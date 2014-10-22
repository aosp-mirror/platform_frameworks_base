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

package android.alsa;

import android.util.Slog;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Vector;

/**
 * @hide Retrieves information from an ALSA "cards" file.
 */
public class AlsaCardsParser {
    private static final String TAG = "AlsaCardsParser";

    private static LineTokenizer tokenizer_ = new LineTokenizer(" :[]");

    public class AlsaCardRecord {
        public int mCardNum = -1;
        public String mField1 = "";
        public String mCardName = "";
        public String mCardDescription = "";

        public AlsaCardRecord() {}

        public boolean parse(String line, int lineIndex) {
            int tokenIndex = 0;
            int delimIndex = 0;
            if (lineIndex == 0) {
                // line # (skip)
                tokenIndex = tokenizer_.nextToken(line, tokenIndex);
                delimIndex = tokenizer_.nextDelimiter(line, tokenIndex);

                // mField1
                tokenIndex = tokenizer_.nextToken(line, delimIndex);
                delimIndex = tokenizer_.nextDelimiter(line, tokenIndex);
                mField1 = line.substring(tokenIndex, delimIndex);

                // mCardName
                tokenIndex = tokenizer_.nextToken(line, delimIndex);
                // delimIndex = tokenizer_.nextDelimiter(line, tokenIndex);
                mCardName = line.substring(tokenIndex);
                // done
              } else if (lineIndex == 1) {
                  tokenIndex = tokenizer_.nextToken(line, 0);
                  if (tokenIndex != -1) {
                      mCardDescription = line.substring(tokenIndex);
                  }
            }

            return true;
        }

        public String textFormat() {
          return mCardName + " : " + mCardDescription;
        }
    }

    private Vector<AlsaCardRecord> cardRecords_ = new Vector<AlsaCardRecord>();

    public void scan() {
          cardRecords_.clear();
          final String cardsFilePath = "/proc/asound/cards";
          File cardsFile = new File(cardsFilePath);
          try {
              FileReader reader = new FileReader(cardsFile);
              BufferedReader bufferedReader = new BufferedReader(reader);
              String line = "";
              while ((line = bufferedReader.readLine()) != null) {
                  AlsaCardRecord cardRecord = new AlsaCardRecord();
                  cardRecord.parse(line, 0);
                  cardRecord.parse(line = bufferedReader.readLine(), 1);
                  cardRecords_.add(cardRecord);
              }
              reader.close();
          } catch (FileNotFoundException e) {
              e.printStackTrace();
          } catch (IOException e) {
              e.printStackTrace();
          }
      }

      public AlsaCardRecord getCardRecordAt(int index) {
          return cardRecords_.get(index);
      }

      public int getNumCardRecords() {
          return cardRecords_.size();
      }

    public void Log() {
      int numCardRecs = getNumCardRecords();
      for (int index = 0; index < numCardRecs; ++index) {
          Slog.w(TAG, "usb:" + getCardRecordAt(index).textFormat());
      }
    }

    public AlsaCardsParser() {}
}
