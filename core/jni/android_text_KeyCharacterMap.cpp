/*
 * Copyright 2006, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
*/

#include <ui/KeyCharacterMap.h>

#include <nativehelper/jni.h>
#include <android_runtime/AndroidRuntime.h>
#include <nativehelper/JNIHelp.h>

namespace android {

static jint
ctor(JNIEnv *env, jobject clazz, jint id)
{
    return reinterpret_cast<int>(KeyCharacterMap::load(id));
}

static void
dtor(JNIEnv *env, jobject clazz, jint ptr)
{
    delete reinterpret_cast<KeyCharacterMap*>(ptr);
}

static jchar
get(JNIEnv *env, jobject clazz, jint ptr, jint keycode, jint meta)
{
    return reinterpret_cast<KeyCharacterMap*>(ptr)->get(keycode, meta);
}

static jchar
getNumber(JNIEnv *env, jobject clazz, jint ptr, jint keycode)
{
    return reinterpret_cast<KeyCharacterMap*>(ptr)->getNumber(keycode);
}

static jchar
getMatch(JNIEnv *env, jobject clazz, jint ptr, jint keycode, jcharArray chars, jint modifiers)
{
    jchar rv;
    jchar* ch = env->GetCharArrayElements(chars, NULL);
    jsize chsize = env->GetArrayLength(chars);

    rv = reinterpret_cast<KeyCharacterMap*>(ptr)->getMatch(keycode, ch, chsize, modifiers);

    env->ReleaseCharArrayElements(chars, ch, JNI_ABORT);
    return rv;
}

static jchar
getDisplayLabel(JNIEnv *env, jobject clazz, jint ptr, jint keycode)
{
    return reinterpret_cast<KeyCharacterMap*>(ptr)->getDisplayLabel(keycode);
}

static jfieldID gKeyDataMetaField;
static jfieldID gKeyDataNumberField;
static jfieldID gKeyDataDisplayLabelField;

static jboolean
getKeyData(JNIEnv *env, jobject clazz, jint ptr, jint keycode, jobject keydata)
{
    jboolean rv;

    unsigned short displayLabel = env->GetCharField(keydata, gKeyDataDisplayLabelField);
    unsigned short number = env->GetCharField(keydata, gKeyDataNumberField);

    jcharArray chars = (jcharArray) env->GetObjectField(keydata, gKeyDataMetaField);
    jchar* ch = env->GetCharArrayElements(chars, NULL);

    KeyCharacterMap* kmap = reinterpret_cast<KeyCharacterMap*>(ptr);
    rv = kmap->getKeyData(keycode, &displayLabel, &number, ch);

    env->SetCharField(keydata, gKeyDataDisplayLabelField, displayLabel);
    env->SetCharField(keydata, gKeyDataNumberField, number);

    env->ReleaseCharArrayElements(chars, ch, 0);
    return rv;
}

static jint
getKeyboardType(JNIEnv *env, jobject clazz, jint ptr)
{
    return reinterpret_cast<KeyCharacterMap*>(ptr)->getKeyboardType();
}

static jlongArray
getEvents(JNIEnv *env, jobject clazz, jint ptr, jcharArray jchars)
{
    KeyCharacterMap* kmap = reinterpret_cast<KeyCharacterMap*>(ptr);

    uint16_t* chars = env->GetCharArrayElements(jchars, NULL);
    size_t len = env->GetArrayLength(jchars);

    Vector<int32_t> keys;
    Vector<uint32_t> modifiers;
    bool success = kmap->getEvents(chars, len, &keys, &modifiers);

    env->ReleaseCharArrayElements(jchars, chars, JNI_ABORT);

    if (success) {
        size_t N = keys.size();

        jlongArray rv = env->NewLongArray(N);
        uint64_t* results = (uint64_t*)env->GetLongArrayElements(rv, NULL);

        for (size_t i=0; i<N; i++) {
            uint64_t v = modifiers[i];
            v <<= 32;
            v |= keys[i];
            results[i] = v;
        }

        env->ReleaseLongArrayElements(rv, (jlong*)results, 0);
        return rv;
    } else {
        return NULL;
    }
}

// ============================================================================
/*
 * JNI registration.
 */

static JNINativeMethod g_methods[] = {
    /* name, signature, funcPtr */
    { "ctor_native",             "(I)I",    (void*)ctor },
    { "dtor_native",             "(I)V",    (void*)dtor },
    { "get_native",              "(III)C", (void*)get },
    { "getNumber_native",        "(II)C",   (void*)getNumber },
    { "getMatch_native",         "(II[CI)C", (void*)getMatch },
    { "getDisplayLabel_native",  "(II)C",   (void*)getDisplayLabel },
    { "getKeyData_native",       "(IILandroid/view/KeyCharacterMap$KeyData;)Z",
                                            (void*)getKeyData },
    { "getKeyboardType_native",  "(I)I",    (void*)getKeyboardType },
    { "getEvents_native",        "(I[C)[J", (void*)getEvents }
};

int register_android_text_KeyCharacterMap(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass("android/view/KeyCharacterMap$KeyData");
    if (clazz == NULL) {
        LOGE("Can't find android/view/KeyCharacterMap$KeyData");
        return -1;
    }

    gKeyDataMetaField = env->GetFieldID(clazz, "meta", "[C");
    gKeyDataNumberField = env->GetFieldID(clazz, "number", "C");
    gKeyDataDisplayLabelField = env->GetFieldID(clazz, "displayLabel", "C");

    return AndroidRuntime::registerNativeMethods(env,
            "android/view/KeyCharacterMap", g_methods, NELEM(g_methods));
}

}; // namespace android



