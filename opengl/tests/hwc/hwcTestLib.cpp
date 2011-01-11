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
 * Hardware Composer Test Library
 * Utility library functions for use by the Hardware Composer test cases
 */

#include <sstream>
#include <string>

#include <arpa/inet.h> // For ntohl() and htonl()

#include <hwc/hwcTestLib.h>

// Defines
#define NUMA(a) (sizeof(a) / sizeof(a [0]))

// Function Prototypes
static void printGLString(const char *name, GLenum s);
static void checkEglError(const char* op, EGLBoolean returnVal = EGL_TRUE);
static void checkGlError(const char* op);
static void printEGLConfiguration(EGLDisplay dpy, EGLConfig config);

using namespace std;
using namespace android;


#define BITSPERBYTE            8 // TODO: Obtain from <values.h>, once
                                 // it has been added

// Initialize Display
void hwcTestInitDisplay(bool verbose, EGLDisplay *dpy, EGLSurface *surface,
    EGLint *width, EGLint *height)
{
    static EGLContext context;

    int rv;

    EGLBoolean returnValue;
    EGLConfig myConfig = {0};
    EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    EGLint sConfigAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE };
    EGLint majorVersion, minorVersion;

    checkEglError("<init>");
    *dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    checkEglError("eglGetDisplay");
    if (*dpy == EGL_NO_DISPLAY) {
        testPrintE("eglGetDisplay returned EGL_NO_DISPLAY");
        exit(70);
    }

    returnValue = eglInitialize(*dpy, &majorVersion, &minorVersion);
    checkEglError("eglInitialize", returnValue);
    if (verbose) {
        testPrintI("EGL version %d.%d", majorVersion, minorVersion);
    }
    if (returnValue != EGL_TRUE) {
        testPrintE("eglInitialize failed");
        exit(71);
    }

    EGLNativeWindowType window = android_createDisplaySurface();
    if (window == NULL) {
        testPrintE("android_createDisplaySurface failed");
        exit(72);
    }
    returnValue = EGLUtils::selectConfigForNativeWindow(*dpy,
        sConfigAttribs, window, &myConfig);
    if (returnValue) {
        testPrintE("EGLUtils::selectConfigForNativeWindow() returned %d",
            returnValue);
        exit(73);
    }
    checkEglError("EGLUtils::selectConfigForNativeWindow");

    if (verbose) {
        testPrintI("Chose this configuration:");
        printEGLConfiguration(*dpy, myConfig);
    }

    *surface = eglCreateWindowSurface(*dpy, myConfig, window, NULL);
    checkEglError("eglCreateWindowSurface");
    if (*surface == EGL_NO_SURFACE) {
        testPrintE("gelCreateWindowSurface failed.");
        exit(74);
    }

    context = eglCreateContext(*dpy, myConfig, EGL_NO_CONTEXT, contextAttribs);
    checkEglError("eglCreateContext");
    if (context == EGL_NO_CONTEXT) {
        testPrintE("eglCreateContext failed");
        exit(75);
    }
    returnValue = eglMakeCurrent(*dpy, *surface, *surface, context);
    checkEglError("eglMakeCurrent", returnValue);
    if (returnValue != EGL_TRUE) {
        testPrintE("eglMakeCurrent failed");
        exit(76);
    }
    eglQuerySurface(*dpy, *surface, EGL_WIDTH, width);
    checkEglError("eglQuerySurface");
    eglQuerySurface(*dpy, *surface, EGL_HEIGHT, height);
    checkEglError("eglQuerySurface");

    if (verbose) {
        testPrintI("Window dimensions: %d x %d", *width, *height);

        printGLString("Version", GL_VERSION);
        printGLString("Vendor", GL_VENDOR);
        printGLString("Renderer", GL_RENDERER);
        printGLString("Extensions", GL_EXTENSIONS);
    }
}

// Open Hardware Composer Device
void hwcTestOpenHwc(hwc_composer_device_t **hwcDevicePtr)
{
    int rv;
    hw_module_t const *hwcModule;

    if ((rv = hw_get_module(HWC_HARDWARE_MODULE_ID, &hwcModule)) != 0) {
        testPrintE("hw_get_module failed, rv: %i", rv);
        errno = -rv;
        perror(NULL);
        exit(77);
    }
    if ((rv = hwc_open(hwcModule, hwcDevicePtr)) != 0) {
        testPrintE("hwc_open failed, rv: %i", rv);
        errno = -rv;
        perror(NULL);
        exit(78);
    }
}

// Color fraction class to string conversion
ColorFract::operator string()
{
    ostringstream out;

    out << '[' << this->c1() << ", "
        << this->c2() << ", "
        << this->c3() << ']';

    return out.str();
}

// Dimension class to string conversion
HwcTestDim::operator string()
{
    ostringstream out;

    out << '[' << this->width() << ", "
        << this->height() << ']';

    return out.str();
}

