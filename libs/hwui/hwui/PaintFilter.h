#ifndef ANDROID_GRAPHICS_PAINT_FILTER_H_
#define ANDROID_GRAPHICS_PAINT_FILTER_H_

class SkPaint;

namespace android {

class PaintFilter : public SkRefCnt {
public:
    /**
     *  Called with the paint that will be used to draw.
     *  The implementation may modify the paint as they wish.
     */
    virtual void filter(SkPaint*) = 0;
    virtual void filterFullPaint(Paint*) = 0;
};

} // namespace android

#endif
