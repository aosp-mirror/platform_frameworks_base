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
 * Hardware Composer Commit Points
 *
 * Synopsis
 *   hwcCommit [options] graphicFormat ...
 *     options:
 *       -s [width, height] - Starting dimension
 *       -v - Verbose
 *
 *      graphic formats:
 *        RGBA8888 (reference frame default)
 *        RGBX8888
 *        RGB888
 *        RGB565
 *        BGRA8888
 *        RGBA5551
 *        RGBA4444
 *        YV12
 *
 * Description
 *   The Hardware Composer (HWC) Commit test is a benchmark that
 *   discovers the points at which the HWC will commit to rendering an
 *   overlay(s).  Before rendering a set of overlays, the HWC is shown
 *   the list through a prepare call.  During the prepare call the HWC
 *   is able to examine the list and specify which overlays it is able
 *   to handle.  The overlays that it can't handle are typically composited
 *   by a higher level (e.g. Surface Flinger) and then the original list
 *   plus a composit of what HWC passed on are provided back to the HWC
 *   for rendering.
 *
 *   Once an implementation of the HWC has been shipped, a regression would
 *   likely occur if a latter implementation started passing on conditions
 *   that it used to commit to.  The primary purpose of this benchmark
 *   is the automated discovery of the commit points, where an implementation
 *   is on the edge between committing and not committing.  These are commonly
 *   referred to as commit points.  Between implementations changes to the
 *   commit points are allowed, as long as they improve what the HWC commits
 *   to.  Once an implementation of the HWC is shipped, the commit points are
 *   not allowed to regress in future implementations.
 *
 *   This benchmark takes a sampling and then adjusts until it finds a
 *   commit point.  It doesn't exhaustively check all possible conditions,
 *   which do to the number of combinations would be impossible.  Instead
 *   it starts its search from a starting dimension, that can be changed
 *   via the -s option.  The search is also bounded by a set of search
 *   limits, that are hard-coded into a structure of constants named
 *   searchLimits.  Results that happen to reach a searchLimit are prefixed
 *   with >=, so that it is known that the value could possibly be larger.
 *
 *   Measurements are made for each of the graphic formats specified as
 *   positional parameters on the command-line.  If no graphic formats
 *   are specified on the command line, then by default measurements are
 *   made and reported for each of the known graphic format.
 */

#include <algorithm>
#include <assert.h>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <ctime>
#include <iomanip>
#include <istream>
#include <libgen.h>
#include <list>
#include <sched.h>
#include <sstream>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <vector>

#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <ui/FramebufferNativeWindow.h>
#include <ui/GraphicBuffer.h>
#include <ui/EGLUtils.h>

#define LOG_TAG "hwcCommitTest"
#include <utils/Log.h>
#include <testUtil.h>

#include <hardware/hwcomposer.h>

#include <glTestLib.h>
#include <hwc/hwcTestLib.h>

using namespace std;
using namespace android;

// Defaults
const HwcTestDim defaultStartDim = HwcTestDim(100, 100);
const bool defaultVerbose = false;

const uint32_t   defaultFormat = HAL_PIXEL_FORMAT_RGBA_8888;
const int32_t    defaultTransform = 0;
const uint32_t   defaultBlend = HWC_BLENDING_NONE;
const ColorFract defaultColor(0.5, 0.5, 0.5);
const float      defaultAlpha = 1.0; // Opaque
const HwcTestDim defaultSourceDim(1, 1);
const struct hwc_rect defaultSourceCrop = {0, 0, 1, 1};
const struct hwc_rect defaultDisplayFrame = {0, 0, 100, 100};

// Global Constants
const uint32_t printFieldWidth = 2;
const struct searchLimits {
    uint32_t   numOverlays;
    HwcTestDim sourceCrop;
} searchLimits = {
    10,
    HwcTestDim(3000, 2000),
};
const struct transformType {
    const char *desc;
    uint32_t id;
} transformType[] = {
    {"fliph",  HWC_TRANSFORM_FLIP_H},
    {"flipv",  HWC_TRANSFORM_FLIP_V},
    {"rot90",  HWC_TRANSFORM_ROT_90},
    {"rot180", HWC_TRANSFORM_ROT_180},
    {"rot270", HWC_TRANSFORM_ROT_270},
};
const struct blendType {
    const char *desc;
    uint32_t id;
} blendType[] = {
    {"none", HWC_BLENDING_NONE},
    {"premult", HWC_BLENDING_PREMULT},
    {"coverage", HWC_BLENDING_COVERAGE},
};

// Defines
#define MAXCMD               200
#define CMD_STOP_FRAMEWORK   "stop 2>&1"
#define CMD_START_FRAMEWORK  "start 2>&1"

// Macros
#define NUMA(a) (sizeof(a) / sizeof(a [0])) // Num elements in an array

// Local types
class Rectangle {
public:
    Rectangle(uint32_t graphicFormat = defaultFormat,
              HwcTestDim dfDim = HwcTestDim(1, 1),
              HwcTestDim sDim = HwcTestDim(1, 1));
    void setSourceDim(HwcTestDim dim);

    uint32_t     format;
    uint32_t     transform;
    int32_t      blend;
    ColorFract   color;
    float        alpha;
    HwcTestDim   sourceDim;
    struct hwc_rect   sourceCrop;
    struct hwc_rect   displayFrame;
};

class Range {
public:
    Range(void) : _l(0), _u(0) {}
    Range(uint32_t lower, uint32_t upper) : _l(lower), _u(upper) {}
    uint32_t lower(void) { return _l; }
    uint32_t upper(void) { return _u; }

    operator string();

private:
    uint32_t _l; // lower
    uint32_t _u; // upper
};

Range::operator string()
{
    ostringstream out;

    out << '[' << _l << ", " << _u << ']';

    return out.str();
}

class Rational {
public:
    Rational(void) : _n(0), _d(1) {}
    Rational(uint32_t n, uint32_t d) : _n(n), _d(d) {}
    uint32_t numerator(void) { return _n; }
    uint32_t denominator(void) { return _d; }
    void setNumerator(uint32_t numerator) { _n = numerator; }

    bool operator==(const Rational& other) const;
    bool operator!=(const Rational& other) const { return !(*this == other); }
    bool operator<(const Rational& other) const;
    bool operator>(const Rational& other) const {
        return (!(*this == other) && !(*this < other));
    }
    static void double2Rational(double f, Range nRange, Range dRange,
                               Rational& lower, Rational& upper);
        
