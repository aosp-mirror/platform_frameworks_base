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
 */

/*
 * Hardware Composer Rectangles
 *
 * Synopsis
 *   hwcRects [options] (graphicFormat displayFrame [attributes],)...
 *     options:
 *       -D #.## - End of test delay
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
 *      displayFrame
 *        [left, top, right, bottom]
 *
 *      attributes:
 *        transform: none | fliph | flipv | rot90 | rot180 | rot270
 *        blend: none | premult | coverage
 *        color: [0.##, 0.##, 0.##]
 *        alpha: 0.##
 *        sourceDim: [width, height]
 *        sourceCrop: [left, top, right, bottom]
 *
 *      Example:
 *        # White YV12 rectangle, with overlapping turquoise
 *        #  RGBA8888 rectangle at 30%% (alpha: 0.7) transparency
 *        hwcRects -v -D 30.0 \
 *          YV12 [50, 80, 200, 300] transform: none \
 *            color: [1.0, 0.5, 0.5], \
 *          RGBA8888 [100, 150, 300, 400] blend: coverage \
 *            color: [0.251, 0.878, 0.816] alpha: 0.7 \
 *            sourceDim: [50, 60] sourceCrop: [5, 8, 12, 15]
 *
 * Description
 *   Constructs a Hardware Composer (HWC) list of frames from
 *   command-line specified parameters.  Then sends it to the HWC
 *   be rendered.  The intended purpose of this tool is as a means to
 *   reproduce and succinctly specify an observed HWC operation, with
 *   no need to modify/compile a program.
 *
 *   The command-line syntax consists of a few standard command-line
 *   options and then a description of one or more frames.  The frame
 *   descriptions are separated from one another via a comma.  The
 *   beginning of a frame description requires the specification
 *   of the graphic format and then the display frame rectangle where
 *   the frame will be displayed.  The display frame rectangle is
 *   specified as follows, with the right and bottom coordinates being
 *   exclusive values:
 *
 *     [left, top, right, bottom]
 *    
 *   After these two required parameters each frame description can
 *   specify 1 or more optional attributes.  The name of each optional
 *   attribute is preceded by a colon.  The current implementation
 *   then requires white space after the colon and then the value of
 *   the attribute is specified.  See the synopsis section above for
 *   a list of attributes and the format of their expected value.
 */

#include <algorithm>
#include <assert.h>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <ctime>
#include <istream>
#include <libgen.h>
#include <list>
#include <sched.h>
#include <sstream>
#include <stdint.h>
#include <string.h>
#include <unistd.h>

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

#define LOG_TAG "hwcRectsTest"
#include <utils/Log.h>
#include <testUtil.h>

#include <hardware/hwcomposer.h>

#include <glTestLib.h>
#include <hwc/hwcTestLib.h>

using namespace std;
using namespace android;

// Defaults
const bool defaultVerbose = false;
const float defaultEndDelay = 2.0; // Default delay after rendering graphics

const uint32_t   defaultFormat = HAL_PIXEL_FORMAT_RGBA_8888;
const int32_t    defaultTransform = 0;
const uint32_t   defaultBlend = HWC_BLENDING_NONE;
const ColorFract defaultColor(0.5, 0.5, 0.5);
const float      defaultAlpha = 1.0; // Opaque
const HwcTestDim defaultSourceDim(1, 1);
const struct hwc_rect defaultSourceCrop = {0, 0, 1, 1};
const struct hwc_rect defaultDisplayFrame = {0, 0, 100, 100};

// Defines
#define MAXCMD               200
#define CMD_STOP_FRAMEWORK   "stop 2>&1"
#define CMD_START_FRAMEWORK  "start 2>&1"

// Macros
#define NUMA(a) (sizeof(a) / sizeof(a [0])) // Num elements in an array

// Local types
class Rectangle {
public:
    Rectangle() : format(defaultFormat), transform(defaultTransform),
                  blend(defaultBlend), color(defaultColor),
                  alpha(defaultAlpha), sourceDim(defaultSourceDim),
                  sourceCrop(defaultSourceCrop),
                  displayFrame(defaultDisplayFrame) {};

    uint32_t     format;
    uint32_t     transform;
    int32_t      blend;
    ColorFract   color;
    float        alpha;
    HwcTestDim   sourceDim;
    struct hwc_rect   sourceCrop;
    struct hwc_rect   displayFrame;

    sp<GraphicBuffer> texture;
};

// Globals
list<Rectangle> rectangle;
static const int texUsage = GraphicBuffer::USAGE_HW_TEXTURE |
        GraphicBuffer::USAGE_SW_WRITE_RARELY;
