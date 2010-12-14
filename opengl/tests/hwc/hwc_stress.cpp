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

#include <arpa/inet.h> // For ntohl() and htonl()

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

// Represent RGB color as fraction of color components.
// Each of the color components are expected in the range [0.0, 1.0]
class RGBColor {
  public:
    RGBColor(): _r(0.0), _g(0.0), _b(0.0) {};
    RGBColor(float f): _r(f), _g(f), _b(f) {}; // Gray
    RGBColor(float r, float g, float b): _r(r), _g(g), _b(b) {};
    float r(void) const { return _r; }
    float g(void) const { return _g; }
    float b(void) const { return _b; }

  private:
    float _r;
    float _g;
    float _b;
};

// Represent YUV color as fraction of color components.
// Each of the color components are expected in the range [0.0, 1.0]
class YUVColor {
  public:
    YUVColor(): _y(0.0), _u(0.0), _v(0.0) {};
    YUVColor(float f): _y(f), _u(0.0), _v(0.0) {}; // Gray
    YUVColor(float y, float u, float v): _y(y), _u(u), _v(v) {};
    float y(void) const { return _y; }
    float u(void) const { return _u; }
    float v(void) const { return _v; }

  private:
    float _y;
    float _u;
    float _v;
};

// File scope constants
static const struct {
    unsigned int format;
    const char *desc;
} graphicFormat[] = {
    {HAL_PIXEL_FORMAT_RGBA_8888, "RGBA8888"},
    {HAL_PIXEL_FORMAT_RGBX_8888, "RGBX8888"},
    {HAL_PIXEL_FORMAT_RGB_888, "RGB888"},
    {HAL_PIXEL_FORMAT_RGB_565, "RGB565"},
    {HAL_PIXEL_FORMAT_BGRA_8888, "BGRA8888"},
    {HAL_PIXEL_FORMAT_RGBA_5551, "RGBA5551"},
    {HAL_PIXEL_FORMAT_RGBA_4444, "RGBA4444"},
    {HAL_PIXEL_FORMAT_YV12, "YV12"},
};
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
static hw_module_t const *hwcModule;
static hwc_composer_device_t *hwcDevice;
static vector <vector <sp<GraphicBuffer> > > frames;
static EGLDisplay dpy;
static EGLContext context;
static EGLSurface surface;
static EGLint width, height;

// File scope prototypes
static void execCmd(const char *cmd);
static void checkEglError(const char* op, EGLBoolean returnVal = EGL_TRUE);
static void checkGlError(const char* op);
static void printEGLConfiguration(EGLDisplay dpy, EGLConfig config);
static void printGLString(const char *name, GLenum s);
static hwc_layer_list_t *createLayerList(size_t numLayers);
static void freeLayerList(hwc_layer_list_t *list);
static void fillColor(GraphicBuffer *gBuf, RGBColor color, float trans);
static void fillColor(GraphicBuffer *gBuf, YUVColor color, float trans);
void init(void);
void initFrames(unsigned int seed);
void displayList(hwc_layer_list_t *list);
void displayListPrepareModifiable(hwc_layer_list_t *list);
void displayListHandles(hwc_layer_list_t *list);
const char *graphicFormat2str(unsigned int format);
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
    execCmd(cmd);
    testDelay(1.0); // TODO - needs means to query whether asyncronous stop
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
        list = createLayerList(testRandMod(frames.size()) + 1);
        if (list == NULL) {
            testPrintE("createLayerList failed");
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
        if (verbose) { testPrintI("Prepare:"); displayList(list); }
        hwcDevice->prepare(hwcDevice, list);
        if (verbose) {
            testPrintI("Post Prepare:");
            displayListPrepareModifiable(list);
        }

        // Turn off the geometry changed flag
        list->flags &= ~HWC_GEOMETRY_CHANGED;

        // Perform the set operation(s)
        if (verbose) {testPrintI("Set:"); }
        for (unsigned int n1 = 0; n1 < numSet; n1++) {
            if (verbose) {displayListHandles(list); }
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


        freeLayerList(list);
        testPrintI("==== Completed pass: %u", pass);
    }

    testDelay(endDelay);

    // Start framework
    rv = snprintf(cmd, sizeof(cmd), "%s", CMD_START_FRAMEWORK);
    if (rv >= (signed) sizeof(cmd) - 1) {
        testPrintE("Command too long for: %s", CMD_START_FRAMEWORK);
        exit(21);
    }
    execCmd(cmd);

    testPrintI("Successfully completed %u passes", pass - startPass);

    return 0;
}

