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

#include "header.h"

EGLBoolean Debug_eglSwapBuffers(EGLDisplay dpy, EGLSurface draw)
{
    DbgContext * const dbg = getDbgContextThreadSpecific();
    glesv2debugger::Message msg;
    struct : public FunctionCall {
        EGLDisplay dpy;
        EGLSurface draw;

        const int * operator()(gl_hooks_t::gl_t const * const _c, glesv2debugger::Message & msg) {
            msg.set_time(-1);
            return reinterpret_cast<const int *>(true);
        }
    } caller;
    caller.dpy = dpy;
    caller.draw = draw;

    msg.set_arg0(reinterpret_cast<int>(dpy));
    msg.set_arg1(reinterpret_cast<int>(draw));
    if (dbg->captureSwap > 0) {
        dbg->captureSwap--;
        int viewport[4] = {};
        dbg->hooks->gl.glGetIntegerv(GL_VIEWPORT, viewport);
        void * pixels = dbg->GetReadPixelsBuffer(viewport[2] * viewport[3] *
                        dbg->readBytesPerPixel);
        dbg->hooks->gl.glReadPixels(viewport[0], viewport[1], viewport[2],
                                    viewport[3], GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        dbg->CompressReadPixelBuffer(msg.mutable_data());
        msg.set_data_type(msg.ReferencedImage);
        msg.set_pixel_format(GL_RGBA);
        msg.set_pixel_type(GL_UNSIGNED_BYTE);
        msg.set_image_width(viewport[2]);
        msg.set_image_height(viewport[3]);
    }
    int * ret = MessageLoop(caller, msg, glesv2debugger::Message_Function_eglSwapBuffers);
    return static_cast<EGLBoolean>(reinterpret_cast<int>(ret));
}
