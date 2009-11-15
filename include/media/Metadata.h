/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_MEDIA_METADATA_H__
#define ANDROID_MEDIA_METADATA_H__

#include <sys/types.h>
#include <utils/Errors.h>
#include <utils/RefBase.h>
#include <utils/SortedVector.h>

namespace android {
class Parcel;

namespace media {

// Metadata is a class to build/serialize a set of metadata in a Parcel.
//
// This class should be kept in sync with android/media/Metadata.java.
// It provides all the metadata ids available and methods to build the
// header, add records and adjust the set size header field.
//
// Typical Usage:
// ==============
//  Parcel p;
//  media::Metadata data(&p);
//
//  data.appendHeader();
//  data.appendBool(Metadata::kPauseAvailable, true);
//   ... more append ...
//  data.updateLength();
//

class Metadata {
  public:
    typedef int32_t Type;
    typedef SortedVector<Type> Filter;

    static const Type kAny = 0;

    // Keep in sync with android/media/Metadata.java
    static const Type kTitle = 1;           // String
    static const Type kComment = 2;         // String
    static const Type kCopyright = 3;       // String
    static const Type kAlbum = 4;           // String
    static const Type kArtist = 5;          // String
    static const Type kAuthor = 6;          // String
    static const Type kComposer = 7;        // String
    static const Type kGenre = 8;           // String
    static const Type kDate = 9;            // Date
    static const Type kDuration = 10;       // Integer(millisec)
    static const Type kCdTrackNum = 11;     // Integer 1-based
    static const Type kCdTrackMax = 12;     // Integer
    static const Type kRating = 13;         // String
    static const Type kAlbumArt = 14;       // byte[]
    static const Type kVideoFrame = 15;     // Bitmap
    static const Type kCaption = 16;        // TimedText

    static const Type kBitRate = 17;       // Integer, Aggregate rate of
    // all the streams in bps.

    static const Type kAudioBitRate = 18; // Integer, bps
    static const Type kVideoBitRate = 19; // Integer, bps
    static const Type kAudioSampleRate = 20; // Integer, Hz
    static const Type kVideoframeRate = 21;  // Integer, Hz

    // See RFC2046 and RFC4281.
    static const Type kMimeType = 22;      // String
    static const Type kAudioCodec = 23;    // String
    static const Type kVideoCodec = 24;    // String

    static const Type kVideoHeight = 25;   // Integer
    static const Type kVideoWidth = 26;    // Integer
    static const Type kNumTracks = 27;     // Integer
    static const Type kDrmCrippled = 28;   // Boolean

    // Playback capabilities.
    static const Type kPauseAvailable = 29;        // Boolean
    static const Type kSeekBackwardAvailable = 30; // Boolean
    static const Type kSeekForwardAvailable = 31;  // Boolean

    // @param p[inout] The parcel to append the metadata records
    // to. The global metadata header should have been set already.
    explicit Metadata(Parcel *p);
    ~Metadata();

    // Rewind the underlying parcel, undoing all the changes.
    void resetParcel();

    // Append the size and 'META' marker.
    bool appendHeader();

    // Once all the records have been added, call this to update the
    // lenght field in the header.
    void updateLength();

    // append* are methods to append metadata.
    // @param key Is the metadata Id.
    // @param val Is the value of the metadata.
    // @return true if successful, false otherwise.
    // TODO: add more as needed to handle other types.
    bool appendBool(Type key, bool val);
    bool appendInt32(Type key, int32_t val);

  private:
    Metadata(const Metadata&);
    Metadata& operator=(const Metadata&);


    // Checks the key is valid and not already present.
    bool checkKey(Type key);

    Parcel *mData;
    size_t mBegin;
};

}  // namespace android::media
}  // namespace android

#endif  // ANDROID_MEDIA_METADATA_H__
