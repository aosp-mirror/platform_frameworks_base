/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef TIMED_TEXT_SRT_SOURCE_H_
#define TIMED_TEXT_SRT_SOURCE_H_

#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <utils/Compat.h>  // off64_t

#include "TimedTextSource.h"

namespace android {

class AString;
class DataSource;
class MediaBuffer;
class Parcel;

class TimedTextSRTSource : public TimedTextSource {
 public:
  TimedTextSRTSource(const sp<DataSource>& dataSource);
  virtual status_t start();
  virtual status_t stop();
  virtual status_t read(
          int64_t *timeUs,
          Parcel *parcel,
          const MediaSource::ReadOptions *options = NULL);

 protected:
  virtual ~TimedTextSRTSource();

 private:
  sp<DataSource> mSource;

  struct TextInfo {
      int64_t endTimeUs;
      // The offset of the text in the original file.
      off64_t offset;
      int textLen;
  };

  int mIndex;
  KeyedVector<int64_t, TextInfo> mTextVector;

  void reset();
  status_t scanFile();
  status_t getNextSubtitleInfo(
          off64_t *offset, int64_t *startTimeUs, TextInfo *info);
  status_t readNextLine(off64_t *offset, AString *data);
  status_t getText(
          const MediaSource::ReadOptions *options,
          AString *text, int64_t *startTimeUs, int64_t *endTimeUs);
  status_t extractAndAppendLocalDescriptions(
          int64_t timeUs, const AString &text, Parcel *parcel);

  DISALLOW_EVIL_CONSTRUCTORS(TimedTextSRTSource);
};

}  // namespace android

#endif  // TIMED_TEXT_SRT_SOURCE_H_