    operator string() const;
    operator double() const { return (double) _n / (double) _d; }


private:
    uint32_t _n;
    uint32_t _d;
};

// Globals
static const int texUsage = GraphicBuffer::USAGE_HW_TEXTURE |
        GraphicBuffer::USAGE_SW_WRITE_RARELY;
static hwc_composer_device_t *hwcDevice;
static EGLDisplay dpy;
static EGLSurface surface;
static EGLint width, height;
static size_t maxHeadingLen;
static vector<string> formats;

// Measurements
struct meas {
    uint32_t format;
    uint32_t startDimOverlays;
    uint32_t maxNonOverlapping;
    uint32_t maxOverlapping;
    list<uint32_t> transforms;
    list<uint32_t> blends;
    struct displayFrame {
        uint32_t minWidth;
        uint32_t minHeight;
        HwcTestDim minDim;
        uint32_t maxWidth;
        uint32_t maxHeight;
        HwcTestDim maxDim;
    } df;
    struct sourceCrop {
        uint32_t minWidth;
        uint32_t minHeight;
        HwcTestDim minDim;
        uint32_t maxWidth;
        uint32_t maxHeight;
        HwcTestDim maxDim;
        Rational hScale;
        HwcTestDim hScaleBestDf;
        HwcTestDim hScaleBestSc;
        Rational vScale;
        HwcTestDim vScaleBestDf;
        HwcTestDim vScaleBestSc;
    } sc;
    vector<uint32_t> overlapBlendNone;
    vector<uint32_t> overlapBlendPremult;
    vector<uint32_t> overlapBlendCoverage;
};
vector<meas> measurements;

// Function prototypes
uint32_t numOverlays(list<Rectangle>& rectList);
uint32_t maxOverlays(uint32_t format, bool allowOverlap);
list<uint32_t> supportedTransforms(uint32_t format);
list<uint32_t> supportedBlends(uint32_t format);
uint32_t dfMinWidth(uint32_t format);
uint32_t dfMinHeight(uint32_t format);
uint32_t dfMaxWidth(uint32_t format);
uint32_t dfMaxHeight(uint32_t format);
HwcTestDim dfMinDim(uint32_t format);
HwcTestDim dfMaxDim(uint32_t format);
uint32_t scMinWidth(uint32_t format, const HwcTestDim& dfDim);
uint32_t scMinHeight(uint32_t format, const HwcTestDim& dfDim);
uint32_t scMaxWidth(uint32_t format, const HwcTestDim& dfDim);
uint32_t scMaxHeight(uint32_t format, const HwcTestDim& dfDim);
HwcTestDim scMinDim(uint32_t format, const HwcTestDim& dfDim);
HwcTestDim scMaxDim(uint32_t format, const HwcTestDim& dfDim);
Rational scHScale(uint32_t format,
                  const HwcTestDim& dfMin, const HwcTestDim& dfMax,
                  const HwcTestDim& scMin, const HwcTestDim& scMax,
                  HwcTestDim& outBestDf, HwcTestDim& outBestSc);
Rational scVScale(uint32_t format,
                  const HwcTestDim& dfMin, const HwcTestDim& dfMax,
                  const HwcTestDim& scMin, const HwcTestDim& scMax,
                  HwcTestDim& outBestDf, HwcTestDim& outBestSc);
uint32_t numOverlapping(uint32_t backgroundFormat, uint32_t foregroundFormat,
                        uint32_t backgroundBlend, uint32_t foregroundBlend);
string transformList2str(const list<uint32_t>& transformList);
string blendList2str(const list<uint32_t>& blendList);
void init(void);
void printFormatHeadings(size_t indent);
void printOverlapLine(size_t indent, const string formatStr,
                      const vector<uint32_t>& results);
void printSyntax(const char *cmd);

// Command-line option settings
static bool verbose = defaultVerbose;
static HwcTestDim startDim = defaultStartDim;

/*
 * Main
 *
 * Performs the following high-level sequence of operations:
 *
 *   1. Command-line parsing
 *
 *   2. Form a list of command-line specified graphic formats.  If
 *      no formats are specified, then form a list of all known formats.
 *
 *   3. Stop framework
 *      Only one user at a time is allowed to use the HWC.  Surface
 *      Flinger uses the HWC and is part of the framework.  Need to
 *      stop the framework so that Surface Flinger will stop using
 *      the HWC.
 *   
 *   4. Initialization
 *
 *   5. For each graphic format in the previously formed list perform
 *      measurements on that format and report the results.
 *
 *   6. Start framework
 */
