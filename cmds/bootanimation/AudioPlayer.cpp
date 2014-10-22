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

#define LOG_NDEBUG 0
#define LOG_TAG "BootAnim_AudioPlayer"

#include "AudioPlayer.h"

#include <androidfw/ZipFileRO.h>
#include <tinyalsa/asoundlib.h>
#include <utils/Log.h>
#include <utils/String8.h>

#define ID_RIFF 0x46464952
#define ID_WAVE 0x45564157
#define ID_FMT  0x20746d66
#define ID_DATA 0x61746164

// Maximum line length for audio_conf.txt
// We only accept lines less than this length to avoid overflows using sscanf()
#define MAX_LINE_LENGTH 1024

struct riff_wave_header {
    uint32_t riff_id;
    uint32_t riff_sz;
    uint32_t wave_id;
};

struct chunk_header {
    uint32_t id;
    uint32_t sz;
};

struct chunk_fmt {
    uint16_t audio_format;
    uint16_t num_channels;
    uint32_t sample_rate;
    uint32_t byte_rate;
    uint16_t block_align;
    uint16_t bits_per_sample;
};


namespace android {

AudioPlayer::AudioPlayer()
    :   mCard(-1),
        mDevice(-1),
        mPeriodSize(0),
        mPeriodCount(0),
        mCurrentFile(NULL)
{
}

AudioPlayer::~AudioPlayer() {
}

static bool setMixerValue(struct mixer* mixer, const char* name, const char* values)
{
    if (!mixer) {
        ALOGE("no mixer in setMixerValue");
        return false;
    }
    struct mixer_ctl *ctl = mixer_get_ctl_by_name(mixer, name);
    if (!ctl) {
        ALOGE("mixer_get_ctl_by_name failed for %s", name);
        return false;
    }

    enum mixer_ctl_type type = mixer_ctl_get_type(ctl);
    int numValues = mixer_ctl_get_num_values(ctl);
    int intValue;
    char stringValue[MAX_LINE_LENGTH];

    for (int i = 0; i < numValues && values; i++) {
        // strip leading space
        while (*values == ' ') values++;
        if (*values == 0) break;

        switch (type) {
            case MIXER_CTL_TYPE_BOOL:
            case MIXER_CTL_TYPE_INT:
                if (sscanf(values, "%d", &intValue) == 1) {
                    if (mixer_ctl_set_value(ctl, i, intValue) != 0) {
                        ALOGE("mixer_ctl_set_value failed for %s %d", name, intValue);
                    }
                } else {
                    ALOGE("Could not parse %s as int for %d", intValue, name);
                }
                break;
            case MIXER_CTL_TYPE_ENUM:
                if (sscanf(values, "%s", stringValue) == 1) {
                    if (mixer_ctl_set_enum_by_string(ctl, stringValue) != 0) {
                        ALOGE("mixer_ctl_set_enum_by_string failed for %s %%s", name, stringValue);
                    }
                } else {
                    ALOGE("Could not parse %s as enum for %d", stringValue, name);
                }
                break;
            default:
                ALOGE("unsupported mixer type %d for %s", type, name);
                break;
        }

        values = strchr(values, ' ');
    }

    return true;
}


/*
 * Parse the audio configuration file.
 * The file is named audio_conf.txt and must begin with the following header:
 *
 * card=<ALSA card number>
 * device=<ALSA device number>
 * period_size=<period size>
 * period_count=<period count>
 *
 * This header is followed by zero or more mixer settings, each with the format:
 * mixer "<name>" = <value list>
 * Since mixer names can contain spaces, the name must be enclosed in double quotes.
 * The values in the value list can be integers, booleans (represented by 0 or 1)
 * or strings for enum values.
 */
bool AudioPlayer::init(const char* config)
{
    int tempInt;
    struct mixer* mixer = NULL;
    char    name[MAX_LINE_LENGTH];

    for (;;) {
        const char* endl = strstr(config, "\n");
        if (!endl) break;
        String8 line(config, endl - config);
        if (line.length() >= MAX_LINE_LENGTH) {
            ALOGE("Line too long in audio_conf.txt");
            return false;
        }
        const char* l = line.string();

        if (sscanf(l, "card=%d", &tempInt) == 1) {
            ALOGD("card=%d", tempInt);
            mCard = tempInt;

            mixer = mixer_open(mCard);
            if (!mixer) {
                ALOGE("could not open mixer for card %d", mCard);
                return false;
            }
        } else if (sscanf(l, "device=%d", &tempInt) == 1) {
            ALOGD("device=%d", tempInt);
            mDevice = tempInt;
        } else if (sscanf(l, "period_size=%d", &tempInt) == 1) {
            ALOGD("period_size=%d", tempInt);
            mPeriodSize = tempInt;
        } else if (sscanf(l, "period_count=%d", &tempInt) == 1) {
            ALOGD("period_count=%d", tempInt);
            mPeriodCount = tempInt;
        } else if (sscanf(l, "mixer \"%[0-9a-zA-Z _]s\"", name) == 1) {
            const char* values = strchr(l, '=');
            if (values) {
                values++;   // skip '='
                ALOGD("name: \"%s\" = %s", name, values);
                setMixerValue(mixer, name, values);
            } else {
                ALOGE("values missing for name: \"%s\"", name);
            }
        }
        config = ++endl;
    }

    mixer_close(mixer);

    if (mCard >= 0 && mDevice >= 0) {
        return true;
    }

    return false;
}

void AudioPlayer::playFile(struct FileMap* fileMap) {
    // stop any currently playing sound
    requestExitAndWait();

    mCurrentFile = fileMap;
    run("bootanim audio", PRIORITY_URGENT_AUDIO);
}

bool AudioPlayer::threadLoop()
{
    struct pcm_config config;
    struct pcm *pcm = NULL;
    bool moreChunks = true;
    const struct chunk_fmt* chunkFmt = NULL;
    void* buffer = NULL;
    int bufferSize;
    const uint8_t* wavData;
    size_t wavLength;
    const struct riff_wave_header* wavHeader;

    if (mCurrentFile == NULL) {
        ALOGE("mCurrentFile is NULL");
        return false;
     }

    wavData = (const uint8_t *)mCurrentFile->getDataPtr();
    if (!wavData) {
        ALOGE("Could not access WAV file data");
        goto exit;
    }
    wavLength = mCurrentFile->getDataLength();

    wavHeader = (const struct riff_wave_header *)wavData;
    if (wavLength < sizeof(*wavHeader) || (wavHeader->riff_id != ID_RIFF) ||
        (wavHeader->wave_id != ID_WAVE)) {
        ALOGE("Error: audio file is not a riff/wave file\n");
        goto exit;
    }
    wavData += sizeof(*wavHeader);
    wavLength -= sizeof(*wavHeader);

    do {
        const struct chunk_header* chunkHeader = (const struct chunk_header*)wavData;
        if (wavLength < sizeof(*chunkHeader)) {
            ALOGE("EOF reading chunk headers");
            goto exit;
        }

        wavData += sizeof(*chunkHeader);
        wavLength -=  sizeof(*chunkHeader);

        switch (chunkHeader->id) {
            case ID_FMT:
                chunkFmt = (const struct chunk_fmt *)wavData;
                wavData += chunkHeader->sz;
                wavLength -= chunkHeader->sz;
                break;
            case ID_DATA:
                /* Stop looking for chunks */
                moreChunks = 0;
                break;
            default:
                /* Unknown chunk, skip bytes */
                wavData += chunkHeader->sz;
                wavLength -= chunkHeader->sz;
        }
    } while (moreChunks);

    if (!chunkFmt) {
        ALOGE("format not found in WAV file");
        goto exit;
    }


    memset(&config, 0, sizeof(config));
    config.channels = chunkFmt->num_channels;
    config.rate = chunkFmt->sample_rate;
    config.period_size = mPeriodSize;
    config.period_count = mPeriodCount;
    config.start_threshold = mPeriodSize / 4;
    config.stop_threshold = INT_MAX;
    config.avail_min = config.start_threshold;
    if (chunkFmt->bits_per_sample != 16) {
        ALOGE("only 16 bit WAV files are supported");
        goto exit;
    }
    config.format = PCM_FORMAT_S16_LE;

    pcm = pcm_open(mCard, mDevice, PCM_OUT, &config);
    if (!pcm || !pcm_is_ready(pcm)) {
        ALOGE("Unable to open PCM device (%s)\n", pcm_get_error(pcm));
        goto exit;
    }

    bufferSize = pcm_frames_to_bytes(pcm, pcm_get_buffer_size(pcm));

    while (wavLength > 0) {
        if (exitPending()) goto exit;
        size_t count = bufferSize;
        if (count > wavLength)
            count = wavLength;

        if (pcm_write(pcm, wavData, count)) {
            ALOGE("pcm_write failed (%s)", pcm_get_error(pcm));
            goto exit;
        }
        wavData += count;
        wavLength -= count;
    }

exit:
    if (pcm)
        pcm_close(pcm);
    mCurrentFile->release();
    mCurrentFile = NULL;
    return false;
}

} // namespace android
