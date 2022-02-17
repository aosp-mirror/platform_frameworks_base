#ifndef ANDROID_GRAPHICS_PAINT_FILTER_H_
#define ANDROID_GRAPHICS_PAINT_FILTER_H_

#include <SkRefCnt.h>

namespace android {

class Paint;

class PaintFilter : public SkRefCnt {
public:
    /**
     *  Called with the paint that will be used to draw.
     *  The implementation may modify the paint as they wish.
     */
    virtual void filterFullPaint(Paint*) = 0;
};

} // namespace android

#endif
