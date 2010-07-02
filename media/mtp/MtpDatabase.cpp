/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "MtpDatabase"

#include "MtpDebug.h"
#include "MtpDatabase.h"
#include "MtpTypes.h"
#include "mtp.h"

namespace android {

MtpDatabase::~MtpDatabase() {
}

uint32_t MtpDatabase::getTableForFile(MtpObjectFormat format) {
    switch (format) {
        case MTP_FORMAT_AIFF:
        case MTP_FORMAT_WAV:
        case MTP_FORMAT_MP3:
        case MTP_FORMAT_FLAC:
        case MTP_FORMAT_UNDEFINED_AUDIO:
        case MTP_FORMAT_WMA:
        case MTP_FORMAT_OGG:
        case MTP_FORMAT_AAC:
        case MTP_FORMAT_AUDIBLE:
            return kObjectHandleTableAudio;
        case MTP_FORMAT_AVI:
        case MTP_FORMAT_MPEG:
        case MTP_FORMAT_ASF:
        case MTP_FORMAT_UNDEFINED_VIDEO:
        case MTP_FORMAT_WMV:
        case MTP_FORMAT_MP4_CONTAINER:
        case MTP_FORMAT_MP2:
        case MTP_FORMAT_3GP_CONTAINER:
            return kObjectHandleTableVideo;
        case MTP_FORMAT_DEFINED:
        case MTP_FORMAT_EXIF_JPEG:
        case MTP_FORMAT_TIFF_EP:
        case MTP_FORMAT_FLASHPIX:
        case MTP_FORMAT_BMP:
        case MTP_FORMAT_CIFF:
        case MTP_FORMAT_GIF:
        case MTP_FORMAT_JFIF:
        case MTP_FORMAT_CD:
        case MTP_FORMAT_PICT:
        case MTP_FORMAT_PNG:
        case MTP_FORMAT_TIFF:
        case MTP_FORMAT_TIFF_IT:
        case MTP_FORMAT_JP2:
        case MTP_FORMAT_JPX:
        case MTP_FORMAT_WINDOWS_IMAGE_FORMAT:
            return kObjectHandleTableImage;
        case MTP_FORMAT_ABSTRACT_AUDIO_PLAYLIST:
        case MTP_FORMAT_ABSTRACT_AV_PLAYLIST:
        case MTP_FORMAT_ABSTRACT_VIDEO_PLAYLIST:
        case MTP_FORMAT_WPL_PLAYLIST:
        case MTP_FORMAT_M3U_PLAYLIST:
        case MTP_FORMAT_MPL_PLAYLIST:
        case MTP_FORMAT_ASX_PLAYLIST:
        case MTP_FORMAT_PLS_PLAYLIST:
            return kObjectHandleTablePlaylist;
        default:
            return kObjectHandleTableFile;
    }
}

}  // namespace android
