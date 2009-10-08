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

#include <arpa/inet.h>

#include <ctype.h>
#include <pthread.h>

#include <media/stagefright/MPEG4Writer.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/Utils.h>

namespace android {

class MPEG4Writer::Track {
public:
    Track(MPEG4Writer *owner, const sp<MediaSource> &source);
    ~Track();

    status_t start();
    void stop();
    bool reachedEOS();

    int64_t getDuration() const;
    void writeTrackHeader(int32_t trackID);

private:
    MPEG4Writer *mOwner;
    sp<MetaData> mMeta;
    sp<MediaSource> mSource;
    volatile bool mDone;

    pthread_t mThread;

    struct SampleInfo {
        size_t size;
        off_t offset;
        int64_t timestamp;
    };
    List<SampleInfo> mSampleInfos;

    void *mCodecSpecificData;
    size_t mCodecSpecificDataSize;

    bool mReachedEOS;

    static void *ThreadWrapper(void *me);
    void threadEntry();

    Track(const Track &);
    Track &operator=(const Track &);
};

MPEG4Writer::MPEG4Writer(const char *filename)
    : mFile(fopen(filename, "wb")),
      mOffset(0),
      mMdatOffset(0) {
    CHECK(mFile != NULL);
}

MPEG4Writer::~MPEG4Writer() {
    stop();

    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        delete *it;
    }
    mTracks.clear();
}

void MPEG4Writer::addSource(const sp<MediaSource> &source) {
    Track *track = new Track(this, source);
    mTracks.push_back(track);
}

status_t MPEG4Writer::start() {
    if (mFile == NULL) {
        return UNKNOWN_ERROR;
    }

    beginBox("ftyp");
      writeFourcc("isom");
      writeInt32(0);
      writeFourcc("isom");
    endBox();

    mMdatOffset = mOffset;
    write("\x00\x00\x00\x01mdat????????", 16);

    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        status_t err = (*it)->start();

        if (err != OK) {
            for (List<Track *>::iterator it2 = mTracks.begin();
                 it2 != it; ++it2) {
                (*it2)->stop();
            }

            return err;
        }
    }

    return OK;
}

void MPEG4Writer::stop() {
    if (mFile == NULL) {
        return;
    }

    int64_t max_duration = 0;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        (*it)->stop();

        int64_t duration = (*it)->getDuration();
        if (duration > max_duration) {
            max_duration = duration;
        }
    }

    // Fix up the size of the 'mdat' chunk.
    fseek(mFile, mMdatOffset + 8, SEEK_SET);
    int64_t size = mOffset - mMdatOffset;
    size = hton64(size);
    fwrite(&size, 1, 8, mFile);
    fseek(mFile, mOffset, SEEK_SET);

    time_t now = time(NULL);

    beginBox("moov");

      beginBox("mvhd");
        writeInt32(0);             // version=0, flags=0
        writeInt32(now);           // creation time
        writeInt32(now);           // modification time
        writeInt32(1000);          // timescale
        writeInt32(max_duration);
        writeInt32(0x10000);       // rate
        writeInt16(0x100);         // volume
        writeInt16(0);             // reserved
        writeInt32(0);             // reserved
        writeInt32(0);             // reserved
        writeInt32(0x10000);       // matrix
        writeInt32(0);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0x10000);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0);
        writeInt32(0x40000000);
        writeInt32(0);             // predefined
        writeInt32(0);             // predefined
        writeInt32(0);             // predefined
        writeInt32(0);             // predefined
        writeInt32(0);             // predefined
        writeInt32(0);             // predefined
        writeInt32(mTracks.size() + 1);  // nextTrackID
      endBox();  // mvhd

      int32_t id = 1;
      for (List<Track *>::iterator it = mTracks.begin();
           it != mTracks.end(); ++it, ++id) {
          (*it)->writeTrackHeader(id);
      }
    endBox();  // moov

    CHECK(mBoxes.empty());

    fclose(mFile);
    mFile = NULL;
}