int
main(int argc, char *argv[])
{
    int     rv, opt;
    char   *chptr;
    bool    error;
    string  str;
    char cmd[MAXCMD];
    list<Rectangle> rectList;

    testSetLogCatTag(LOG_TAG);

    // Parse command line arguments
    while ((opt = getopt(argc, argv, "s:v?h")) != -1) {
        switch (opt) {

          case 's': // Start Dimension
            // Use arguments until next starts with a dash
            // or current ends with a > or ]
            str = optarg;
            while (optind < argc) {
                if (*argv[optind] == '-') { break; }
                char endChar = (str.length() > 1) ? str[str.length() - 1] : 0;
                if ((endChar == '>') || (endChar == ']')) { break; }
                str += " " + string(argv[optind++]);
            }
            {
                istringstream in(str);
                startDim = hwcTestParseDim(in, error);
                // Any parse error or characters not used by parser
                if (error
                    || (((unsigned int) in.tellg() != in.str().length())
                        && (in.tellg() != (streampos) -1))) {
                    testPrintE("Invalid command-line specified start "
                               "dimension of: %s", str.c_str());
                    exit(8);
                }
            }
            break;

          case 'v': // Verbose
            verbose = true;
            break;

          case 'h': // Help
          case '?':
          default:
            printSyntax(basename(argv[0]));
            exit(((optopt == 0) || (optopt == '?')) ? 0 : 11);
        }
    }

    // Positional parameters
    // Positional parameters provide the names of graphic formats that
    // measurements are to be made on.  Measurements are made on all
    // known graphic formats when no positional parameters are provided.
    if (optind == argc) {
        // No command-line specified graphic formats
        // Add all graphic formats to the list of formats to be measured
        for (unsigned int n1 = 0; n1 < NUMA(hwcTestGraphicFormat); n1++) {
            formats.push_back(hwcTestGraphicFormat[n1].desc);
        }
    } else {
        // Add names of command-line specified graphic formats to the
        // list of formats to be tested
        for (; argv[optind] != NULL; optind++) {
            formats.push_back(argv[optind]);
        }
    }

    // Determine length of longest specified graphic format.
    // This value is used for output formating
    for (vector<string>::iterator it = formats.begin();
         it != formats.end(); ++it) {
         maxHeadingLen = max(maxHeadingLen, it->length());
    }

    // Stop framework
    rv = snprintf(cmd, sizeof(cmd), "%s", CMD_STOP_FRAMEWORK);
    if (rv >= (signed) sizeof(cmd) - 1) {
        testPrintE("Command too long for: %s", CMD_STOP_FRAMEWORK);
        exit(14);
    }
    testExecCmd(cmd);
    testDelay(1.0); // TODO - needs means to query whether asynchronous stop
                    // framework operation has completed.  For now, just wait
                    // a long time.

    testPrintI("startDim: %s", ((string) startDim).c_str());

    init();

    // For each of the graphic formats
    for (vector<string>::iterator itFormat = formats.begin();
         itFormat != formats.end(); ++itFormat) {

        // Locate hwcTestLib structure that describes this format
        const struct hwcTestGraphicFormat *format;
        format = hwcTestGraphicFormatLookup((*itFormat).c_str());
        if (format == NULL) {
            testPrintE("Unknown graphic format of: %s", (*itFormat).c_str());
            exit(1);
        }

        // Display format header
        testPrintI("format: %s", format->desc);

        // Create area to hold the measurements
        struct meas meas;
        struct meas *measPtr;
        meas.format = format->format;
        measurements.push_back(meas);
        measPtr = &measurements[measurements.size() - 1];

        // Start dimension num overlays
        Rectangle rect(format->format, startDim);
        rectList.clear();
        rectList.push_back(rect);
        measPtr->startDimOverlays = numOverlays(rectList);
        testPrintI("  startDimOverlays: %u", measPtr->startDimOverlays);

        // Skip the rest of the measurements, when the start dimension
        // doesn't produce an overlay
        if (measPtr->startDimOverlays == 0) { continue; }

        // Max Overlays
        measPtr->maxNonOverlapping = maxOverlays(format->format, false);
        testPrintI("  max nonOverlapping overlays: %s%u",
                   (measPtr->maxNonOverlapping == searchLimits.numOverlays)
                       ? ">= " : "",
                   measPtr->maxNonOverlapping);
        measPtr->maxOverlapping = maxOverlays(format->format, true);
        testPrintI("  max Overlapping overlays: %s%u",
                   (measPtr->maxOverlapping == searchLimits.numOverlays)
                       ? ">= " : "",
                   measPtr->maxOverlapping);

        // Transforms and blends
        measPtr->transforms = supportedTransforms(format->format);
        testPrintI("  transforms: %s",
                   transformList2str(measPtr->transforms).c_str());
        measPtr->blends = supportedBlends(format->format);
        testPrintI("  blends: %s",
                   blendList2str(measPtr->blends).c_str());

        // Display frame measurements
        measPtr->df.minWidth = dfMinWidth(format->format);
        testPrintI("  dfMinWidth: %u", measPtr->df.minWidth);

        measPtr->df.minHeight = dfMinHeight(format->format);
        testPrintI("  dfMinHeight: %u", measPtr->df.minHeight);

        measPtr->df.maxWidth = dfMaxWidth(format->format);
        testPrintI("  dfMaxWidth: %u", measPtr->df.maxWidth);

        measPtr->df.maxHeight = dfMaxHeight(format->format);
        testPrintI("  dfMaxHeight: %u", measPtr->df.maxHeight);

        measPtr->df.minDim = dfMinDim(format->format);
        testPrintI("  dfMinDim: %s", ((string) measPtr->df.minDim).c_str());

        measPtr->df.maxDim = dfMaxDim(format->format);
        testPrintI("  dfMaxDim: %s", ((string) measPtr->df.maxDim).c_str());

        // Source crop measurements
        measPtr->sc.minWidth = scMinWidth(format->format, measPtr->df.minDim);
        testPrintI("  scMinWidth: %u", measPtr->sc.minWidth);

        measPtr->sc.minHeight = scMinHeight(format->format, measPtr->df.minDim);
        testPrintI("  scMinHeight: %u", measPtr->sc.minHeight);

        measPtr->sc.maxWidth = scMaxWidth(format->format, measPtr->df.maxDim);
        testPrintI("  scMaxWidth: %s%u", (measPtr->sc.maxWidth
                   == searchLimits.sourceCrop.width()) ? ">= " : "",
                   measPtr->sc.maxWidth);

        measPtr->sc.maxHeight = scMaxHeight(format->format, measPtr->df.maxDim);
        testPrintI("  scMaxHeight: %s%u", (measPtr->sc.maxHeight
                   == searchLimits.sourceCrop.height()) ? ">= " : "",
                   measPtr->sc.maxHeight);

        measPtr->sc.minDim = scMinDim(format->format, measPtr->df.minDim);
        testPrintI("  scMinDim: %s", ((string) measPtr->sc.minDim).c_str());

        measPtr->sc.maxDim = scMaxDim(format->format, measPtr->df.maxDim);
        testPrintI("  scMaxDim: %s%s", ((measPtr->sc.maxDim.width()
                         >= searchLimits.sourceCrop.width())
                         || (measPtr->sc.maxDim.width() >=
                         searchLimits.sourceCrop.height())) ? ">= " : "",
                   ((string) measPtr->sc.maxDim).c_str());

        measPtr->sc.hScale = scHScale(format->format,
                                      measPtr->df.minDim, measPtr->df.maxDim,
                                      measPtr->sc.minDim, measPtr->sc.maxDim,
                                      measPtr->sc.hScaleBestDf,
                                      measPtr->sc.hScaleBestSc);
        testPrintI("  scHScale: %s%f",
                   (measPtr->sc.hScale
                       >= Rational(searchLimits.sourceCrop.width(),
                                   measPtr->df.minDim.width())) ? ">= " : "",
                   (double) measPtr->sc.hScale);
        testPrintI("    HScale Best Display Frame: %s",
                   ((string) measPtr->sc.hScaleBestDf).c_str());
        testPrintI("    HScale Best Source Crop: %s",
                   ((string) measPtr->sc.hScaleBestSc).c_str());

        measPtr->sc.vScale = scVScale(format->format,
                                      measPtr->df.minDim, measPtr->df.maxDim,
                                      measPtr->sc.minDim, measPtr->sc.maxDim,
                                      measPtr->sc.vScaleBestDf,
                                      measPtr->sc.vScaleBestSc);
        testPrintI("  scVScale: %s%f",
                   (measPtr->sc.vScale
                       >= Rational(searchLimits.sourceCrop.height(),
                                   measPtr->df.minDim.height())) ? ">= " : "",
                   (double) measPtr->sc.vScale);
        testPrintI("    VScale Best Display Frame: %s",
                   ((string) measPtr->sc.vScaleBestDf).c_str());
        testPrintI("    VScale Best Source Crop: %s",
                   ((string) measPtr->sc.vScaleBestSc).c_str());

        // Overlap two graphic formats and different blends
        // Results displayed after all overlap measurments with
        // current format in the foreground
        // TODO: make measurments with background blend other than
        //       none.  All of these measurements are done with a
        //       background blend of HWC_BLENDING_NONE, with the
        //       blend type of the foregound being varied.
        uint32_t foregroundFormat = format->format;
        for (vector<string>::iterator it = formats.begin();
             it != formats.end(); ++it) {
            uint32_t num;

            const struct hwcTestGraphicFormat *backgroundFormatPtr
                = hwcTestGraphicFormatLookup((*it).c_str());
            uint32_t backgroundFormat = backgroundFormatPtr->format;

            num = numOverlapping(backgroundFormat, foregroundFormat,
                                 HWC_BLENDING_NONE, HWC_BLENDING_NONE);
            measPtr->overlapBlendNone.push_back(num);

            num = numOverlapping(backgroundFormat, foregroundFormat,
                                 HWC_BLENDING_NONE, HWC_BLENDING_PREMULT);
            measPtr->overlapBlendPremult.push_back(num);

            num = numOverlapping(backgroundFormat, foregroundFormat,
                                 HWC_BLENDING_NONE, HWC_BLENDING_COVERAGE);
            measPtr->overlapBlendCoverage.push_back(num);
        }

    }

    // Display overlap results
    size_t indent = 2;
    testPrintI("overlapping blend: none");
    printFormatHeadings(indent);
    for (vector<string>::iterator it = formats.begin();
         it != formats.end(); ++it) {
        printOverlapLine(indent, *it, measurements[it
                         - formats.begin()].overlapBlendNone);
    }
    testPrintI("");

    testPrintI("overlapping blend: premult");
    printFormatHeadings(indent);
    for (vector<string>::iterator it = formats.begin();
         it != formats.end(); ++it) {
        printOverlapLine(indent, *it, measurements[it
                         - formats.begin()].overlapBlendPremult);
    }
    testPrintI("");

    testPrintI("overlapping blend: coverage");
    printFormatHeadings(indent);
    for (vector<string>::iterator it = formats.begin();
         it != formats.end(); ++it) {
        printOverlapLine(indent, *it, measurements[it
                         - formats.begin()].overlapBlendCoverage);
    }
    testPrintI("");

    // Start framework
    rv = snprintf(cmd, sizeof(cmd), "%s", CMD_START_FRAMEWORK);
    if (rv >= (signed) sizeof(cmd) - 1) {
        testPrintE("Command too long for: %s", CMD_START_FRAMEWORK);
        exit(21);
    }
    testExecCmd(cmd);

    return 0;
}

