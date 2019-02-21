/*
 * Copyright (C) 2006 The Android Open Source Project
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

#define ATRACE_TAG ATRACE_TAG_DALVIK
#define LOG_TAG "AndroidRuntime"
#define LOG_NDEBUG 1

#include <android_runtime/AndroidRuntime.h>

#include <android-base/macros.h>
#include <android-base/properties.h>
#include <binder/IBinder.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <utils/Log.h>
#include <utils/misc.h>
#include <utils/Trace.h>
#include <binder/Parcel.h>
#include <utils/threads.h>
#include <cutils/properties.h>
#include <server_configurable_flags/get_flags.h>

#include <SkGraphics.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/JniInvocation.h>
#include "android_util_Binder.h"

#include <stdio.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <signal.h>
#include <dirent.h>
#include <assert.h>

#include <string>
#include <vector>

using namespace android;
using android::base::GetProperty;

extern int register_android_os_Binder(JNIEnv* env);
extern int register_android_os_Process(JNIEnv* env);
extern int register_android_graphics_Bitmap(JNIEnv*);
extern int register_android_graphics_BitmapFactory(JNIEnv*);
extern int register_android_graphics_BitmapRegionDecoder(JNIEnv*);
extern int register_android_graphics_ByteBufferStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_Camera(JNIEnv* env);
extern int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_GraphicBuffer(JNIEnv* env);
extern int register_android_graphics_Graphics(JNIEnv* env);
extern int register_android_graphics_ImageDecoder(JNIEnv*);
extern int register_android_graphics_drawable_AnimatedImageDrawable(JNIEnv*);
extern int register_android_graphics_Interpolator(JNIEnv* env);
extern int register_android_graphics_MaskFilter(JNIEnv* env);
extern int register_android_graphics_Movie(JNIEnv* env);
extern int register_android_graphics_NinePatch(JNIEnv*);
extern int register_android_graphics_PathEffect(JNIEnv* env);
extern int register_android_graphics_Shader(JNIEnv* env);
extern int register_android_graphics_Typeface(JNIEnv* env);
extern int register_android_graphics_YuvImage(JNIEnv* env);

extern int register_com_google_android_gles_jni_EGLImpl(JNIEnv* env);
extern int register_com_google_android_gles_jni_GLImpl(JNIEnv* env);
extern int register_android_opengl_jni_EGL14(JNIEnv* env);
extern int register_android_opengl_jni_EGL15(JNIEnv* env);
extern int register_android_opengl_jni_EGLExt(JNIEnv* env);
extern int register_android_opengl_jni_GLES10(JNIEnv* env);
extern int register_android_opengl_jni_GLES10Ext(JNIEnv* env);
extern int register_android_opengl_jni_GLES11(JNIEnv* env);
extern int register_android_opengl_jni_GLES11Ext(JNIEnv* env);
extern int register_android_opengl_jni_GLES20(JNIEnv* env);
extern int register_android_opengl_jni_GLES30(JNIEnv* env);
extern int register_android_opengl_jni_GLES31(JNIEnv* env);
extern int register_android_opengl_jni_GLES31Ext(JNIEnv* env);
extern int register_android_opengl_jni_GLES32(JNIEnv* env);

extern int register_android_hardware_Camera(JNIEnv *env);
extern int register_android_hardware_camera2_CameraMetadata(JNIEnv *env);
extern int register_android_hardware_camera2_legacy_LegacyCameraDevice(JNIEnv *env);
extern int register_android_hardware_camera2_legacy_PerfMeasurement(JNIEnv *env);
extern int register_android_hardware_camera2_DngCreator(JNIEnv *env);
extern int register_android_hardware_HardwareBuffer(JNIEnv *env);
extern int register_android_hardware_SensorManager(JNIEnv *env);
extern int register_android_hardware_SerialPort(JNIEnv *env);
extern int register_android_hardware_SoundTrigger(JNIEnv *env);
extern int register_android_hardware_UsbDevice(JNIEnv *env);
extern int register_android_hardware_UsbDeviceConnection(JNIEnv *env);
extern int register_android_hardware_UsbRequest(JNIEnv *env);
extern int register_android_hardware_location_ActivityRecognitionHardware(JNIEnv* env);

extern int register_android_media_AudioEffectDescriptor(JNIEnv *env);
extern int register_android_media_AudioRecord(JNIEnv *env);
extern int register_android_media_AudioSystem(JNIEnv *env);
extern int register_android_media_AudioTrack(JNIEnv *env);
extern int register_android_media_AudioAttributes(JNIEnv *env);
extern int register_android_media_AudioProductStrategies(JNIEnv *env);
extern int register_android_media_MicrophoneInfo(JNIEnv *env);
extern int register_android_media_JetPlayer(JNIEnv *env);
extern int register_android_media_ToneGenerator(JNIEnv *env);
extern int register_android_media_midi(JNIEnv *env);

namespace android {

/*
 * JNI-based registration functions.  Note these are properly contained in
 * namespace android.
 */
