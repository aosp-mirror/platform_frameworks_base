/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_RS_FILE_A3D_DECLS_H
#define ANDROID_RS_FILE_A3D_DECLS_H


#define A3D_MAGIC_KEY "Android3D_ff"

namespace android {
namespace renderscript {

    enum A3DChunkType {
        CHUNK_EMPTY,

        CHUNK_ELEMENT,
        CHUNK_ELEMENT_SOURCE,
        CHUNK_VERTICIES,
        CHUNK_MESH,
        CHUNK_PRIMITIVE,

        CHUNK_LAST
    };


}
}
#endif //ANDROID_RS_FILE_A3D_H