// Determine the maximum number of overlays that are all of the same format
// that the HWC will commit to.  If allowOverlap is true, then the rectangles
// are laid out on a diagonal starting from the upper left corner.  With
// each rectangle adjust one pixel to the right and one pixel down.
// When allowOverlap is false, the rectangles are tiled in column major
// order.  Note, column major ordering is used so that the initial rectangles
// are all on different horizontal scan rows.  It is common that hardware
// has limits on the number of objects it can handle on any single row.
uint32_t maxOverlays(uint32_t format, bool allowOverlap)
{
    unsigned int max = 0;

    for (unsigned int numRects = 1; numRects <= searchLimits.numOverlays;
         numRects++) {
        list<Rectangle> rectList;

        for (unsigned int x = 0;
             (x + startDim.width()) < (unsigned int) width;
             x += (allowOverlap) ? 1 : startDim.width()) {
            for (unsigned int y = 0;
                 (y + startDim.height()) < (unsigned int) height;
                 y += (allowOverlap) ? 1 : startDim.height()) {
                Rectangle rect(format, startDim, startDim);
                rect.displayFrame.left = x;
                rect.displayFrame.top = y;
                rect.displayFrame.right = x + startDim.width();
                rect.displayFrame.bottom = y + startDim.height();

                rectList.push_back(rect);

                if (rectList.size() >= numRects) { break; }
            }
            if (rectList.size() >= numRects) { break; }
        }

        uint32_t num = numOverlays(rectList);
        if (num > max) { max = num; }
    }

    return max;
}

// Measures what transforms (i.e. flip horizontal, rotate 180) are
// supported by the specified format
list<uint32_t> supportedTransforms(uint32_t format)
{
    list<uint32_t> rv;
    list<Rectangle> rectList;
    Rectangle rect(format, startDim);

    // For each of the transform types
    for (unsigned int idx = 0; idx < NUMA(transformType); idx++) {
        unsigned int id = transformType[idx].id;

        rect.transform = id;
        rectList.clear();
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);

        if (num == 1) {
            rv.push_back(id);
        }
    }

    return rv;
}

// Determines which types of blends (i.e. none, premult, coverage) are
// supported by the specified format
list<uint32_t> supportedBlends(uint32_t format)
{
    list<uint32_t> rv;
    list<Rectangle> rectList;
    Rectangle rect(format, startDim);

    // For each of the blend types
    for (unsigned int idx = 0; idx < NUMA(blendType); idx++) {
        unsigned int id = blendType[idx].id;

        rect.blend = id;
        rectList.clear();
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);

        if (num == 1) {
            rv.push_back(id);
        }
    }

    return rv;
}

// Determines the minimum width of any display frame of the given format
// that the HWC will commit to.
uint32_t dfMinWidth(uint32_t format)
{
    uint32_t w;
    list<Rectangle> rectList;

    for (w = 1; w <= startDim.width(); w++) {
        HwcTestDim dim(w, startDim.height());
        Rectangle rect(format, dim);
        rectList.clear();
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);
        if (num > 0) {
            return w;
        }
    }
    if (w > startDim.width()) {
        testPrintE("Failed to locate display frame min width");
        exit(33);
    }

    return w;
}

