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

#ifndef ANDROID_EFFECTBASSBOOSTAPI_H_
#define ANDROID_EFFECTBASSBOOSTAPI_H_

#include <media/EffectApi.h>

#if __cplusplus
extern "C" {
#endif

#ifndef OPENSL_ES_H_
static const effect_uuid_t SL_IID_BASSBOOST_ = { 0x0634f220, 0xddd4, 0x11db, 0xa0fc, { 0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b } };
const effect_uuid_t * const SL_IID_BASSBOOST = &SL_IID_BASSBOOST_;
#endif //OPENSL_ES_H_

/* enumerated parameter settings for BassBoost effect */
typedef enum
{
    BASSBOOST_PARAM_STRENGTH_SUPPORTED,
    BASSBOOST_PARAM_STRENGTH
} t_bassboost_params;

#if __cplusplus
}  // extern "C"
#endif


#endif /*ANDROID_EFFECTBASSBOOSTAPI_H_*/