static hwc_composer_device_t *hwcDevice;
static EGLDisplay dpy;
static EGLSurface surface;
static EGLint width, height;

// Function prototypes
static Rectangle parseRect(string rectStr);
void init(void);
void printSyntax(const char *cmd);

// Command-line option settings
static bool verbose = defaultVerbose;
static float endDelay = defaultEndDelay;

/*
 * Main
 *
 * Performs the following high-level sequence of operations:
 *
 *   1. Parse command-line options
 *
 *   2. Stop framework
 *
 *   3. Initialization
 *
 *   4. Parse frame descriptions
 *
 *   5. Create HWC list from frame descriptions
 *
 *   6. Have HWC render the list description of the frames
 *
 *   7. Delay for amount of time given by endDelay
 *
 *   8. Start framework
 */
int
main(int argc, char *argv[])
{
    int     rv, opt;
    char   *chptr;
    bool    error;
    string  str;
    char cmd[MAXCMD];

    testSetLogCatTag(LOG_TAG);

    // Parse command line arguments
    while ((opt = getopt(argc, argv, "D:v?h")) != -1) {
        switch (opt) {
          case 'D': // End of test delay
            endDelay = strtod(optarg, &chptr);
            if ((*chptr != '\0') || (endDelay < 0.0)) {
                testPrintE("Invalid command-line specified end of test delay "
                           "of: %s", optarg);
                exit(1);
            }
            break;

          case 'v': // Verbose
            verbose = true;
            break;

          case 'h': // Help
          case '?':
          default:
            printSyntax(basename(argv[0]));
            exit(((optopt == 0) || (optopt == '?')) ? 0 : 2);
        }
    }

    // Stop framework
    rv = snprintf(cmd, sizeof(cmd), "%s", CMD_STOP_FRAMEWORK);
    if (rv >= (signed) sizeof(cmd) - 1) {
        testPrintE("Command too long for: %s", CMD_STOP_FRAMEWORK);
        exit(3);
    }
    testExecCmd(cmd);
    testDelay(1.0); // TODO - needs means to query whether asyncronous stop
                    // framework operation has completed.  For now, just wait
                    // a long time.

    init();

    // Parse rectangle descriptions
    int numOpen = 0; // Current number of unmatched <[
    string rectDesc(""); // String description of a single rectangle
    while (optind < argc) {
        string argNext = string(argv[optind++]);

        if (rectDesc.length()) { rectDesc += ' '; }
        rectDesc += argNext;

        // Count number of opening <[ and matching >]
        // At this point not worried about an opening character being
        // matched by it's corresponding closing character.  For example,
        // "<1.0, 2.0]" is incorrect because the opening < should be matched
        // with a closing >, instead of the closing ].  Such errors are
        // detected when the actual value is parsed.
        for (unsigned int n1 = 0; n1 < argNext.length(); n1++) {
            switch(argNext[n1]) {
              case '[':
              case '<':
                numOpen++;
                break;

              case ']':
              case '>':
                numOpen--;
                break;
            }

            // Error anytime there is more closing then opening characters
            if (numOpen < 0) {
                testPrintI("Mismatched number of opening <[ with "
                           "closing >] in: %s", rectDesc.c_str());
                exit(4);
            }
        }

        // Description of a rectangle is complete when all opening
        // <[ are closed with >] and the string ends with a comma or
        // there are no more args.
        if ((numOpen == 0) && rectDesc.length()
            && ((rectDesc[rectDesc.length() - 1] == ',')
                || (optind == argc))) {
            // Remove trailing comma if it is present
            if (rectDesc[rectDesc.length() - 1] == ',') {
                rectDesc.erase(rectDesc.length() - 1);
            }

            // Parse string description of rectangle
            Rectangle rect = parseRect(rectDesc);

            // Add to the list of rectangles
            rectangle.push_back(rect);

            // Prepare for description of another rectangle
            rectDesc = string("");
        }
    }

    // Create list of frames
    hwc_layer_list_t *list;
    list = hwcTestCreateLayerList(rectangle.size());
    if (list == NULL) {
        testPrintE("hwcTestCreateLayerList failed");
        exit(5);
    }

    hwc_layer_t *layer = &list->hwLayers[0];
    for (std::list<Rectangle>::iterator it = rectangle.begin();
         it != rectangle.end(); ++it, ++layer) {
        layer->handle = it->texture->handle;
        layer->blending = it->blend;
        layer->transform = it->transform;
        layer->sourceCrop = it->sourceCrop;
        layer->displayFrame = it->displayFrame;

        layer->visibleRegionScreen.numRects = 1;
        layer->visibleRegionScreen.rects = &layer->displayFrame;
    }

    // Perform prepare operation
    if (verbose) { testPrintI("Prepare:"); hwcTestDisplayList(list); }
    hwcDevice->prepare(hwcDevice, list);
    if (verbose) {
        testPrintI("Post Prepare:");
        hwcTestDisplayListPrepareModifiable(list);
    }

    // Turn off the geometry changed flag
    list->flags &= ~HWC_GEOMETRY_CHANGED;

    // Perform the set operation(s)
    if (verbose) {testPrintI("Set:"); }
    if (verbose) { hwcTestDisplayListHandles(list); }
    hwcDevice->set(hwcDevice, dpy, surface, list);

    testDelay(endDelay);

    // Start framework
    rv = snprintf(cmd, sizeof(cmd), "%s", CMD_START_FRAMEWORK);
    if (rv >= (signed) sizeof(cmd) - 1) {
        testPrintE("Command too long for: %s", CMD_START_FRAMEWORK);
        exit(6);
    }
    testExecCmd(cmd);

    return 0;
}