// Display frame minimum height
uint32_t dfMinHeight(uint32_t format)
{
    uint32_t h;
    list<Rectangle> rectList;

    for (h = 1; h <= startDim.height(); h++) {
        HwcTestDim dim(startDim.width(), h);
        Rectangle rect(format, dim);
        rectList.clear();
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);
        if (num > 0) {
            return h;
        }
    }
    if (h > startDim.height()) {
        testPrintE("Failed to locate display frame min height");
        exit(34);
    }

    return h;
}

// Display frame maximum width
uint32_t dfMaxWidth(uint32_t format)
{
    uint32_t w;
    list<Rectangle> rectList;

    for (w = width; w >= startDim.width(); w--) {
        HwcTestDim dim(w, startDim.height());
        Rectangle rect(format, dim);
        rectList.clear();
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);
        if (num > 0) {
            return w;
        }
    }
    if (w < startDim.width()) {
        testPrintE("Failed to locate display frame max width");
        exit(35);
    }

    return w;
}

// Display frame maximum height
uint32_t dfMaxHeight(uint32_t format)
{
    uint32_t h;

    for (h = height; h >= startDim.height(); h--) {
        HwcTestDim dim(startDim.width(), h);
        Rectangle rect(format, dim);
        list<Rectangle> rectList;
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);
        if (num > 0) {
            return h;
        }
    }
    if (h < startDim.height()) {
        testPrintE("Failed to locate display frame max height");
        exit(36);
    }

    return h;
}

// Determine the minimum number of pixels that the HWC will ever commit to.
// Note, this might be different that dfMinWidth * dfMinHeight, in that this
// function adjusts both the width and height from the starting dimension.
HwcTestDim dfMinDim(uint32_t format)
{
    uint64_t bestMinPixels = 0;
    HwcTestDim bestDim;
    bool bestSet = false; // True when value has been assigned to
                          // bestMinPixels and bestDim

    bool origVerbose = verbose;  // Temporarily turn off verbose
    verbose = false;
    for (uint32_t w = 1; w <= startDim.width(); w++) {
        for (uint32_t h = 1; h <= startDim.height(); h++) {
            if (bestSet && ((w > bestMinPixels) || (h > bestMinPixels))) {
                break;
            }

            HwcTestDim dim(w, h);
            Rectangle rect(format, dim);
            list<Rectangle> rectList;
            rectList.push_back(rect);
            uint32_t num = numOverlays(rectList);
            if (num > 0) {
                uint64_t pixels = dim.width() * dim.height();
                if (!bestSet || (pixels < bestMinPixels)) {
                    bestMinPixels = pixels;
                    bestDim = dim;
                    bestSet = true;
                }
            }
        }
    }
    verbose = origVerbose;

    if (!bestSet) {
        testPrintE("Unable to locate display frame min dimension");
        exit(20);
    }

    return bestDim;
}

// Display frame maximum dimension
HwcTestDim dfMaxDim(uint32_t format)
{
    uint64_t bestMaxPixels = 0;
    HwcTestDim bestDim;
    bool bestSet = false; // True when value has been assigned to
                          // bestMaxPixels and bestDim;

    // Potentially increase benchmark performance by first checking
    // for the common case of supporting a full display frame.
    HwcTestDim dim(width, height);
    Rectangle rect(format, dim);
    list<Rectangle> rectList;
    rectList.push_back(rect);
    uint32_t num = numOverlays(rectList);
    if (num == 1) { return dim; }

    // TODO: Use a binary search
    bool origVerbose = verbose;  // Temporarily turn off verbose
    verbose = false;
    for (uint32_t w = startDim.width(); w <= (uint32_t) width; w++) {
        for (uint32_t h = startDim.height(); h <= (uint32_t) height; h++) {
            if (bestSet && ((w * h) <= bestMaxPixels)) { continue; }

            HwcTestDim dim(w, h);
            Rectangle rect(format, dim);
            list<Rectangle> rectList;
            rectList.push_back(rect);
            uint32_t num = numOverlays(rectList);
            if (num > 0) {
                uint64_t pixels = dim.width() * dim.height();
                if (!bestSet || (pixels > bestMaxPixels)) {
                    bestMaxPixels = pixels;
                    bestDim = dim;
                    bestSet = true;
                }
            }
        }
    }
    verbose = origVerbose;

    if (!bestSet) {
        testPrintE("Unable to locate display frame max dimension");
        exit(21);
    }

    return bestDim;
}

// Source crop minimum width
uint32_t scMinWidth(uint32_t format, const HwcTestDim& dfDim)
{
    uint32_t w;
    list<Rectangle> rectList;

    // Source crop frame min width
    for (w = 1; w <= dfDim.width(); w++) {
        Rectangle rect(format, dfDim, HwcTestDim(w, dfDim.height()));
        rectList.clear();
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);
        if (num > 0) {
            return w;
        }
    }
    testPrintE("Failed to locate source crop min width");
    exit(35);
}

// Source crop minimum height
uint32_t scMinHeight(uint32_t format, const HwcTestDim& dfDim)
{
    uint32_t h;
    list<Rectangle> rectList;

    for (h = 1; h <= dfDim.height(); h++) {
        Rectangle rect(format, dfDim, HwcTestDim(dfDim.width(), h));
        rectList.clear();
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);
        if (num > 0) {
            return h;
        }
    }
    testPrintE("Failed to locate source crop min height");
    exit(36);
}

// Source crop maximum width
uint32_t scMaxWidth(uint32_t format, const HwcTestDim& dfDim)
{
    uint32_t w;
    list<Rectangle> rectList;

    for (w = searchLimits.sourceCrop.width(); w >= dfDim.width(); w--) {
        Rectangle rect(format, dfDim, HwcTestDim(w, dfDim.height()));
        rectList.clear();
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);
        if (num > 0) {
            return w;
        }
    }
    testPrintE("Failed to locate source crop max width");
    exit(35);
}

// Source crop maximum height
uint32_t scMaxHeight(uint32_t format, const HwcTestDim& dfDim)
{
    uint32_t h;
    list<Rectangle> rectList;

    for (h = searchLimits.sourceCrop.height(); h >= dfDim.height(); h--) {
        Rectangle rect(format, dfDim, HwcTestDim(dfDim.width(), h));
        rectList.clear();
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);
        if (num > 0) {
            return h;
        }
    }
    testPrintE("Failed to locate source crop max height");
    exit(36);
}

