#ifndef _ANDROID_GRAPHICS_AUTO_DECODE_CANCEL_H_
#define _ANDROID_GRAPHICS_AUTO_DECODE_CANCEL_H_

#include <jni.h>
#include "SkImageDecoder.h"

class AutoDecoderCancel {
public:
    AutoDecoderCancel(jobject options, SkImageDecoder* decoder);
    ~AutoDecoderCancel();

    static bool RequestCancel(jobject options);

private:
    AutoDecoderCancel*  fNext;
    AutoDecoderCancel*  fPrev;
    jobject             fJOptions;  // java options object
    SkImageDecoder*     fDecoder;

#ifdef SK_DEBUG
    static void Validate();
#else
    static void Validate() {}
#endif
};

#endif  // _ANDROID_GRAPHICS_AUTO_DECODE_CANCEL_H_
