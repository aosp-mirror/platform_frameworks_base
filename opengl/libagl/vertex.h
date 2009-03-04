/* libs/opengles/vertex.h
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

#ifndef ANDROID_OPENGLES_VERTEX_H
#define ANDROID_OPENGLES_VERTEX_H

#include <stdint.h>
#include <stddef.h>
#include <sys/types.h>

namespace android {

namespace gl {
struct vertex_t;
struct ogles_context_t;
};

void ogles_init_vertex(ogles_context_t* c);
void ogles_uninit_vertex(ogles_context_t* c);

void ogles_vertex_perspective2D(ogles_context_t*, vertex_t*);

void ogles_vertex_perspective3D(ogles_context_t*, vertex_t*);
void ogles_vertex_perspective3DZ(ogles_context_t*, vertex_t*);
void ogles_vertex_clipAllPerspective3D(ogles_context_t*, vertex_t*);
void ogles_vertex_clipAllPerspective3DZ(ogles_context_t*, vertex_t*);


void ogles_vertex_project(ogles_context_t* c, vertex_t*);

}; // namespace android

#endif // ANDROID_OPENGLES_VERTEX_H

