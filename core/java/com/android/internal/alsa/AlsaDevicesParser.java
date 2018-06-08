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
 * @hide
 * Retrieves information from an ALSA "devices" file.
 */
/*
 * NOTE: This class is currently not being used, but may be needed in the future.
 */
public class AlsaDevicesParser {
    private static final String TAG = "AlsaDevicesParser";
    protected static final boolean DEBUG = false;

    private static final String kDevicesFilePath = "/proc/asound/devices";

    private static final int kIndex_CardDeviceField = 5;
    private static final int kStartIndex_CardNum = 6;
    private static final int kEndIndex_CardNum = 8; // one past
    private static final int kStartIndex_DeviceNum = 9;
    private static final int kEndIndex_DeviceNum = 11; // one past
    private static final int kStartIndex_Type = 14;

    private static LineTokenizer mTokenizer = new LineTokenizer(" :[]-");

    private boolean mHasCaptureDevices = false;
    private boolean mHasPlaybackDevices = false;
    private boolean mHasMIDIDevices = false;

    public static final int SCANSTATUS_NOTSCANNED = -1;
    public static final int SCANSTATUS_SUCCESS = 0;
    public static final int SCANSTATUS_FAIL = 1;
    public static final int SCANSTATUS_EMPTY = 2;
    private int mScanStatus = SCANSTATUS_NOTSCANNED;

    public class AlsaDeviceRecord {
        public static final int kDeviceType_Unknown = -1;
        public static final int kDeviceType_Audio = 0;
        public static final int kDeviceType_Control = 1;
        public static final int kDeviceType_MIDI = 2;

        public static final int kDeviceDir_Unknown = -1;
        public static final int kDeviceDir_Capture = 0;
        public static final int kDeviceDir_Playback = 1;

        int mCardNum = -1;
        int mDeviceNum = -1;
        int mDeviceType = kDeviceType_Unknown;
        int mDeviceDir = kDeviceDir_Unknown;

        public AlsaDeviceRecord() {}

        public boolean parse(String line) {
            // "0123456789012345678901234567890"
            // "  2: [ 0-31]: digital audio playback"
            // "  3: [ 0-30]: digital audio capture"
            // " 35: [ 1]   : control"
            // " 36: [ 2- 0]: raw midi"

            final int kToken_LineNum = 0;
            final int kToken_CardNum = 1;
            final int kToken_DeviceNum = 2;
            final int kToken_Type0 = 3; // "digital", "control", "raw"
            final int kToken_Type1 = 4; // "audio", "midi"
            final int kToken_Type2 = 5; // "capture", "playback"

            int tokenOffset = 0;
            int delimOffset = 0;
            int tokenIndex = kToken_LineNum;
            while (true) {
                tokenOffset = mTokenizer.nextToken(line, delimOffset);
                if (tokenOffset == LineTokenizer.kTokenNotFound) {
                    break; // bail
                }
                delimOffset = mTokenizer.nextDelimiter(line, tokenOffset);
                if (delimOffset == LineTokenizer.kTokenNotFound) {
                    delimOffset = line.length();
                }
                String token = line.substring(tokenOffset, delimOffset);

                try {
                    switch (tokenIndex) {
                    case kToken_LineNum:
                        // ignore
                        break;

                    case kToken_CardNum:
                        mCardNum = Integer.parseInt(token);
                        if (line.charAt(delimOffset) != '-') {
                            tokenIndex++; // no device # in the token stream
                        }
                        break;

                    case kToken_DeviceNum:
                        mDeviceNum = Integer.parseInt(token);
                        break;

                    case kToken_Type0:
                        if (token.equals("digital")) {
                            // NOP
                        } else if (token.equals("control")) {
                            mDeviceType = kDeviceType_Control;
                        } else if (token.equals("raw")) {
                            // NOP
                        }
                        break;

                    case kToken_Type1:
                        if (token.equals("audio")) {
                            mDeviceType = kDeviceType_Audio;
                        } else if (token.equals("midi")) {
                            mDeviceType = kDeviceType_MIDI;
                            mHasMIDIDevices = true;
                        }
                        break;

                    case kToken_Type2:
                        if (token.equals("capture")) {
                            mDeviceDir = kDeviceDir_Capture;
                            mHasCaptureDevices = true;
                        } else if (token.equals("playback")) {
                            mDeviceDir = kDeviceDir_Playback;
                            mHasPlaybackDevices = true;
                        }
                        break;
                    } // switch (tokenIndex)
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Failed to parse token " + tokenIndex + " of " + kDevicesFilePath
                        + " token: " + token);
                    return false;
                }

                tokenIndex++;
            } // while (true)