extern int register_android_app_admin_SecurityLog(JNIEnv* env);
extern int register_android_content_AssetManager(JNIEnv* env);
extern int register_android_util_EventLog(JNIEnv* env);
extern int register_android_util_StatsLog(JNIEnv* env);
extern int register_android_util_Log(JNIEnv* env);
extern int register_android_util_MemoryIntArray(JNIEnv* env);
extern int register_android_util_PathParser(JNIEnv* env);
extern int register_android_content_StringBlock(JNIEnv* env);
extern int register_android_content_XmlBlock(JNIEnv* env);
extern int register_android_content_res_ApkAssets(JNIEnv* env);
extern int register_android_graphics_Canvas(JNIEnv* env);
extern int register_android_graphics_CanvasProperty(JNIEnv* env);
extern int register_android_graphics_ColorFilter(JNIEnv* env);
extern int register_android_graphics_ColorSpace(JNIEnv* env);
extern int register_android_graphics_DrawFilter(JNIEnv* env);
extern int register_android_graphics_FontFamily(JNIEnv* env);
extern int register_android_graphics_Matrix(JNIEnv* env);
extern int register_android_graphics_Paint(JNIEnv* env);
extern int register_android_graphics_Path(JNIEnv* env);
extern int register_android_graphics_PathMeasure(JNIEnv* env);
extern int register_android_graphics_Picture(JNIEnv*);
extern int register_android_graphics_Region(JNIEnv* env);
extern int register_android_graphics_SurfaceTexture(JNIEnv* env);
extern int register_android_graphics_drawable_AnimatedVectorDrawable(JNIEnv* env);
extern int register_android_graphics_drawable_VectorDrawable(JNIEnv* env);
extern int register_android_graphics_fonts_Font(JNIEnv* env);
extern int register_android_graphics_fonts_FontFamily(JNIEnv* env);
extern int register_android_graphics_pdf_PdfDocument(JNIEnv* env);
extern int register_android_graphics_pdf_PdfEditor(JNIEnv* env);
extern int register_android_graphics_pdf_PdfRenderer(JNIEnv* env);
extern int register_android_graphics_text_MeasuredText(JNIEnv* env);
extern int register_android_graphics_text_LineBreaker(JNIEnv *env);
extern int register_android_view_DisplayEventReceiver(JNIEnv* env);
extern int register_android_view_DisplayListCanvas(JNIEnv* env);
extern int register_android_view_InputApplicationHandle(JNIEnv* env);
extern int register_android_view_InputWindowHandle(JNIEnv* env);
extern int register_android_view_TextureLayer(JNIEnv* env);
extern int register_android_view_RenderNode(JNIEnv* env);
extern int register_android_view_RenderNodeAnimator(JNIEnv* env);
extern int register_android_view_Surface(JNIEnv* env);
extern int register_android_view_SurfaceControl(JNIEnv* env);
extern int register_android_view_SurfaceSession(JNIEnv* env);
extern int register_android_view_CompositionSamplingListener(JNIEnv* env);
extern int register_android_view_TextureView(JNIEnv* env);
extern int register_android_view_ThreadedRenderer(JNIEnv* env);
extern int register_com_android_internal_view_animation_NativeInterpolatorFactoryHelper(JNIEnv *env);
extern int register_android_database_CursorWindow(JNIEnv* env);
extern int register_android_database_SQLiteConnection(JNIEnv* env);
extern int register_android_database_SQLiteGlobal(JNIEnv* env);
extern int register_android_database_SQLiteDebug(JNIEnv* env);
extern int register_android_nio_utils(JNIEnv* env);
extern int register_android_os_Debug(JNIEnv* env);
extern int register_android_os_GraphicsEnvironment(JNIEnv* env);
extern int register_android_os_HidlSupport(JNIEnv* env);
extern int register_android_os_HwBinder(JNIEnv *env);
extern int register_android_os_HwBlob(JNIEnv *env);
extern int register_android_os_HwParcel(JNIEnv *env);
extern int register_android_os_HwRemoteBinder(JNIEnv *env);
extern int register_android_os_NativeHandle(JNIEnv *env);
extern int register_android_os_MessageQueue(JNIEnv* env);
extern int register_android_os_Parcel(JNIEnv* env);
extern int register_android_os_SELinux(JNIEnv* env);
extern int register_android_os_VintfObject(JNIEnv *env);
extern int register_android_os_VintfRuntimeInfo(JNIEnv *env);
extern int register_android_os_SystemProperties(JNIEnv *env);
extern int register_android_os_SystemClock(JNIEnv* env);
extern int register_android_os_Trace(JNIEnv* env);
extern int register_android_os_FileObserver(JNIEnv *env);
extern int register_android_os_UEventObserver(JNIEnv* env);
extern int register_android_os_MemoryFile(JNIEnv* env);
extern int register_android_os_SharedMemory(JNIEnv* env);
extern int register_android_net_LocalSocketImpl(JNIEnv* env);
extern int register_android_net_NetworkUtils(JNIEnv* env);
extern int register_android_text_AndroidCharacter(JNIEnv *env);
extern int register_android_text_Hyphenator(JNIEnv *env);
extern int register_android_opengl_classes(JNIEnv *env);
extern int register_android_ddm_DdmHandleNativeHeap(JNIEnv *env);
extern int register_android_server_NetworkManagementSocketTagger(JNIEnv* env);
extern int register_android_backup_BackupDataInput(JNIEnv *env);
extern int register_android_backup_BackupDataOutput(JNIEnv *env);
extern int register_android_backup_FileBackupHelperBase(JNIEnv *env);
extern int register_android_backup_BackupHelperDispatcher(JNIEnv *env);
extern int register_android_app_backup_FullBackup(JNIEnv *env);
extern int register_android_app_Activity(JNIEnv *env);
extern int register_android_app_ActivityThread(JNIEnv *env);
extern int register_android_app_NativeActivity(JNIEnv *env);
extern int register_android_media_RemoteDisplay(JNIEnv *env);
extern int register_android_util_jar_StrictJarFile(JNIEnv* env);
extern int register_android_view_InputChannel(JNIEnv* env);
extern int register_android_view_InputDevice(JNIEnv* env);
extern int register_android_view_InputEventReceiver(JNIEnv* env);
extern int register_android_view_InputEventSender(JNIEnv* env);
extern int register_android_view_InputQueue(JNIEnv* env);
extern int register_android_view_KeyCharacterMap(JNIEnv *env);
extern int register_android_view_KeyEvent(JNIEnv* env);
extern int register_android_view_MotionEvent(JNIEnv* env);
extern int register_android_view_PointerIcon(JNIEnv* env);
extern int register_android_view_VelocityTracker(JNIEnv* env);
extern int register_android_content_res_ObbScanner(JNIEnv* env);
extern int register_android_content_res_Configuration(JNIEnv* env);
extern int register_android_animation_PropertyValuesHolder(JNIEnv *env);
extern int register_android_security_Scrypt(JNIEnv *env);
extern int register_com_android_internal_content_NativeLibraryHelper(JNIEnv *env);
extern int register_com_android_internal_net_NetworkStatsFactory(JNIEnv *env);
extern int register_com_android_internal_os_AtomicDirectory(JNIEnv *env);
extern int register_com_android_internal_os_ClassLoaderFactory(JNIEnv* env);
extern int register_com_android_internal_os_FuseAppLoop(JNIEnv* env);
extern int register_com_android_internal_os_Zygote(JNIEnv *env);
extern int register_com_android_internal_os_ZygoteInit(JNIEnv *env);
extern int register_com_android_internal_util_VirtualRefBasePtr(JNIEnv *env);

// Namespace for Android Runtime flags applied during boot time.
static const char* RUNTIME_NATIVE_BOOT_NAMESPACE = "runtime_native_boot";
// Feature flag name to enable/disable generational garbage collection in ART's
// Concurrent Copying (CC) garbage collector.
static const char* ENABLE_GENERATIONAL_CC = "enable_generational_cc";
// Runtime option enabling generational garbage collection in ART's Concurrent
// Copying (CC) garbage collector.
static const char* kGenerationalCCRuntimeOption = "-Xgc:generational_cc";
// Runtime option disabling generational garbage collection in ART's Concurrent
// Copying (CC) garbage collector.
static const char* kNoGenerationalCCRuntimeOption = "-Xgc:nogenerational_cc";

static AndroidRuntime* gCurRuntime = NULL;

/*
 * Code written in the Java Programming Language calls here from main().
 */
static void com_android_internal_os_RuntimeInit_nativeFinishInit(JNIEnv* env, jobject clazz)
{
    gCurRuntime->onStarted();
}

static void com_android_internal_os_ZygoteInit_nativeZygoteInit(JNIEnv* env, jobject clazz)
{
    gCurRuntime->onZygoteInit();
}

static void com_android_internal_os_RuntimeInit_nativeSetExitWithoutCleanup(JNIEnv* env,
        jobject clazz, jboolean exitWithoutCleanup)
{
    gCurRuntime->setExitWithoutCleanup(exitWithoutCleanup);
}

/*
 * JNI registration.
 */

int register_com_android_internal_os_RuntimeInit(JNIEnv* env)
{
    const JNINativeMethod methods[] = {
        { "nativeFinishInit", "()V",
            (void*) com_android_internal_os_RuntimeInit_nativeFinishInit },
        { "nativeSetExitWithoutCleanup", "(Z)V",
            (void*) com_android_internal_os_RuntimeInit_nativeSetExitWithoutCleanup },
    };
    return jniRegisterNativeMethods(env, "com/android/internal/os/RuntimeInit",
        methods, NELEM(methods));
}

int register_com_android_internal_os_ZygoteInit_nativeZygoteInit(JNIEnv* env)
{
    const JNINativeMethod methods[] = {
        { "nativeZygoteInit", "()V",
            (void*) com_android_internal_os_ZygoteInit_nativeZygoteInit },
    };
    return jniRegisterNativeMethods(env, "com/android/internal/os/ZygoteInit",
        methods, NELEM(methods));
}

// ----------------------------------------------------------------------

/*static*/ JavaVM* AndroidRuntime::mJavaVM = NULL;

AndroidRuntime::AndroidRuntime(char* argBlockStart, const size_t argBlockLength) :
        mExitWithoutCleanup(false),
        mArgBlockStart(argBlockStart),
        mArgBlockLength(argBlockLength)
{
    SkGraphics::Init();

    // Pre-allocate enough space to hold a fair number of options.
    mOptions.setCapacity(20);

    assert(gCurRuntime == NULL);        // one per process
    gCurRuntime = this;
}

AndroidRuntime::~AndroidRuntime()
{
}

/*
 * Register native methods using JNI.
 */
/*static*/ int AndroidRuntime::registerNativeMethods(JNIEnv* env,
    const char* className, const JNINativeMethod* gMethods, int numMethods)
{
    return jniRegisterNativeMethods(env, className, gMethods, numMethods);
}

void AndroidRuntime::setArgv0(const char* argv0, bool setProcName) {
    if (setProcName) {
        int len = strlen(argv0);
        if (len < 15) {
            pthread_setname_np(pthread_self(), argv0);
        } else {
            pthread_setname_np(pthread_self(), argv0 + len - 15);
        }
    }
    memset(mArgBlockStart, 0, mArgBlockLength);
    strlcpy(mArgBlockStart, argv0, mArgBlockLength);
}

status_t AndroidRuntime::callMain(const String8& className, jclass clazz,
    const Vector<String8>& args)
{
    JNIEnv* env;
    jmethodID methodId;

    ALOGD("Calling main entry %s", className.string());

    env = getJNIEnv();
    if (clazz == NULL || env == NULL) {
        return UNKNOWN_ERROR;
    }

    methodId = env->GetStaticMethodID(clazz, "main", "([Ljava/lang/String;)V");
    if (methodId == NULL) {
        ALOGE("ERROR: could not find method %s.main(String[])\n", className.string());
        return UNKNOWN_ERROR;
    }

    /*
     * We want to call main() with a String array with our arguments in it.
     * Create an array and populate it.
     */
    jclass stringClass;
    jobjectArray strArray;

    const size_t numArgs = args.size();
    stringClass = env->FindClass("java/lang/String");
    strArray = env->NewObjectArray(numArgs, stringClass, NULL);

    for (size_t i = 0; i < numArgs; i++) {
        jstring argStr = env->NewStringUTF(args[i].string());
        env->SetObjectArrayElement(strArray, i, argStr);
    }

    env->CallStaticVoidMethod(clazz, methodId, strArray);
    return NO_ERROR;
}

