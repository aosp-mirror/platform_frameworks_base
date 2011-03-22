/*
 * Copyrightm (C) 2010 The Android Open Source Project
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

#include <strings.h>

#include "AudioCodec.h"

extern AudioCodec *newAlawCodec();
extern AudioCodec *newUlawCodec();
extern AudioCodec *newGsmCodec();
extern AudioCodec *newAmrCodec();
extern AudioCodec *newGsmEfrCodec();

struct AudioCodecType {
    const char *name;
    AudioCodec *(*create)();
} gAudioCodecTypes[] = {
    {"PCMA", newAlawCodec},
    {"PCMU", newUlawCodec},
    {"GSM", newGsmCodec},
    {"AMR", newAmrCodec},
    {"GSM-EFR", newGsmEfrCodec},
    {NULL, NULL},
};

AudioCodec *newAudioCodec(const char *codecName)
{
    AudioCodecType *type = gAudioCodecTypes;
    while (type->name != NULL) {
        if (strcasecmp(codecName, type->name) == 0) {
            AudioCodec *codec = type->create();
            codec->name = type->name;
            return codec;
        }
        ++type;
    }
    return NULL;
}
