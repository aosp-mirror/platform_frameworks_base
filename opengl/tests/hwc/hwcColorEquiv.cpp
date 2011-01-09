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
 * Hardware Composer Color Equivalence
 *
 * Synopsis
 *   hwc_colorequiv [options] eFmt
 *
 *     options:
         -v - verbose
 *       -s <0.##, 0.##, 0.##> - Start color (default: <0.0, 0.0, 0.0>
 *       -e <0.##, 0.##, 0.##> - Ending color (default: <1.0, 1.0, 1.0>
 *       -r fmt - reference graphic format
 *       -D #.## - End of test delay
 *
 *     graphic formats:
 *       RGBA8888 (reference frame default)
 *       RGBX8888
 *       RGB888
 *       RGB565
 *       BGRA8888
 *       RGBA5551
 *       RGBA4444
 *       YV12
 *
 * Description
 *   Renders a horizontal blend in two frames.  The first frame is rendered
 *   in the upper third of the display and is called the reference frame.
 *   The second frame is displayed in the middle third and is called the
 *   equivalence frame.  The primary purpose of this utility is to verify
 *   that the colors produced in the reference and equivalence frames are
 *   the same.  The colors are the same when the colors are the same
 *   vertically between the reference and equivalence frames.
 *
 *   By default the reference frame is rendered through the use of the
 *   RGBA8888 graphic format.  The -r option can be used to specify a
 *   non-default reference frame graphic format.  The graphic format of
 *   the equivalence frame is determined by a single required positional
 *   parameter.  Intentionally there is no default for the graphic format
 *   of the equivalence frame.
 *
 *   The horizontal blend in the reference frame is produced from a linear
 *   interpolation from a start color (default: <0.0, 0.0, 0.0> on the left
 *   side to an end color (default <1.0, 1.0, 1.0> on the right side.  Where
 *   possible the equivalence frame is rendered with the equivalent color
 *   from the reference frame.  A color of black is used in the equivalence
 *   frame for cases where an equivalent color does not exist.
 */

#include <algorithm>
#include <assert.h>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <ctime>
#include <libgen.h>
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

#define LOG_TAG "hwcColorEquivTest"
#include <utils/Log.h>
#include <testUtil.h>

#include <hardware/hwcomposer.h>

#include "hwcTestLib.h"

using namespace std;
using namespace android;

// Defaults for command-line options
const bool defaultVerbose = false;
const ColorFract defaultStartColor(0.0, 0.0, 0.0);
const ColorFract defaultEndColor(1.0, 1.0, 1.0);
const char *defaultRefFormat = "RGBA8888";
const float defaultEndDelay = 2.0; // Default delay after rendering graphics

// Defines
#define MAXSTR               100
#define MAXCMD               200
#define BITSPERBYTE            8 // TODO: Obtain from <values.h>, once
                                 // it has been added

#define CMD_STOP_FRAMEWORK   "stop 2>&1"
#define CMD_START_FRAMEWORK  "start 2>&1"

// Macros
#define NUMA(a) (sizeof(a) / sizeof(a [0])) // Num elements in an array
#define MEMCLR(addr, size) do { \
        memset((addr), 0, (size)); \
    } while (0)

// Globals
static const int texUsage = GraphicBuffer::USAGE_HW_TEXTURE |
        GraphicBuffer::USAGE_SW_WRITE_RARELY;
static hwc_composer_device_t *hwcDevice;
static EGLDisplay dpy;
static EGLSurface surface;
static EGLint width, height;

// Functions prototypes
void init(void);
void printSyntax(const char *cmd);

// Command-line option settings
static bool verbose = defaultVerbose;
static ColorFract startRefColor = defaultStartColor;
static ColorFract endRefColor = defaultEndColor;
static float endDelay = defaultEndDelay;
static const struct hwcTestGraphicFormat *refFormat
    = hwcTestGraphicFormatLookup(defaultRefFormat);
static const struct hwcTestGraphicFormat *equivFormat;

/*
 * Main
 *
 * Performs the following high-level sequence of operations:
 *
 *   1. Command-line parsing
 *
 *   2. Stop framework
 *
 *   3. Initialization
 *
 *   4. Create Hardware Composer description of reference and equivalence frames
 *
 *   5. Have Hardware Composer render the reference and equivalence frames
 *
 *   6. Delay for amount of time given by endDelay
 *
 *   7. Start framework
 */
int
main(int argc, char *argv[])
{
    int rv, opt;
    bool error;
    char *chptr;
    unsigned int pass;
    char cmd[MAXCMD];
    string str;

    testSetLogCatTag(LOG_TAG);

    assert(refFormat != NULL);

    testSetLogCatTag(LOG_TAG);

    // Parse command line arguments
    while ((opt = getopt(argc, argv, "vs:e:r:D:?h")) != -1) {
        switch (opt) {
          case 'D': // End of test delay
                    // Delay between completion of final pass and restart
                    // of framework
            endDelay = strtod(optarg, &chptr);
            if ((*chptr != '\0') || (endDelay < 0.0)) {
                testPrintE("Invalid command-line specified end of test delay "
                           "of: %s", optarg);
                exit(1);
            }
            break;

          case 's': // Starting reference color
            str = optarg;
            while (optind < argc) {
                if (*argv[optind] == '-') { break; }
                char endChar = (str.length() > 1) ? str[str.length() - 1] : 0;
                if ((endChar == '>') || (endChar == ']')) { break; }
                str += " " + string(argv[optind++]);
            }
            {
                istringstream in(str);
                startRefColor = hwcTestParseColor(in, error);
                // Any parse error or characters not used by parser
                if (error
                    || (((unsigned int) in.tellg() != in.str().length())
                        && (in.tellg() != (streampos) -1))) {
                    testPrintE("Invalid command-line specified start "
                               "reference color of: %s", str.c_str());
                    exit(2);
                }
            }
            break;

          case 'e': // Ending reference color
            str = optarg;
            while (optind < argc) {
                if (*argv[optind] == '-') { break; }
                char endChar = (str.length() > 1) ? str[str.length() - 1] : 0;
                if ((endChar == '>') || (endChar == ']')) { break; }
                str += " " + string(argv[optind++]);
            }
            {
                istringstream in(str);
                endRefColor = hwcTestParseColor(in, error);
                // Any parse error or characters not used by parser
                if (error
                    || (((unsigned int) in.tellg() != in.str().length())
                        && (in.tellg() != (streampos) -1))) {
                    testPrintE("Invalid command-line specified end "
                               "reference color of: %s", str.c_str());
                    exit(3);
                }
            }
            break;

          case 'r': // Reference graphic format
            refFormat = hwcTestGraphicFormatLookup(optarg);
            if (refFormat == NULL) {
                testPrintE("Unkown command-line specified reference graphic "
                           "format of: %s", optarg);
                printSyntax(basename(argv[0]));
                exit(4);
            }
            break;

          case 'v': // Verbose
            verbose = true;
            break;

          case 'h': // Help
          case '?':
          default:
            printSyntax(basename(argv[0]));
            exit(((optopt == 0) || (optopt == '?')) ? 0 : 5);
        }
    }

    // Expect a single positional parameter, which specifies the
    // equivalence graphic format.
    if (argc != (optind + 1)) {
        testPrintE("Expected a single command-line postional parameter");
        printSyntax(basename(argv[0]));
        exit(6);
    }
    equivFormat = hwcTestGraphicFormatLookup(argv[optind]);
    if (equivFormat == NULL) {
        testPrintE("Unkown command-line specified equivalence graphic "
                   "format of: %s", argv[optind]);
        printSyntax(basename(argv[0]));
        exit(7);
    }

    testPrintI("refFormat: %u %s", refFormat->format, refFormat->desc);
    testPrintI("equivFormat: %u %s", equivFormat->format, equivFormat->desc);
    testPrintI("startRefColor: %s", ((string) startRefColor).c_str());
    testPrintI("endRefColor: %s", ((string) endRefColor).c_str());
    testPrintI("endDelay: %f", endDelay);

    // Stop framework
    rv = snprintf(cmd, sizeof(cmd), "%s", CMD_STOP_FRAMEWORK);
    if (rv >= (signed) sizeof(cmd) - 1) {
        testPrintE("Command too long for: %s", CMD_STOP_FRAMEWORK);
        exit(8);
    }
    testExecCmd(cmd);
    testDelay(1.0); // TODO - needs means to query whether asynchronous stop
                    // framework operation has completed.  For now, just wait
                    // a long time.

    init();

    // Use the upper third of the display for the reference frame and
    // the middle third for the equivalence frame.
    unsigned int refHeight = height / 3;
    unsigned int refPosY = 0; // Reference frame Y position
    unsigned int refPosX = 0; // Reference frame X position
    unsigned int refWidth = width - refPosX;
    if ((refWidth & refFormat->wMod) != 0) {
        refWidth += refFormat->wMod - (refWidth % refFormat->wMod);
    }
    unsigned int equivHeight = height / 3;
    unsigned int equivPosY = refHeight; // Equivalence frame Y position
    unsigned int equivPosX = 0;         // Equivalence frame X position
    unsigned int equivWidth = width - equivPosX;
    if ((equivWidth & equivFormat->wMod) != 0) {
        equivWidth += equivFormat->wMod - (equivWidth % equivFormat->wMod);
    }

    // Create reference and equivalence graphic buffers
    const unsigned int numFrames = 2;
    sp<GraphicBuffer> refFrame;
    refFrame = new GraphicBuffer(refWidth, refHeight,
                                 refFormat->format, texUsage);
    if ((rv = refFrame->initCheck()) != NO_ERROR) {
        testPrintE("refFrame initCheck failed, rv: %i", rv);
        testPrintE("  width %u height: %u format: %u %s", refWidth, refHeight,
                   refFormat->format,
                   hwcTestGraphicFormat2str(refFormat->format));
        exit(9);
    }
    testPrintI("refFrame width: %u height: %u format: %u %s",
               refWidth, refHeight, refFormat->format,
               hwcTestGraphicFormat2str(refFormat->format));

    sp<GraphicBuffer> equivFrame;
    equivFrame = new GraphicBuffer(equivWidth, equivHeight,
                                   equivFormat->format, texUsage);
    if ((rv = refFrame->initCheck()) != NO_ERROR) {
        testPrintE("refFrame initCheck failed, rv: %i", rv);
        testPrintE("  width %u height: %u format: %u %s", refWidth, refHeight,
                   refFormat->format,
                   hwcTestGraphicFormat2str(refFormat->format));
        exit(10);
    }
    testPrintI("equivFrame width: %u height: %u format: %u %s",
               equivWidth, equivHeight, equivFormat->format,
               hwcTestGraphicFormat2str(equivFormat->format));

    // Fill the frames with a horizontal blend
    hwcTestFillColorHBlend(refFrame.get(), refFormat->format,
                           startRefColor, endRefColor);
    hwcTestFillColorHBlend(equivFrame.get(), refFormat->format,
                           startRefColor, endRefColor);

    hwc_layer_list_t *list;
    size_t size = sizeof(hwc_layer_list) + numFrames * sizeof(hwc_layer_t);
    if ((list = (hwc_layer_list_t *) calloc(1, size)) == NULL) {
        testPrintE("Allocate list failed");
        exit(11);
    }
    list->flags = HWC_GEOMETRY_CHANGED;
    list->numHwLayers = numFrames;

    hwc_layer_t *layer = &list->hwLayers[0];
    layer->handle = refFrame->handle;
    layer->blending = HWC_BLENDING_NONE;
    layer->sourceCrop.left = 0;
    layer->sourceCrop.top = 0;
    layer->sourceCrop.right = width;
    layer->sourceCrop.bottom = refHeight;
    layer->displayFrame.left = 0;
    layer->displayFrame.top = 0;
    layer->displayFrame.right = width;
    layer->displayFrame.bottom = refHeight;
    layer->visibleRegionScreen.numRects = 1;
    layer->visibleRegionScreen.rects = &layer->displayFrame;

    layer++;
    layer->handle = equivFrame->handle;
    layer->blending = HWC_BLENDING_NONE;
    layer->sourceCrop.left = 0;
    layer->sourceCrop.top = 0;
    layer->sourceCrop.right = width;
    layer->sourceCrop.bottom = equivHeight;
    layer->displayFrame.left = 0;
    layer->displayFrame.top = refHeight;
    layer->displayFrame.right = width;
    layer->displayFrame.bottom = layer->displayFrame.top + equivHeight;
    layer->visibleRegionScreen.numRects = 1;
    layer->visibleRegionScreen.rects = &layer->displayFrame;

    // Perform prepare operation
    if (verbose) { testPrintI("Prepare:"); hwcTestDisplayList(list); }
    hwcDevice->prepare(hwcDevice, list);
    if (verbose) {
        testPrintI("Post Prepare:");
        hwcTestDisplayListPrepareModifiable(list);
    }

    // Turn off the geometry changed flag
    list->flags &= ~HWC_GEOMETRY_CHANGED;

    if (verbose) {hwcTestDisplayListHandles(list); }
    hwcDevice->set(hwcDevice, dpy, surface, list);

    testDelay(endDelay);

    // Start framework
    rv = snprintf(cmd, sizeof(cmd), "%s", CMD_START_FRAMEWORK);
    if (rv >= (signed) sizeof(cmd) - 1) {
        testPrintE("Command too long for: %s", CMD_START_FRAMEWORK);
        exit(12);
    }
    testExecCmd(cmd);

    return 0;
}

void init(void)
{
    // Seed pseudo random number generator
    // Seeding causes fill horizontal blend to fill the pad area with
    // a deterministic set of values.
    srand48(0);

    hwcTestInitDisplay(verbose, &dpy, &surface, &width, &height);

    hwcTestOpenHwc(&hwcDevice);
}

void printSyntax(const char *cmd)
{
    testPrintE("  %s [options] graphicFormat", cmd);
    testPrintE("    options:");
    testPrintE("      -s <0.##, 0.##, 0.##> - Starting reference color");
    testPrintE("      -e <0.##, 0.##, 0.##> - Ending reference color");
    testPrintE("      -r format - Reference graphic format");
    testPrintE("      -D #.## - End of test delay");
    testPrintE("      -v Verbose");
    testPrintE("");
    testPrintE("    graphic formats:");
    for (unsigned int n1 = 0; n1 < NUMA(hwcTestGraphicFormat); n1++) {
        testPrintE("      %s", hwcTestGraphicFormat[n1].desc);
    }
}