/*
 * The VM calls this through the "exit" hook.
 */
static void runtime_exit(int code)
{
    gCurRuntime->exit(code);
}

/*
 * The VM calls this through the "vfprintf" hook.
 *
 * We ignore "fp" and just write the results to the log file.
 */
static void runtime_vfprintf(FILE* fp, const char* format, va_list ap)
{
    LOG_PRI_VA(ANDROID_LOG_INFO, "vm-printf", format, ap);
}

/**
 * The VM calls this when mutex contention debugging is enabled to
 * determine whether or not the blocked thread was a "sensitive thread"
 * for user responsiveness/smoothess.
 *
 * Our policy for this is whether or not we're tracing any StrictMode
 * events on this thread (which we might've inherited via Binder calls
 * into us)
 */
static bool runtime_isSensitiveThread() {
    IPCThreadState* state = IPCThreadState::selfOrNull();
    return state && state->getStrictModePolicy() != 0;
}

static int hasDir(const char* dir)
{
    struct stat s;
    int res = stat(dir, &s);
    if (res == 0) {
        return S_ISDIR(s.st_mode);
    }
    return 0;
}

static bool hasFile(const char* file) {
    struct stat s;
    int res = stat(file, &s);
    if (res == 0) {
        return S_ISREG(s.st_mode);
    }
    return false;
}

/*
 * Read the persistent locale. Inspects the following system properties
 * (in order) and returns the first non-empty property in the list :
 *
 * (1) persist.sys.locale
 * (2) persist.sys.language/country/localevar (country and localevar are
 * inspected iff. language is non-empty.
 * (3) ro.product.locale
 * (4) ro.product.locale.language/region
 *
 * Note that we need to inspect persist.sys.language/country/localevar to
 * preserve language settings for devices that are upgrading from Lollipop
 * to M. The same goes for ro.product.locale.language/region as well.
 */
const std::string readLocale()
{
    const std::string locale = GetProperty("persist.sys.locale", "");
    if (!locale.empty()) {
        return locale;
    }

    const std::string language = GetProperty("persist.sys.language", "");
    if (!language.empty()) {
        const std::string country = GetProperty("persist.sys.country", "");
        const std::string variant = GetProperty("persist.sys.localevar", "");

        std::string out = language;
        if (!country.empty()) {
            out = out + "-" + country;
        }

        if (!variant.empty()) {
            out = out + "-" + variant;
        }

        return out;
    }

    const std::string productLocale = GetProperty("ro.product.locale", "");
    if (!productLocale.empty()) {
        return productLocale;
    }

    // If persist.sys.locale and ro.product.locale are missing,
    // construct a locale value from the individual locale components.
    const std::string productLanguage = GetProperty("ro.product.locale.language", "en");
    const std::string productRegion = GetProperty("ro.product.locale.region", "US");

    return productLanguage + "-" + productRegion;
}

void AndroidRuntime::addOption(const char* optionString, void* extraInfo)
{
    JavaVMOption opt;
    opt.optionString = optionString;
    opt.extraInfo = extraInfo;
    mOptions.add(opt);
}

/*
 * Parse a property containing space-separated options that should be
 * passed directly to the VM, e.g. "-Xmx32m -verbose:gc -Xregenmap".
 *
 * This will cut up "extraOptsBuf" as we chop it into individual options.
 *
 * If "quotingArg" is non-null, it is passed before each extra option in mOptions.
 *
 * Adds the strings, if any, to mOptions.
 */
void AndroidRuntime::parseExtraOpts(char* extraOptsBuf, const char* quotingArg)
{
    char* start = extraOptsBuf;
    char* end = NULL;
    while (*start != '\0') {
        while (*start == ' ')                   /* skip leading whitespace */
            start++;
        if (*start == '\0')                     /* was trailing ws, bail */
            break;

        end = start+1;
        while (*end != ' ' && *end != '\0')     /* find end of token */
            end++;
        if (*end == ' ')
            *end++ = '\0';          /* mark end, advance to indicate more */

        if (quotingArg != NULL) {
            addOption(quotingArg);
        }
        addOption(start);
        start = end;
    }
}

/*
 * Reads a "property" into "buffer" with a default of "defaultArg". If
 * the property is non-empty, it is treated as a runtime option such
 * as "-Xmx32m".
 *
 * The "runtimeArg" is a prefix for the option such as "-Xms" or "-Xmx".
 *
 * If an argument is found, it is added to mOptions.
 *
 * If an option is found, it is added to mOptions and true is
 * returned. Otherwise false is returned.
 */
bool AndroidRuntime::parseRuntimeOption(const char* property,
                                        char* buffer,
                                        const char* runtimeArg,
                                        const char* defaultArg)
{
    strcpy(buffer, runtimeArg);
    size_t runtimeArgLen = strlen(runtimeArg);
    property_get(property, buffer+runtimeArgLen, defaultArg);
    if (buffer[runtimeArgLen] == '\0') {
        return false;
    }
    addOption(buffer);
    return true;
}

/*
 * Reads a "property" into "buffer". If the property is non-empty, it
 * is treated as a dex2oat compiler option that should be
 * passed as a quoted option, e.g. "-Ximage-compiler-option --compiler-filter=assume-verified".
 *
 * The "compilerArg" is a prefix for the option such as "--compiler-filter=".
 *
 * The "quotingArg" should be "-Ximage-compiler-option" or "-Xcompiler-option".
 *
 * If an option is found, it is added to mOptions and true is
 * returned. Otherwise false is returned.
 */
bool AndroidRuntime::parseCompilerOption(const char* property,
                                         char* buffer,
                                         const char* compilerArg,
                                         const char* quotingArg)
{
    strcpy(buffer, compilerArg);
    size_t compilerArgLen = strlen(compilerArg);
    property_get(property, buffer+compilerArgLen, "");
    if (buffer[compilerArgLen] == '\0') {
        return false;
    }
    addOption(quotingArg);
    addOption(buffer);
    return true;
}

/*
 * Reads a "property" into "buffer". If the property is non-empty, it
 * is treated as a dex2oat compiler runtime option that should be
 * passed as a quoted option, e.g. "-Ximage-compiler-option
 * --runtime-arg -Ximage-compiler-option -Xmx32m".
 *
 * The "runtimeArg" is a prefix for the option such as "-Xms" or "-Xmx".
 *
 * The "quotingArg" should be "-Ximage-compiler-option" or "-Xcompiler-option".
 *
 * If an option is found, it is added to mOptions and true is
 * returned. Otherwise false is returned.
 */
bool AndroidRuntime::parseCompilerRuntimeOption(const char* property,
                                                char* buffer,
                                                const char* runtimeArg,
                                                const char* quotingArg)
{
    strcpy(buffer, runtimeArg);
    size_t runtimeArgLen = strlen(runtimeArg);
    property_get(property, buffer+runtimeArgLen, "");
    if (buffer[runtimeArgLen] == '\0') {
        return false;
    }
    addOption(quotingArg);
    addOption("--runtime-arg");
    addOption(quotingArg);
    addOption(buffer);
    return true;
}

/*
 * Start the Dalvik Virtual Machine.
 *
 * Various arguments, most determined by system properties, are passed in.
 * The "mOptions" vector is updated.
 *
 * CAUTION: when adding options in here, be careful not to put the
 * char buffer inside a nested scope.  Adding the buffer to the
 * options using mOptions.add() does not copy the buffer, so if the
 * buffer goes out of scope the option may be overwritten.  It's best
 * to put the buffer at the top of the function so that it is more
 * unlikely that someone will surround it in a scope at a later time
 * and thus introduce a bug.
 *
 * Returns 0 on success.
 */