off_t MPEG4Writer::addSample(MediaBuffer *buffer) {
    Mutex::Autolock autoLock(mLock);

    off_t old_offset = mOffset;

    fwrite((const uint8_t *)buffer->data() + buffer->range_offset(),
           1, buffer->range_length(), mFile);

    mOffset += buffer->range_length();

    return old_offset;
}

void MPEG4Writer::beginBox(const char *fourcc) {
    CHECK_EQ(strlen(fourcc), 4);

    mBoxes.push_back(mOffset);

    writeInt32(0);
    writeFourcc(fourcc);
}

void MPEG4Writer::endBox() {
    CHECK(!mBoxes.empty());

    off_t offset = *--mBoxes.end();
    mBoxes.erase(--mBoxes.end());

    fseek(mFile, offset, SEEK_SET);
    writeInt32(mOffset - offset);
    mOffset -= 4;
    fseek(mFile, mOffset, SEEK_SET);
}

void MPEG4Writer::writeInt8(int8_t x) {
    fwrite(&x, 1, 1, mFile);
    ++mOffset;
}

void MPEG4Writer::writeInt16(int16_t x) {
    x = htons(x);
    fwrite(&x, 1, 2, mFile);
    mOffset += 2;
}

void MPEG4Writer::writeInt32(int32_t x) {
    x = htonl(x);
    fwrite(&x, 1, 4, mFile);
    mOffset += 4;
}

void MPEG4Writer::writeInt64(int64_t x) {
    x = hton64(x);
    fwrite(&x, 1, 8, mFile);
    mOffset += 8;
}

void MPEG4Writer::writeCString(const char *s) {
    size_t n = strlen(s);

    fwrite(s, 1, n + 1, mFile);
    mOffset += n + 1;
}

void MPEG4Writer::writeFourcc(const char *s) {
    CHECK_EQ(strlen(s), 4);
    fwrite(s, 1, 4, mFile);
    mOffset += 4;
}

void MPEG4Writer::write(const void *data, size_t size) {
    fwrite(data, 1, size, mFile);
    mOffset += size;
}

bool MPEG4Writer::reachedEOS() {
    bool allDone = true;
    for (List<Track *>::iterator it = mTracks.begin();
         it != mTracks.end(); ++it) {
        if (!(*it)->reachedEOS()) {
            allDone = false;
            break;
        }
    }

    return allDone;
}

////////////////////////////////////////////////////////////////////////////////

MPEG4Writer::Track::Track(
        MPEG4Writer *owner, const sp<MediaSource> &source)
    : mOwner(owner),
      mMeta(source->getFormat()),
      mSource(source),
      mDone(false),
      mCodecSpecificData(NULL),
      mCodecSpecificDataSize(0),
      mReachedEOS(false) {
}

MPEG4Writer::Track::~Track() {
    stop();

    if (mCodecSpecificData != NULL) {
        free(mCodecSpecificData);
        mCodecSpecificData = NULL;
    }
}

status_t MPEG4Writer::Track::start() {
    status_t err = mSource->start();

    if (err != OK) {
        mDone = mReachedEOS = true;
        return err;
    }

    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_JOINABLE);

    mDone = false;
    mReachedEOS = false;

    pthread_create(&mThread, &attr, ThreadWrapper, this);
    pthread_attr_destroy(&attr);

    return OK;
}

void MPEG4Writer::Track::stop() {
    if (mDone) {
        return;
    }

    mDone = true;

    void *dummy;
    pthread_join(mThread, &dummy);

    mSource->stop();
}

bool MPEG4Writer::Track::reachedEOS() {
    return mReachedEOS;
}

// static
void *MPEG4Writer::Track::ThreadWrapper(void *me) {
    Track *track = static_cast<Track *>(me);

    track->threadEntry();

    return NULL;
}