// Dimension class to hwc_rect conversion
HwcTestDim::operator hwc_rect() const
{
    hwc_rect rect;

    rect.left = rect.top = 0;

    rect.right = this->_w;
    rect.bottom = this->_h;

    return rect;
}

// Hardware Composer rectangle to string conversion
string hwcTestRect2str(const struct hwc_rect& rect)
{
    ostringstream out;

    out << '[';
    out << rect.left << ", ";
    out << rect.top << ", ";
    out << rect.right << ", ";
    out << rect.bottom;
    out << ']';

    return out.str();
}

// Parse HWC rectangle description of form [left, top, right, bottom]
struct hwc_rect hwcTestParseHwcRect(istringstream& in, bool& error)
{
    struct hwc_rect rect;
    char chStart, ch;

    // Defensively specify that an error occurred.  Will clear
    // error flag if all of parsing succeeds.
    error = true;

    // First character should be a [ or <
    in >> chStart;
    if (!in || ((chStart != '<') && (chStart != '['))) { return rect; }

    // Left
    in >> rect.left;
    if (!in) { return rect; }
    in >> ch;
    if (!in || (ch != ',')) { return rect; }

    // Top
    in >> rect.top;
    if (!in) { return rect; }
    in >> ch;
    if (!in || (ch != ',')) { return rect; }

    // Right
    in >> rect.right;
    if (!in) { return rect; }
    in >> ch;
    if (!in || (ch != ',')) { return rect; }

    // Bottom
    in >> rect.bottom;
    if (!in) { return rect; }

    // Closing > or ]
    in >> ch;
    if (!in) { return rect; }
    if (((chStart == '<') && (ch != '>'))
        || ((chStart == '[') && (ch != ']'))) { return rect; }

    // Validate right and bottom are greater than left and top
    if ((rect.right <= rect.left) || (rect.bottom <= rect.top)) { return rect; }

    // Made It, clear error indicator
    error = false;

    return rect;
}

// Parse dimension of form [width, height]
HwcTestDim hwcTestParseDim(istringstream& in, bool& error)
{
    HwcTestDim dim;
    char chStart, ch;
    uint32_t val;

    // Defensively specify that an error occurred.  Will clear
    // error flag if all of parsing succeeds.
    error = true;

    // First character should be a [ or <
    in >> chStart;
    if (!in || ((chStart != '<') && (chStart != '['))) { return dim; }

    // Width
    in >> val;
    if (!in) { return dim; }
    dim.setWidth(val);
    in >> ch;
    if (!in || (ch != ',')) { return dim; }

    // Height
    in >> val;
    if (!in) { return dim; }
    dim.setHeight(val);

    // Closing > or ]
    in >> ch;
    if (!in) { return dim; }
    if (((chStart == '<') && (ch != '>'))
        || ((chStart == '[') && (ch != ']'))) { return dim; }

    // Validate width and height greater than 0
    if ((dim.width() <= 0) || (dim.height() <= 0)) { return dim; }

    // Made It, clear error indicator
    error = false;
    return dim;
}

// Parse fractional color of form [0.##, 0.##, 0.##]
// Fractional values can be from 0.0 to 1.0 inclusive.  Note, integer
// values of 0.0 and 1.0, which are non-fractional, are considered valid.
// They are an exception, all other valid inputs are fractions.
ColorFract hwcTestParseColor(istringstream& in, bool& error)
{
    ColorFract color;
    char chStart, ch;
    float c1, c2, c3;

    // Defensively specify that an error occurred.  Will clear
    // error flag if all of parsing succeeds.
    error = true;

    // First character should be a [ or <
    in >> chStart;
    if (!in || ((chStart != '<') && (chStart != '['))) { return color; }

    // 1st Component
    in >> c1;
    if (!in) { return color; }
    if ((c1 < 0.0) || (c1 > 1.0)) { return color; }
    in >> ch;
    if (!in || (ch != ',')) { return color; }

    // 2nd Component
    in >> c2;
    if (!in) { return color; }
    if ((c2 < 0.0) || (c2 > 1.0)) { return color; }
    in >> ch;
    if (!in || (ch != ',')) { return color; }

    // 3rd Component
    in >> c3;
    if (!in) { return color; }
    if ((c3 < 0.0) || (c3 > 1.0)) { return color; }

    // Closing > or ]
    in >> ch;
    if (!in) { return color; }
    if (((chStart == '<') && (ch != '>'))
        || ((chStart == '[') && (ch != ']'))) { return color; }

    // Are all the components fractional
    if ((c1 < 0.0) || (c1 > 1.0)
        || (c2 < 0.0) || (c2 > 1.0)
        || (c3 < 0.0) || (c3 > 1.0)) { return color; }

    // Made It, clear error indicator
    error = false;

    return ColorFract(c1, c2, c3);
}