int AndroidRuntime::startVm(JavaVM** pJavaVM, JNIEnv** pEnv, bool zygote)
{
    JavaVMInitArgs initArgs;
    char propBuf[PROPERTY_VALUE_MAX];
    char jniOptsBuf[sizeof("-Xjniopts:")-1 + PROPERTY_VALUE_MAX];
    char heapstartsizeOptsBuf[sizeof("-Xms")-1 + PROPERTY_VALUE_MAX];
    char heapsizeOptsBuf[sizeof("-Xmx")-1 + PROPERTY_VALUE_MAX];
    char heapgrowthlimitOptsBuf[sizeof("-XX:HeapGrowthLimit=")-1 + PROPERTY_VALUE_MAX];
    char heapminfreeOptsBuf[sizeof("-XX:HeapMinFree=")-1 + PROPERTY_VALUE_MAX];
    char heapmaxfreeOptsBuf[sizeof("-XX:HeapMaxFree=")-1 + PROPERTY_VALUE_MAX];
    char usejitOptsBuf[sizeof("-Xusejit:")-1 + PROPERTY_VALUE_MAX];
    char jitpthreadpriorityOptsBuf[sizeof("-Xjitpthreadpriority:")-1 + PROPERTY_VALUE_MAX];
    char jitmaxsizeOptsBuf[sizeof("-Xjitmaxsize:")-1 + PROPERTY_VALUE_MAX];
    char jitinitialsizeOptsBuf[sizeof("-Xjitinitialsize:")-1 + PROPERTY_VALUE_MAX];
    char jitthresholdOptsBuf[sizeof("-Xjitthreshold:")-1 + PROPERTY_VALUE_MAX];
    char useJitProfilesOptsBuf[sizeof("-Xjitsaveprofilinginfo:")-1 + PROPERTY_VALUE_MAX];
    char jitprithreadweightOptBuf[sizeof("-Xjitprithreadweight:")-1 + PROPERTY_VALUE_MAX];
    char jittransitionweightOptBuf[sizeof("-Xjittransitionweight:")-1 + PROPERTY_VALUE_MAX];
    char hotstartupsamplesOptsBuf[sizeof("-Xps-hot-startup-method-samples:")-1 + PROPERTY_VALUE_MAX];
    char madviseRandomOptsBuf[sizeof("-XX:MadviseRandomAccess:")-1 + PROPERTY_VALUE_MAX];
    char gctypeOptsBuf[sizeof("-Xgc:")-1 + PROPERTY_VALUE_MAX];
    char backgroundgcOptsBuf[sizeof("-XX:BackgroundGC=")-1 + PROPERTY_VALUE_MAX];
    char heaptargetutilizationOptsBuf[sizeof("-XX:HeapTargetUtilization=")-1 + PROPERTY_VALUE_MAX];
    char foregroundHeapGrowthMultiplierOptsBuf[
            sizeof("-XX:ForegroundHeapGrowthMultiplier=")-1 + PROPERTY_VALUE_MAX];
    char cachePruneBuf[sizeof("-Xzygote-max-boot-retry=")-1 + PROPERTY_VALUE_MAX];
    char dex2oatXmsImageFlagsBuf[sizeof("-Xms")-1 + PROPERTY_VALUE_MAX];
    char dex2oatXmxImageFlagsBuf[sizeof("-Xmx")-1 + PROPERTY_VALUE_MAX];
    char dex2oatXmsFlagsBuf[sizeof("-Xms")-1 + PROPERTY_VALUE_MAX];
    char dex2oatXmxFlagsBuf[sizeof("-Xmx")-1 + PROPERTY_VALUE_MAX];
    char dex2oatCompilerFilterBuf[sizeof("--compiler-filter=")-1 + PROPERTY_VALUE_MAX];
    char dex2oatImageCompilerFilterBuf[sizeof("--compiler-filter=")-1 + PROPERTY_VALUE_MAX];
    char dex2oatThreadsBuf[sizeof("-j")-1 + PROPERTY_VALUE_MAX];
    char dex2oatThreadsImageBuf[sizeof("-j")-1 + PROPERTY_VALUE_MAX];
    char dex2oat_isa_variant_key[PROPERTY_KEY_MAX];
    char dex2oat_isa_variant[sizeof("--instruction-set-variant=") -1 + PROPERTY_VALUE_MAX];
    char dex2oat_isa_features_key[PROPERTY_KEY_MAX];
    char dex2oat_isa_features[sizeof("--instruction-set-features=") -1 + PROPERTY_VALUE_MAX];
    char dex2oatFlagsBuf[PROPERTY_VALUE_MAX];
    char dex2oatImageFlagsBuf[PROPERTY_VALUE_MAX];
    char extraOptsBuf[PROPERTY_VALUE_MAX];
    char voldDecryptBuf[PROPERTY_VALUE_MAX];
    enum {
      kEMDefault,
      kEMIntPortable,
      kEMIntFast,
      kEMJitCompiler,
    } executionMode = kEMDefault;
    char localeOption[sizeof("-Duser.locale=") + PROPERTY_VALUE_MAX];
    char lockProfThresholdBuf[sizeof("-Xlockprofthreshold:")-1 + PROPERTY_VALUE_MAX];
    char nativeBridgeLibrary[sizeof("-XX:NativeBridge=") + PROPERTY_VALUE_MAX];
    char cpuAbiListBuf[sizeof("--cpu-abilist=") + PROPERTY_VALUE_MAX];
    char methodTraceFileBuf[sizeof("-Xmethod-trace-file:") + PROPERTY_VALUE_MAX];
    char methodTraceFileSizeBuf[sizeof("-Xmethod-trace-file-size:") + PROPERTY_VALUE_MAX];
    std::string fingerprintBuf;
    char jdwpProviderBuf[sizeof("-XjdwpProvider:") - 1 + PROPERTY_VALUE_MAX];
    char bootImageBuf[sizeof("-Ximage:") - 1 + PROPERTY_VALUE_MAX];

    if (parseRuntimeOption("dalvik.vm.boot-image", bootImageBuf, "-Ximage:")) {
        ALOGI("Boot image: '%s'\n", bootImageBuf);
    }

    bool checkJni = false;
    property_get("dalvik.vm.checkjni", propBuf, "");
    if (strcmp(propBuf, "true") == 0) {
        checkJni = true;
    } else if (strcmp(propBuf, "false") != 0) {
        /* property is neither true nor false; fall back on kernel parameter */
        property_get("ro.kernel.android.checkjni", propBuf, "");
        if (propBuf[0] == '1') {
            checkJni = true;
        }
    }
    ALOGV("CheckJNI is %s\n", checkJni ? "ON" : "OFF");
    if (checkJni) {
        /* extended JNI checking */
        addOption("-Xcheck:jni");

        /* with -Xcheck:jni, this provides a JNI function call trace */
        //addOption("-verbose:jni");
    }

    property_get("dalvik.vm.execution-mode", propBuf, "");
    if (strcmp(propBuf, "int:portable") == 0) {
        executionMode = kEMIntPortable;
    } else if (strcmp(propBuf, "int:fast") == 0) {
        executionMode = kEMIntFast;
    } else if (strcmp(propBuf, "int:jit") == 0) {
        executionMode = kEMJitCompiler;
    }

    strcpy(jniOptsBuf, "-Xjniopts:");
    if (parseRuntimeOption("dalvik.vm.jniopts", jniOptsBuf, "-Xjniopts:")) {
        ALOGI("JNI options: '%s'\n", jniOptsBuf);
    }

    /* route exit() to our handler */
    addOption("exit", (void*) runtime_exit);

    /* route fprintf() to our handler */
    addOption("vfprintf", (void*) runtime_vfprintf);

    /* register the framework-specific "is sensitive thread" hook */
    addOption("sensitiveThread", (void*) runtime_isSensitiveThread);

    /* enable verbose; standard options are { jni, gc, class } */
    //addOption("-verbose:jni");
    addOption("-verbose:gc");
    //addOption("-verbose:class");

    /*
     * The default starting and maximum size of the heap.  Larger
     * values should be specified in a product property override.
     */
    parseRuntimeOption("dalvik.vm.heapstartsize", heapstartsizeOptsBuf, "-Xms", "4m");
    parseRuntimeOption("dalvik.vm.heapsize", heapsizeOptsBuf, "-Xmx", "16m");

    parseRuntimeOption("dalvik.vm.heapgrowthlimit", heapgrowthlimitOptsBuf, "-XX:HeapGrowthLimit=");
    parseRuntimeOption("dalvik.vm.heapminfree", heapminfreeOptsBuf, "-XX:HeapMinFree=");
    parseRuntimeOption("dalvik.vm.heapmaxfree", heapmaxfreeOptsBuf, "-XX:HeapMaxFree=");
    parseRuntimeOption("dalvik.vm.heaptargetutilization",
                       heaptargetutilizationOptsBuf,
                       "-XX:HeapTargetUtilization=");

    /* Foreground heap growth multiplier option */
    parseRuntimeOption("dalvik.vm.foreground-heap-growth-multiplier",
                       foregroundHeapGrowthMultiplierOptsBuf,
                       "-XX:ForegroundHeapGrowthMultiplier=");

    /*
     * JIT related options.
     */
    parseRuntimeOption("dalvik.vm.usejit", usejitOptsBuf, "-Xusejit:");
    parseRuntimeOption("dalvik.vm.jitmaxsize", jitmaxsizeOptsBuf, "-Xjitmaxsize:");
    parseRuntimeOption("dalvik.vm.jitinitialsize", jitinitialsizeOptsBuf, "-Xjitinitialsize:");
    parseRuntimeOption("dalvik.vm.jitthreshold", jitthresholdOptsBuf, "-Xjitthreshold:");
    parseRuntimeOption("dalvik.vm.jitpthreadpriority",
                       jitpthreadpriorityOptsBuf,
                       "-Xjitpthreadpriority:");
    property_get("dalvik.vm.usejitprofiles", useJitProfilesOptsBuf, "");
    if (strcmp(useJitProfilesOptsBuf, "true") == 0) {
        addOption("-Xjitsaveprofilinginfo");
    }

    parseRuntimeOption("dalvik.vm.jitprithreadweight",
                       jitprithreadweightOptBuf,
                       "-Xjitprithreadweight:");

    parseRuntimeOption("dalvik.vm.jittransitionweight",
                       jittransitionweightOptBuf,
                       "-Xjittransitionweight:");

    property_get("dalvik.vm.profilebootimage", propBuf, "");
    if (strcmp(propBuf, "true") == 0) {
        addOption("-Xps-profile-boot-class-path");
        addOption("-Xps-profile-aot-code");
    }

    /*
     * Madvise related options.
     */
    parseRuntimeOption("dalvik.vm.madvise-random", madviseRandomOptsBuf, "-XX:MadviseRandomAccess:");

    /*
     * Profile related options.
     */
    parseRuntimeOption("dalvik.vm.hot-startup-method-samples", hotstartupsamplesOptsBuf,
            "-Xps-hot-startup-method-samples:");

    property_get("ro.config.low_ram", propBuf, "");
    if (strcmp(propBuf, "true") == 0) {
      addOption("-XX:LowMemoryMode");
    }

    /*
     * Garbage-collection related options.
     */
    parseRuntimeOption("dalvik.vm.gctype", gctypeOptsBuf, "-Xgc:");

    // If it set, honor the "enable_generational_cc" device configuration;
    // otherwise, let the runtime use its default behavior.
    std::string enable_generational_cc =
        server_configurable_flags::GetServerConfigurableFlag(RUNTIME_NATIVE_BOOT_NAMESPACE,
                                                             ENABLE_GENERATIONAL_CC,
                                                             /*default_value=*/ "");
    if (enable_generational_cc == "true") {
        addOption(kGenerationalCCRuntimeOption);
    } else if (enable_generational_cc == "false") {
        addOption(kNoGenerationalCCRuntimeOption);
    }

    parseRuntimeOption("dalvik.vm.backgroundgctype", backgroundgcOptsBuf, "-XX:BackgroundGC=");

    /*
     * Enable debugging only for apps forked from zygote.
     */
    if (zygote) {
      // Set the JDWP provider and required arguments. By default let the runtime choose how JDWP is
      // implemented. When this is not set the runtime defaults to not allowing JDWP.
      addOption("-XjdwpOptions:suspend=n,server=y");
      parseRuntimeOption("dalvik.vm.jdwp-provider",
                         jdwpProviderBuf,
                         "-XjdwpProvider:",
                         "default");
    }

    parseRuntimeOption("dalvik.vm.lockprof.threshold",
                       lockProfThresholdBuf,
                       "-Xlockprofthreshold:");

    if (executionMode == kEMIntPortable) {
        addOption("-Xint:portable");
    } else if (executionMode == kEMIntFast) {
        addOption("-Xint:fast");
    } else if (executionMode == kEMJitCompiler) {
        addOption("-Xint:jit");
    }

    // If we are booting without the real /data, don't spend time compiling.
    property_get("vold.decrypt", voldDecryptBuf, "");
    bool skip_compilation = ((strcmp(voldDecryptBuf, "trigger_restart_min_framework") == 0) ||
                             (strcmp(voldDecryptBuf, "1") == 0));

    // Extra options for boot.art/boot.oat image generation.
    parseCompilerRuntimeOption("dalvik.vm.image-dex2oat-Xms", dex2oatXmsImageFlagsBuf,
                               "-Xms", "-Ximage-compiler-option");
    parseCompilerRuntimeOption("dalvik.vm.image-dex2oat-Xmx", dex2oatXmxImageFlagsBuf,
                               "-Xmx", "-Ximage-compiler-option");
    if (skip_compilation) {
        addOption("-Ximage-compiler-option");
        addOption("--compiler-filter=assume-verified");
    } else {
        parseCompilerOption("dalvik.vm.image-dex2oat-filter", dex2oatImageCompilerFilterBuf,
                            "--compiler-filter=", "-Ximage-compiler-option");
    }

    // If there is a boot profile, it takes precedence over the image and preloaded classes.
    if (hasFile("/system/etc/boot-image.prof")) {
        addOption("-Ximage-compiler-option");
        addOption("--profile-file=/system/etc/boot-image.prof");
        addOption("-Ximage-compiler-option");
        addOption("--compiler-filter=speed-profile");
    } else {
        // Make sure there is a preloaded-classes file.
        if (!hasFile("/system/etc/preloaded-classes")) {
            ALOGE("Missing preloaded-classes file, /system/etc/preloaded-classes not found: %s\n",
                  strerror(errno));
            return -1;
        }
        addOption("-Ximage-compiler-option");
        addOption("--image-classes=/system/etc/preloaded-classes");

        // If there is a dirty-image-objects file, push it.
        if (hasFile("/system/etc/dirty-image-objects")) {
            addOption("-Ximage-compiler-option");
            addOption("--dirty-image-objects=/system/etc/dirty-image-objects");
        }
    }

    property_get("dalvik.vm.image-dex2oat-flags", dex2oatImageFlagsBuf, "");
    parseExtraOpts(dex2oatImageFlagsBuf, "-Ximage-compiler-option");

    // Extra options for DexClassLoader.
    parseCompilerRuntimeOption("dalvik.vm.dex2oat-Xms", dex2oatXmsFlagsBuf,
                               "-Xms", "-Xcompiler-option");
    parseCompilerRuntimeOption("dalvik.vm.dex2oat-Xmx", dex2oatXmxFlagsBuf,
                               "-Xmx", "-Xcompiler-option");
    if (skip_compilation) {
        addOption("-Xcompiler-option");
        addOption("--compiler-filter=assume-verified");

        // We skip compilation when a minimal runtime is brought up for decryption. In that case
        // /data is temporarily backed by a tmpfs, which is usually small.
        // If the system image contains prebuilts, they will be relocated into the tmpfs. In this
        // specific situation it is acceptable to *not* relocate and run out of the prebuilts
        // directly instead.
        addOption("--runtime-arg");
        addOption("-Xnorelocate");
    } else {
        parseCompilerOption("dalvik.vm.dex2oat-filter", dex2oatCompilerFilterBuf,
                            "--compiler-filter=", "-Xcompiler-option");
    }
    parseCompilerOption("dalvik.vm.dex2oat-threads", dex2oatThreadsBuf, "-j", "-Xcompiler-option");
    parseCompilerOption("dalvik.vm.image-dex2oat-threads", dex2oatThreadsImageBuf, "-j",
                        "-Ximage-compiler-option");

    // The runtime will compile a boot image, when necessary, not using installd. Thus, we need to
    // pass the instruction-set-features/variant as an image-compiler-option.
    // Note: it is OK to reuse the buffer, as the values are exactly the same between
    //       * compiler-option, used for runtime compilation (DexClassLoader)
    //       * image-compiler-option, used for boot-image compilation on device

    // Copy the variant.
    sprintf(dex2oat_isa_variant_key, "dalvik.vm.isa.%s.variant", ABI_STRING);
    parseCompilerOption(dex2oat_isa_variant_key, dex2oat_isa_variant,
                        "--instruction-set-variant=", "-Ximage-compiler-option");
    parseCompilerOption(dex2oat_isa_variant_key, dex2oat_isa_variant,
                        "--instruction-set-variant=", "-Xcompiler-option");
    // Copy the features.
    sprintf(dex2oat_isa_features_key, "dalvik.vm.isa.%s.features", ABI_STRING);
    parseCompilerOption(dex2oat_isa_features_key, dex2oat_isa_features,
                        "--instruction-set-features=", "-Ximage-compiler-option");
    parseCompilerOption(dex2oat_isa_features_key, dex2oat_isa_features,
                        "--instruction-set-features=", "-Xcompiler-option");


    property_get("dalvik.vm.dex2oat-flags", dex2oatFlagsBuf, "");
    parseExtraOpts(dex2oatFlagsBuf, "-Xcompiler-option");

    /* extra options; parse this late so it overrides others */
    property_get("dalvik.vm.extra-opts", extraOptsBuf, "");
    parseExtraOpts(extraOptsBuf, NULL);

    /* Set the properties for locale */
    {
        strcpy(localeOption, "-Duser.locale=");
        const std::string locale = readLocale();
        strncat(localeOption, locale.c_str(), PROPERTY_VALUE_MAX);
        addOption(localeOption);
    }

    // Trace files are stored in /data/misc/trace which is writable only in debug mode.
    property_get("ro.debuggable", propBuf, "0");
    if (strcmp(propBuf, "1") == 0) {
        property_get("dalvik.vm.method-trace", propBuf, "false");
        if (strcmp(propBuf, "true") == 0) {
            addOption("-Xmethod-trace");
            parseRuntimeOption("dalvik.vm.method-trace-file",
                               methodTraceFileBuf,
                               "-Xmethod-trace-file:");
            parseRuntimeOption("dalvik.vm.method-trace-file-siz",
                               methodTraceFileSizeBuf,
                               "-Xmethod-trace-file-size:");
            property_get("dalvik.vm.method-trace-stream", propBuf, "false");
            if (strcmp(propBuf, "true") == 0) {
                addOption("-Xmethod-trace-stream");
            }
        }
    }

    // Native bridge library. "0" means that native bridge is disabled.
    //
    // Note: bridging is only enabled for the zygote. Other runs of
    //       app_process may not have the permissions to mount etc.
    property_get("ro.dalvik.vm.native.bridge", propBuf, "");
    if (propBuf[0] == '\0') {
        ALOGW("ro.dalvik.vm.native.bridge is not expected to be empty");
    } else if (zygote && strcmp(propBuf, "0") != 0) {
        snprintf(nativeBridgeLibrary, sizeof("-XX:NativeBridge=") + PROPERTY_VALUE_MAX,
                 "-XX:NativeBridge=%s", propBuf);
        addOption(nativeBridgeLibrary);
    }

#if defined(__LP64__)
    const char* cpu_abilist_property_name = "ro.product.cpu.abilist64";
#else
    const char* cpu_abilist_property_name = "ro.product.cpu.abilist32";
#endif  // defined(__LP64__)
    property_get(cpu_abilist_property_name, propBuf, "");
    if (propBuf[0] == '\0') {
        ALOGE("%s is not expected to be empty", cpu_abilist_property_name);
        return -1;
    }
    snprintf(cpuAbiListBuf, sizeof(cpuAbiListBuf), "--cpu-abilist=%s", propBuf);
    addOption(cpuAbiListBuf);

    // Dalvik-cache pruning counter.
    parseRuntimeOption("dalvik.vm.zygote.max-boot-retry", cachePruneBuf,
                       "-Xzygote-max-boot-retry=");

    /*
     * When running with debug.generate-debug-info, add --generate-debug-info to
     * the compiler options so that the boot image, if it is compiled on device,
     * will include native debugging information.
     */
    property_get("debug.generate-debug-info", propBuf, "");
    if (strcmp(propBuf, "true") == 0) {
        addOption("-Xcompiler-option");
        addOption("--generate-debug-info");
        addOption("-Ximage-compiler-option");
        addOption("--generate-debug-info");
    }

    // The mini-debug-info makes it possible to backtrace through JIT code.
    if (property_get_bool("dalvik.vm.minidebuginfo", 0)) {
        addOption("-Xcompiler-option");
        addOption("--generate-mini-debug-info");
    }

    /*
     * Retrieve the build fingerprint and provide it to the runtime. That way, ANR dumps will
     * contain the fingerprint and can be parsed.
     * Fingerprints are potentially longer than PROPERTY_VALUE_MAX, so parseRuntimeOption() cannot
     * be used here.
     * Do not ever re-assign fingerprintBuf as its c_str() value is stored in mOptions.
     */
    std::string fingerprint = GetProperty("ro.build.fingerprint", "");
    if (!fingerprint.empty()) {
        fingerprintBuf = "-Xfingerprint:" + fingerprint;
        addOption(fingerprintBuf.c_str());
    }

    initArgs.version = JNI_VERSION_1_4;
    initArgs.options = mOptions.editArray();
    initArgs.nOptions = mOptions.size();
    initArgs.ignoreUnrecognized = JNI_FALSE;

    /*
     * Initialize the VM.
     *
     * The JavaVM* is essentially per-process, and the JNIEnv* is per-thread.
     * If this call succeeds, the VM is ready, and we can start issuing
     * JNI calls.
     */
    if (JNI_CreateJavaVM(pJavaVM, pEnv, &initArgs) < 0) {
        ALOGE("JNI_CreateJavaVM failed\n");
        return -1;
    }

    return 0;
}

