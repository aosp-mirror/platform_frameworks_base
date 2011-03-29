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
    DbgContext * const dbg = getDbgContextThreadSpecific(); \
    GLint readFormat, readType; \
    dbg->hooks->gl.glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT, &readFormat); \
    dbg->hooks->gl.glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE, &readType); \
    unsigned readSize = GetBytesPerPixel(readFormat, readType) * width * height; \
    void * readData = dbg->GetReadPixelsBuffer(readSize); \
    dbg->hooks->gl.glReadPixels(x, y, width, height, readFormat, readType, readData); \
    dbg->CompressReadPixelBuffer(msg.mutable_data()); \
    msg.set_data_type(msg.ReferencedImage); \
    msg.set_pixel_format(readFormat); \
    msg.set_pixel_type(readType);

#define EXTEND_Debug_glCopyTexSubImage2D EXTEND_Debug_glCopyTexImage2D

#define EXTEND_Debug_glShaderSource \
    std::string * const data = msg.mutable_data(); \
    for (unsigned i = 0; i < count; i++) \
        if (!length || length[i] < 0) \
            data->append(string[i]); \
        else \
            data->append(string[i], length[i]);

#define EXTEND_Debug_glTexImage2D \
    if (pixels) { \
        DbgContext * const dbg = getDbgContextThreadSpecific(); \
        const unsigned size = GetBytesPerPixel(format, type) * width * height; \
        assert(0 < size); \
        dbg->Compress(pixels, size, msg.mutable_data()); \
    }

#define EXTEND_Debug_glTexSubImage2D EXTEND_Debug_glTexImage2D