// Look up and return pointer to structure with the characteristics
// of the graphic format named by the desc parameter.  Search failure
// indicated by the return of NULL.
const struct hwcTestGraphicFormat *hwcTestGraphicFormatLookup(const char *desc)
{
    for (unsigned int n1 = 0; n1 < NUMA(hwcTestGraphicFormat); n1++) {
        if (string(desc) == string(hwcTestGraphicFormat[n1].desc)) {
            return &hwcTestGraphicFormat[n1];
        }
    }

    return NULL;
}

// Look up and return pointer to structure with the characteristics
// of the graphic format specified by the id parameter.  Search failure
// indicated by the return of NULL.
const struct hwcTestGraphicFormat *hwcTestGraphicFormatLookup(uint32_t id)
{
    for (unsigned int n1 = 0; n1 < NUMA(hwcTestGraphicFormat); n1++) {
        if (id == hwcTestGraphicFormat[n1].format) {
            return &hwcTestGraphicFormat[n1];
        }
    }

    return NULL;
}


// Given the integer ID of a graphic format, return a pointer to
// a string that describes the format.
const char *hwcTestGraphicFormat2str(uint32_t format)
{
    const static char *unknown = "unknown";

    for (unsigned int n1 = 0; n1 < NUMA(hwcTestGraphicFormat); n1++) {
        if (format == hwcTestGraphicFormat[n1].format) {
            return hwcTestGraphicFormat[n1].desc;
        }
    }

    return unknown;
}

/*
 * hwcTestCreateLayerList
 * Dynamically creates layer list with numLayers worth
 * of hwLayers entries.
 */
hwc_layer_list_t *hwcTestCreateLayerList(size_t numLayers)
{
    hwc_layer_list_t *list;

    size_t size = sizeof(hwc_layer_list) + numLayers * sizeof(hwc_layer_t);
    if ((list = (hwc_layer_list_t *) calloc(1, size)) == NULL) {
        return NULL;
    }
    list->flags = HWC_GEOMETRY_CHANGED;
    list->numHwLayers = numLayers;

    return list;
}

/*
 * hwcTestFreeLayerList
 * Frees memory previous allocated via hwcTestCreateLayerList().
 */
void hwcTestFreeLayerList(hwc_layer_list_t *list)
{
    free(list);
}

// Display the settings of the layer list pointed to by list
void hwcTestDisplayList(hwc_layer_list_t *list)
{
    testPrintI("  flags: %#x%s", list->flags,
               (list->flags & HWC_GEOMETRY_CHANGED) ? " GEOMETRY_CHANGED" : "");
    testPrintI("  numHwLayers: %u", list->numHwLayers);

    for (unsigned int layer = 0; layer < list->numHwLayers; layer++) {
        testPrintI("    layer %u compositionType: %#x%s%s", layer,
                   list->hwLayers[layer].compositionType,
                   (list->hwLayers[layer].compositionType == HWC_FRAMEBUFFER)
                       ? " FRAMEBUFFER" : "",
                   (list->hwLayers[layer].compositionType == HWC_OVERLAY)
                       ? " OVERLAY" : "");

        testPrintI("      hints: %#x",
                   list->hwLayers[layer].hints,
                   (list->hwLayers[layer].hints & HWC_HINT_TRIPLE_BUFFER)
                       ? " TRIPLE_BUFFER" : "",
                   (list->hwLayers[layer].hints & HWC_HINT_CLEAR_FB)
                       ? " CLEAR_FB" : "");

        testPrintI("      flags: %#x%s",
                   list->hwLayers[layer].flags,
                   (list->hwLayers[layer].flags & HWC_SKIP_LAYER)
                       ? " SKIP_LAYER" : "");

        testPrintI("      handle: %p",
                   list->hwLayers[layer].handle);

        // Intentionally skipped display of ROT_180 & ROT_270,
        // which are formed from combinations of the other flags.
        testPrintI("      transform: %#x%s%s%s",
                   list->hwLayers[layer].transform,
                   (list->hwLayers[layer].transform & HWC_TRANSFORM_FLIP_H)
                       ? " FLIP_H" : "",
                   (list->hwLayers[layer].transform & HWC_TRANSFORM_FLIP_V)
                       ? " FLIP_V" : "",
                   (list->hwLayers[layer].transform & HWC_TRANSFORM_ROT_90)
                       ? " ROT_90" : "");

        testPrintI("      blending: %#x%s%s%s",
                   list->hwLayers[layer].blending,
                   (list->hwLayers[layer].blending == HWC_BLENDING_NONE)
                       ? " NONE" : "",
                   (list->hwLayers[layer].blending == HWC_BLENDING_PREMULT)
                       ? " PREMULT" : "",
                   (list->hwLayers[layer].blending == HWC_BLENDING_COVERAGE)
                       ? " COVERAGE" : "");

        testPrintI("      sourceCrop: %s",
                   hwcTestRect2str(list->hwLayers[layer].sourceCrop).c_str());
        testPrintI("      displayFrame: %s",
                   hwcTestRect2str(list->hwLayers[layer].displayFrame).c_str());
        testPrintI("      scaleFactor: [%f, %f]",
                   (float) (list->hwLayers[layer].sourceCrop.right
                            - list->hwLayers[layer].sourceCrop.left)
                       / (float) (list->hwLayers[layer].displayFrame.right
                            - list->hwLayers[layer].displayFrame.left),
                   (float) (list->hwLayers[layer].sourceCrop.bottom
                            - list->hwLayers[layer].sourceCrop.top)
                       / (float) (list->hwLayers[layer].displayFrame.bottom
                            - list->hwLayers[layer].displayFrame.top));
    }
}

