/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include "TimedTextParser.h"
#include <media/stagefright/DataSource.h>

namespace android {

TimedTextParser::TimedTextParser()
    : mDataSource(NULL),
      mOffset(0),
      mIndex(0) {
}

TimedTextParser::~TimedTextParser() {
    reset();
}

status_t TimedTextParser::init(
        const sp<DataSource> &dataSource, FileType fileType) {
    mDataSource = dataSource;
    mFileType = fileType;

    status_t err;
    if ((err = scanFile()) != OK) {
        reset();
        return err;
    }

    return OK;
}

void TimedTextParser::reset() {
    mDataSource.clear();
    mTextVector.clear();
    mOffset = 0;
    mIndex = 0;
}

// scan the text file to get start/stop time and the
// offset of each piece of text content
status_t TimedTextParser::scanFile() {
    if (mFileType != OUT_OF_BAND_FILE_SRT) {
        return ERROR_UNSUPPORTED;
    }

    off64_t offset = 0;
    int64_t startTimeUs;
    bool endOfFile = false;

    while (!endOfFile) {
        TextInfo info;
        status_t err = getNextInSrtFileFormat(&offset, &startTimeUs, &info);

        if (err != OK) {
            if (err == ERROR_END_OF_STREAM) {
                endOfFile = true;
            } else {
                return err;
            }
        } else {
            mTextVector.add(startTimeUs, info);
        }
    }

    if (mTextVector.isEmpty()) {
        return ERROR_MALFORMED;
    }
    return OK;
}

// read one line started from *offset and store it into data.
status_t TimedTextParser::readNextLine(off64_t *offset, AString *data) {
    char character;

    data->clear();

    while (true) {
        ssize_t err;
        if ((err = mDataSource->readAt(*offset, &character, 1)) < 1) {
            if (err == 0) {
                return ERROR_END_OF_STREAM;
            }
            return ERROR_IO;
        }

        (*offset) ++;

        // a line could end with CR, LF or CR + LF
        if (character == 10) {
            break;
        } else if (character == 13) {
            if ((err = mDataSource->readAt(*offset, &character, 1)) < 1) {
                if (err == 0) { // end of the stream
                    return OK;
                }
                return ERROR_IO;
            }

            (*offset) ++;

            if (character != 10) {
                (*offset) --;
            }
            break;
        }

        data->append(character);
    }

    return OK;
}

/* SRT format:
 *  Subtitle number
 *  Start time --> End time
 *  Text of subtitle (one or more lines)
 *  Blank lines
 *
 * .srt file example:
 *  1
 *  00:00:20,000 --> 00:00:24,400
 *  Altocumulus clouds occur between six thousand
 *
 *  2
 *  00:00:24,600 --> 00:00:27,800
 *  and twenty thousand feet above ground level.
 */
status_t TimedTextParser::getNextInSrtFileFormat(
        off64_t *offset, int64_t *startTimeUs, TextInfo *info) {
    AString data;
    status_t err;

    // To skip blank lines.
    do {
        if ((err = readNextLine(offset, &data)) != OK) {
            return err;
        }
        data.trim();
    } while(data.empty());

    // Just ignore the first non-blank line which is subtitle sequence number.

    if ((err = readNextLine(offset, &data)) != OK) {
        return err;
    }
    int hour1, hour2, min1, min2, sec1, sec2, msec1, msec2;
    // the start time format is: hours:minutes:seconds,milliseconds
    // 00:00:24,600 --> 00:00:27,800
    if (sscanf(data.c_str(), "%02d:%02d:%02d,%03d --> %02d:%02d:%02d,%03d",
                &hour1, &min1, &sec1, &msec1, &hour2, &min2, &sec2, &msec2) != 8) {
        return ERROR_MALFORMED;
    }

    *startTimeUs = ((hour1 * 3600 + min1 * 60 + sec1) * 1000 + msec1) * 1000ll;
    info->endTimeUs = ((hour2 * 3600 + min2 * 60 + sec2) * 1000 + msec2) * 1000ll;
    if (info->endTimeUs <= *startTimeUs) {
        return ERROR_MALFORMED;
    }

    info->offset = *offset;

    bool needMoreData = true;
    while (needMoreData) {
        if ((err = readNextLine(offset, &data)) != OK) {
            if (err == ERROR_END_OF_STREAM) {
                needMoreData = false;
            } else {
                return err;
            }
        }

        if (needMoreData) {
            data.trim();
            if (data.empty()) {
                // it's an empty line used to separate two subtitles
                needMoreData = false;
            }
        }
    }

    info->textLen = *offset - info->offset;

    return OK;
}

status_t TimedTextParser::getText(
        AString *text, int64_t *startTimeUs, int64_t *endTimeUs,
        const MediaSource::ReadOptions *options) {
    Mutex::Autolock autoLock(mLock);

    text->clear();

    int64_t seekTimeUs;
    MediaSource::ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        int64_t lastEndTimeUs = mTextVector.valueAt(mTextVector.size() - 1).endTimeUs;
        int64_t firstStartTimeUs = mTextVector.keyAt(0);

        if (seekTimeUs < 0 || seekTimeUs > lastEndTimeUs) {
            return ERROR_OUT_OF_RANGE;
        } else if (seekTimeUs < firstStartTimeUs) {
            mIndex = 0;
        } else {
            // binary search
            ssize_t low = 0;
            ssize_t high = mTextVector.size() - 1;
            ssize_t mid = 0;
            int64_t currTimeUs;

            while (low <= high) {
                mid = low + (high - low)/2;
                currTimeUs = mTextVector.keyAt(mid);
                const int diff = currTimeUs - seekTimeUs;

                if (diff == 0) {
                    break;
                } else if (diff < 0) {
                    low = mid + 1;
                } else {
                    if ((high == mid + 1)
                            && (seekTimeUs < mTextVector.keyAt(high))) {
                        break;
                    }
                    high = mid - 1;
                }
            }

            mIndex = mid;
        }
    }

    TextInfo info = mTextVector.valueAt(mIndex);
    *startTimeUs = mTextVector.keyAt(mIndex);
    *endTimeUs = info.endTimeUs;
    mIndex ++;

    char *str = new char[info.textLen];
    if (mDataSource->readAt(info.offset, str, info.textLen) < info.textLen) {
        delete[] str;
        return ERROR_IO;
    }

    text->append(str, info.textLen);
    delete[] str;
    return OK;
}

}  // namespace android