// Source crop minimum dimension
// Discovers the source crop with the least number of pixels that the
// HWC will commit to.  Note, this may be different from scMinWidth
// * scMinHeight, in that this function searches for a combination of
// width and height.  While the other routines always keep one of the
// dimensions equal to the corresponding start dimension.
HwcTestDim scMinDim(uint32_t format, const HwcTestDim& dfDim)
{
    uint64_t bestMinPixels = 0;
    HwcTestDim bestDim;
    bool bestSet = false; // True when value has been assigned to
                          // bestMinPixels and bestDim

    bool origVerbose = verbose;  // Temporarily turn off verbose
    verbose = false;
    for (uint32_t w = 1; w <= dfDim.width(); w++) {
        for (uint32_t h = 1; h <= dfDim.height(); h++) {
            if (bestSet && ((w > bestMinPixels) || (h > bestMinPixels))) {
                break;
            }

            HwcTestDim dim(w, h);
            Rectangle rect(format, dfDim, HwcTestDim(w, h));
            list<Rectangle> rectList;
            rectList.push_back(rect);
            uint32_t num = numOverlays(rectList);
            if (num > 0) {
                uint64_t pixels = dim.width() * dim.height();
                if (!bestSet || (pixels < bestMinPixels)) {
                    bestMinPixels = pixels;
                    bestDim = dim;
                    bestSet = true;
                }
            }
        }
    }
    verbose = origVerbose;

    if (!bestSet) {
        testPrintE("Unable to locate source crop min dimension");
        exit(20);
    }

    return bestDim;
}

// Source crop maximum dimension
HwcTestDim scMaxDim(uint32_t format, const HwcTestDim& dfDim)
{
    uint64_t bestMaxPixels = 0;
    HwcTestDim bestDim;
    bool bestSet = false; // True when value has been assigned to
                          // bestMaxPixels and bestDim;

    // Potentially increase benchmark performance by first checking
    // for the common case of supporting the maximum checked source size
    HwcTestDim dim = searchLimits.sourceCrop;
    Rectangle rect(format, dfDim, searchLimits.sourceCrop);
    list<Rectangle> rectList;
    rectList.push_back(rect);
    uint32_t num = numOverlays(rectList);
    if (num == 1) { return dim; }

    // TODO: Use a binary search
    bool origVerbose = verbose;  // Temporarily turn off verbose
    verbose = false;
    for (uint32_t w = dfDim.width();
         w <= searchLimits.sourceCrop.width(); w++) {
        for (uint32_t h = dfDim.height();
             h <= searchLimits.sourceCrop.height(); h++) {
            if (bestSet && ((w * h) <= bestMaxPixels)) { continue; }

            HwcTestDim dim(w, h);
            Rectangle rect(format, dfDim, dim);
            list<Rectangle> rectList;
            rectList.push_back(rect);
            uint32_t num = numOverlays(rectList);
            if (num > 0) {
                uint64_t pixels = dim.width() * dim.height();
                if (!bestSet || (pixels > bestMaxPixels)) {
                    bestMaxPixels = pixels;
                    bestDim = dim;
                    bestSet = true;
                }
            }
        }
    }
    verbose = origVerbose;

    if (!bestSet) {
        testPrintE("Unable to locate source crop max dimension");
        exit(21);
    }

    return bestDim;
}

// Source crop horizontal scale
// Determines the maximum factor by which the source crop can be larger
// that the display frame.  The commit point is discovered through a
// binary search of rational numbers.  The numerator in each of the
// rational numbers contains the dimension for the source crop, while
// the denominator specifies the dimension for the display frame.  On
// each pass of the binary search the mid-point between the greatest
// point committed to (best) and the smallest point in which a commit
// has failed is calculated.  This mid-point is then passed to a function
// named double2Rational, which determines the closest rational numbers
// just below and above the mid-point.  By default the lower rational
// number is used for the scale factor on the next pass of the binary
// search.  The upper value is only used when best is already equal
// to the lower value.  This only occurs when the lower value has already
// been tried.
Rational scHScale(uint32_t format,
                      const HwcTestDim& dfMin, const HwcTestDim& dfMax,
                      const HwcTestDim& scMin, const HwcTestDim& scMax,
                      HwcTestDim& outBestDf, HwcTestDim& outBestSc)
{
    HwcTestDim scDim, dfDim; // Source crop and display frame dimension
    Rational best(0, 1), minBad;  // Current bounds for a binary search
                                  // MinGood is set below the lowest
                                  // possible scale.  The value of minBad,
                                  // will be set by the first pass
                                  // of the binary search.

    // Perform the passes of the binary search
    bool firstPass = true;
    do {
        // On first pass try the maximum scale within the search limits
        if (firstPass) {
            // Try the maximum possible scale, within the search limits
            scDim = HwcTestDim(searchLimits.sourceCrop.width(), scMin.height());
            dfDim = dfMin;
        } else {
            // Subsequent pass
            // Halve the difference between best and minBad.
            Rational lower, upper, selected;

            // Try the closest ratio halfway between minBood and minBad;
            // TODO: Avoid rounding issue by using Rational type for
            //       midpoint.  For now will use double, which should
            //       have more than sufficient resolution.
            double mid = (double) best
                         + ((double) minBad - (double) best) / 2.0;
            Rational::double2Rational(mid,
                            Range(scMin.width(), scMax.width()),
                            Range(dfMin.width(), dfMax.width()),
                            lower, upper);
            if (((lower == best) && (upper == minBad))) {
                return best;
            }

            // Use lower value unless its already been tried
            selected = (lower != best) ? lower : upper;

            // Assign the size of the source crop and display frame
            // from the selected ratio of source crop to display frame.
            scDim = HwcTestDim(selected.numerator(), scMin.height());
            dfDim = HwcTestDim(selected.denominator(), dfMin.height());
        }

        // See if the HWC will commit to this combination
        Rectangle rect(format, dfDim, scDim);
        list<Rectangle> rectList;
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);

        if (verbose) {
            testPrintI("  scHscale num: %u scale: %f dfDim: %s scDim: %s",
                       num, (float) Rational(scDim.width(), dfDim.width()),
                       ((string) dfDim).c_str(), ((string) scDim).c_str());
        }
        if (num == 1) {
            // HWC committed to the combination
            // This is the best scale factor seen so far.  Report the
            // dimensions to the caller, in case nothing better is seen.
            outBestDf = dfDim;
            outBestSc = scDim;

            // Success on the first pass means the largest possible scale
            // is supported, in which case no need to search any further.
            if (firstPass) { return Rational(scDim.width(), dfDim.width()); }

            // Update the lower bound of the binary search
            best = Rational(scDim.width(), dfDim.width());
        } else {
            // HWC didn't commit to this combination, so update the
            // upper bound of the binary search.
            minBad = Rational(scDim.width(), dfDim.width());
        }

        firstPass = false;
    } while (best != minBad);

    return best;
}