/*
 * Display List Prepare Modifiable
 *
 * Displays the portions of a list that are meant to be modified by
 * a prepare call.
 */
void hwcTestDisplayListPrepareModifiable(hwc_layer_list_t *list)
{
    uint32_t numOverlays = 0;
    for (unsigned int layer = 0; layer < list->numHwLayers; layer++) {
        if (list->hwLayers[layer].compositionType == HWC_OVERLAY) {
            numOverlays++;
        }
        testPrintI("    layer %u compositionType: %#x%s%s", layer,
                   list->hwLayers[layer].compositionType,
                   (list->hwLayers[layer].compositionType == HWC_FRAMEBUFFER)
                       ? " FRAMEBUFFER" : "",
                   (list->hwLayers[layer].compositionType == HWC_OVERLAY)
                       ? " OVERLAY" : "");
        testPrintI("      hints: %#x%s%s",
                   list->hwLayers[layer].hints,
                   (list->hwLayers[layer].hints & HWC_HINT_TRIPLE_BUFFER)
                       ? " TRIPLE_BUFFER" : "",
                   (list->hwLayers[layer].hints & HWC_HINT_CLEAR_FB)
                       ? " CLEAR_FB" : "");
    }
    testPrintI("    numOverlays: %u", numOverlays);
}

/*
 * Display List Handles
 *
 * Displays the handles of all the graphic buffers in the list.
 */
void hwcTestDisplayListHandles(hwc_layer_list_t *list)
{
    const unsigned int maxLayersPerLine = 6;

    ostringstream str("  layers:");
    for (unsigned int layer = 0; layer < list->numHwLayers; layer++) {
        str << ' ' << list->hwLayers[layer].handle;
        if (((layer % maxLayersPerLine) == (maxLayersPerLine - 1))
            && (layer != list->numHwLayers - 1)) {
            testPrintI("%s", str.str().c_str());
            str.str("    ");
        }
    }
    testPrintI("%s", str.str().c_str());
}

// Returns a uint32_t that contains a format specific representation of a
// single pixel of the given color and alpha values.
uint32_t hwcTestColor2Pixel(uint32_t format, ColorFract color, float alpha)
{
    const struct attrib {
        uint32_t format;
        bool   hostByteOrder;
        size_t bytes;
        size_t c1Offset;
        size_t c1Size;
        size_t c2Offset;
        size_t c2Size;
        size_t c3Offset;
        size_t c3Size;
        size_t aOffset;
        size_t aSize;
    } attributes[] = {
        {HAL_PIXEL_FORMAT_RGBA_8888, false, 4,  0, 8,  8, 8, 16, 8, 24, 8},
        {HAL_PIXEL_FORMAT_RGBX_8888, false, 4,  0, 8,  8, 8, 16, 8,  0, 0},
        {HAL_PIXEL_FORMAT_RGB_888,   false, 3,  0, 8,  8, 8, 16, 8,  0, 0},
        {HAL_PIXEL_FORMAT_RGB_565,   true,  2,  0, 5,  5, 6, 11, 5,  0, 0},
        {HAL_PIXEL_FORMAT_BGRA_8888, false, 4, 16, 8,  8, 8,  0, 8, 24, 8},
        {HAL_PIXEL_FORMAT_RGBA_5551, true , 2,  0, 5,  5, 5, 10, 5, 15, 1},
        {HAL_PIXEL_FORMAT_RGBA_4444, false, 2, 12, 4,  0, 4,  4, 4,  8, 4},
        {HAL_PIXEL_FORMAT_YV12,      true,  3, 16, 8,  8, 8,  0, 8,  0, 0},  
    };

    const struct attrib *attrib;
    for (attrib = attributes; attrib < attributes + NUMA(attributes);
         attrib++) {
        if (attrib->format == format) { break; }
    }
    if (attrib >= attributes + NUMA(attributes)) {
        testPrintE("colorFract2Pixel unsupported format of: %u", format);
        exit(80);
    }

    uint32_t pixel;
    pixel = htonl((uint32_t) round((((1 << attrib->c1Size) - 1) * color.c1()))
         << ((sizeof(pixel) * BITSPERBYTE)
             - (attrib->c1Offset + attrib->c1Size)));
    pixel |= htonl((uint32_t) round((((1 << attrib->c2Size) - 1) * color.c2()))
         << ((sizeof(pixel) * BITSPERBYTE)
             - (attrib->c2Offset + attrib->c2Size)));
    pixel |= htonl((uint32_t) round((((1 << attrib->c3Size) - 1) * color.c3()))
         << ((sizeof(pixel) * BITSPERBYTE)
             - (attrib->c3Offset + attrib->c3Size)));
    if (attrib->aSize) {
        pixel |= htonl((uint32_t) round((((1 << attrib->aSize) - 1) * alpha))
             << ((sizeof(pixel) * BITSPERBYTE)
                 - (attrib->aOffset + attrib->aSize)));
    }
    if (attrib->hostByteOrder) {
        pixel = ntohl(pixel);
        pixel >>= sizeof(pixel) * BITSPERBYTE - attrib->bytes * BITSPERBYTE;
    }

    return pixel;
}

