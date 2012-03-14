#include "SineSource.h"

#include <binder/ProcessState.h>
#include <media/mediarecorder.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/AMRWriter.h>
#include <media/stagefright/AudioPlayer.h>
#include <media/stagefright/AudioSource.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>

#include <system/audio.h>

using namespace android;

int main() {
    // We only have an AMR-WB encoder on sholes...
    static bool outputWBAMR = false;
    static const int32_t kSampleRate = outputWBAMR ? 16000 : 8000;
    static const int32_t kNumChannels = 1;

    android::ProcessState::self()->startThreadPool();

    OMXClient client;
    CHECK_EQ(client.connect(), (status_t)OK);

#if 0
    sp<MediaSource> source = new SineSource(kSampleRate, kNumChannels);
#else
    sp<MediaSource> source = new AudioSource(
            AUDIO_SOURCE_DEFAULT,
            kSampleRate,
            audio_channel_in_mask_from_count(kNumChannels));
#endif

    sp<MetaData> meta = new MetaData;

    meta->setCString(
            kKeyMIMEType,
            outputWBAMR ? MEDIA_MIMETYPE_AUDIO_AMR_WB
                        : MEDIA_MIMETYPE_AUDIO_AMR_NB);

    meta->setInt32(kKeyChannelCount, kNumChannels);
    meta->setInt32(kKeySampleRate, kSampleRate);

    int32_t maxInputSize;
    if (source->getFormat()->findInt32(kKeyMaxInputSize, &maxInputSize)) {
        meta->setInt32(kKeyMaxInputSize, maxInputSize);
    }

    sp<MediaSource> encoder = OMXCodec::Create(
            client.interface(),
            meta, true /* createEncoder */,
            source);

#if 1
    sp<AMRWriter> writer = new AMRWriter("/sdcard/out.amr");
    writer->addSource(encoder);
    writer->start();
    sleep(10);
    writer->stop();
#else
    sp<MediaSource> decoder = OMXCodec::Create(
            client.interface(),
            meta, false /* createEncoder */,
            encoder);

#if 0
    AudioPlayer *player = new AudioPlayer(NULL);
    player->setSource(decoder);

    player->start();

    sleep(10);

    player->stop();

    delete player;
    player = NULL;
#elif 0
    CHECK_EQ(decoder->start(), (status_t)OK);

    MediaBuffer *buffer;
    while (decoder->read(&buffer) == OK) {
        // do something with buffer

        putchar('.');
        fflush(stdout);

        buffer->release();
        buffer = NULL;
    }

    CHECK_EQ(decoder->stop(), (status_t)OK);
#endif
#endif

    return 0;
}
