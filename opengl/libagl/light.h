/* libs/opengles/light.h
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

#ifndef ANDROID_OPENGLES_LIGHT_H
#define ANDROID_OPENGLES_LIGHT_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>


// Set to 1 for object-space lighting evaluation.
// There are still some bugs with object-space lighting,
// especially visible in the San Angeles demo.
#define OBJECT_SPACE_LIGHTING   0


namespace android {

namespace gl {
struct ogles_context_t;
};

void ogles_init_light(ogles_context_t* c);
void ogles_uninit_light(ogles_context_t* c);
void ogles_invalidate_lighting_mvui(ogles_context_t* c);

}; // namespace android

#endif // ANDROID_OPENGLES_LIGHT_H

