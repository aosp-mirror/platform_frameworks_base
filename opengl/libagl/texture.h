/* libs/opengles/texture.h
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#ifndef ANDROID_OPENGLES_TEXTURE_H
#define ANDROID_OPENGLES_TEXTURE_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>

#include <private/pixelflinger/ggl_context.h>

#include <GLES/gl.h>

#include "context.h"

namespace android {

void ogles_init_texture(ogles_context_t* c);
void ogles_uninit_texture(ogles_context_t* c);
void ogles_validate_texture(ogles_context_t* c);
void ogles_lock_textures(ogles_context_t* c);
void ogles_unlock_textures(ogles_context_t* c);

}; // namespace android

#endif // ANDROID_OPENGLES_TEXTURE_H