/*
 * Execute Command
 *
 * Executes the command pointed to by cmd.  Output from the
 * executed command is captured and sent to LogCat Info.  Once
 * the command has finished execution, it's exit status is captured
 * and checked for an exit status of zero.  Any other exit status
 * causes diagnostic information to be printed and an immediate
 * testcase failure.
 */
static void execCmd(const char *cmd)
{
    FILE *fp;
    int rv;
    int status;
    char str[MAXSTR];

    // Display command to be executed
    testPrintI("cmd: %s", cmd);

    // Execute the command
    fflush(stdout);
    if ((fp = popen(cmd, "r")) == NULL) {
        testPrintE("execCmd popen failed, errno: %i", errno);
        exit(30);
    }

    // Obtain and display each line of output from the executed command
    while (fgets(str, sizeof(str), fp) != NULL) {
        if ((strlen(str) > 1) && (str[strlen(str) - 1] == '\n')) {
            str[strlen(str) - 1] = '\0';
        }
        testPrintI(" out: %s", str);
    }

    // Obtain and check return status of executed command.
    // Fail on non-zero exit status
    status = pclose(fp);
    if (!(WIFEXITED(status) && (WEXITSTATUS(status) == 0))) {
        testPrintE("Unexpected command failure");
        testPrintE("  status: %#x", status);
        if (WIFEXITED(status)) {
            testPrintE("WEXITSTATUS: %i", WEXITSTATUS(status));
        }
        if (WIFSIGNALED(status)) {
            testPrintE("WTERMSIG: %i", WTERMSIG(status));
        }
        exit(31);
    }
}

static void checkEglError(const char* op, EGLBoolean returnVal) {
    if (returnVal != EGL_TRUE) {
        testPrintE("%s() returned %d", op, returnVal);
    }

    for (EGLint error = eglGetError(); error != EGL_SUCCESS; error
            = eglGetError()) {
        testPrintE("after %s() eglError %s (0x%x)",
                   op, EGLUtils::strerror(error), error);
    }
}

static void checkGlError(const char* op) {
    for (GLint error = glGetError(); error; error
            = glGetError()) {
        testPrintE("after %s() glError (0x%x)", op, error);
    }
}

static void printEGLConfiguration(EGLDisplay dpy, EGLConfig config) {

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
        EGLint returnVal = eglGetConfigAttrib(dpy, config, names[j].attribute, &value);
        EGLint error = eglGetError();
        if (returnVal && error == EGL_SUCCESS) {
            testPrintI(" %s: %d (%#x)", names[j].name, value, value);
        }
    }
    testPrintI("");
}

static void printGLString(const char *name, GLenum s)
{
    const char *v = (const char *) glGetString(s);

    if (v == NULL) {
        testPrintI("GL %s unknown", name);
    } else {
        testPrintI("GL %s = %s", name, v);
    }
}

/*
 * createLayerList
 * dynamically creates layer list with numLayers worth
 * of hwLayers entries.
 */
static hwc_layer_list_t *createLayerList(size_t numLayers)
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
 * freeLayerList
 * Frees memory previous allocated via createLayerList().
 */
static void freeLayerList(hwc_layer_list_t *list)
{
    free(list);
}

