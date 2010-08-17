#ifndef AutoDecodeCancel_DEFINED
#define AutoDecodeCancel_DEFINED

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

#endif
