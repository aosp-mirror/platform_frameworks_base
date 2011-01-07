/*
 * Copyright (C) 2010 The Android Open Source Project
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
 * Hardware Composer stress test
 *
 * Performs a pseudo-random (prandom) sequence of operations to the
 * Hardware Composer (HWC), for a specified number of passes or for
 * a specified period of time.  By default the period of time is FLT_MAX,
 * so that the number of passes will take precedence.
 *
 * The passes are grouped together, where (pass / passesPerGroup) specifies
 * which group a particular pass is in.  This causes every passesPerGroup
 * worth of sequential passes to be within the same group.  Computationally
 * intensive operations are performed just once at the beginning of a group
 * of passes and then used by all the passes in that group.  This is done
 * so as to increase both the average and peak rate of graphic operations,
 * by moving computationally intensive operations to the beginning of a group.
 * In particular, at the start of each group of passes a set of
 * graphic buffers are created, then used by the first and remaining
 * passes of that group of passes.
 *
 * The per-group initialization of the graphic buffers is performed
 * by a function called initFrames.  This function creates an array
 * of smart pointers to the graphic buffers, in the form of a vector
 * of vectors.  The array is accessed in row major order, so each
 * row is a vector of smart pointers.  All the pointers of a single
 * row point to graphic buffers which use the same pixel format and
 * have the same dimension, although it is likely that each one is
 * filled with a different color.  This is done so that after doing
 * the first HWC prepare then set call, subsequent set calls can
 * be made with each of the layer handles changed to a different
 * graphic buffer within the same row.  Since the graphic buffers
 * in a particular row have the same pixel format and dimension,
 * additional HWC set calls can be made, without having to perform
 * an HWC prepare call.
 *
 * This test supports the following command-line options:
 *
 *   -v        Verbose
 *   -s num    Starting pass
 *   -e num    Ending pass
 *   -p num    Execute the single pass specified by num
 *   -n num    Number of set operations to perform after each prepare operation
 *   -t float  Maximum time in seconds to execute the test
 *   -d float  Delay in seconds performed after each set operation
 *   -D float  Delay in seconds performed after the last pass is executed
 *
 * Typically the test is executed for a large range of passes.  By default
 * passes 0 through 99999 (100,000 passes) are executed.  Although this test
 * does not validate the generated image, at times it is useful to reexecute
 * a particular pass and leave the displayed image on the screen for an
 * extended period of time.  This can be done either by setting the -s
 * and -e options to the desired pass, along with a large value for -D.
 * This can also be done via the -p option, again with a large value for
 * the -D options.
 *
 * So far this test only contains code to create graphic buffers with
 * a continuous solid color.  Although this test is unable to validate the
 * image produced, any image that contains other than rectangles of a solid
 * color are incorrect.  Note that the rectangles may use a transparent
 * color and have a blending operation that causes the color in overlapping
 * rectangles to be mixed.  In such cases the overlapping portions may have
 * a different color from the rest of the rectangle.
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

#define LOG_TAG "hwcStressTest"
#include <utils/Log.h>
#include <testUtil.h>

#include <hardware/hwcomposer.h>

#include <glTestLib.h>
#include <hwc/hwcTestLib.h>

using namespace std;
using namespace android;

const float maxSizeRatio = 1.3;  // Graphic buffers can be upto this munch
                                 // larger than the default screen size
const unsigned int passesPerGroup = 10; // A group of passes all use the same
                                        // graphic buffers

// Ratios at which rare and frequent conditions should be produced
const float rareRatio = 0.1;
const float freqRatio = 0.9;

// Defaults for command-line options
const bool defaultVerbose = false;
const unsigned int defaultStartPass = 0;
const unsigned int defaultEndPass = 99999;
const unsigned int defaultPerPassNumSet = 10;
const float defaultPerSetDelay = 0.0; // Default delay after each set
                                      // operation.  Default delay of
                                      // zero used so as to perform the
                                      // the set operations as quickly
                                      // as possible.
const float defaultEndDelay = 2.0; // Default delay between completion of
                                   // final pass and restart of framework
const float defaultDuration = FLT_MAX; // A fairly long time, so that
                                       // range of passes will have
                                       // precedence

// Command-line option settings
static bool verbose = defaultVerbose;
static unsigned int startPass = defaultStartPass;
static unsigned int endPass = defaultEndPass;
static unsigned int numSet = defaultPerPassNumSet;
static float perSetDelay = defaultPerSetDelay;
static float endDelay = defaultEndDelay;
static float duration = defaultDuration;

// Command-line mutual exclusion detection flags.
// Corresponding flag set true once an option is used.
bool eFlag, sFlag, pFlag;

#define MAXSTR               100
#define MAXCMD               200
#define BITSPERBYTE            8 // TODO: Obtain from <values.h>, once
                                 // it has been added

#define CMD_STOP_FRAMEWORK   "stop 2>&1"
#define CMD_START_FRAMEWORK  "start 2>&1"

#define NUMA(a) (sizeof(a) / sizeof(a [0]))
#define MEMCLR(addr, size) do { \
        memset((addr), 0, (size)); \
    } while (0)

// File scope constants
const unsigned int blendingOps[] = {
    HWC_BLENDING_NONE,
    HWC_BLENDING_PREMULT,
    HWC_BLENDING_COVERAGE,
};
const unsigned int layerFlags[] = {
    HWC_SKIP_LAYER,
};
const vector<unsigned int> vecLayerFlags(layerFlags,
    layerFlags + NUMA(layerFlags));

const unsigned int transformFlags[] = {
    HWC_TRANSFORM_FLIP_H,
    HWC_TRANSFORM_FLIP_V,
    HWC_TRANSFORM_ROT_90,
    // ROT_180 & ROT_270 intentionally not listed, because they
    // they are formed from combinations of the flags already listed.
};
const vector<unsigned int> vecTransformFlags(transformFlags,
    transformFlags + NUMA(transformFlags));

// File scope globals
static const int texUsage = GraphicBuffer::USAGE_HW_TEXTURE |
        GraphicBuffer::USAGE_SW_WRITE_RARELY;
static hwc_composer_device_t *hwcDevice;
static EGLDisplay dpy;
static EGLSurface surface;
static EGLint width, height;
static vector <vector <sp<GraphicBuffer> > > frames;

// File scope prototypes
void init(void);
void initFrames(unsigned int seed);
template <class T> vector<T> vectorRandSelect(const vector<T>& vec, size_t num);
template <class T> T vectorOr(const vector<T>& vec);

/*
 * Main
 *
 * Performs the following high-level sequence of operations:
 *
 *   1. Command-line parsing
 *
 *   2. Initialization
 *
 *   3. For each pass:
 *
 *        a. If pass is first pass or in a different group from the
 *           previous pass, initialize the array of graphic buffers.
 *
 *        b. Create a HWC list with room to specify a prandomly
 *           selected number of layers.
 *
 *        c. Select a subset of the rows from the graphic buffer array,
 *           such that there is a unique row to be used for each
 *           of the layers in the HWC list.
 *
 *        d. Prandomly fill in the HWC list with handles
 *           selected from any of the columns of the selected row.
 *
 *        e. Pass the populated list to the HWC prepare call.
 *
 *        f. Pass the populated list to the HWC set call.
 *
 *        g. If additional set calls are to be made, then for each
 *           additional set call, select a new set of handles and
 *           perform the set call.
 */