// Sets the pixel at the given x and y coordinates to the color and alpha
// value given by pixel.  The contents of pixel is format specific.  It's
// value should come from a call to hwcTestColor2Pixel().
void hwcTestSetPixel(GraphicBuffer *gBuf, unsigned char *buf,
              uint32_t x, uint32_t y, uint32_t pixel)
{

    const struct attrib {
        int format;
        size_t bytes;
    } attributes[] = {
        {HAL_PIXEL_FORMAT_RGBA_8888,  4},
        {HAL_PIXEL_FORMAT_RGBX_8888,  4},
        {HAL_PIXEL_FORMAT_RGB_888,    3},
        {HAL_PIXEL_FORMAT_RGB_565,    2},
        {HAL_PIXEL_FORMAT_BGRA_8888,  4},
        {HAL_PIXEL_FORMAT_RGBA_5551,  2},
        {HAL_PIXEL_FORMAT_RGBA_4444,  2},
    };

    if (gBuf->getPixelFormat() == HAL_PIXEL_FORMAT_YV12) {
        uint32_t yPlaneOffset, uPlaneOffset, vPlaneOffset;
        uint32_t yPlaneStride = gBuf->getStride();
        uint32_t uPlaneStride = ((gBuf->getStride() / 2) + 0xf) & ~0xf;
        uint32_t vPlaneStride = uPlaneStride;
        yPlaneOffset = 0;
        vPlaneOffset = yPlaneOffset + yPlaneStride * gBuf->getHeight();
        uPlaneOffset = vPlaneOffset
                       + vPlaneStride * (gBuf->getHeight() / 2);
        *(buf + yPlaneOffset + y * yPlaneStride + x) = pixel & 0xff;
        *(buf + uPlaneOffset + (y / 2) * uPlaneStride + (x / 2))
            = (pixel & 0xff00) >> 8;
        *(buf + vPlaneOffset + (y / 2) * vPlaneStride + (x / 2))
            = (pixel & 0xff0000) >> 16;

        return;
    }

    const struct attrib *attrib;
    for (attrib = attributes; attrib < attributes + NUMA(attributes);
         attrib++) {
        if (attrib->format == gBuf->getPixelFormat()) { break; }
    }
    if (attrib >= attributes + NUMA(attributes)) {
        testPrintE("setPixel unsupported format of: %u",
                   gBuf->getPixelFormat());
        exit(90);
    }

    memmove(buf + ((gBuf->getStride() * attrib->bytes) * y)
            + (attrib->bytes * x), &pixel, attrib->bytes);
}

// Fill a given graphic buffer with a uniform color and alpha
void hwcTestFillColor(GraphicBuffer *gBuf, ColorFract color, float alpha)
{
    unsigned char* buf = NULL;
    status_t err;
    uint32_t pixel;

    pixel = hwcTestColor2Pixel(gBuf->getPixelFormat(), color, alpha);

    err = gBuf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&buf));
    if (err != 0) {
        testPrintE("hwcTestFillColor lock failed: %d", err);
        exit(100);
    }

    for (unsigned int x = 0; x < gBuf->getStride(); x++) {
        for (unsigned int y = 0; y < gBuf->getHeight(); y++) {
            uint32_t val = pixel;
            hwcTestSetPixel(gBuf, buf, x, y, (x < gBuf->getWidth())
                            ? pixel : testRand());
        }
    }

    err = gBuf->unlock();
    if (err != 0) {
        testPrintE("hwcTestFillColor unlock failed: %d", err);
        exit(101);
    }
}

