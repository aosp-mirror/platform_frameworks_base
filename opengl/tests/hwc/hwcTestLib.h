/*
 * Copyright (C) 2011 The Android Open Source Project
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
 *
 */

/*
 * Hardware Composer Test Library Header
 */

#include <sstream>
#include <string>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <ui/FramebufferNativeWindow.h>
#include <ui/GraphicBuffer.h>
#include <ui/EGLUtils.h>

#include <utils/Log.h>
#include <testUtil.h>

#include <hardware/hwcomposer.h>

// Characteristics of known graphic formats
const struct hwcTestGraphicFormat {
    uint32_t format;
    const char *desc;
    uint32_t wMod, hMod; // Width/height mod this value must equal zero
} hwcTestGraphicFormat[] = {
    {HAL_PIXEL_FORMAT_RGBA_8888, "RGBA8888", 1, 1},
    {HAL_PIXEL_FORMAT_RGBX_8888, "RGBX8888", 1, 1},
    {HAL_PIXEL_FORMAT_RGB_888,   "RGB888",   1, 1},
    {HAL_PIXEL_FORMAT_RGB_565,   "RGB565",   1, 1},
    {HAL_PIXEL_FORMAT_BGRA_8888, "BGRA8888", 1, 1},
    {HAL_PIXEL_FORMAT_RGBA_5551, "RGBA5551", 1, 1},
    {HAL_PIXEL_FORMAT_RGBA_4444, "RGBA4444", 1, 1},
    {HAL_PIXEL_FORMAT_YV12,      "YV12",     2, 2},
};

// Represent RGB color as fraction of color components.
// Each of the color components are expected in the range [0.0, 1.0]
class ColorFract {
  public:
    ColorFract(): _c1(0.0), _c2(0.0), _c3(0.0) {};
    ColorFract(float c1, float c2, float c3): _c1(c1), _c2(c2), _c3(c3) {};
    float c1(void) const { return _c1; }
    float c2(void) const { return _c2; }
    float c3(void) const { return _c3; }

    operator std::string();

  private:
    float _c1;
    float _c2;
    float _c3;
};

// Represent RGB color as fraction of color components.
// Each of the color components are expected in the range [0.0, 1.0]
class ColorRGB {
  public:
    ColorRGB(): _r(0.0), _g(0.0), _b(0.0) {};
    ColorRGB(float f): _r(f), _g(f), _b(f) {}; // Gray
    ColorRGB(float r, float g, float b): _r(r), _g(g), _b(b) {};
    float r(void) const { return _r; }
    float g(void) const { return _g; }
    float b(void) const { return _b; }

  private:
    float _r;
    float _g;
    float _b;
};

// Dimension - width and height of a rectanguler area
class HwcTestDim {
  public:
    HwcTestDim(): _w(0), _h(0) {};
    HwcTestDim(uint32_t w, uint32_t h) : _w(w), _h(h) {}
    uint32_t width(void) const { return _w; }
    uint32_t height(void) const { return _h; }
    void setWidth(uint32_t w) { _w = w; }
    void setHeight(uint32_t h) { _h = h; }

    operator std::string();
    operator hwc_rect() const;

  private:
    uint32_t _w;
    uint32_t _h;
};

// Function Prototypes
void hwcTestInitDisplay(bool verbose, EGLDisplay *dpy, EGLSurface *surface,
    EGLint *width, EGLint *height);
void hwcTestOpenHwc(hwc_composer_device_t **hwcDevicePtr);
const struct hwcTestGraphicFormat *hwcTestGraphicFormatLookup(const char *desc);
const struct hwcTestGraphicFormat *hwcTestGraphicFormatLookup(uint32_t id);
const char *hwcTestGraphicFormat2str(uint32_t format);
std::string hwcTestRect2str(const struct hwc_rect& rect);

hwc_layer_list_t *hwcTestCreateLayerList(size_t numLayers);
void hwcTestFreeLayerList(hwc_layer_list_t *list);
void hwcTestDisplayList(hwc_layer_list_t *list);
void hwcTestDisplayListPrepareModifiable(hwc_layer_list_t *list);
void hwcTestDisplayListHandles(hwc_layer_list_t *list);

uint32_t hwcTestColor2Pixel(uint32_t format, ColorFract color, float alpha);
void hwcTestColorConvert(uint32_t fromFormat, uint32_t toFormat,
                  ColorFract& color);
void hwcTestSetPixel(android::GraphicBuffer *gBuf, unsigned char *buf,
                     uint32_t x, uint32_t y, uint32_t pixel);
void hwcTestFillColor(android::GraphicBuffer *gBuf, ColorFract color,
                      float alpha);
void hwcTestFillColorHBlend(android::GraphicBuffer *gBuf,
                            uint32_t colorFormat,
                            ColorFract startColor, ColorFract endColor);
ColorFract hwcTestParseColor(std::istringstream& in, bool& error);
struct hwc_rect hwcTestParseHwcRect(std::istringstream& in, bool& error);
HwcTestDim hwcTestParseDim(std::istringstream& in, bool& error);