// Parse string description of rectangle and add it to list of rectangles
// to be rendered.
static Rectangle parseRect(string rectStr)
{
    int rv;
    string str;
    bool   error;
    istringstream in(rectStr);
    const struct hwcTestGraphicFormat *format;
    Rectangle rect;
    struct hwc_rect hwcRect;

    // Graphic Format
    in >> str;
    if (!in) {
        testPrintE("Error parsing format from: %s", rectStr.c_str());
        exit(20);
    }
    format = hwcTestGraphicFormatLookup(str.c_str());
    if (format == NULL) {
        testPrintE("Unknown graphic format in: %s", rectStr.c_str());
        exit(21);
    }
    rect.format = format->format;

    // Display Frame
    rect.displayFrame = hwcTestParseHwcRect(in, error);
    if (error) {
        testPrintE("Invalid display frame in: %s", rectStr.c_str());
        exit(22);
    }

    // Set default sourceDim and sourceCrop based on size of display frame.
    // Default is source size equal to the size of the display frame, with
    // the source crop being the entire size of the source frame.
    rect.sourceDim = HwcTestDim(rect.displayFrame.right
                                     - rect.displayFrame.left,
                                 rect.displayFrame.bottom
                                     - rect.displayFrame.top);
    rect.sourceCrop.left = 0;
    rect.sourceCrop.top = 0;
    rect.sourceCrop.right = rect.sourceDim.width();
    rect.sourceCrop.bottom = rect.sourceDim.height();

    // Optional settings
    while ((in.tellg() < (streampos) in.str().length())
           && (in.tellg() != (streampos) -1)) {
        string attrName;

        in >> attrName;
        if (in.eof()) { break; }
        if (!in) {
            testPrintE("Error reading attribute name in: %s",
                       rectStr.c_str());
            exit(23);
        }

        // Transform
        if (attrName == "transform:") { // Transform
            string str;

            in >> str;
            if (str == "none") {
                rect.transform = 0;
            } else if (str == "fliph") {
                rect.transform = HWC_TRANSFORM_FLIP_H;
            } else if (str == "flipv") {
                rect.transform = HWC_TRANSFORM_FLIP_V;
            } else if (str == "rot90") {
                rect.transform = HWC_TRANSFORM_ROT_90;
            } else if (str == "rot180") {
                rect.transform = HWC_TRANSFORM_ROT_180;
            } else if (str == "rot270") {
                rect.transform = HWC_TRANSFORM_ROT_270;
            } else {
                testPrintE("Unknown transform of \"%s\" in: %s", str.c_str(),
                           rectStr.c_str());
                exit(24);
            }
        } else if (attrName == "blend:") { // Blend
            string str;

            in >> str;
            if (str == string("none")) {
                rect.blend = HWC_BLENDING_NONE;
            } else if (str == "premult") {
                rect.blend = HWC_BLENDING_PREMULT;
            } else if (str == "coverage") {
                rect.blend = HWC_BLENDING_COVERAGE;
            } else {
                testPrintE("Unknown blend of \"%s\" in: %s", str.c_str(),
                           rectStr.c_str());
                exit(25);
            }
        } else if (attrName == "color:") { // Color
            rect.color = hwcTestParseColor(in, error);
            if (error) {
                testPrintE("Error parsing color in: %s", rectStr.c_str());
                exit(26);
            }
        } else if (attrName == "alpha:") { // Alpha
            in >> rect.alpha;
            if (!in) {
                testPrintE("Error parsing value for alpha attribute in: %s",
                           rectStr.c_str());
                exit(27);
            }
        } else if (attrName == "sourceDim:") { // Source Dimension
           rect.sourceDim = hwcTestParseDim(in, error);
            if (error) {
                testPrintE("Error parsing source dimenision in: %s",
                           rectStr.c_str());
                exit(28);
            }
        } else if (attrName == "sourceCrop:") { // Source Crop
            rect.sourceCrop = hwcTestParseHwcRect(in, error);
            if (error) {
                testPrintE("Error parsing source crop in: %s",
                           rectStr.c_str());
                exit(29);
            }
        } else { // Unknown attribute
            testPrintE("Unknown attribute of \"%s\" in: %s", attrName.c_str(),
                       rectStr.c_str());
            exit(30);
        }
    }

    // Validate
    if (((uint32_t) rect.sourceCrop.left >= rect.sourceDim.width())
        || ((uint32_t) rect.sourceCrop.right > rect.sourceDim.width())
        || ((uint32_t) rect.sourceCrop.top >= rect.sourceDim.height())
        || ((uint32_t) rect.sourceCrop.bottom > rect.sourceDim.height())) {
        testPrintE("Invalid source crop in: %s", rectStr.c_str());
        exit(31);
    }
    if ((rect.displayFrame.left >= width)
        || (rect.displayFrame.right > width)
        || (rect.displayFrame.top >= height)
        || (rect.displayFrame.bottom > height)) {
        testPrintE("Invalid display frame in: %s", rectStr.c_str());
        exit(32);
    }
    if ((rect.alpha < 0.0) || (rect.alpha > 1.0)) {
        testPrintE("Invalid alpha in: %s", rectStr.c_str());
        exit(33);
    }

    // Create source texture
    rect.texture = new GraphicBuffer(rect.sourceDim.width(),
                                     rect.sourceDim.height(),
                                     rect.format, texUsage);
    if ((rv = rect.texture->initCheck()) != NO_ERROR) {
        testPrintE("source texture initCheck failed, rv: %i", rv);
        testPrintE("  %s", rectStr.c_str());

    }

    // Fill with uniform color
    hwcTestFillColor(rect.texture.get(), rect.color, rect.alpha);
    if (verbose) {
        testPrintI("    buf: %p handle: %p format: %s width: %u height: %u "
                   "color: %s alpha: %f",
                   rect.texture.get(), rect.texture->handle, format->desc,
                   rect.sourceDim.width(), rect.sourceDim.height(),
                   string(rect.color).c_str(), rect.alpha);
    }

    return rect;
}