static void fillColor(GraphicBuffer *gBuf, RGBColor color, float trans)
{
    unsigned char* buf = NULL;
    status_t err;
    unsigned int numPixels = gBuf->getWidth() * gBuf->getHeight();
    uint32_t pixel;

    // RGB 2 YUV conversion ratios
    const struct rgb2yuvRatios {
        int format;
        float weightRed;
        float weightBlu;
        float weightGrn;
    } rgb2yuvRatios[] = {
        { HAL_PIXEL_FORMAT_YV12, 0.299, 0.114, 0.587 },
    };

    const struct rgbAttrib {
        int format;
        bool   hostByteOrder;
        size_t bytes;
        size_t rOffset;
        size_t rSize;
        size_t gOffset;
        size_t gSize;
        size_t bOffset;
        size_t bSize;
        size_t aOffset;
        size_t aSize;
    } rgbAttributes[] = {
        {HAL_PIXEL_FORMAT_RGBA_8888, false, 4,  0, 8,  8, 8, 16, 8, 24, 8},
        {HAL_PIXEL_FORMAT_RGBX_8888, false, 4,  0, 8,  8, 8, 16, 8,  0, 0},
        {HAL_PIXEL_FORMAT_RGB_888,   false, 3,  0, 8,  8, 8, 16, 8,  0, 0},
        {HAL_PIXEL_FORMAT_RGB_565,   true,  2,  0, 5,  5, 6, 11, 5,  0, 0},
        {HAL_PIXEL_FORMAT_BGRA_8888, false, 4, 16, 8,  8, 8,  0, 8, 24, 8},
        {HAL_PIXEL_FORMAT_RGBA_5551, true , 2,  0, 5,  5, 5, 10, 5, 15, 1},
        {HAL_PIXEL_FORMAT_RGBA_4444, false, 2, 12, 4,  0, 4,  4, 4,  8, 4},
    };

    // If YUV format, convert color and pass work to YUV color fill
    for (unsigned int n1 = 0; n1 < NUMA(rgb2yuvRatios); n1++) {
        if (gBuf->getPixelFormat() == rgb2yuvRatios[n1].format) {
            float wr = rgb2yuvRatios[n1].weightRed;
            float wb = rgb2yuvRatios[n1].weightBlu;
            float wg = rgb2yuvRatios[n1].weightGrn;
            float y = wr * color.r() + wb * color.b() + wg * color.g();
            float u = 0.5 * ((color.b() - y) / (1 - wb)) + 0.5;
            float v = 0.5 * ((color.r() - y) / (1 - wr)) + 0.5;
            YUVColor yuvColor(y, u, v);
            fillColor(gBuf, yuvColor, trans);
            return;
        }
    }

    const struct rgbAttrib *attrib;
    for (attrib = rgbAttributes; attrib < rgbAttributes + NUMA(rgbAttributes);
         attrib++) {
        if (attrib->format == gBuf->getPixelFormat()) { break; }
    }
    if (attrib >= rgbAttributes + NUMA(rgbAttributes)) {
        testPrintE("fillColor rgb unsupported format of: %u",
        gBuf->getPixelFormat());
        exit(50);
    }

    pixel = htonl((uint32_t) (((1 << attrib->rSize) - 1) * color.r())
         << ((sizeof(pixel) * BITSPERBYTE)
             - (attrib->rOffset + attrib->rSize)));
    pixel |= htonl((uint32_t) (((1 << attrib->gSize) - 1) * color.g())
         << ((sizeof(pixel) * BITSPERBYTE)
             - (attrib->gOffset + attrib->gSize)));
    pixel |= htonl((uint32_t) (((1 << attrib->bSize) - 1) * color.b())
         << ((sizeof(pixel) * BITSPERBYTE)
             - (attrib->bOffset + attrib->bSize)));
    if (attrib->aSize) {
        pixel |= htonl((uint32_t) (((1 << attrib->aSize) - 1) * trans)
             << ((sizeof(pixel) * BITSPERBYTE)
                 - (attrib->aOffset + attrib->aSize)));
    }
    if (attrib->hostByteOrder) {
        pixel = ntohl(pixel);
        pixel >>= sizeof(pixel) * BITSPERBYTE - attrib->bytes * BITSPERBYTE;
    }

    err = gBuf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&buf));
    if (err != 0) {
        testPrintE("fillColor rgb lock failed: %d", err);
        exit(51);
    }

    for (unsigned int n1 = 0; n1 < numPixels; n1++) {
        memmove(buf, &pixel, attrib->bytes);
        buf += attrib->bytes;
    }

    err = gBuf->unlock();
    if (err != 0) {
        testPrintE("fillColor rgb unlock failed: %d", err);
        exit(52);
    }
}

