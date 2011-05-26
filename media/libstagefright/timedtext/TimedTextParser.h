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

#ifndef TIMED_TEXT_PARSER_H_

#define TIMED_TEXT_PARSER_H_

#include <media/MediaPlayerInterface.h>
#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AString.h>
#include <media/stagefright/MediaSource.h>

namespace android {

class DataSource;

class TimedTextParser : public RefBase {
public:
    TimedTextParser();
    virtual ~TimedTextParser();

    enum FileType {
        OUT_OF_BAND_FILE_SRT = 1,
    };

    status_t getText(AString *text, int64_t *startTimeUs, int64_t *endTimeUs,
                     const MediaSource::ReadOptions *options = NULL);
    status_t init(const sp<DataSource> &dataSource, FileType fileType);
    void reset();

private:
    Mutex mLock;

    sp<DataSource> mDataSource;
    off64_t mOffset;

    struct TextInfo {
        int64_t endTimeUs;
        // the offset of the text in the original file
        off64_t offset;
        int textLen;
    };

    int mIndex;
    FileType mFileType;

    // the key indicated the start time of the text
    KeyedVector<int64_t, TextInfo> mTextVector;

    status_t getNextInSrtFileFormat(
            off64_t *offset, int64_t *startTimeUs, TextInfo *info);
    status_t readNextLine(off64_t *offset, AString *data);

    status_t scanFile();

    DISALLOW_EVIL_CONSTRUCTORS(TimedTextParser);
};

}  // namespace android

#endif  // TIMED_TEXT_PARSER_H_