// Source crop vertical scale
// Determines the maximum factor by which the source crop can be larger
// that the display frame.  The commit point is discovered through a
// binary search of rational numbers.  The numerator in each of the
// rational numbers contains the dimension for the source crop, while
// the denominator specifies the dimension for the display frame.  On
// each pass of the binary search the mid-point between the greatest
// point committed to (best) and the smallest point in which a commit
// has failed is calculated.  This mid-point is then passed to a function
// named double2Rational, which determines the closest rational numbers
// just below and above the mid-point.  By default the lower rational
// number is used for the scale factor on the next pass of the binary
// search.  The upper value is only used when best is already equal
// to the lower value.  This only occurs when the lower value has already
// been tried.
Rational scVScale(uint32_t format,
                      const HwcTestDim& dfMin, const HwcTestDim& dfMax,
                      const HwcTestDim& scMin, const HwcTestDim& scMax,
                      HwcTestDim& outBestDf, HwcTestDim& outBestSc)
{
    HwcTestDim scDim, dfDim; // Source crop and display frame dimension
    Rational best(0, 1), minBad;  // Current bounds for a binary search
                                  // MinGood is set below the lowest
                                  // possible scale.  The value of minBad,
                                  // will be set by the first pass
                                  // of the binary search.

    // Perform the passes of the binary search
    bool firstPass = true;
    do {
        // On first pass try the maximum scale within the search limits
        if (firstPass) {
            // Try the maximum possible scale, within the search limits
            scDim = HwcTestDim(scMin.width(), searchLimits.sourceCrop.height());
            dfDim = dfMin;
        } else {
            // Subsequent pass
            // Halve the difference between best and minBad.
            Rational lower, upper, selected;

            // Try the closest ratio halfway between minBood and minBad;
            // TODO: Avoid rounding issue by using Rational type for
            //       midpoint.  For now will use double, which should
            //       have more than sufficient resolution.
            double mid = (double) best
                         + ((double) minBad - (double) best) / 2.0;
            Rational::double2Rational(mid,
                            Range(scMin.height(), scMax.height()),
                            Range(dfMin.height(), dfMax.height()),
                            lower, upper);
            if (((lower == best) && (upper == minBad))) {
                return best;
            }

            // Use lower value unless its already been tried
            selected = (lower != best) ? lower : upper;

            // Assign the size of the source crop and display frame
            // from the selected ratio of source crop to display frame.
            scDim = HwcTestDim(scMin.width(), selected.numerator());
            dfDim = HwcTestDim(dfMin.width(), selected.denominator());
        }

        // See if the HWC will commit to this combination
        Rectangle rect(format, dfDim, scDim);
        list<Rectangle> rectList;
        rectList.push_back(rect);
        uint32_t num = numOverlays(rectList);

        if (verbose) {
            testPrintI("  scHscale num: %u scale: %f dfDim: %s scDim: %s",
                       num, (float) Rational(scDim.height(), dfDim.height()),
                       ((string) dfDim).c_str(), ((string) scDim).c_str());
        }
        if (num == 1) {
            // HWC committed to the combination
            // This is the best scale factor seen so far.  Report the
            // dimensions to the caller, in case nothing better is seen.
            outBestDf = dfDim;
            outBestSc = scDim;

            // Success on the first pass means the largest possible scale
            // is supported, in which case no need to search any further.
            if (firstPass) { return Rational(scDim.height(), dfDim.height()); }

            // Update the lower bound of the binary search
            best = Rational(scDim.height(), dfDim.height());
        } else {
            // HWC didn't commit to this combination, so update the
            // upper bound of the binary search.
            minBad = Rational(scDim.height(), dfDim.height());
        }

        firstPass = false;
    } while (best != minBad);

    return best;
}

uint32_t numOverlapping(uint32_t backgroundFormat, uint32_t foregroundFormat,
                        uint32_t backgroundBlend, uint32_t foregroundBlend)
{
    list<Rectangle> rectList;

    Rectangle background(backgroundFormat, startDim, startDim);
    background.blend = backgroundBlend;
    rectList.push_back(background);

    // TODO: Handle cases where startDim is so small that adding 5
    //       causes frames not to overlap.
    // TODO: Handle cases where startDim is so large that adding 5
    //       cause a portion or all of the foreground displayFrame
    //       to be off the display.
    Rectangle foreground(foregroundFormat, startDim, startDim);
    foreground.displayFrame.left += 5;
    foreground.displayFrame.top += 5;
    foreground.displayFrame.right += 5;
    foreground.displayFrame.bottom += 5;
    background.blend = foregroundBlend;
    rectList.push_back(foreground);

    uint32_t num = numOverlays(rectList);

    return num;
}

Rectangle::Rectangle(uint32_t graphicFormat, HwcTestDim dfDim,
                     HwcTestDim sDim) :
    format(graphicFormat), transform(defaultTransform),
    blend(defaultBlend), color(defaultColor), alpha(defaultAlpha),
    sourceCrop(sDim), displayFrame(dfDim)
{
    // Set source dimension
    // Can't use a base initializer, because the setting of format
    // must be done before setting the sourceDimension.
    setSourceDim(sDim);
}

void Rectangle::setSourceDim(HwcTestDim dim)
{
    this->sourceDim = dim;

    const struct hwcTestGraphicFormat *attrib;
    attrib = hwcTestGraphicFormatLookup(this->format);
    if (attrib != NULL) {
        if (sourceDim.width() % attrib->wMod) {
            sourceDim.setWidth(sourceDim.width() + attrib->wMod
            - (sourceDim.width() % attrib->wMod));
        }
        if (sourceDim.height() % attrib->hMod) {
            sourceDim.setHeight(sourceDim.height() + attrib->hMod
            - (sourceDim.height() % attrib->hMod));
        }
    }
}