static void fillColor(GraphicBuffer *gBuf, YUVColor color, float trans)
{
    unsigned char* buf = NULL;
    status_t err;
    unsigned int width = gBuf->getWidth();
    unsigned int height = gBuf->getHeight();

    const struct yuvAttrib {
        int format;
        size_t padWidth;
        bool   planar;
        unsigned int uSubSampX;
        unsigned int uSubSampY;
        unsigned int vSubSampX;
        unsigned int vSubSampY;
    } yuvAttributes[] = {
        { HAL_PIXEL_FORMAT_YV12, 16, true, 2, 2, 2, 2},
    };

    const struct yuvAttrib *attrib;
    for (attrib = yuvAttributes; attrib < yuvAttributes + NUMA(yuvAttributes);
         attrib++) {
        if (attrib->format == gBuf->getPixelFormat()) { break; }
    }
    if (attrib >= yuvAttributes + NUMA(yuvAttributes)) {
        testPrintE("fillColor yuv unsupported format of: %u",
        gBuf->getPixelFormat());
        exit(60);
    }

    assert(attrib->planar == true); // So far, only know how to handle planar

    // If needed round width up to pad size
    if (width % attrib->padWidth) {
        width += attrib->padWidth - (width % attrib->padWidth);
    }
    assert((width % attrib->padWidth) == 0);

    err = gBuf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&buf));
    if (err != 0) {
        testPrintE("fillColor lock failed: %d", err);
        exit(61);
    }

    // Fill in Y component
    for (unsigned int x = 0; x < width; x++) {
        for (unsigned int y = 0; y < height; y++) {
            *buf++ = (x < gBuf->getWidth()) ? (255 * color.y()) : 0;
        }
    }

    // Fill in U component
    for (unsigned int x = 0; x < width; x += attrib->uSubSampX) {
        for (unsigned int y = 0; y < height; y += attrib->uSubSampY) {
            *buf++ = (x < gBuf->getWidth()) ? (255 * color.u()) : 0;
        }
    }

    // Fill in V component
    for (unsigned int x = 0; x < width; x += attrib->vSubSampX) {
        for (unsigned int y = 0; y < height; y += attrib->vSubSampY) {
            *buf++ = (x < gBuf->getWidth()) ? (255 * color.v()) : 0;
        }
    }

    err = gBuf->unlock();
    if (err != 0) {
        testPrintE("fillColor unlock failed: %d", err);
        exit(62);
    }
}

void init(void)
{
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
    dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    checkEglError("eglGetDisplay");
    if (dpy == EGL_NO_DISPLAY) {
        testPrintE("eglGetDisplay returned EGL_NO_DISPLAY");
        exit(70);
    }

    returnValue = eglInitialize(dpy, &majorVersion, &minorVersion);
    checkEglError("eglInitialize", returnValue);
    testPrintI("EGL version %d.%d", majorVersion, minorVersion);
    if (returnValue != EGL_TRUE) {
        testPrintE("eglInitialize failed");
        exit(71);
    }

    EGLNativeWindowType window = android_createDisplaySurface();
    if (window == NULL) {
        testPrintE("android_createDisplaySurface failed");
        exit(72);
    }
    returnValue = EGLUtils::selectConfigForNativeWindow(dpy,
        sConfigAttribs, window, &myConfig);
    if (returnValue) {
        testPrintE("EGLUtils::selectConfigForNativeWindow() returned %d",
            returnValue);
        exit(73);
    }
    checkEglError("EGLUtils::selectConfigForNativeWindow");

    testPrintI("Chose this configuration:");
    printEGLConfiguration(dpy, myConfig);

    surface = eglCreateWindowSurface(dpy, myConfig, window, NULL);
    checkEglError("eglCreateWindowSurface");
    if (surface == EGL_NO_SURFACE) {
        testPrintE("gelCreateWindowSurface failed.");
        exit(74);
    }

    context = eglCreateContext(dpy, myConfig, EGL_NO_CONTEXT, contextAttribs);
    checkEglError("eglCreateContext");
    if (context == EGL_NO_CONTEXT) {
        testPrintE("eglCreateContext failed");
        exit(75);
    }
    returnValue = eglMakeCurrent(dpy, surface, surface, context);
    checkEglError("eglMakeCurrent", returnValue);
    if (returnValue != EGL_TRUE) {
        testPrintE("eglMakeCurrent failed");
        exit(76);
    }
    eglQuerySurface(dpy, surface, EGL_WIDTH, &width);
    checkEglError("eglQuerySurface");
    eglQuerySurface(dpy, surface, EGL_HEIGHT, &height);
    checkEglError("eglQuerySurface");

    fprintf(stderr, "Window dimensions: %d x %d", width, height);

    printGLString("Version", GL_VERSION);
    printGLString("Vendor", GL_VENDOR);
    printGLString("Renderer", GL_RENDERER);
    printGLString("Extensions", GL_EXTENSIONS);

    if ((rv = hw_get_module(HWC_HARDWARE_MODULE_ID, &hwcModule)) != 0) {
        testPrintE("hw_get_module failed, rv: %i", rv);
        errno = -rv;
        perror(NULL);
        exit(77);
    }
    if ((rv = hwc_open(hwcModule, &hwcDevice)) != 0) {
        testPrintE("hwc_open failed, rv: %i", rv);
        errno = -rv;
        perror(NULL);
        exit(78);
    }

    testPrintI("");
}

