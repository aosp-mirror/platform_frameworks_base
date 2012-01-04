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

#include <MimeTypeUtil.h>
#include <utils/Log.h>

namespace android {

#undef LOG_TAG
#define LOG_TAG "MimeTypeUtil"

#ifdef DRM_OMA_FL_ENGINE_DEBUG
#define LOG_NDEBUG 0
#define LOG_DEBUG(...) ALOGD(__VA_ARGS__)
#else
#define LOG_DEBUG(...)
#endif

enum {
    MIMETYPE_AUDIO       = 0,
    MIMETYPE_APPLICATION = 1,
    MIMETYPE_IMAGE       = 2,
    MIMETYPE_VIDEO       = 3,
    MIMETYPE_LAST        = -1,
};

struct MimeGroup{
    int         type;     // Audio, video,.. use the enum values
    const char* pGroup;   // "audio/", "video/",.. should contain the last "/"
    int         size;     // Number of bytes. e.g. "audio/" = 6 bytes
};

struct MimeTypeList{
    int         type;
    const char* pMimeExt;  // Everything after the '/' e.g. audio/x-mpeg -> "x-mpeg"
    int         size;      // Number of bytes. e.g. "x-mpeg" = 6 bytes
    const char* pMimeType; // Mimetype that should be returned
};


// Known mimetypes by android
static const char mime_type_audio_mpeg[]  = "audio/mpeg";
static const char mime_type_audio_3gpp[]  = "audio/3gpp";
static const char mime_type_audio_amr[]   = "audio/amr-wb";
static const char mime_type_audio_aac[]   = "audio/mp4a-latm";
static const char mime_type_audio_wav[]   = "audio/wav";

static const char mime_type_video_mpeg4[] = "video/mpeg4";
static const char mime_type_video_3gpp[]  = "video/3gpp";

// Known mimetype groups
static const char mime_group_audio[]       = "audio/";
static const char mime_group_application[] = "application/";
static const char mime_group_image[]       = "image/";
static const char mime_group_video[]       = "video/";
static const char mime_type_unsupported[]  = "unsupported/drm.mimetype";

static struct MimeGroup mimeGroup[] = {
    {MIMETYPE_AUDIO,       mime_group_audio,        sizeof(mime_group_audio)-1},
    {MIMETYPE_APPLICATION, mime_group_application,  sizeof(mime_group_application)-1},
    {MIMETYPE_IMAGE,       mime_group_image,        sizeof(mime_group_image)-1},
    {MIMETYPE_VIDEO,       mime_group_video,        sizeof(mime_group_video)-1},
    {MIMETYPE_LAST,        NULL,                    0} // Must be last entry
};

// List of all mimetypes that should be converted.
static struct MimeTypeList mimeTypeList[] = {
    // Mp3 mime types
    {MIMETYPE_AUDIO, "mp3",          sizeof("mp3")-1,         mime_type_audio_mpeg},
    {MIMETYPE_AUDIO, "x-mpeg",       sizeof("x-mpeg")-1,      mime_type_audio_mpeg},
    {MIMETYPE_AUDIO, "x-mp3",        sizeof("x-mp3")-1,       mime_type_audio_mpeg},
    {MIMETYPE_AUDIO, "mpg",          sizeof("mpg")-1,         mime_type_audio_mpeg},
    {MIMETYPE_AUDIO, "mpg3",         sizeof("mpg")-1,         mime_type_audio_mpeg},
    {MIMETYPE_AUDIO, "x-mpg",        sizeof("x-mpg")-1,       mime_type_audio_mpeg},
    {MIMETYPE_AUDIO, "x-mpegaudio",  sizeof("x-mpegaudio")-1, mime_type_audio_mpeg},

    // 3gpp audio mime types
    {MIMETYPE_AUDIO, "3gp",          sizeof("3gp")-1,         mime_type_audio_3gpp},

    // Amr audio mime types
    {MIMETYPE_AUDIO, "amr",          sizeof("amr")-1,         mime_type_audio_amr},

    // Aac audio mime types
    {MIMETYPE_AUDIO, "aac",          sizeof("aac")-1,         mime_type_audio_aac},

    // Wav audio mime types
    {MIMETYPE_AUDIO, "x-wav",        sizeof("x-wav")-1,       mime_type_audio_wav},

    // Mpeg4 video mime types
    {MIMETYPE_VIDEO, "mpg4",         sizeof("mpg4")-1,        mime_type_video_mpeg4},
    {MIMETYPE_VIDEO, "mp4v-es",      sizeof("mp4v-es")-1,     mime_type_video_mpeg4},

    // 3gpp video mime types
    {MIMETYPE_VIDEO, "3gp",          sizeof("3gp")-1,         mime_type_video_3gpp},

    // Must be last entry
    {MIMETYPE_LAST,  NULL,           0,                       NULL}
};

/**
 * May convert the mimetype if there is a well known
 * replacement mimetype otherwise the original mimetype
 * is returned.
 *
 * If the mimetype is of unsupported group i.e. application/*
 * then "unsupported/drm.mimetype" will be returned.
 *
 * @param mimeType - mimetype in lower case to convert.
 *
 * @return mimetype or "unsupported/drm.mimetype".
 */
String8 MimeTypeUtil::convertMimeType(String8& mimeType) {
    String8 result = mimeType;
    const char* pMimeType;
    struct MimeGroup* pGroup;
    struct MimeTypeList* pMimeItem;
    int len;
    pMimeType = mimeType.string();
    if (NULL != pMimeType) {
        if ((0 == strncmp(pMimeType, mime_group_audio, (sizeof mime_group_audio) - 1)) ||
            (0 == strncmp(pMimeType, mime_group_video, (sizeof mime_group_video) - 1))) {
            /* Check which group the mimetype is */
            pGroup = mimeGroup;
            while (MIMETYPE_LAST != pGroup->type) {
                if (0 == strncmp(pMimeType, pGroup->pGroup, pGroup->size)) {
                    break;
                }
                pGroup++;
            }

            /* Go through the mimetype list. Only check items of the correct group */
            if (MIMETYPE_LAST != pGroup->type) {
                pMimeItem = mimeTypeList;
                len = strlen (pMimeType+pGroup->size);
                while (MIMETYPE_LAST != pMimeItem->type) {
                    if ((pGroup->type == pMimeItem->type) &&
                        (len == pMimeItem->size) &&
                        (0 == strcmp(pMimeType+pGroup->size, pMimeItem->pMimeExt))) {
                        result = String8(pMimeItem->pMimeType);
                        break;
                    }
                    pMimeItem++;
                }
            }
        } else {
            result = String8(mime_type_unsupported);
        }
        LOG_DEBUG("convertMimeType got mimetype %s, converted into mimetype %s",
                pMimeType, result.string());
    }
    return result;
}
};
