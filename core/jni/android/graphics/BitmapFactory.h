#ifndef _ANDROID_GRAPHICS_BITMAP_FACTORY_H_
#define _ANDROID_GRAPHICS_BITMAP_FACTORY_H_

#include "GraphicsJNI.h"
#include "SkEncodedFormat.h"

extern jclass gOptions_class;
extern jfieldID gOptions_justBoundsFieldID;
extern jfieldID gOptions_sampleSizeFieldID;
extern jfieldID gOptions_configFieldID;
extern jfieldID gOptions_premultipliedFieldID;
extern jfieldID gOptions_ditherFieldID;
extern jfieldID gOptions_purgeableFieldID;
extern jfieldID gOptions_shareableFieldID;
extern jfieldID gOptions_nativeAllocFieldID;
extern jfieldID gOptions_preferQualityOverSpeedFieldID;
extern jfieldID gOptions_widthFieldID;
extern jfieldID gOptions_heightFieldID;
extern jfieldID gOptions_mimeFieldID;
extern jfieldID gOptions_mCancelID;
extern jfieldID gOptions_bitmapFieldID;

jstring encodedFormatToString(JNIEnv* env, SkEncodedFormat format);

jobject decodeBitmap(JNIEnv* env, void* data, size_t size);

#endif  // _ANDROID_GRAPHICS_BITMAP_FACTORY_H_
