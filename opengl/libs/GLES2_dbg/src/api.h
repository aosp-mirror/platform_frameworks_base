/*
 ** Copyright 2011, The Android Open Source Project
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

#define EXTEND_Debug_glCopyTexImage2D \
    void * pixels = malloc(width * height * 4); \
    getGLTraceThreadSpecific()->gl.glReadPixels(x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels); \
    DbgContext * const dbg = getDbgContextThreadSpecific(); \
    const unsigned compressed = dbg->Compress(pixels, width * height * 4); \
    msg.set_data(dbg->lzf_buf, compressed); \
    free(pixels);

#define EXTEND_Debug_glCopyTexSubImage2D EXTEND_Debug_glCopyTexImage2D

#define EXTEND_Debug_glShaderSource \
    std::string * const data = msg.mutable_data(); \
    for (unsigned i = 0; i < count; i++) \
        if (!length || length[i] < 0) \
            data->append(string[i]); \
        else \
            data->append(string[i], length[i]);