char* AndroidRuntime::toSlashClassName(const char* className)
{
    char* result = strdup(className);
    for (char* cp = result; *cp != '\0'; cp++) {
        if (*cp == '.') {
            *cp = '/';
        }
    }
    return result;
}

/** Create a Java string from an ASCII or Latin-1 string */
jstring AndroidRuntime::NewStringLatin1(JNIEnv* env, const char* bytes) {
    if (!bytes) return NULL;
    int length = strlen(bytes);
    jchar* buffer = (jchar *)alloca(length * sizeof(jchar));
    if (!buffer) return NULL;
    jchar* chp = buffer;
    for (int i = 0; i < length; i++) {
        *chp++ = *bytes++;
    }
    return env->NewString(buffer, length);
}


/*
 * Start the Android runtime.  This involves starting the virtual machine
 * and calling the "static void main(String[] args)" method in the class
 * named by "className".
 *
 * Passes the main function two arguments, the class name and the specified
 * options string.
 */
void AndroidRuntime::start(const char* className, const Vector<String8>& options, bool zygote)
{
    ALOGD(">>>>>> START %s uid %d <<<<<<\n",
            className != NULL ? className : "(unknown)", getuid());

    static const String8 startSystemServer("start-system-server");

    /*
     * 'startSystemServer == true' means runtime is obsolete and not run from
     * init.rc anymore, so we print out the boot start event here.
     */
    for (size_t i = 0; i < options.size(); ++i) {
        if (options[i] == startSystemServer) {
           /* track our progress through the boot sequence */
           const int LOG_BOOT_PROGRESS_START = 3000;
           LOG_EVENT_LONG(LOG_BOOT_PROGRESS_START,  ns2ms(systemTime(SYSTEM_TIME_MONOTONIC)));
        }
    }

    const char* rootDir = getenv("ANDROID_ROOT");
    if (rootDir == NULL) {
        rootDir = "/system";
        if (!hasDir("/system")) {
            LOG_FATAL("No root directory specified, and /system does not exist.");
            return;
        }
        setenv("ANDROID_ROOT", rootDir, 1);
    }

    const char* runtimeRootDir = getenv("ANDROID_RUNTIME_ROOT");
    if (runtimeRootDir == NULL) {
        LOG_FATAL("No runtime directory specified with ANDROID_RUNTIME_ROOT environment variable.");
        return;
    }

    //const char* kernelHack = getenv("LD_ASSUME_KERNEL");
    //ALOGD("Found LD_ASSUME_KERNEL='%s'\n", kernelHack);

    /* start the virtual machine */
    JniInvocation jni_invocation;
    jni_invocation.Init(NULL);
    JNIEnv* env;
    if (startVm(&mJavaVM, &env, zygote) != 0) {
        return;
    }
    onVmCreated(env);

    /*
     * Register android functions.
     */
    if (startReg(env) < 0) {
        ALOGE("Unable to register all android natives\n");
        return;
    }

    /*
     * We want to call main() with a String array with arguments in it.
     * At present we have two arguments, the class name and an option string.
     * Create an array to hold them.
     */
    jclass stringClass;
    jobjectArray strArray;
    jstring classNameStr;

    stringClass = env->FindClass("java/lang/String");
    assert(stringClass != NULL);
    strArray = env->NewObjectArray(options.size() + 1, stringClass, NULL);
    assert(strArray != NULL);
    classNameStr = env->NewStringUTF(className);
    assert(classNameStr != NULL);
    env->SetObjectArrayElement(strArray, 0, classNameStr);

    for (size_t i = 0; i < options.size(); ++i) {
        jstring optionsStr = env->NewStringUTF(options.itemAt(i).string());
        assert(optionsStr != NULL);
        env->SetObjectArrayElement(strArray, i + 1, optionsStr);
    }

    /*
     * Start VM.  This thread becomes the main thread of the VM, and will
     * not return until the VM exits.
     */
    char* slashClassName = toSlashClassName(className != NULL ? className : "");
    jclass startClass = env->FindClass(slashClassName);
    if (startClass == NULL) {
        ALOGE("JavaVM unable to locate class '%s'\n", slashClassName);
        /* keep going */
    } else {
        jmethodID startMeth = env->GetStaticMethodID(startClass, "main",
            "([Ljava/lang/String;)V");
        if (startMeth == NULL) {
            ALOGE("JavaVM unable to find main() in '%s'\n", className);
            /* keep going */
        } else {
            env->CallStaticVoidMethod(startClass, startMeth, strArray);

#if 0
            if (env->ExceptionCheck())
                threadExitUncaughtException(env);
#endif
        }
    }
    free(slashClassName);

    ALOGD("Shutting down VM\n");
    if (mJavaVM->DetachCurrentThread() != JNI_OK)
        ALOGW("Warning: unable to detach main thread\n");
    if (mJavaVM->DestroyJavaVM() != 0)
        ALOGW("Warning: VM did not shut down cleanly\n");
}