int
main(int argc, char *argv[])
{
    int rv, opt;
    char *chptr;
    unsigned int pass;
    char cmd[MAXCMD];
    struct timeval startTime, currentTime, delta;

    testSetLogCatTag(LOG_TAG);

    // Parse command line arguments
    while ((opt = getopt(argc, argv, "vp:d:D:n:s:e:t:?h")) != -1) {
        switch (opt) {
          case 'd': // Delay after each set operation
            perSetDelay = strtod(optarg, &chptr);
            if ((*chptr != '\0') || (perSetDelay < 0.0)) {
                testPrintE("Invalid command-line specified per pass delay of: "
                           "%s", optarg);
                exit(1);
            }
            break;

          case 'D': // End of test delay
                    // Delay between completion of final pass and restart
                    // of framework
            endDelay = strtod(optarg, &chptr);
            if ((*chptr != '\0') || (endDelay < 0.0)) {
                testPrintE("Invalid command-line specified end of test delay "
                           "of: %s", optarg);
                exit(2);
            }
            break;

          case 't': // Duration
            duration = strtod(optarg, &chptr);
            if ((*chptr != '\0') || (duration < 0.0)) {
                testPrintE("Invalid command-line specified duration of: %s",
                           optarg);
                exit(3);
            }
            break;

          case 'n': // Num set operations per pass
            numSet = strtoul(optarg, &chptr, 10);
            if (*chptr != '\0') {
                testPrintE("Invalid command-line specified num set per pass "
                           "of: %s", optarg);
                exit(4);
            }
            break;

          case 's': // Starting Pass
            sFlag = true;
            if (pFlag) {
                testPrintE("Invalid combination of command-line options.");
                testPrintE("  The -p option is mutually exclusive from the");
                testPrintE("  -s and -e options.");
                exit(5);
            }
            startPass = strtoul(optarg, &chptr, 10);
            if (*chptr != '\0') {
                testPrintE("Invalid command-line specified starting pass "
                           "of: %s", optarg);
                exit(6);
            }
            break;

          case 'e': // Ending Pass
            eFlag = true;
            if (pFlag) {
                testPrintE("Invalid combination of command-line options.");
                testPrintE("  The -p option is mutually exclusive from the");
                testPrintE("  -s and -e options.");
                exit(7);
            }
            endPass = strtoul(optarg, &chptr, 10);
            if (*chptr != '\0') {
                testPrintE("Invalid command-line specified ending pass "
                           "of: %s", optarg);
                exit(8);
            }
            break;

          case 'p': // Run a single specified pass
            pFlag = true;
            if (sFlag || eFlag) {
                testPrintE("Invalid combination of command-line options.");
                testPrintE("  The -p option is mutually exclusive from the");
                testPrintE("  -s and -e options.");
                exit(9);
            }
            startPass = endPass = strtoul(optarg, &chptr, 10);
            if (*chptr != '\0') {
                testPrintE("Invalid command-line specified pass of: %s",
                           optarg);
                exit(10);
            }
            break;

          case 'v': // Verbose
            verbose = true;
            break;

          case 'h': // Help
          case '?':
          default:
            testPrintE("  %s [options]", basename(argv[0]));
            testPrintE("    options:");
            testPrintE("      -p Execute specified pass");
            testPrintE("      -s Starting pass");
            testPrintE("      -e Ending pass");
            testPrintE("      -t Duration");
            testPrintE("      -d Delay after each set operation");
            testPrintE("      -D End of test delay");
            testPrintE("      -n Num set operations per pass");
            testPrintE("      -v Verbose");
            exit(((optopt == 0) || (optopt == '?')) ? 0 : 11);
        }
    }
    if (endPass < startPass) {
        testPrintE("Unexpected ending pass before starting pass");
        testPrintE("  startPass: %u endPass: %u", startPass, endPass);
        exit(12);
    }
    if (argc != optind) {
        testPrintE("Unexpected command-line postional argument");
        testPrintE("  %s [-s start_pass] [-e end_pass] [-t duration]",
            basename(argv[0]));
        exit(13);
    }
    testPrintI("duration: %g", duration);
    testPrintI("startPass: %u", startPass);
    testPrintI("endPass: %u", endPass);
    testPrintI("numSet: %u", numSet);

    // Stop framework
    rv = snprintf(cmd, sizeof(cmd), "%s", CMD_STOP_FRAMEWORK);
    if (rv >= (signed) sizeof(cmd) - 1) {
        testPrintE("Command too long for: %s", CMD_STOP_FRAMEWORK);
        exit(14);
    }
    testExecCmd(cmd);
    testDelay(1.0); // TODO - need means to query whether asyncronous stop
                    // framework operation has completed.  For now, just wait
                    // a long time.

    init();

    // For each pass
    gettimeofday(&startTime, NULL);
    for (pass = startPass; pass <= endPass; pass++) {
        // Stop if duration of work has already been performed
        gettimeofday(&currentTime, NULL);
        delta = tvDelta(&startTime, &currentTime);
        if (tv2double(&delta) > duration) { break; }

        // Regenerate a new set of test frames when this pass is
        // either the first pass or is in a different group then
        // the previous pass.  A group of passes are passes that
        // all have the same quotient when their pass number is
        // divided by passesPerGroup.
        if ((pass == startPass)
            || ((pass / passesPerGroup) != ((pass - 1) / passesPerGroup))) {
            initFrames(pass / passesPerGroup);
        }

        testPrintI("==== Starting pass: %u", pass);

        // Cause deterministic sequence of prandom numbers to be
        // generated for this pass.
        srand48(pass);

        hwc_layer_list_t *list;
        list = hwcTestCreateLayerList(testRandMod(frames.size()) + 1);
        if (list == NULL) {
            testPrintE("hwcTestCreateLayerList failed");
            exit(20);
        }

        // Prandomly select a subset of frames to be used by this pass.
        vector <vector <sp<GraphicBuffer> > > selectedFrames;
        selectedFrames = vectorRandSelect(frames, list->numHwLayers);

        // Any transform tends to create a layer that the hardware
        // composer is unable to support and thus has to leave for
        // SurfaceFlinger.  Place heavy bias on specifying no transforms.
        bool noTransform = testRandFract() > rareRatio;

        for (unsigned int n1 = 0; n1 < list->numHwLayers; n1++) {
            unsigned int idx = testRandMod(selectedFrames[n1].size());
            sp<GraphicBuffer> gBuf = selectedFrames[n1][idx];
            hwc_layer_t *layer = &list->hwLayers[n1];
            layer->handle = gBuf->handle;

            layer->blending = blendingOps[testRandMod(NUMA(blendingOps))];
            layer->flags = (testRandFract() > rareRatio) ? 0
                : vectorOr(vectorRandSelect(vecLayerFlags,
                           testRandMod(vecLayerFlags.size() + 1)));
            layer->transform = (noTransform || testRandFract() > rareRatio) ? 0
                : vectorOr(vectorRandSelect(vecTransformFlags,
                           testRandMod(vecTransformFlags.size() + 1)));
            layer->sourceCrop.left = testRandMod(gBuf->getWidth());
            layer->sourceCrop.top = testRandMod(gBuf->getHeight());
            layer->sourceCrop.right = layer->sourceCrop.left
                + testRandMod(gBuf->getWidth() - layer->sourceCrop.left) + 1;
            layer->sourceCrop.bottom = layer->sourceCrop.top
                + testRandMod(gBuf->getHeight() - layer->sourceCrop.top) + 1;
            layer->displayFrame.left = testRandMod(width);
            layer->displayFrame.top = testRandMod(height);
            layer->displayFrame.right = layer->displayFrame.left
                + testRandMod(width - layer->displayFrame.left) + 1;
            layer->displayFrame.bottom = layer->displayFrame.top
                + testRandMod(height - layer->displayFrame.top) + 1;

            // Increase the frequency that a scale factor of 1.0 from
            // the sourceCrop to displayFrame occurs.  This is the
            // most common scale factor used by applications and would
            // be rarely produced by this stress test without this
            // logic.
            if (testRandFract() <= freqRatio) {
                // Only change to scale factor to 1.0 if both the
                // width and height will fit.
                int sourceWidth = layer->sourceCrop.right
                                  - layer->sourceCrop.left;
                int sourceHeight = layer->sourceCrop.bottom
                                   - layer->sourceCrop.top;
                if (((layer->displayFrame.left + sourceWidth) <= width)
                    && ((layer->displayFrame.top + sourceHeight) <= height)) {
                    layer->displayFrame.right = layer->displayFrame.left
                                                + sourceWidth;
                    layer->displayFrame.bottom = layer->displayFrame.top
                                                 + sourceHeight;
                }
            }

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
        for (unsigned int n1 = 0; n1 < numSet; n1++) {
            if (verbose) { hwcTestDisplayListHandles(list); }
            hwcDevice->set(hwcDevice, dpy, surface, list);

            // Prandomly select a new set of handles
            for (unsigned int n1 = 0; n1 < list->numHwLayers; n1++) {
                unsigned int idx = testRandMod(selectedFrames[n1].size());
                sp<GraphicBuffer> gBuf = selectedFrames[n1][idx];
                hwc_layer_t *layer = &list->hwLayers[n1];
                layer->handle = (native_handle_t *) gBuf->handle;
            }

            testDelay(perSetDelay);
        }

        hwcTestFreeLayerList(list);
        testPrintI("==== Completed pass: %u", pass);
    }

    testDelay(endDelay);

    // Start framework
    rv = snprintf(cmd, sizeof(cmd), "%s", CMD_START_FRAMEWORK);
    if (rv >= (signed) sizeof(cmd) - 1) {
        testPrintE("Command too long for: %s", CMD_START_FRAMEWORK);
        exit(21);
    }
    testExecCmd(cmd);

    testPrintI("Successfully completed %u passes", pass - startPass);

    return 0;
}

void init(void)
{
    srand48(0); // Defensively set pseudo random number generator.
                // Should not need to set this, because a stress test
                // sets the seed on each pass.  Defensively set it here
                // so that future code that uses pseudo random numbers
                // before the first pass will be deterministic.

    hwcTestInitDisplay(verbose, &dpy, &surface, &width, &height);

    hwcTestOpenHwc(&hwcDevice);
}

/*
 * Initialize Frames
 *
 * Creates an array of graphic buffers, within the global variable
 * named frames.  The graphic buffers are contained within a vector of
 * vectors.  All the graphic buffers in a particular row are of the same
 * format and dimension.  Each graphic buffer is uniformly filled with a
 * prandomly selected color.  It is likely that each buffer, even
 * in the same row, will be filled with a unique color.
 */
void initFrames(unsigned int seed)
{
    int rv;
    const size_t maxRows = 5;
    const size_t minCols = 2;  // Need at least double buffering
    const size_t maxCols = 4;  // One more than triple buffering

    if (verbose) { testPrintI("initFrames seed: %u", seed); }
    srand48(seed);
    size_t rows = testRandMod(maxRows) + 1;

    frames.clear();
    frames.resize(rows);

    for (unsigned int row = 0; row < rows; row++) {
        // All frames within a row have to have the same format and
        // dimensions.  Width and height need to be >= 1.
        unsigned int formatIdx = testRandMod(NUMA(hwcTestGraphicFormat));
        const struct hwcTestGraphicFormat *formatPtr
            = &hwcTestGraphicFormat[formatIdx];
        int format = formatPtr->format;

        // Pick width and height, which must be >= 1 and the size
        // mod the wMod/hMod value must be equal to 0.
        size_t w = (width * maxSizeRatio) * testRandFract();
        size_t h = (height * maxSizeRatio) * testRandFract();
        w = max(1u, w);
        h = max(1u, h);
        if ((w % formatPtr->wMod) != 0) {
            w += formatPtr->wMod - (w % formatPtr->wMod);
        }
        if ((h % formatPtr->hMod) != 0) {
            h += formatPtr->hMod - (h % formatPtr->hMod);
        }
        if (verbose) {
            testPrintI("  frame %u width: %u height: %u format: %u %s",
                       row, w, h, format, hwcTestGraphicFormat2str(format));
        }

        size_t cols = testRandMod((maxCols + 1) - minCols) + minCols;
        frames[row].resize(cols);
        for (unsigned int col = 0; col < cols; col++) {
            ColorFract color(testRandFract(), testRandFract(), testRandFract());
            float alpha = testRandFract();

            frames[row][col] = new GraphicBuffer(w, h, format, texUsage);
            if ((rv = frames[row][col]->initCheck()) != NO_ERROR) {
                testPrintE("GraphicBuffer initCheck failed, rv: %i", rv);
                testPrintE("  frame %u width: %u height: %u format: %u %s",
                           row, w, h, format, hwcTestGraphicFormat2str(format));
                exit(80);
            }

            hwcTestFillColor(frames[row][col].get(), color, alpha);
            if (verbose) {
                testPrintI("    buf: %p handle: %p color: %s alpha: %f",
                           frames[row][col].get(), frames[row][col]->handle,
                           string(color).c_str(), alpha);
            }
        }
    }
}

/*
 * Vector Random Select
 *
 * Prandomly selects and returns num elements from vec.
 */
template <class T>
vector<T> vectorRandSelect(const vector<T>& vec, size_t num)
{
    vector<T> rv = vec;

    while (rv.size() > num) {
        rv.erase(rv.begin() + testRandMod(rv.size()));
    }

    return rv;
}

/*
 * Vector Or
 *
 * Or's togethen the values of each element of vec and returns the result.
 */
template <class T>
T vectorOr(const vector<T>& vec)
{
    T rv = 0;

    for (size_t n1 = 0; n1 < vec.size(); n1++) {
        rv |= vec[n1];
    }

    return rv;
}