// Fill the given buffer with a horizontal blend of colors, with the left
// side color given by startColor and the right side color given by
// endColor.  The startColor and endColor values are specified in the format
// given by colorFormat, which might be different from the format of the
// graphic buffer.  When different, a color conversion is done when possible
// to the graphic format of the graphic buffer.  A color of black is
// produced for cases where the conversion is impossible (e.g. out of gamut
// values).
void hwcTestFillColorHBlend(GraphicBuffer *gBuf, uint32_t colorFormat,
                            ColorFract startColor, ColorFract endColor)
{
    status_t err;
    unsigned char* buf = NULL;
    const uint32_t width = gBuf->getWidth();
    const uint32_t height = gBuf->getHeight();
    const uint32_t stride = gBuf->getStride();

    err = gBuf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&buf));
    if (err != 0) {
        testPrintE("hwcTestFillColorHBlend lock failed: %d", err);
        exit(110);
    }

    for (unsigned int x = 0; x < stride; x++) {
        uint32_t pixel;
        if (x < width) {
            ColorFract color(startColor.c1() + (endColor.c1() - startColor.c1())
                                 * ((float) x / (float) (width - 1)),
                             startColor.c2() + (endColor.c2() - startColor.c2())
                                 * ((float) x / (float) (width - 1)),
                             startColor.c3() + (endColor.c3() - startColor.c3())
                                 * ((float) x / (float) (width - 1)));

            // When formats differ, convert colors.
            // Important to not convert when formats are the same, since
            // out of gamut colors are always converted to black.
            if (colorFormat != (uint32_t) gBuf->getPixelFormat()) {
                hwcTestColorConvert(colorFormat, gBuf->getPixelFormat(), color);
            }
            pixel = hwcTestColor2Pixel(gBuf->getPixelFormat(), color, 1.0);
        } else {
            // Fill pad with random values
            pixel = testRand();
        }

        for (unsigned int y = 0; y < height; y++) {
            hwcTestSetPixel(gBuf, buf, x, y, pixel);
        }
    }

    err = gBuf->unlock();
    if (err != 0) {
        testPrintE("hwcTestFillColorHBlend unlock failed: %d", err);
        exit(111);
    }
}

/*
 * When possible, converts color specified as a full range value in
 * the fromFormat, into an equivalent full range color in the toFormat.
 * When conversion is impossible (e.g. out of gamut color) a color
 * or black in the full range output format is produced.  The input
 * color is given as a fractional color in the parameter named color.
 * The produced color is written over the same parameter used to
 * provide the input color.
 *
 * Each graphic format has 3 color components and each of these
 * components has both a full and in gamut range.  This function uses
 * a table that provides the full and in gamut ranges of each of the
 * supported graphic formats.  The full range is given by members named
 * c[123]Min to c[123]Max, while the in gamut range is given by members
 * named c[123]Low to c[123]High.  In most cases the full and in gamut
 * ranges are equivalent.  This occurs when the c[123]Min == c[123]Low and
 * c[123]High == c[123]Max.
 *
 * The input and produced colors are both specified as a fractional amount
 * of the full range.  The diagram below provides an overview of the
 * conversion process.  The main steps are:
 *
 *   1. Produce black if the input color is out of gamut.
 *
 *   2. Convert the in gamut color into the fraction of the fromFromat
 *      in gamut range.
 *
 *   3. Convert from the fraction of the in gamut from format range to
 *      the fraction of the in gamut to format range.  Produce black
 *      if an equivalent color does not exists.
 *
 *   4. Covert from the fraction of the in gamut to format to the
 *      fraction of the full range to format.
 *
 *       From Format                 To Format
 *    max           high            high        max
 *    ----+                 +-----------+
 *    high \               /             \      high
 *    ------\-------------+               +-------->
 *           \
 *            \                   +--- black --+
 *             \                 /              \
 *              \               /                +-->
 *    low        \             /                  low
 *    -------- ---+-- black --+
 *    min             low           low           min
 *     ^               ^      ^      ^             ^
 *     |               |      |      |             |
 *     |               |      |      |             +-- fraction of full range
 *     |               |      |      +-- fraction of valid range
 *     |               |      +-- fromFormat to toFormat color conversion
 *     |               +-- fraction of valid range
 *     +-- fraction of full range
 */
