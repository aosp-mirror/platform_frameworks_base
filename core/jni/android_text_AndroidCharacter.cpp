/* //device/libs/android_runtime/android_text_AndroidCharacter.cpp
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

#define LOG_TAG "AndroidUnicode"

#include "JNIHelp.h"
#include <android_runtime/AndroidRuntime.h>
#include "utils/misc.h"
#include "utils/Log.h"
#include "unicode/uchar.h"

#define PROPERTY_UNDEFINED (-1)

// ICU => JDK mapping
static int directionality_map[U_CHAR_DIRECTION_COUNT] = {
    0, // U_LEFT_TO_RIGHT (0) => DIRECTIONALITY_LEFT_TO_RIGHT (0)
    1, // U_RIGHT_TO_LEFT (1) => DIRECTIONALITY_RIGHT_TO_LEFT (1)
    3, // U_EUROPEAN_NUMBER (2) => DIRECTIONALITY_EUROPEAN_NUMBER (3)
    4, // U_EUROPEAN_NUMBER_SEPARATOR (3) => DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR (4)
    5, // U_EUROPEAN_NUMBER_TERMINATOR (4) => DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR (5)
    6, // U_ARABIC_NUMBER (5) => DIRECTIONALITY_ARABIC_NUMBER (6)
    7, // U_COMMON_NUMBER_SEPARATOR (6) => DIRECTIONALITY_COMMON_NUMBER_SEPARATOR (7)
    10, // U_BLOCK_SEPARATOR (7) => DIRECTIONALITY_PARAGRAPH_SEPARATOR (10)
    11, // U_SEGMENT_SEPARATOR (8) => DIRECTIONALITY_SEGMENT_SEPARATOR (11)
    12, // U_WHITE_SPACE_NEUTRAL (9) => DIRECTIONALITY_WHITESPACE (12)
    13, // U_OTHER_NEUTRAL (10) => DIRECTIONALITY_OTHER_NEUTRALS (13)
    14, // U_LEFT_TO_RIGHT_EMBEDDING (11) => DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING (14)
    15, // U_LEFT_TO_RIGHT_OVERRIDE (12) => DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE (15)
    2, // U_RIGHT_TO_LEFT_ARABIC (13) => DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC (2)
    16, // U_RIGHT_TO_LEFT_EMBEDDING (14) => DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING (16)
    17, // U_RIGHT_TO_LEFT_OVERRIDE (15) => DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE (17)
    18, // U_POP_DIRECTIONAL_FORMAT (16) => DIRECTIONALITY_POP_DIRECTIONAL_FORMAT (18)
    8, // U_DIR_NON_SPACING_MARK (17) => DIRECTIONALITY_NONSPACING_MARK (8)
    9, // U_BOUNDARY_NEUTRAL (18) => DIRECTIONALITY_BOUNDARY_NEUTRAL (9)
};

namespace android {
    
static void getDirectionalities(JNIEnv* env, jobject obj, jcharArray srcArray, jbyteArray destArray, int count)
{
    jchar* src = env->GetCharArrayElements(srcArray, NULL);
    jbyte* dest = env->GetByteArrayElements(destArray, NULL);
    if (src == NULL || dest == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        goto DIRECTION_END;
    }

    if (env->GetArrayLength(srcArray) < count || env->GetArrayLength(destArray) < count) {
        jniThrowException(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
        goto DIRECTION_END;
    }

    for (int i = 0; i < count; i++) {
        if (src[i] >= 0xD800 && src[i] <= 0xDBFF &&
            i + 1 < count &&
            src[i + 1] >= 0xDC00 && src[i + 1] <= 0xDFFF) {
            int c = 0x00010000 + ((src[i] - 0xD800) << 10) +
                                 (src[i + 1] & 0x3FF);
            int dir = u_charDirection(c);
            if (dir < 0 || dir >= U_CHAR_DIRECTION_COUNT)
                dir = PROPERTY_UNDEFINED;
            else
                dir = directionality_map[dir];

            dest[i++] = dir;
            dest[i] = dir;
        } else {
            int c = src[i];
            int dir = u_charDirection(c);
            if (dir < 0 || dir >= U_CHAR_DIRECTION_COUNT)
                dest[i] = PROPERTY_UNDEFINED;
            else
                dest[i] = directionality_map[dir];
        }
    }
    
DIRECTION_END:
    env->ReleaseCharArrayElements(srcArray, src, JNI_ABORT);
    env->ReleaseByteArrayElements(destArray, dest, JNI_ABORT);
}

static jint getEastAsianWidth(JNIEnv* env, jobject obj, jchar input)
{
    int width = u_getIntPropertyValue(input, UCHAR_EAST_ASIAN_WIDTH);
    if (width < 0 || width >= U_EA_COUNT)
        width = PROPERTY_UNDEFINED;

    return width;
}

static void getEastAsianWidths(JNIEnv* env, jobject obj, jcharArray srcArray,
                               int start, int count, jbyteArray destArray)
{
    jchar* src = env->GetCharArrayElements(srcArray, NULL);
    jbyte* dest = env->GetByteArrayElements(destArray, NULL);
    if (src == NULL || dest == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        goto EA_END;
    }

    if (start < 0 || start > start + count
            || env->GetArrayLength(srcArray) < (start + count)
            || env->GetArrayLength(destArray) < count) {
        jniThrowException(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
        goto EA_END;
    }

    for (int i = 0; i < count; i++) {
        const int srci = start + i;
        if (src[srci] >= 0xD800 && src[srci] <= 0xDBFF &&
            i + 1 < count &&
            src[srci + 1] >= 0xDC00 && src[srci + 1] <= 0xDFFF) {
            int c = 0x00010000 + ((src[srci] - 0xD800) << 10) +
                                 (src[srci + 1] & 0x3FF);
            int width = u_getIntPropertyValue(c, UCHAR_EAST_ASIAN_WIDTH);
            if (width < 0 || width >= U_EA_COUNT)
                width = PROPERTY_UNDEFINED;

            dest[i++] = width;
            dest[i] = width;
        } else {
            int c = src[srci];
            int width = u_getIntPropertyValue(c, UCHAR_EAST_ASIAN_WIDTH);
            if (width < 0 || width >= U_EA_COUNT)
                width = PROPERTY_UNDEFINED;

            dest[i] = width;
        }
    }

EA_END:
    env->ReleaseCharArrayElements(srcArray, src, JNI_ABORT);
    env->ReleaseByteArrayElements(destArray, dest, JNI_ABORT);
}

static jboolean mirror(JNIEnv* env, jobject obj, jcharArray charArray, int start, int count)
{
    jchar* data = env->GetCharArrayElements(charArray, NULL);
    bool ret = false;

    if (data == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        goto MIRROR_END;
    }

    if (start < 0 || start > start + count
            || env->GetArrayLength(charArray) < start + count) {
        jniThrowException(env, "java/lang/ArrayIndexOutOfBoundsException", NULL);
        goto MIRROR_END;
    }

    for (int i = start; i < start + count; i++) {
        // XXX this thinks it knows that surrogates are never mirrored

        int c1 = data[i];
        int c2 = u_charMirror(c1);

        if (c1 != c2) {
            data[i] = c2;
            ret = true;
        }
    }

MIRROR_END:
    env->ReleaseCharArrayElements(charArray, data, JNI_ABORT);
	return ret;
}

static jchar getMirror(JNIEnv* env, jobject obj, jchar c)
{   
    return u_charMirror(c);
}

static JNINativeMethod gMethods[] = {
	{ "getDirectionalities", "([C[BI)V",
        (void*) getDirectionalities },
	{ "getEastAsianWidth", "(C)I",
        (void*) getEastAsianWidth },
	{ "getEastAsianWidths", "([CII[B)V",
        (void*) getEastAsianWidths },
	{ "mirror", "([CII)Z",
        (void*) mirror },
	{ "getMirror", "(C)C",
        (void*) getMirror }
};

int register_android_text_AndroidCharacter(JNIEnv* env)
{
    jclass clazz = env->FindClass("android/text/AndroidCharacter");
    LOG_ASSERT(clazz, "Cannot find android/text/AndroidCharacter");
    
    return AndroidRuntime::registerNativeMethods(env, "android/text/AndroidCharacter",
            gMethods, NELEM(gMethods));
}

}