void AndroidRuntime::exit(int code)
{
    if (mExitWithoutCleanup) {
        ALOGI("VM exiting with result code %d, cleanup skipped.", code);
        ::_exit(code);
    } else {
        ALOGI("VM exiting with result code %d.", code);
        onExit(code);
        ::exit(code);
    }
}

void AndroidRuntime::onVmCreated(JNIEnv* env)
{
    // If AndroidRuntime had anything to do here, we'd have done it in 'start'.
}

/*
 * Get the JNIEnv pointer for this thread.
 *
 * Returns NULL if the slot wasn't allocated or populated.
 */
/*static*/ JNIEnv* AndroidRuntime::getJNIEnv()
{
    JNIEnv* env;
    JavaVM* vm = AndroidRuntime::getJavaVM();
    assert(vm != NULL);

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK)
        return NULL;
    return env;
}

/*
 * Makes the current thread visible to the VM.
 *
 * The JNIEnv pointer returned is only valid for the current thread, and
 * thus must be tucked into thread-local storage.
 */
static int javaAttachThread(const char* threadName, JNIEnv** pEnv)
{
    JavaVMAttachArgs args;
    JavaVM* vm;
    jint result;

    vm = AndroidRuntime::getJavaVM();
    assert(vm != NULL);

    args.version = JNI_VERSION_1_4;
    args.name = (char*) threadName;
    args.group = NULL;

    result = vm->AttachCurrentThread(pEnv, (void*) &args);
    if (result != JNI_OK)
        ALOGI("NOTE: attach of thread '%s' failed\n", threadName);

    return result;
}