void hwcTestColorConvert(uint32_t fromFormat, uint32_t toFormat,
                  ColorFract& color)
{
    const struct attrib {
        uint32_t     format;
        bool         rgb;
        bool         yuv;
        int          c1Min, c1Low, c1High, c1Max;
        int          c2Min, c2Low, c2High, c2Max;
        int          c3Min, c3Low, c3High, c3Max;
    } attributes[] = {
        {HAL_PIXEL_FORMAT_RGBA_8888, true,  false,
         0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255},
        {HAL_PIXEL_FORMAT_RGBX_8888, true,  false,
         0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255},
        {HAL_PIXEL_FORMAT_RGB_888,   true,  false,
         0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255},
        {HAL_PIXEL_FORMAT_RGB_565,   true,  false,
         0, 0, 31, 31, 0, 0, 63, 63, 0, 0, 31, 31},
        {HAL_PIXEL_FORMAT_BGRA_8888, true,  false,
         0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255},
        {HAL_PIXEL_FORMAT_RGBA_5551, true,  false,
         0, 0, 31, 31, 0, 0, 31, 31, 0, 0, 31, 31},
        {HAL_PIXEL_FORMAT_RGBA_4444, true,  false,
         0, 0, 15, 15, 0, 0, 15, 15, 0, 0, 15, 15},
        {HAL_PIXEL_FORMAT_YV12,      false, true,
         0, 16, 235, 255, 0, 16, 240, 255, 0, 16, 240, 255},
    };

    const struct attrib *fromAttrib;
    for (fromAttrib = attributes; fromAttrib < attributes + NUMA(attributes);
         fromAttrib++) {
        if (fromAttrib->format == fromFormat) { break; }
    }
    if (fromAttrib >= attributes + NUMA(attributes)) {
        testPrintE("hwcTestColorConvert unsupported from format of: %u",
                   fromFormat);
        exit(120);
    }

    const struct attrib *toAttrib;
    for (toAttrib = attributes; toAttrib < attributes + NUMA(attributes);
         toAttrib++) {
        if (toAttrib->format == toFormat) { break; }
    }
    if (toAttrib >= attributes + NUMA(attributes)) {
        testPrintE("hwcTestColorConvert unsupported to format of: %u",
                   toFormat);
        exit(121);
    }

    // Produce black if any of the from components are outside the
    // valid color range
    float c1Val = fromAttrib->c1Min
        + ((float) (fromAttrib->c1Max - fromAttrib->c1Min) * color.c1());
    float c2Val = fromAttrib->c2Min
        + ((float) (fromAttrib->c2Max - fromAttrib->c2Min) * color.c2());
    float c3Val = fromAttrib->c3Min
        + ((float) (fromAttrib->c3Max - fromAttrib->c3Min) * color.c3());
    if ((c1Val < fromAttrib->c1Low) || (c1Val > fromAttrib->c1High)
        || (c2Val < fromAttrib->c2Low) || (c2Val > fromAttrib->c2High)
        || (c3Val < fromAttrib->c3Low) || (c3Val > fromAttrib->c3High)) {

        // Return black
        // Will use representation of black from RGBA8888 graphic format
        // and recursively convert it to the requested graphic format.
        color = ColorFract(0.0, 0.0, 0.0);
        hwcTestColorConvert(HAL_PIXEL_FORMAT_RGBA_8888, toFormat, color);
        return;
    }

    // Within from format, convert from fraction of full range
    // to fraction of valid range
    color = ColorFract((c1Val - fromAttrib->c1Low)
                           / (fromAttrib->c1High - fromAttrib->c1Low),
                       (c2Val - fromAttrib->c2Low)
                           / (fromAttrib->c2High - fromAttrib->c2Low),
                       (c3Val - fromAttrib->c3Low)
                           / (fromAttrib->c3High - fromAttrib->c3Low));

    // If needed perform RGB to YUV conversion
    float wr = 0.2126, wg = 0.7152, wb = 0.0722; // ITU709 recommended constants
    if (fromAttrib->rgb && toAttrib->yuv) {
        float r = color.c1(), g = color.c2(), b = color.c3();
        float y = wr * r + wg * g + wb * b;
        float u = 0.5 * ((b - y) / (1.0 - wb)) + 0.5;
        float v = 0.5 * ((r - y) / (1.0 - wr)) + 0.5;

        // Produce black if color is outside the YUV gamut
        if ((y < 0.0) || (y > 1.0)
            || (u < 0.0) || (u > 1.0)
            || (v < 0.0) || (v > 1.0)) {
            y = 0.0;
            u = v = 0.5;
        }

        color = ColorFract(y, u, v);
    }

    // If needed perform YUV to RGB conversion
    // Equations determined from the ITU709 equations for RGB to YUV
    // conversion, plus the following algebra:
    //
    //   u = 0.5 * ((b - y) / (1.0 - wb)) + 0.5
    //   0.5 * ((b - y) / (1.0 - wb)) = u - 0.5
    //   (b - y) / (1.0 - wb) = 2 * (u - 0.5)
    //   b - y = 2 * (u - 0.5) * (1.0 - wb)
    //   b = 2 * (u - 0.5) * (1.0 - wb) + y
    //
    //   v = 0.5 * ((r -y) / (1.0 - wr)) + 0.5
    //   0.5 * ((r - y) / (1.0 - wr)) = v - 0.5
    //   (r - y) / (1.0 - wr) = 2 * (v - 0.5)
    //   r - y = 2 * (v - 0.5) * (1.0 - wr)
    //   r = 2 * (v - 0.5) * (1.0 - wr) + y
    //
    //   y = wr * r + wg * g + wb * b
    //   wr * r + wg * g + wb * b = y
    //   wg * g = y - wr * r - wb * b
    //   g = (y - wr * r - wb * b) / wg
    if (fromAttrib->yuv && toAttrib->rgb) {
        float y = color.c1(), u = color.c2(), v = color.c3();
        float r = 2.0 * (v - 0.5) * (1.0 - wr) + y;
        float b = 2.0 * (u - 0.5) * (1.0 - wb) + y;
        float g = (y - wr * r - wb * b) / wg;

        // Produce black if color is outside the RGB gamut
        if ((r < 0.0) || (r > 1.0)
            || (g < 0.0) || (g > 1.0)
            || (b < 0.0) || (b > 1.0)) {
            r = g = b = 0.0;
        }

        color = ColorFract(r, g, b);
    }

    // Within to format, convert from fraction of valid range
    // to fraction of full range
    c1Val = (toAttrib->c1Low
        + (float) (toAttrib->c1High - toAttrib->c1Low) * color.c1());
    c2Val = (toAttrib->c1Low
        + (float) (toAttrib->c2High - toAttrib->c2Low) * color.c2());
    c3Val = (toAttrib->c1Low
        + (float) (toAttrib->c3High - toAttrib->c3Low) * color.c3());
    color = ColorFract((float) (c1Val - toAttrib->c1Min)
                           / (float) (toAttrib->c1Max - toAttrib->c1Min),
                       (float) (c2Val - toAttrib->c2Min)
                           / (float) (toAttrib->c2Max - toAttrib->c2Min),
                       (float) (c3Val - toAttrib->c3Min)
                           / (float) (toAttrib->c3Max - toAttrib->c3Min));
}

