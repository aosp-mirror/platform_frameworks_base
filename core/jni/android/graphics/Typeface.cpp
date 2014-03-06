#include "jni.h"
#include <android_runtime/AndroidRuntime.h>

#include "GraphicsJNI.h"
#include "SkStream.h"
#include "SkTypeface.h"
#include "Utils.h"
#include <android_runtime/android_util_AssetManager.h>
#include <androidfw/AssetManager.h>

using namespace android;

class AutoJavaStringToUTF8 {
public:
    AutoJavaStringToUTF8(JNIEnv* env, jstring str) : fEnv(env), fJStr(str)
    {
        fCStr = env->GetStringUTFChars(str, NULL);
    }
    ~AutoJavaStringToUTF8()
    {
        fEnv->ReleaseStringUTFChars(fJStr, fCStr);
    }
    const char* c_str() const { return fCStr; }

private:
    JNIEnv*     fEnv;
    jstring     fJStr;
    const char* fCStr;
};

static SkTypeface* Typeface_create(JNIEnv* env, jobject, jstring name,
                                   SkTypeface::Style style) {
    SkTypeface* face = NULL;

    if (NULL != name) {
        AutoJavaStringToUTF8    str(env, name);
        face = SkTypeface::CreateFromName(str.c_str(), style);
        // Try to find the closest matching font, using the standard heuristic
        if (NULL == face) {
            face = SkTypeface::CreateFromName(str.c_str(), (SkTypeface::Style)(style ^ SkTypeface::kItalic));
        }
        for (int i = 0; NULL == face && i < 4; i++) {
            face = SkTypeface::CreateFromName(str.c_str(), (SkTypeface::Style)i);
        }
    }

    // return the default font at the best style if no exact match exists
    if (NULL == face) {
        face = SkTypeface::CreateFromName(NULL, style);
    }
    return face;
}

static SkTypeface* Typeface_createFromTypeface(JNIEnv* env, jobject, SkTypeface* family, int style) {
    SkTypeface* face = SkTypeface::CreateFromTypeface(family, (SkTypeface::Style)style);
    // Try to find the closest matching font, using the standard heuristic
    if (NULL == face) {
        face = SkTypeface::CreateFromTypeface(family, (SkTypeface::Style)(style ^ SkTypeface::kItalic));
    }
    for (int i = 0; NULL == face && i < 4; i++) {
        face = SkTypeface::CreateFromTypeface(family, (SkTypeface::Style)i);
    }
    if (NULL == face) {
        face = SkTypeface::CreateFromName(NULL, (SkTypeface::Style)style);
    }
    return face;
}

static void Typeface_unref(JNIEnv* env, jobject obj, SkTypeface* face) {
    SkSafeUnref(face);
}

static int Typeface_getStyle(JNIEnv* env, jobject obj, SkTypeface* face) {
    return face->style();
}

static SkTypeface* Typeface_createFromAsset(JNIEnv* env, jobject,
                                            jobject jassetMgr,
                                            jstring jpath) {

    NPE_CHECK_RETURN_ZERO(env, jassetMgr);
    NPE_CHECK_RETURN_ZERO(env, jpath);

    AssetManager* mgr = assetManagerForJavaObject(env, jassetMgr);
    if (NULL == mgr) {
        return NULL;
    }

    AutoJavaStringToUTF8    str(env, jpath);
    Asset* asset = mgr->open(str.c_str(), Asset::ACCESS_BUFFER);
    if (NULL == asset) {
        return NULL;
    }

    SkStream* stream = new AssetStreamAdaptor(asset,
                                              AssetStreamAdaptor::kYes_OwnAsset,
                                              AssetStreamAdaptor::kYes_HasMemoryBase);
    SkTypeface* face = SkTypeface::CreateFromStream(stream);
    // SkTypeFace::CreateFromStream calls ref() on the stream, so we
    // need to unref it here or it won't be freed later on
    stream->unref();

    return face;
}

static SkTypeface* Typeface_createFromFile(JNIEnv* env, jobject, jstring jpath) {
    NPE_CHECK_RETURN_ZERO(env, jpath);

    AutoJavaStringToUTF8 str(env, jpath);

    return SkTypeface::CreateFromFile(str.c_str());
}

///////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gTypefaceMethods[] = {
    { "nativeCreate",        "(Ljava/lang/String;I)I", (void*)Typeface_create },
    { "nativeCreateFromTypeface", "(II)I", (void*)Typeface_createFromTypeface },
    { "nativeUnref",              "(I)V",  (void*)Typeface_unref },
    { "nativeGetStyle",           "(I)I",  (void*)Typeface_getStyle },
    { "nativeCreateFromAsset",    "(Landroid/content/res/AssetManager;Ljava/lang/String;)I",
                                           (void*)Typeface_createFromAsset },
    { "nativeCreateFromFile",     "(Ljava/lang/String;)I",
                                           (void*)Typeface_createFromFile },
};

int register_android_graphics_Typeface(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env,
                                                       "android/graphics/Typeface",
                                                       gTypefaceMethods,
                                                       SK_ARRAY_COUNT(gTypefaceMethods));
}
