/* libs/opengles/state.h
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

#ifndef ANDROID_OPENGLES_STATE_H
#define ANDROID_OPENGLES_STATE_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>

#include <private/pixelflinger/ggl_context.h>

#include <GLES/gl.h>

#include <stdio.h>

namespace android {

ogles_context_t *ogles_init(size_t extra);
void ogles_uninit(ogles_context_t* c);
void _ogles_error(ogles_context_t* c, GLenum error);

#ifndef TRACE_GL_ERRORS
#define TRACE_GL_ERRORS 0
#endif

#if TRACE_GL_ERRORS
#define ogles_error(c, error) \
do { \
  printf("ogles_error at file %s line %d\n", __FILE__, __LINE__); \
  _ogles_error(c, error); \
} while (0)
#else /* !TRACE_GL_ERRORS */
#define ogles_error(c, error) _ogles_error((c), (error))
#endif

}; // namespace android

#endif // ANDROID_OPENGLES_STATE_H