// TODO: Use PrintGLString, CechckGlError, and PrintEGLConfiguration
//       from libglTest
static void printGLString(const char *name, GLenum s)
{
    const char *v = (const char *) glGetString(s);

    if (v == NULL) {
        testPrintI("GL %s unknown", name);
    } else {
        testPrintI("GL %s = %s", name, v);
    }
}

static void checkEglError(const char* op, EGLBoolean returnVal)
{
    if (returnVal != EGL_TRUE) {
        testPrintE("%s() returned %d", op, returnVal);
    }

    for (EGLint error = eglGetError(); error != EGL_SUCCESS; error
            = eglGetError()) {
        testPrintE("after %s() eglError %s (0x%x)",
                   op, EGLUtils::strerror(error), error);
    }
}

static void checkGlError(const char* op)
{
    for (GLint error = glGetError(); error; error
            = glGetError()) {
        testPrintE("after %s() glError (0x%x)", op, error);
    }
}

static void printEGLConfiguration(EGLDisplay dpy, EGLConfig config)
{

#define X(VAL) {VAL, #VAL}
    struct {EGLint attribute; const char* name;} names[] = {
    X(EGL_BUFFER_SIZE),
    X(EGL_ALPHA_SIZE),
    X(EGL_BLUE_SIZE),
    X(EGL_GREEN_SIZE),
    X(EGL_RED_SIZE),
    X(EGL_DEPTH_SIZE),
    X(EGL_STENCIL_SIZE),
    X(EGL_CONFIG_CAVEAT),
    X(EGL_CONFIG_ID),
    X(EGL_LEVEL),
    X(EGL_MAX_PBUFFER_HEIGHT),
    X(EGL_MAX_PBUFFER_PIXELS),
    X(EGL_MAX_PBUFFER_WIDTH),
    X(EGL_NATIVE_RENDERABLE),
    X(EGL_NATIVE_VISUAL_ID),
    X(EGL_NATIVE_VISUAL_TYPE),
    X(EGL_SAMPLES),
    X(EGL_SAMPLE_BUFFERS),
    X(EGL_SURFACE_TYPE),
    X(EGL_TRANSPARENT_TYPE),
    X(EGL_TRANSPARENT_RED_VALUE),
    X(EGL_TRANSPARENT_GREEN_VALUE),
    X(EGL_TRANSPARENT_BLUE_VALUE),
    X(EGL_BIND_TO_TEXTURE_RGB),
    X(EGL_BIND_TO_TEXTURE_RGBA),
    X(EGL_MIN_SWAP_INTERVAL),
    X(EGL_MAX_SWAP_INTERVAL),
    X(EGL_LUMINANCE_SIZE),
    X(EGL_ALPHA_MASK_SIZE),
    X(EGL_COLOR_BUFFER_TYPE),
    X(EGL_RENDERABLE_TYPE),
    X(EGL_CONFORMANT),
   };
#undef X

    for (size_t j = 0; j < sizeof(names) / sizeof(names[0]); j++) {
        EGLint value = -1;
        EGLint returnVal = eglGetConfigAttrib(dpy, config, names[j].attribute,
                                              &value);
        EGLint error = eglGetError();
        if (returnVal && error == EGL_SUCCESS) {
            testPrintI(" %s: %d (%#x)", names[j].name, value, value);
        }
    }
    testPrintI("");
}