void MPEG4Writer::Track::threadEntry() {
    bool is_mpeg4 = false;
    sp<MetaData> meta = mSource->getFormat();
    const char *mime;
    meta->findCString(kKeyMIMEType, &mime);
    is_mpeg4 = !strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_MPEG4);

    MediaBuffer *buffer;
    while (!mDone && mSource->read(&buffer) == OK) {
        if (buffer->range_length() == 0) {
            buffer->release();
            buffer = NULL;

            continue;
        }

        if (mCodecSpecificData == NULL && is_mpeg4) {
            const uint8_t *data =
                (const uint8_t *)buffer->data() + buffer->range_offset();

            const size_t size = buffer->range_length();

            size_t offset = 0;
            while (offset + 3 < size) {
                if (data[offset] == 0x00 && data[offset + 1] == 0x00
                    && data[offset + 2] == 0x01 && data[offset + 3] == 0xb6) {
                    break;
                }

                ++offset;
            }

            // CHECK(offset + 3 < size);
            if (offset + 3 >= size) {
                // XXX assume the entire first chunk of data is the codec specific
                // data.
                offset = size;
            }

            mCodecSpecificDataSize = offset;
            mCodecSpecificData = malloc(offset);
            memcpy(mCodecSpecificData, data, offset);

            buffer->set_range(buffer->range_offset() + offset, size - offset);
        }

        off_t offset = mOwner->addSample(buffer);

        SampleInfo info;
        info.size = buffer->range_length();
        info.offset = offset;

        int64_t timestampUs;
        CHECK(buffer->meta_data()->findInt64(kKeyTime, &timestampUs));

        // Our timestamp is in ms.
        info.timestamp = (timestampUs + 500) / 1000;

        mSampleInfos.push_back(info);

        buffer->release();
        buffer = NULL;
    }

    mReachedEOS = true;
}

int64_t MPEG4Writer::Track::getDuration() const {
    return 10000;  // XXX
}