// Rational member functions
bool Rational::operator==(const Rational& other) const
{
    if (((uint64_t) _n * other._d)
        == ((uint64_t) _d * other._n)) { return true; }

    return false;
}

bool Rational::operator<(const Rational& other) const
{
    if (((uint64_t) _n * other._d)
        < ((uint64_t) _d * other._n)) { return true; }

    return false;
}

Rational::operator string() const
{
    ostringstream out;

    out << _n << '/' << _d;

    return out.str();
}

void Rational::double2Rational(double f, Range nRange, Range dRange,
                    Rational& lower, Rational& upper)
{
    Rational bestLower(nRange.lower(), dRange.upper());
    Rational bestUpper(nRange.upper(), dRange.lower());

    // Search for a better solution
    for (uint32_t d = dRange.lower(); d <= dRange.upper(); d++) {
        Rational val(d * f, d);  // Lower, because double to int cast truncates

        if ((val.numerator() < nRange.lower())
            || (val.numerator() > nRange.upper())) { continue; }

        if (((double) val > (double) bestLower) && ((double) val <= f)) {
            bestLower = val;
        } 

        val.setNumerator(val.numerator() + 1);
        if (val.numerator() > nRange.upper()) { continue; }

        if (((double) val < (double) bestUpper) && ((double) val >= f)) {
            bestUpper = val;
        }
    }

    lower = bestLower;
    upper = bestUpper;
}

// Local functions

// Num Overlays
// Given a list of rectangles, determine how many HWC will commit to render
uint32_t numOverlays(list<Rectangle>& rectList)
{
    hwc_layer_list_t *hwcList;
    list<sp<GraphicBuffer> > buffers;

    hwcList = hwcTestCreateLayerList(rectList.size());
    if (hwcList == NULL) {
        testPrintE("numOverlays create hwcList failed");
        exit(30);
    }

    hwc_layer_t *layer = &hwcList->hwLayers[0];
    for (std::list<Rectangle>::iterator it = rectList.begin();
         it != rectList.end(); ++it, ++layer) {
        // Allocate the texture for the source frame
        // and push it onto the buffers list, so that it
        // stays in scope until a return from this function.
        sp<GraphicBuffer> texture;
        texture  = new GraphicBuffer(it->sourceDim.width(),
                                     it->sourceDim.height(),
                                     it->format, texUsage);
        buffers.push_back(texture);

        layer->handle = texture->handle;
        layer->blending = it->blend;
        layer->transform = it->transform;
        layer->sourceCrop = it->sourceCrop;
        layer->displayFrame = it->displayFrame;

        layer->visibleRegionScreen.numRects = 1;
        layer->visibleRegionScreen.rects = &layer->displayFrame;
    }

    // Perform prepare operation
    if (verbose) { testPrintI("Prepare:"); hwcTestDisplayList(hwcList); }
    hwcDevice->prepare(hwcDevice, hwcList);
    if (verbose) {
        testPrintI("Post Prepare:");
        hwcTestDisplayListPrepareModifiable(hwcList);
    }

    // Count the number of overlays
    uint32_t total = 0;
    for (unsigned int n1 = 0; n1 < hwcList->numHwLayers; n1++) {
        if (hwcList->hwLayers[n1].compositionType == HWC_OVERLAY) {
            total++;
        }
    }

    // Free the layer list and graphic buffers
    hwcTestFreeLayerList(hwcList);

    return total;
}

string transformList2str(const list<uint32_t>& transformList)
{
    ostringstream out;

    for (list<uint32_t>::const_iterator it = transformList.begin();
         it != transformList.end(); ++it) {
        uint32_t id = *it;

        if (it != transformList.begin()) {
            out << ", ";
        }
        out << id;

        for (unsigned int idx = 0; idx < NUMA(transformType); idx++) {
            if (id == transformType[idx].id) {
                out << " (" << transformType[idx].desc << ')';
                break;
            }
        }
    }

    return out.str();
}

string blendList2str(const list<uint32_t>& blendList)
{
    ostringstream out;

    for (list<uint32_t>::const_iterator it = blendList.begin();
         it != blendList.end(); ++it) {
        uint32_t id = *it;

        if (it != blendList.begin()) {
            out << ", ";
        }
        out << id;

        for (unsigned int idx = 0; idx < NUMA(blendType); idx++) {
            if (id == blendType[idx].id) {
                out << " (" << blendType[idx].desc << ')';
                break;
            }
        }
    }

    return out.str();
}

void init(void)
{
    srand48(0);

    hwcTestInitDisplay(verbose, &dpy, &surface, &width, &height);

    hwcTestOpenHwc(&hwcDevice);
}

void printFormatHeadings(size_t indent)
{
    for (size_t row = 0; row <= maxHeadingLen; row++) {
        ostringstream line;
        for(vector<string>::iterator it = formats.begin();
            it != formats.end(); ++it) {
            if ((maxHeadingLen - row) <= it->length()) {
                if (row != maxHeadingLen) {
                    char ch = (*it)[it->length() - (maxHeadingLen - row)];
                    line << ' ' << setw(printFieldWidth) << ch;
                } else {
                    line << ' ' << string(printFieldWidth, '-');
                }
            } else {
               line << ' ' << setw(printFieldWidth) << "";
            }
        }
        testPrintI("%*s%s", indent + maxHeadingLen, "",
                   line.str().c_str());
    }
}

void printOverlapLine(size_t indent, const string formatStr,
                        const vector<uint32_t>& results)
{
    ostringstream line;

    line << setw(indent + maxHeadingLen - formatStr.length()) << "";

    line << formatStr;

    for (vector<uint32_t>::const_iterator it = results.begin();
         it != results.end(); ++it) {
        line << ' ' << setw(printFieldWidth) << *it;
    }

    testPrintI("%s", line.str().c_str());
}

void printSyntax(const char *cmd)
{
    testPrintE("  %s [options] [graphicFormat] ...",
               cmd);
    testPrintE("    options:");
    testPrintE("      -s [width, height] - start dimension");
    testPrintE("      -v - Verbose");
    testPrintE("");
    testPrintE("    graphic formats:");
    for (unsigned int n1 = 0; n1 < NUMA(hwcTestGraphicFormat); n1++) {
        testPrintE("      %s", hwcTestGraphicFormat[n1].desc);
    }
}
