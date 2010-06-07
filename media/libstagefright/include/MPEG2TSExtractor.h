#ifndef MPEG2_TS_EXTRACTOR_H_

#define MPEG2_TS_EXTRACTOR_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/MediaExtractor.h>
#include <utils/threads.h>
#include <utils/Vector.h>

namespace android {

struct AnotherPacketSource;
struct ATSParser;
struct DataSource;
struct MPEG2TSSource;
struct String8;

struct MPEG2TSExtractor : public MediaExtractor {
    MPEG2TSExtractor(const sp<DataSource> &source);

    virtual size_t countTracks();
    virtual sp<MediaSource> getTrack(size_t index);
    virtual sp<MetaData> getTrackMetaData(size_t index, uint32_t flags);

    virtual sp<MetaData> getMetaData();

    virtual uint32_t flags() const {
        return CAN_PAUSE;
    }

private:
    friend struct MPEG2TSSource;

    Mutex mLock;

    sp<DataSource> mDataSource;
    sp<ATSParser> mParser;

    Vector<sp<AnotherPacketSource> > mSourceImpls;

    off_t mOffset;

    void init();
    status_t feedMore();

    DISALLOW_EVIL_CONSTRUCTORS(MPEG2TSExtractor);
};

bool SniffMPEG2TS(
        const sp<DataSource> &source, String8 *mimeType, float *confidence);

}  // namespace android

#endif  // MPEG2_TS_EXTRACTOR_H_
