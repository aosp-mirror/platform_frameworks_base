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

#include "jni.h"

#include "SkCanvas.h"
#include "SkPaint.h"
#include "unicode/utypes.h"

namespace android {

class TextLayout {
public:

    enum {
        kDirection_LTR = 0,
        kDirection_RTL = 1,

        kDirection_Mask = 0x1
    };

    enum {
        kBidi_LTR = 0,
        kBidi_RTL = 1,
        kBidi_Default_LTR = 2,
        kBidi_Default_RTL = 3,
        kBidi_Force_LTR = 4,
        kBidi_Force_RTL = 5,

        kBidi_Mask = 0x7
    };

    /*
     * Draws a unidirectional run of text.
     */
    static void drawTextRun(SkPaint* paint, const jchar* chars,
                            jint start, jint count, jint contextCount,
                            int dirFlags, jfloat x, jfloat y, SkCanvas* canvas);

    static void getTextRunAdvances(SkPaint *paint, const jchar *chars, jint start,
                                   jint count, jint contextCount, jint dirFlags,
                                   jfloat *resultAdvances, jfloat &resultTotalAdvance);

    static void drawText(SkPaint* paint, const jchar* text, jsize len,
                         jint bidiFlags, jfloat x, jfloat y, SkCanvas* canvas);

    static void getTextPath(SkPaint *paint, const jchar *text, jsize len,
                            jint bidiFlags, jfloat x, jfloat y, SkPath *path);

    static void drawTextOnPath(SkPaint* paint, const jchar* text, jsize len,
                               int bidiFlags, jfloat hOffset, jfloat vOffset,
                               SkPath* path, SkCanvas* canvas);
                               
   static bool prepareText(SkPaint *paint, const jchar* text, jsize len, jint bidiFlags,
        const jchar** outText, int32_t* outBytes, jchar** outBuffer);
    static bool prepareRtlTextRun(const jchar* context, jsize start, jsize& count,
        jsize contextCount, jchar* shaped);
        

private:
    static bool needsLayout(const jchar* text, jint len, jint bidiFlags);
    static int shapeRtlText(const jchar* context, jsize start, jsize count, jsize contextCount,
                            jchar* shaped, UErrorCode &status);
    static jint layoutLine(const jchar* text, jint len, jint flags, int &dir, jchar* buffer,
                           UErrorCode &status);
    static void handleText(SkPaint *paint, const jchar* text, jsize len,
                           int bidiFlags, jfloat x, jfloat y,SkCanvas *canvas, SkPath *path);
};

}