/*
 * Detach the current thread from the set visible to the VM.
 */
static int javaDetachThread(void)
{
    JavaVM* vm;
    jint result;

    vm = AndroidRuntime::getJavaVM();
    assert(vm != NULL);

    result = vm->DetachCurrentThread();
    if (result != JNI_OK)
        ALOGE("ERROR: thread detach failed\n");
    return result;
}

/*
 * When starting a native thread that will be visible from the VM, we
 * bounce through this to get the right attach/detach action.
 * Note that this function calls free(args)
 */
/*static*/ int AndroidRuntime::javaThreadShell(void* args) {
    void* start = ((void**)args)[0];
    void* userData = ((void **)args)[1];
    char* name = (char*) ((void **)args)[2];        // we own this storage
    free(args);
    JNIEnv* env;
    int result;

    /* hook us into the VM */
    if (javaAttachThread(name, &env) != JNI_OK)
        return -1;

    /* start the thread running */
    result = (*(android_thread_func_t)start)(userData);

    /* unhook us */
    javaDetachThread();
    free(name);

    return result;
}

/*
 * This is invoked from androidCreateThreadEtc() via the callback
 * set with androidSetCreateThreadFunc().
 *
 * We need to create the new thread in such a way that it gets hooked
 * into the VM before it really starts executing.
 */
/*static*/ int AndroidRuntime::javaCreateThreadEtc(
                                android_thread_func_t entryFunction,
                                void* userData,
                                const char* threadName,
                                int32_t threadPriority,
                                size_t threadStackSize,
                                android_thread_id_t* threadId)
{
    void** args = (void**) malloc(3 * sizeof(void*));   // javaThreadShell must free
    int result;

    LOG_ALWAYS_FATAL_IF(threadName == nullptr, "threadName not provided to javaCreateThreadEtc");

    args[0] = (void*) entryFunction;
    args[1] = userData;
    args[2] = (void*) strdup(threadName);   // javaThreadShell must free

    result = androidCreateRawThreadEtc(AndroidRuntime::javaThreadShell, args,
        threadName, threadPriority, threadStackSize, threadId);
    return result;
}

/*
 * Create a thread that is visible from the VM.
 *
 * This is called from elsewhere in the library.
 */
/*static*/ android_thread_id_t AndroidRuntime::createJavaThread(const char* name,
    void (*start)(void *), void* arg)
{
    android_thread_id_t threadId = 0;
    javaCreateThreadEtc((android_thread_func_t) start, arg, name,
        ANDROID_PRIORITY_DEFAULT, 0, &threadId);
    return threadId;
}

#if 0
static void quickTest(void* arg)
{
    const char* str = (const char*) arg;

    printf("In quickTest: %s\n", str);
}
#endif

#ifdef NDEBUG
    #define REG_JNI(name)      { name }
    struct RegJNIRec {
        int (*mProc)(JNIEnv*);
    };
#else
    #define REG_JNI(name)      { name, #name }
    struct RegJNIRec {
        int (*mProc)(JNIEnv*);
        const char* mName;
    };
#endif

typedef void (*RegJAMProc)();

static int register_jni_procs(const RegJNIRec array[], size_t count, JNIEnv* env)
{
    for (size_t i = 0; i < count; i++) {
        if (array[i].mProc(env) < 0) {
#ifndef NDEBUG
            ALOGD("----------!!! %s failed to load\n", array[i].mName);
#endif
            return -1;
        }
    }
    return 0;
}