void init(void)
{
    // Seed pseudo random number generator
    // Needed so that the pad areas of frames are filled with a deterministic
    // pseudo random value.
    srand48(0);

    hwcTestInitDisplay(verbose, &dpy, &surface, &width, &height);

    hwcTestOpenHwc(&hwcDevice);
}

void printSyntax(const char *cmd)
{
    testPrintE("  %s [options] (graphicFormat displayFrame [attributes],)...",
               cmd);
    testPrintE("    options:");
    testPrintE("      -D End of test delay");
    testPrintE("      -v Verbose");
    testPrintE("");
    testPrintE("    graphic formats:");
    for (unsigned int n1 = 0; n1 < NUMA(hwcTestGraphicFormat); n1++) {
        testPrintE("      %s", hwcTestGraphicFormat[n1].desc);
    }
    testPrintE("");
    testPrintE("    displayFrame");
    testPrintE("      [left, top, right, bottom]");
    testPrintE("");
    testPrintE("    attributes:");
    testPrintE("      transform: none | fliph | flipv | rot90 | rot180 "
               " | rot270");
    testPrintE("      blend: none | premult | coverage");
    testPrintE("      color: [0.##, 0.##, 0.##]");
    testPrintE("      alpha: 0.##");
    testPrintE("      sourceDim: [width, height]");
    testPrintE("      sourceCrop: [left, top, right, bottom]");
    testPrintE("");
    testPrintE("    Example:");
    testPrintE("      # White YV12 rectangle, with overlapping turquoise ");
    testPrintE("      #  RGBA8888 rectangle at 30%% (alpha: 0.7) transparency");
    testPrintE("      %s -v -D 30.0 \\", cmd);
    testPrintE("        YV12 [50, 80, 200, 300] transform: none \\");
    testPrintE("          color: [1.0, 0.5, 0.5], \\");
    testPrintE("        RGBA8888 [100, 150, 300, 400] blend: coverage \\");
    testPrintE("          color: [0.251, 0.878, 0.816] alpha: 0.7 \\");
    testPrintE("          sourceDim: [50, 60] sourceCrop: [5, 8, 12, 15]");
}
