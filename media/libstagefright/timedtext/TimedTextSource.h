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

#ifndef TIMED_TEXT_SOURCE_H_
#define TIMED_TEXT_SOURCE_H_

#include <media/stagefright/foundation/ABase.h>  // for DISALLOW_XXX macro.
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>  // for MediaSource::ReadOptions
#include <utils/RefBase.h>

namespace android {

class DataSource;
class MetaData;
class Parcel;

class TimedTextSource : public RefBase {
 public:
  enum FileType {
      OUT_OF_BAND_FILE_SRT = 1,
      OUT_OF_BAND_FILE_SMI = 2,
  };
  static sp<TimedTextSource> CreateTimedTextSource(
      const sp<MediaSource>& source);
  static sp<TimedTextSource> CreateTimedTextSource(
      const sp<DataSource>& source, FileType filetype);
  TimedTextSource() {}
  virtual status_t start() = 0;
  virtual status_t stop() = 0;
  // Returns subtitle parcel and its start time.
  virtual status_t read(
          int64_t *startTimeUs,
          int64_t *endTimeUs,
          Parcel *parcel,
          const MediaSource::ReadOptions *options = NULL) = 0;
  virtual status_t extractGlobalDescriptions(Parcel *parcel) {
      return INVALID_OPERATION;
  }
  virtual sp<MetaData> getFormat();

 protected:
  virtual ~TimedTextSource() { }

 private:
  DISALLOW_EVIL_CONSTRUCTORS(TimedTextSource);
};

}  // namespace android

#endif  // TIMED_TEXT_SOURCE_H_