static const RegJNIRec gRegJNI[] = {
    REG_JNI(register_com_android_internal_os_RuntimeInit),
    REG_JNI(register_com_android_internal_os_ZygoteInit_nativeZygoteInit),
    REG_JNI(register_android_os_SystemClock),
    REG_JNI(register_android_util_EventLog),
    REG_JNI(register_android_util_Log),
    REG_JNI(register_android_util_MemoryIntArray),
    REG_JNI(register_android_util_PathParser),
    REG_JNI(register_android_util_StatsLog),
    REG_JNI(register_android_app_admin_SecurityLog),
    REG_JNI(register_android_content_AssetManager),
    REG_JNI(register_android_content_StringBlock),
    REG_JNI(register_android_content_XmlBlock),
    REG_JNI(register_android_content_res_ApkAssets),
    REG_JNI(register_android_text_AndroidCharacter),
    REG_JNI(register_android_text_Hyphenator),
    REG_JNI(register_android_view_InputDevice),
    REG_JNI(register_android_view_KeyCharacterMap),
    REG_JNI(register_android_os_Process),
    REG_JNI(register_android_os_SystemProperties),
    REG_JNI(register_android_os_Binder),
    REG_JNI(register_android_os_Parcel),
    REG_JNI(register_android_os_HidlSupport),
    REG_JNI(register_android_os_HwBinder),
    REG_JNI(register_android_os_HwBlob),
    REG_JNI(register_android_os_HwParcel),
    REG_JNI(register_android_os_HwRemoteBinder),
    REG_JNI(register_android_os_NativeHandle),
    REG_JNI(register_android_os_VintfObject),
    REG_JNI(register_android_os_VintfRuntimeInfo),
    REG_JNI(register_android_nio_utils),
    REG_JNI(register_android_graphics_Canvas),
    // This needs to be before register_android_graphics_Graphics, or the latter
    // will not be able to find the jmethodID for ColorSpace.get().
    REG_JNI(register_android_graphics_ColorSpace),
    REG_JNI(register_android_graphics_Graphics),
    REG_JNI(register_android_view_DisplayEventReceiver),
    REG_JNI(register_android_view_RenderNode),
    REG_JNI(register_android_view_RenderNodeAnimator),
    REG_JNI(register_android_view_DisplayListCanvas),
    REG_JNI(register_android_view_InputApplicationHandle),
    REG_JNI(register_android_view_InputWindowHandle),
    REG_JNI(register_android_view_TextureLayer),
    REG_JNI(register_android_view_ThreadedRenderer),
    REG_JNI(register_android_view_Surface),
    REG_JNI(register_android_view_SurfaceControl),
    REG_JNI(register_android_view_SurfaceSession),
    REG_JNI(register_android_view_CompositionSamplingListener),
    REG_JNI(register_android_view_TextureView),
    REG_JNI(register_com_android_internal_view_animation_NativeInterpolatorFactoryHelper),
    REG_JNI(register_com_google_android_gles_jni_EGLImpl),
    REG_JNI(register_com_google_android_gles_jni_GLImpl),
    REG_JNI(register_android_opengl_jni_EGL14),
    REG_JNI(register_android_opengl_jni_EGL15),
    REG_JNI(register_android_opengl_jni_EGLExt),
    REG_JNI(register_android_opengl_jni_GLES10),
    REG_JNI(register_android_opengl_jni_GLES10Ext),
    REG_JNI(register_android_opengl_jni_GLES11),
    REG_JNI(register_android_opengl_jni_GLES11Ext),
    REG_JNI(register_android_opengl_jni_GLES20),
    REG_JNI(register_android_opengl_jni_GLES30),
    REG_JNI(register_android_opengl_jni_GLES31),
    REG_JNI(register_android_opengl_jni_GLES31Ext),
    REG_JNI(register_android_opengl_jni_GLES32),

    REG_JNI(register_android_graphics_Bitmap),
    REG_JNI(register_android_graphics_BitmapFactory),
    REG_JNI(register_android_graphics_BitmapRegionDecoder),
    REG_JNI(register_android_graphics_ByteBufferStreamAdaptor),
    REG_JNI(register_android_graphics_Camera),
    REG_JNI(register_android_graphics_CreateJavaOutputStreamAdaptor),
    REG_JNI(register_android_graphics_CanvasProperty),
    REG_JNI(register_android_graphics_ColorFilter),
    REG_JNI(register_android_graphics_DrawFilter),
    REG_JNI(register_android_graphics_FontFamily),
    REG_JNI(register_android_graphics_GraphicBuffer),
    REG_JNI(register_android_graphics_ImageDecoder),
    REG_JNI(register_android_graphics_drawable_AnimatedImageDrawable),
    REG_JNI(register_android_graphics_Interpolator),
    REG_JNI(register_android_graphics_MaskFilter),
    REG_JNI(register_android_graphics_Matrix),
    REG_JNI(register_android_graphics_Movie),
    REG_JNI(register_android_graphics_NinePatch),
    REG_JNI(register_android_graphics_Paint),
    REG_JNI(register_android_graphics_Path),
    REG_JNI(register_android_graphics_PathMeasure),
    REG_JNI(register_android_graphics_PathEffect),
    REG_JNI(register_android_graphics_Picture),
    REG_JNI(register_android_graphics_Region),
    REG_JNI(register_android_graphics_Shader),
    REG_JNI(register_android_graphics_SurfaceTexture),
    REG_JNI(register_android_graphics_Typeface),
    REG_JNI(register_android_graphics_YuvImage),
    REG_JNI(register_android_graphics_drawable_AnimatedVectorDrawable),
    REG_JNI(register_android_graphics_drawable_VectorDrawable),
    REG_JNI(register_android_graphics_fonts_Font),
    REG_JNI(register_android_graphics_fonts_FontFamily),
    REG_JNI(register_android_graphics_pdf_PdfDocument),
    REG_JNI(register_android_graphics_pdf_PdfEditor),
    REG_JNI(register_android_graphics_pdf_PdfRenderer),
    REG_JNI(register_android_graphics_text_MeasuredText),
    REG_JNI(register_android_graphics_text_LineBreaker),

    REG_JNI(register_android_database_CursorWindow),
    REG_JNI(register_android_database_SQLiteConnection),
    REG_JNI(register_android_database_SQLiteGlobal),
    REG_JNI(register_android_database_SQLiteDebug),
    REG_JNI(register_android_os_Debug),
    REG_JNI(register_android_os_FileObserver),
    REG_JNI(register_android_os_GraphicsEnvironment),
    REG_JNI(register_android_os_MessageQueue),
    REG_JNI(register_android_os_SELinux),
    REG_JNI(register_android_os_Trace),
    REG_JNI(register_android_os_UEventObserver),
    REG_JNI(register_android_net_LocalSocketImpl),
    REG_JNI(register_android_net_NetworkUtils),
    REG_JNI(register_android_os_MemoryFile),
    REG_JNI(register_android_os_SharedMemory),
    REG_JNI(register_com_android_internal_os_ClassLoaderFactory),
    REG_JNI(register_com_android_internal_os_Zygote),
    REG_JNI(register_com_android_internal_os_ZygoteInit),
    REG_JNI(register_com_android_internal_util_VirtualRefBasePtr),
    REG_JNI(register_android_hardware_Camera),
    REG_JNI(register_android_hardware_camera2_CameraMetadata),
    REG_JNI(register_android_hardware_camera2_legacy_LegacyCameraDevice),
    REG_JNI(register_android_hardware_camera2_legacy_PerfMeasurement),
    REG_JNI(register_android_hardware_camera2_DngCreator),
    REG_JNI(register_android_hardware_HardwareBuffer),
    REG_JNI(register_android_hardware_SensorManager),
    REG_JNI(register_android_hardware_SerialPort),
    REG_JNI(register_android_hardware_SoundTrigger),
    REG_JNI(register_android_hardware_UsbDevice),
    REG_JNI(register_android_hardware_UsbDeviceConnection),
    REG_JNI(register_android_hardware_UsbRequest),
    REG_JNI(register_android_hardware_location_ActivityRecognitionHardware),
    REG_JNI(register_android_media_AudioEffectDescriptor),
    REG_JNI(register_android_media_AudioSystem),
    REG_JNI(register_android_media_AudioRecord),
    REG_JNI(register_android_media_AudioTrack),
    REG_JNI(register_android_media_AudioAttributes),
    REG_JNI(register_android_media_AudioProductStrategies),
    REG_JNI(register_android_media_JetPlayer),
    REG_JNI(register_android_media_MicrophoneInfo),
    REG_JNI(register_android_media_RemoteDisplay),
    REG_JNI(register_android_media_ToneGenerator),
    REG_JNI(register_android_media_midi),

    REG_JNI(register_android_opengl_classes),
    REG_JNI(register_android_server_NetworkManagementSocketTagger),
    REG_JNI(register_android_ddm_DdmHandleNativeHeap),
    REG_JNI(register_android_backup_BackupDataInput),
    REG_JNI(register_android_backup_BackupDataOutput),
    REG_JNI(register_android_backup_FileBackupHelperBase),
    REG_JNI(register_android_backup_BackupHelperDispatcher),
    REG_JNI(register_android_app_backup_FullBackup),
    REG_JNI(register_android_app_Activity),
    REG_JNI(register_android_app_ActivityThread),
    REG_JNI(register_android_app_NativeActivity),
    REG_JNI(register_android_util_jar_StrictJarFile),
    REG_JNI(register_android_view_InputChannel),
    REG_JNI(register_android_view_InputEventReceiver),
    REG_JNI(register_android_view_InputEventSender),
    REG_JNI(register_android_view_InputQueue),
    REG_JNI(register_android_view_KeyEvent),
    REG_JNI(register_android_view_MotionEvent),
    REG_JNI(register_android_view_PointerIcon),
    REG_JNI(register_android_view_VelocityTracker),

    REG_JNI(register_android_content_res_ObbScanner),
    REG_JNI(register_android_content_res_Configuration),

    REG_JNI(register_android_animation_PropertyValuesHolder),
    REG_JNI(register_android_security_Scrypt),
    REG_JNI(register_com_android_internal_content_NativeLibraryHelper),
    REG_JNI(register_com_android_internal_net_NetworkStatsFactory),
    REG_JNI(register_com_android_internal_os_AtomicDirectory),
    REG_JNI(register_com_android_internal_os_FuseAppLoop),
};

/*
 * Register android native functions with the VM.
 */
/*static*/ int AndroidRuntime::startReg(JNIEnv* env)
{
    ATRACE_NAME("RegisterAndroidNatives");
    /*
     * This hook causes all future threads created in this process to be
     * attached to the JavaVM.  (This needs to go away in favor of JNI
     * Attach calls.)
     */
    androidSetCreateThreadFunc((android_create_thread_fn) javaCreateThreadEtc);

    ALOGV("--- registering native functions ---\n");

    /*
     * Every "register" function calls one or more things that return
     * a local reference (e.g. FindClass).  Because we haven't really
     * started the VM yet, they're all getting stored in the base frame
     * and never released.  Use Push/Pop to manage the storage.
     */
    env->PushLocalFrame(200);

    if (register_jni_procs(gRegJNI, NELEM(gRegJNI), env) < 0) {
        env->PopLocalFrame(NULL);
        return -1;
    }
    env->PopLocalFrame(NULL);

    //createJavaThread("fubar", quickTest, (void*) "hello");

    return 0;
}

AndroidRuntime* AndroidRuntime::getRuntime()
{
    return gCurRuntime;
}

/**
 * Used by surface flinger's DdmConnection to register native methods from
 * the framework.
 */
extern "C" jint registerFrameworkNatives(JNIEnv* env) {
    return register_jni_procs(gRegJNI, NELEM(gRegJNI), env);
}
}   // namespace android