            return true;
        } // parse()

        public String textFormat() {
            StringBuilder sb = new StringBuilder();
            sb.append("[" + mCardNum + ":" + mDeviceNum + "]");

            switch (mDeviceType) {
            case kDeviceType_Unknown:
            default:
                sb.append(" N/A");
                break;
            case kDeviceType_Audio:
                sb.append(" Audio");
                break;
            case kDeviceType_Control:
                sb.append(" Control");
                break;
            case kDeviceType_MIDI:
                sb.append(" MIDI");
                break;
            }

            switch (mDeviceDir) {
            case kDeviceDir_Unknown:
            default:
                sb.append(" N/A");
                break;
            case kDeviceDir_Capture:
                sb.append(" Capture");
                break;
            case kDeviceDir_Playback:
                sb.append(" Playback");
                break;
            }

            return sb.toString();
        }
    }

    private final ArrayList<AlsaDeviceRecord> mDeviceRecords = new ArrayList<AlsaDeviceRecord>();

    public AlsaDevicesParser() {}

    //
    // Access
    //
    public int getDefaultDeviceNum(int card) {
        // TODO - This (obviously) isn't sufficient. Revisit.
        return 0;
    }

    //
    // Predicates
    //
    public boolean hasPlaybackDevices(int card) {
        for (AlsaDeviceRecord deviceRecord : mDeviceRecords) {
            if (deviceRecord.mCardNum == card &&
                deviceRecord.mDeviceType == AlsaDeviceRecord.kDeviceType_Audio &&
                deviceRecord.mDeviceDir == AlsaDeviceRecord.kDeviceDir_Playback) {
                return true;
            }
        }
        return false;
    }

    public boolean hasCaptureDevices(int card) {
        for (AlsaDeviceRecord deviceRecord : mDeviceRecords) {
            if (deviceRecord.mCardNum == card &&
                deviceRecord.mDeviceType == AlsaDeviceRecord.kDeviceType_Audio &&
                deviceRecord.mDeviceDir == AlsaDeviceRecord.kDeviceDir_Capture) {
                return true;
            }
        }
        return false;
    }

    public boolean hasMIDIDevices(int card) {
        for (AlsaDeviceRecord deviceRecord : mDeviceRecords) {
            if (deviceRecord.mCardNum == card &&
                deviceRecord.mDeviceType == AlsaDeviceRecord.kDeviceType_MIDI) {
                return true;
            }
        }
        return false;
    }

    //
    // Process
    //
    private boolean isLineDeviceRecord(String line) {
        return line.charAt(kIndex_CardDeviceField) == '[';
    }

    public int scan() {
        if (DEBUG) {
            Slog.i(TAG, "AlsaDevicesParser.scan()....");
        }

        mDeviceRecords.clear();

        File devicesFile = new File(kDevicesFilePath);
        try {
            FileReader reader = new FileReader(devicesFile);
            BufferedReader bufferedReader = new BufferedReader(reader);
            String line = "";
            while ((line = bufferedReader.readLine()) != null) {
                if (isLineDeviceRecord(line)) {
                    AlsaDeviceRecord deviceRecord = new AlsaDeviceRecord();
                    deviceRecord.parse(line);
                    Slog.i(TAG, deviceRecord.textFormat());
                    mDeviceRecords.add(deviceRecord);
                }
            }
            reader.close();
            // success if we add at least 1 record
            if (mDeviceRecords.size() > 0) {
                mScanStatus = SCANSTATUS_SUCCESS;
            } else {
                mScanStatus = SCANSTATUS_EMPTY;
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            mScanStatus = SCANSTATUS_FAIL;
        } catch (IOException e) {
            e.printStackTrace();
            mScanStatus = SCANSTATUS_FAIL;
        }
        if (DEBUG) {
            Slog.i(TAG, "  status:" + mScanStatus);
        }
        return mScanStatus;
    }

    public int getScanStatus() {
        return mScanStatus;
    }

    //
    // Loging
    //
    private void Log(String heading) {
        if (DEBUG) {
            Slog.i(TAG, heading);
            for (AlsaDeviceRecord deviceRecord : mDeviceRecords) {
                Slog.i(TAG, deviceRecord.textFormat());
            }
        }
    }
} // class AlsaDevicesParser

