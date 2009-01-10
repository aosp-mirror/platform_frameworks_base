/*
 * Copyright (C) 2008 The Android Open Source Project
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

#include <stdio.h>
#include <assert.h>

#include <core/SkCanvas.h>
#include <core/SkDevice.h>
#include <core/SkPaint.h>
#include <utils/SkGLCanvas.h>
#include "GraphicsJNI.h"

#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>

// ----------------------------------------------------------------------------

namespace android {

static int gPrevDur;

static void android_view_ViewRoot_showFPS(JNIEnv* env, jobject, jobject jcanvas,
                                          jint dur) {
    NPE_CHECK_RETURN_VOID(env, jcanvas);
    SkCanvas* canvas = GraphicsJNI::getNativeCanvas(env, jcanvas);
    const SkBitmap& bm = canvas->getDevice()->accessBitmap(false);
    int height = bm.height();
    SkScalar bot = SkIntToScalar(height);

    if (height < 200) {
        return;
    }

    SkMatrix m;
    SkRect   r;
    SkPaint  p;
    char    str[4];

    dur = (gPrevDur + dur) >> 1;
    gPrevDur = dur;

    dur = 1000 / dur;
    str[3] = (char)('0' + dur % 10); dur /= 10;
    str[2] = (char)('0' + dur % 10); dur /= 10;
    str[1] = (char)('0' + dur % 10); dur /= 10;
    str[0] = (char)('0' + dur % 10);

    m.reset();
    r.set(0, bot-SkIntToScalar(10), SkIntToScalar(26), bot);
    p.setAntiAlias(true);
    p.setTextSize(SkIntToScalar(10));

    canvas->save();
    canvas->setMatrix(m);
    canvas->clipRect(r, SkRegion::kReplace_Op);
    p.setColor(SK_ColorWHITE);
    canvas->drawPaint(p);
    p.setColor(SK_ColorBLACK);
    canvas->drawText(str, 4, SkIntToScalar(1), bot - SK_Scalar1, p);
    canvas->restore();
}

static void android_view_ViewRoot_abandonGlCaches(JNIEnv* env, jobject) {
    SkGLCanvas::AbandonAllTextures();
}

// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/ViewRoot";

static JNINativeMethod gMethods[] = {
    {   "nativeShowFPS", "(Landroid/graphics/Canvas;I)V",
                                        (void*)android_view_ViewRoot_showFPS },
    {   "nativeAbandonGlCaches", "()V", 
                                (void*)android_view_ViewRoot_abandonGlCaches }
};

int register_android_view_ViewRoot(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env,
            kClassPathName, gMethods, NELEM(gMethods));
}

};