/*
 * Initialize Frames
 *
 * Creates an array of graphic buffers, within the global variable
 * named frames.  The graphic buffers are contained within a vector of
 * verctors.  All the graphic buffers in a particular row are of the same
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
        int format = graphicFormat[testRandMod(NUMA(graphicFormat))].format;
        size_t w = (width * maxSizeRatio) * testRandFract();
        size_t h = (height * maxSizeRatio) * testRandFract();
        w = max(1u, w);
        h = max(1u, h);
        if (verbose) {
            testPrintI("  frame %u width: %u height: %u format: %u %s",
                       row, w, h, format, graphicFormat2str(format));
        }

        size_t cols = testRandMod((maxCols + 1) - minCols) + minCols;
        frames[row].resize(cols);
        for (unsigned int col = 0; col < cols; col++) {
            RGBColor color(testRandFract(), testRandFract(), testRandFract());
            float transp = testRandFract();

            frames[row][col] = new GraphicBuffer(w, h, format, texUsage);
            if ((rv = frames[row][col]->initCheck()) != NO_ERROR) {
                testPrintE("GraphicBuffer initCheck failed, rv: %i", rv);
                testPrintE("  frame %u width: %u height: %u format: %u %s",
                           row, w, h, format, graphicFormat2str(format));
                exit(80);
            }

            fillColor(frames[row][col].get(), color, transp);
            if (verbose) {
                testPrintI("    buf: %p handle: %p color: <%f, %f, %f> "
                           "transp: %f",
                           frames[row][col].get(), frames[row][col]->handle,
                           color.r(), color.g(), color.b(), transp);
            }
        }
    }
}

void displayList(hwc_layer_list_t *list)
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

        testPrintI("      blending: %#x",
                   list->hwLayers[layer].blending,
                   (list->hwLayers[layer].blending == HWC_BLENDING_NONE)
                       ? " NONE" : "",
                   (list->hwLayers[layer].blending == HWC_BLENDING_PREMULT)
                       ? " PREMULT" : "",
                   (list->hwLayers[layer].blending == HWC_BLENDING_COVERAGE)
                       ? " COVERAGE" : "");

        testPrintI("      sourceCrop: [%i, %i, %i, %i]",
                   list->hwLayers[layer].sourceCrop.left,
                   list->hwLayers[layer].sourceCrop.top,
                   list->hwLayers[layer].sourceCrop.right,
                   list->hwLayers[layer].sourceCrop.bottom);

        testPrintI("      displayFrame: [%i, %i, %i, %i]",
                   list->hwLayers[layer].displayFrame.left,
                   list->hwLayers[layer].displayFrame.top,
                   list->hwLayers[layer].displayFrame.right,
                   list->hwLayers[layer].displayFrame.bottom);
        testPrintI("      scaleFactor: [%f %f]",
                   (float) (list->hwLayers[layer].displayFrame.right
                            - list->hwLayers[layer].displayFrame.left)
                       / (float) (list->hwLayers[layer].sourceCrop.right
                            - list->hwLayers[layer].sourceCrop.left),
                   (float) (list->hwLayers[layer].displayFrame.bottom
                            - list->hwLayers[layer].displayFrame.top)
                       / (float) (list->hwLayers[layer].sourceCrop.bottom
                            - list->hwLayers[layer].sourceCrop.top));
    }
}

/*
 * Display List Prepare Modifiable
 *
 * Displays the portions of a list that are meant to be modified by
 * a prepare call.
 */
void displayListPrepareModifiable(hwc_layer_list_t *list)
{
    for (unsigned int layer = 0; layer < list->numHwLayers; layer++) {
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
}

/*
 * Display List Handles
 *
 * Displays the handles of all the graphic buffers in the list.
 */
void displayListHandles(hwc_layer_list_t *list)
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

const char *graphicFormat2str(unsigned int format)
{
    const static char *unknown = "unknown";

    for (unsigned int n1 = 0; n1 < NUMA(graphicFormat); n1++) {
        if (format == graphicFormat[n1].format) {
            return graphicFormat[n1].desc;
        }
    }

    return unknown;
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