void MPEG4Writer::Track::writeTrackHeader(int32_t trackID) {
    const char *mime;
    bool success = mMeta->findCString(kKeyMIMEType, &mime);
    CHECK(success);

    bool is_audio = !strncasecmp(mime, "audio/", 6);

    time_t now = time(NULL);

    mOwner->beginBox("trak");

      mOwner->beginBox("tkhd");
        mOwner->writeInt32(0);             // version=0, flags=0
        mOwner->writeInt32(now);           // creation time
        mOwner->writeInt32(now);           // modification time
        mOwner->writeInt32(trackID);
        mOwner->writeInt32(0);             // reserved
        mOwner->writeInt32(getDuration());
        mOwner->writeInt32(0);             // reserved
        mOwner->writeInt32(0);             // reserved
        mOwner->writeInt16(0);             // layer
        mOwner->writeInt16(0);             // alternate group
        mOwner->writeInt16(is_audio ? 0x100 : 0);  // volume
        mOwner->writeInt16(0);             // reserved

        mOwner->writeInt32(0x10000);       // matrix
        mOwner->writeInt32(0);
        mOwner->writeInt32(0);
        mOwner->writeInt32(0);
        mOwner->writeInt32(0x10000);
        mOwner->writeInt32(0);
        mOwner->writeInt32(0);
        mOwner->writeInt32(0);
        mOwner->writeInt32(0x40000000);

        if (is_audio) {
            mOwner->writeInt32(0);
            mOwner->writeInt32(0);
        } else {
            int32_t width, height;
            bool success = mMeta->findInt32(kKeyWidth, &width);
            success = success && mMeta->findInt32(kKeyHeight, &height);
            CHECK(success);

            mOwner->writeInt32(width);
            mOwner->writeInt32(height);
        }
      mOwner->endBox();  // tkhd

      mOwner->beginBox("mdia");

        mOwner->beginBox("mdhd");
          mOwner->writeInt32(0);             // version=0, flags=0
          mOwner->writeInt32(now);           // creation time
          mOwner->writeInt32(now);           // modification time
          mOwner->writeInt32(1000);          // timescale
          mOwner->writeInt32(getDuration());
          mOwner->writeInt16(0);             // language code XXX
          mOwner->writeInt16(0);             // predefined
        mOwner->endBox();

        mOwner->beginBox("hdlr");
          mOwner->writeInt32(0);             // version=0, flags=0
          mOwner->writeInt32(0);             // predefined
          mOwner->writeFourcc(is_audio ? "soun" : "vide");
          mOwner->writeInt32(0);             // reserved
          mOwner->writeInt32(0);             // reserved
          mOwner->writeInt32(0);             // reserved
          mOwner->writeCString("");          // name
        mOwner->endBox();

        mOwner->beginBox("minf");

          mOwner->beginBox("dinf");
            mOwner->beginBox("dref");
              mOwner->writeInt32(0);  // version=0, flags=0
              mOwner->writeInt32(1);
              mOwner->beginBox("url ");
                mOwner->writeInt32(1);  // version=0, flags=1
              mOwner->endBox();  // url
            mOwner->endBox();  // dref
          mOwner->endBox();  // dinf

          if (is_audio) {
              mOwner->beginBox("smhd");
              mOwner->writeInt32(0);           // version=0, flags=0
              mOwner->writeInt16(0);           // balance
              mOwner->writeInt16(0);           // reserved
              mOwner->endBox();
          } else {
              mOwner->beginBox("vmhd");
              mOwner->writeInt32(0x00000001);  // version=0, flags=1
              mOwner->writeInt16(0);           // graphics mode
              mOwner->writeInt16(0);           // opcolor
              mOwner->writeInt16(0);
              mOwner->writeInt16(0);
              mOwner->endBox();
          }
        mOwner->endBox();  // minf

        mOwner->beginBox("stbl");

          mOwner->beginBox("stsd");
            mOwner->writeInt32(0);               // version=0, flags=0
            mOwner->writeInt32(1);               // entry count
            if (is_audio) {
                const char *fourcc = NULL;
                if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_NB, mime)) {
                    fourcc = "samr";
                } else if (!strcasecmp(MEDIA_MIMETYPE_AUDIO_AMR_WB, mime)) {
                    fourcc = "sawb";
                } else {
                    LOGE("Unknown mime type '%s'.", mime);
                    CHECK(!"should not be here, unknown mime type.");
                }

                mOwner->beginBox(fourcc);          // audio format
                  mOwner->writeInt32(0);           // reserved
                  mOwner->writeInt16(0);           // reserved
                  mOwner->writeInt16(0);           // data ref index
                  mOwner->writeInt32(0);           // reserved
                  mOwner->writeInt32(0);           // reserved
                  mOwner->writeInt16(2);           // channel count
                  mOwner->writeInt16(16);          // sample size
                  mOwner->writeInt16(0);           // predefined
                  mOwner->writeInt16(0);           // reserved

                  int32_t samplerate;
                  bool success = mMeta->findInt32(kKeySampleRate, &samplerate);
                  CHECK(success);

                  mOwner->writeInt32(samplerate << 16);
                mOwner->endBox();
            } else {
                if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime)) {
                    mOwner->beginBox("mp4v");
                } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_H263, mime)) {
                    mOwner->beginBox("s263");
                } else {
                    LOGE("Unknown mime type '%s'.", mime);
                    CHECK(!"should not be here, unknown mime type.");
                }

                  mOwner->writeInt32(0);           // reserved
                  mOwner->writeInt16(0);           // reserved
                  mOwner->writeInt16(0);           // data ref index
                  mOwner->writeInt16(0);           // predefined
                  mOwner->writeInt16(0);           // reserved
                  mOwner->writeInt32(0);           // predefined
                  mOwner->writeInt32(0);           // predefined
                  mOwner->writeInt32(0);           // predefined

                  int32_t width, height;
                  bool success = mMeta->findInt32(kKeyWidth, &width);
                  success = success && mMeta->findInt32(kKeyHeight, &height);
                  CHECK(success);

                  mOwner->writeInt16(width);
                  mOwner->writeInt16(height);
                  mOwner->writeInt32(0x480000);    // horiz resolution
                  mOwner->writeInt32(0x480000);    // vert resolution
                  mOwner->writeInt32(0);           // reserved
                  mOwner->writeInt16(1);           // frame count
                  mOwner->write("                                ", 32);
                  mOwner->writeInt16(0x18);        // depth
                  mOwner->writeInt16(-1);          // predefined

                  CHECK(23 + mCodecSpecificDataSize < 128);

                  if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_MPEG4, mime)) {
                      mOwner->beginBox("esds");

                        mOwner->writeInt32(0);           // version=0, flags=0

                        mOwner->writeInt8(0x03);  // ES_DescrTag
                        mOwner->writeInt8(23 + mCodecSpecificDataSize);
                        mOwner->writeInt16(0x0000);  // ES_ID
                        mOwner->writeInt8(0x1f);

                        mOwner->writeInt8(0x04);  // DecoderConfigDescrTag
                        mOwner->writeInt8(15 + mCodecSpecificDataSize);
                        mOwner->writeInt8(0x20);  // objectTypeIndication ISO/IEC 14492-2
                        mOwner->writeInt8(0x11);  // streamType VisualStream

                        static const uint8_t kData[] = {
                            0x01, 0x77, 0x00,
                            0x00, 0x03, 0xe8, 0x00,
                            0x00, 0x03, 0xe8, 0x00
                        };
                        mOwner->write(kData, sizeof(kData));
                        
                        mOwner->writeInt8(0x05);  // DecoderSpecificInfoTag

                        mOwner->writeInt8(mCodecSpecificDataSize);
                        mOwner->write(mCodecSpecificData, mCodecSpecificDataSize);

                        static const uint8_t kData2[] = {
                            0x06,  // SLConfigDescriptorTag
                            0x01,
                            0x02
                        };
                        mOwner->write(kData2, sizeof(kData2));

                      mOwner->endBox();  // esds
                  } else if (!strcasecmp(MEDIA_MIMETYPE_VIDEO_H263, mime)) {
                      mOwner->beginBox("d263");

                          mOwner->writeInt32(0);  // vendor
                          mOwner->writeInt8(0);   // decoder version
                          mOwner->writeInt8(10);  // level: 10
                          mOwner->writeInt8(0);   // profile: 0

                      mOwner->endBox();  // d263
                  }
                mOwner->endBox();  // mp4v or s263
            }
          mOwner->endBox();  // stsd

          mOwner->beginBox("stts");
            mOwner->writeInt32(0);  // version=0, flags=0
            mOwner->writeInt32(mSampleInfos.size() - 1);

            List<SampleInfo>::iterator it = mSampleInfos.begin();
            int64_t last = (*it).timestamp;
            ++it;
            while (it != mSampleInfos.end()) {
                mOwner->writeInt32(1);
                mOwner->writeInt32((*it).timestamp - last);

                last = (*it).timestamp;

                ++it;
            }
          mOwner->endBox();  // stts

          mOwner->beginBox("stsz");
            mOwner->writeInt32(0);  // version=0, flags=0
            mOwner->writeInt32(0);  // default sample size
            mOwner->writeInt32(mSampleInfos.size());
            for (List<SampleInfo>::iterator it = mSampleInfos.begin();
                 it != mSampleInfos.end(); ++it) {
                mOwner->writeInt32((*it).size);
            }
          mOwner->endBox();  // stsz

          mOwner->beginBox("stsc");
            mOwner->writeInt32(0);  // version=0, flags=0
            mOwner->writeInt32(mSampleInfos.size());
            int32_t n = 1;
            for (List<SampleInfo>::iterator it = mSampleInfos.begin();
                 it != mSampleInfos.end(); ++it, ++n) {
                mOwner->writeInt32(n);
                mOwner->writeInt32(1);
                mOwner->writeInt32(1);
            }
          mOwner->endBox();  // stsc

          mOwner->beginBox("co64");
            mOwner->writeInt32(0);  // version=0, flags=0
            mOwner->writeInt32(mSampleInfos.size());
            for (List<SampleInfo>::iterator it = mSampleInfos.begin();
                 it != mSampleInfos.end(); ++it, ++n) {
                mOwner->writeInt64((*it).offset);
            }
          mOwner->endBox();  // co64

        mOwner->endBox();  // stbl
      mOwner->endBox();  // mdia
    mOwner->endBox();  // trak
}

}  // namespace android
