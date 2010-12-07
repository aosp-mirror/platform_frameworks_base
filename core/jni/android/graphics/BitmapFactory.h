#ifndef BitmapFactory_DEFINE
#define BitmapFactory_DEFINE

#include "GraphicsJNI.h"

extern jclass gOptions_class;
extern jfieldID gOptions_justBoundsFieldID;
extern jfieldID gOptions_sampleSizeFieldID;
extern jfieldID gOptions_configFieldID;
extern jfieldID gOptions_ditherFieldID;
extern jfieldID gOptions_purgeableFieldID;
extern jfieldID gOptions_shareableFieldID;
extern jfieldID gOptions_nativeAllocFieldID;
extern jfieldID gOptions_preferQualityOverSpeedFieldID;
extern jfieldID gOptions_widthFieldID;
extern jfieldID gOptions_heightFieldID;
extern jfieldID gOptions_mimeFieldID;
extern jfieldID gOptions_mCancelID;

jstring getMimeTypeString(JNIEnv* env, SkImageDecoder::Format format);

#endif
