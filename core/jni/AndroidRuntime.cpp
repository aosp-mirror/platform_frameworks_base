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

#define LOG_TAG "AndroidRuntime"
//#define LOG_NDEBUG 0

#include <android_runtime/AndroidRuntime.h>
#include <binder/IBinder.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <utils/Log.h>
#include <utils/misc.h>
#include <binder/Parcel.h>
#include <utils/threads.h>
#include <cutils/properties.h>

#include <SkGraphics.h>
#include <SkImageDecoder.h>
#include <SkImageRef_GlobalPool.h>

#include "jni.h"
#include "JNIHelp.h"
#include "JniInvocation.h"
#include "android_util_Binder.h"

#include <stdio.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <signal.h>
#include <dirent.h>
#include <assert.h>


using namespace android;

extern int register_android_os_Binder(JNIEnv* env);
extern int register_android_os_Process(JNIEnv* env);
extern int register_android_graphics_Bitmap(JNIEnv*);
extern int register_android_graphics_BitmapFactory(JNIEnv*);
extern int register_android_graphics_BitmapRegionDecoder(JNIEnv*);
extern int register_android_graphics_Camera(JNIEnv* env);
extern int register_android_graphics_CreateJavaOutputStreamAdaptor(JNIEnv* env);
extern int register_android_graphics_Graphics(JNIEnv* env);
extern int register_android_graphics_Interpolator(JNIEnv* env);
extern int register_android_graphics_LayerRasterizer(JNIEnv*);
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
extern int register_android_opengl_jni_EGLExt(JNIEnv* env);
extern int register_android_opengl_jni_GLES10(JNIEnv* env);
extern int register_android_opengl_jni_GLES10Ext(JNIEnv* env);
extern int register_android_opengl_jni_GLES11(JNIEnv* env);
extern int register_android_opengl_jni_GLES11Ext(JNIEnv* env);
extern int register_android_opengl_jni_GLES20(JNIEnv* env);
extern int register_android_opengl_jni_GLES30(JNIEnv* env);

extern int register_android_hardware_Camera(JNIEnv *env);
extern int register_android_hardware_camera2_CameraMetadata(JNIEnv *env);
extern int register_android_hardware_SensorManager(JNIEnv *env);
extern int register_android_hardware_SerialPort(JNIEnv *env);
extern int register_android_hardware_UsbDevice(JNIEnv *env);
extern int register_android_hardware_UsbDeviceConnection(JNIEnv *env);
extern int register_android_hardware_UsbRequest(JNIEnv *env);

extern int register_android_media_AudioRecord(JNIEnv *env);
extern int register_android_media_AudioSystem(JNIEnv *env);
extern int register_android_media_AudioTrack(JNIEnv *env);
extern int register_android_media_JetPlayer(JNIEnv *env);
extern int register_android_media_ToneGenerator(JNIEnv *env);

extern int register_android_util_FloatMath(JNIEnv* env);

namespace android {

/*
 * JNI-based registration functions.  Note these are properly contained in
 * namespace android.
 */
extern int register_android_content_AssetManager(JNIEnv* env);
extern int register_android_util_EventLog(JNIEnv* env);
extern int register_android_util_Log(JNIEnv* env);
extern int register_android_content_StringBlock(JNIEnv* env);
extern int register_android_content_XmlBlock(JNIEnv* env);
extern int register_android_emoji_EmojiFactory(JNIEnv* env);
extern int register_android_graphics_Canvas(JNIEnv* env);
extern int register_android_graphics_ColorFilter(JNIEnv* env);
extern int register_android_graphics_DrawFilter(JNIEnv* env);
extern int register_android_graphics_Matrix(JNIEnv* env);
extern int register_android_graphics_Paint(JNIEnv* env);
extern int register_android_graphics_Path(JNIEnv* env);
extern int register_android_graphics_PathMeasure(JNIEnv* env);
extern int register_android_graphics_Picture(JNIEnv*);
extern int register_android_graphics_PorterDuff(JNIEnv* env);
extern int register_android_graphics_Rasterizer(JNIEnv* env);
extern int register_android_graphics_Region(JNIEnv* env);
extern int register_android_graphics_SurfaceTexture(JNIEnv* env);
extern int register_android_graphics_Xfermode(JNIEnv* env);
extern int register_android_graphics_pdf_PdfDocument(JNIEnv* env);
extern int register_android_view_DisplayEventReceiver(JNIEnv* env);
extern int register_android_view_GraphicBuffer(JNIEnv* env);
extern int register_android_view_GLES20DisplayList(JNIEnv* env);
extern int register_android_view_GLES20Canvas(JNIEnv* env);
extern int register_android_view_HardwareRenderer(JNIEnv* env);
extern int register_android_view_Surface(JNIEnv* env);
extern int register_android_view_SurfaceControl(JNIEnv* env);
extern int register_android_view_SurfaceSession(JNIEnv* env);
extern int register_android_view_TextureView(JNIEnv* env);
extern int register_android_database_CursorWindow(JNIEnv* env);
extern int register_android_database_SQLiteConnection(JNIEnv* env);
extern int register_android_database_SQLiteGlobal(JNIEnv* env);
extern int register_android_database_SQLiteDebug(JNIEnv* env);
extern int register_android_nio_utils(JNIEnv* env);
extern int register_android_os_Debug(JNIEnv* env);
extern int register_android_os_MessageQueue(JNIEnv* env);
extern int register_android_os_Parcel(JNIEnv* env);
extern int register_android_os_SELinux(JNIEnv* env);
extern int register_android_os_SystemProperties(JNIEnv *env);
extern int register_android_os_SystemClock(JNIEnv* env);
extern int register_android_os_Trace(JNIEnv* env);
extern int register_android_os_FileObserver(JNIEnv *env);
extern int register_android_os_UEventObserver(JNIEnv* env);
extern int register_android_os_MemoryFile(JNIEnv* env);
extern int register_android_net_LocalSocketImpl(JNIEnv* env);
extern int register_android_net_NetworkUtils(JNIEnv* env);
extern int register_android_net_TrafficStats(JNIEnv* env);
extern int register_android_net_wifi_WifiNative(JNIEnv* env);
extern int register_android_text_AndroidCharacter(JNIEnv *env);
extern int register_android_text_AndroidBidi(JNIEnv *env);
extern int register_android_opengl_classes(JNIEnv *env);
extern int register_android_server_NetworkManagementSocketTagger(JNIEnv* env);
extern int register_android_server_Watchdog(JNIEnv* env);
extern int register_android_ddm_DdmHandleNativeHeap(JNIEnv *env);
extern int register_com_android_internal_os_ZygoteInit(JNIEnv* env);
extern int register_android_backup_BackupDataInput(JNIEnv *env);
extern int register_android_backup_BackupDataOutput(JNIEnv *env);
extern int register_android_backup_FileBackupHelperBase(JNIEnv *env);
extern int register_android_backup_BackupHelperDispatcher(JNIEnv *env);
extern int register_android_app_backup_FullBackup(JNIEnv *env);
extern int register_android_app_ActivityThread(JNIEnv *env);
extern int register_android_app_NativeActivity(JNIEnv *env);
extern int register_android_media_RemoteDisplay(JNIEnv *env);
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
extern int register_com_android_internal_content_NativeLibraryHelper(JNIEnv *env);
extern int register_com_android_internal_net_NetworkStatsFactory(JNIEnv *env);
extern int register_com_android_internal_os_Zygote(JNIEnv *env);

static AndroidRuntime* gCurRuntime = NULL;

static void doThrow(JNIEnv* env, const char* exc, const char* msg = NULL)
{
    if (jniThrowException(env, exc, msg) != 0)
        assert(false);
}

/*
 * Code written in the Java Programming Language calls here from main().
 */
static void com_android_internal_os_RuntimeInit_nativeFinishInit(JNIEnv* env, jobject clazz)
{
    gCurRuntime->onStarted();
}

static void com_android_internal_os_RuntimeInit_nativeZygoteInit(JNIEnv* env, jobject clazz)
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
static JNINativeMethod gMethods[] = {
    { "nativeFinishInit", "()V",
        (void*) com_android_internal_os_RuntimeInit_nativeFinishInit },
    { "nativeZygoteInit", "()V",
        (void*) com_android_internal_os_RuntimeInit_nativeZygoteInit },
    { "nativeSetExitWithoutCleanup", "(Z)V",
        (void*) com_android_internal_os_RuntimeInit_nativeSetExitWithoutCleanup },
};

int register_com_android_internal_os_RuntimeInit(JNIEnv* env)
{
    return jniRegisterNativeMethods(env, "com/android/internal/os/RuntimeInit",
        gMethods, NELEM(gMethods));
}

// ----------------------------------------------------------------------

/*static*/ JavaVM* AndroidRuntime::mJavaVM = NULL;

AndroidRuntime::AndroidRuntime(char* argBlockStart, const size_t argBlockLength) :
        mExitWithoutCleanup(false),
        mArgBlockStart(argBlockStart),
        mArgBlockLength(argBlockLength)
{
    SkGraphics::Init();
    // this sets our preference for 16bit images during decode
    // in case the src is opaque and 24bit
    SkImageDecoder::SetDeviceConfig(SkBitmap::kRGB_565_Config);
    // This cache is shared between browser native images, and java "purgeable"
    // bitmaps. This globalpool is for images that do not either use the java
    // heap, or are not backed by ashmem. See BitmapFactory.cpp for the key
    // java call site.
    SkImageRef_GlobalPool::SetRAMBudget(512 * 1024);
    // There is also a global font cache, but its budget is specified in code
    // see SkFontHost_android.cpp

    // Pre-allocate enough space to hold a fair number of options.
    mOptions.setCapacity(20);

    assert(gCurRuntime == NULL);        // one per process
    gCurRuntime = this;
}

AndroidRuntime::~AndroidRuntime()
{
    SkGraphics::Term();
}

/*
 * Register native methods using JNI.
 */
/*static*/ int AndroidRuntime::registerNativeMethods(JNIEnv* env,
    const char* className, const JNINativeMethod* gMethods, int numMethods)
{
    return jniRegisterNativeMethods(env, className, gMethods, numMethods);
}

void AndroidRuntime::setArgv0(const char* argv0) {
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


/**
 * Add VM arguments to the to-be-executed VM
 * Stops at first non '-' argument (also stops at an argument of '--')
 * Returns the number of args consumed
 */
int AndroidRuntime::addVmArguments(int argc, const char* const argv[])
{
    int i;

    for (i = 0; i<argc; i++) {
        if (argv[i][0] != '-') {
            return i;
        }
        if (argv[i][1] == '-' && argv[i][2] == 0) {
            return i+1;
        }
        addOption(argv[i]);
    }
    return i;
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

/*
 * Read the persistent locale.
 */
static void readLocale(char* language, char* region)
{
    char propLang[PROPERTY_VALUE_MAX], propRegn[PROPERTY_VALUE_MAX];

    property_get("persist.sys.language", propLang, "");
    property_get("persist.sys.country", propRegn, "");
    if (*propLang == 0 && *propRegn == 0) {
        /* Set to ro properties, default is en_US */
        property_get("ro.product.locale.language", propLang, "en");
        property_get("ro.product.locale.region", propRegn, "US");
    }
    strncat(language, propLang, 3);
    strncat(region, propRegn, 3);
    //ALOGD("language=%s region=%s\n", language, region);
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
 * passed as a quoted option, e.g. "-Ximage-compiler-option --compiler-filter=verify-none".
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
int AndroidRuntime::startVm(JavaVM** pJavaVM, JNIEnv** pEnv)
{
    int result = -1;
    JavaVMInitArgs initArgs;
    char propBuf[PROPERTY_VALUE_MAX];
    char stackTraceFileBuf[sizeof("-Xstacktracefile:")-1 + PROPERTY_VALUE_MAX];
    char dexoptFlagsBuf[PROPERTY_VALUE_MAX];
    char enableAssertBuf[sizeof("-ea:")-1 + PROPERTY_VALUE_MAX];
    char jniOptsBuf[sizeof("-Xjniopts:")-1 + PROPERTY_VALUE_MAX];
    char heapstartsizeOptsBuf[sizeof("-Xms")-1 + PROPERTY_VALUE_MAX];
    char heapsizeOptsBuf[sizeof("-Xmx")-1 + PROPERTY_VALUE_MAX];
    char heapgrowthlimitOptsBuf[sizeof("-XX:HeapGrowthLimit=")-1 + PROPERTY_VALUE_MAX];
    char heapminfreeOptsBuf[sizeof("-XX:HeapMinFree=")-1 + PROPERTY_VALUE_MAX];
    char heapmaxfreeOptsBuf[sizeof("-XX:HeapMaxFree=")-1 + PROPERTY_VALUE_MAX];
    char gctypeOptsBuf[sizeof("-Xgc:")-1 + PROPERTY_VALUE_MAX];
    char backgroundgcOptsBuf[sizeof("-XX:BackgroundGC=")-1 + PROPERTY_VALUE_MAX];
    char heaptargetutilizationOptsBuf[sizeof("-XX:HeapTargetUtilization=")-1 + PROPERTY_VALUE_MAX];
    char jitcodecachesizeOptsBuf[sizeof("-Xjitcodecachesize:")-1 + PROPERTY_VALUE_MAX];
    char dalvikVmLibBuf[PROPERTY_VALUE_MAX];
    char dex2oatXmsImageFlagsBuf[sizeof("-Xms")-1 + PROPERTY_VALUE_MAX];
    char dex2oatXmxImageFlagsBuf[sizeof("-Xmx")-1 + PROPERTY_VALUE_MAX];
    char dex2oatXmsFlagsBuf[sizeof("-Xms")-1 + PROPERTY_VALUE_MAX];
    char dex2oatXmxFlagsBuf[sizeof("-Xmx")-1 + PROPERTY_VALUE_MAX];
    char dex2oatCompilerFilterBuf[sizeof("--compiler-filter=")-1 + PROPERTY_VALUE_MAX];
    char dex2oatImageCompilerFilterBuf[sizeof("--compiler-filter=")-1 + PROPERTY_VALUE_MAX];
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
    char profilePeriod[sizeof("-Xprofile-period:")-1 + PROPERTY_VALUE_MAX];
    char profileDuration[sizeof("-Xprofile-duration:")-1 + PROPERTY_VALUE_MAX];
    char profileInterval[sizeof("-Xprofile-interval:")-1 + PROPERTY_VALUE_MAX];
    char profileBackoff[sizeof("-Xprofile-backoff:")-1 + PROPERTY_VALUE_MAX];
    char profileTopKThreshold[sizeof("-Xprofile-top-k-threshold:")-1 + PROPERTY_VALUE_MAX];
    char profileTopKChangeThreshold[sizeof("-Xprofile-top-k-change-threshold:")-1 +
                                    PROPERTY_VALUE_MAX];
    char profileType[sizeof("-Xprofile-type:")-1 + PROPERTY_VALUE_MAX];
    char profileMaxStackDepth[sizeof("-Xprofile-max-stack-depth:")-1 + PROPERTY_VALUE_MAX];
    char langOption[sizeof("-Duser.language=") + 3];
    char regionOption[sizeof("-Duser.region=") + 3];
    char lockProfThresholdBuf[sizeof("-Xlockprofthreshold:")-1 + PROPERTY_VALUE_MAX];
    char jitOpBuf[sizeof("-Xjitop:")-1 + PROPERTY_VALUE_MAX];
    char jitMethodBuf[sizeof("-Xjitmethod:")-1 + PROPERTY_VALUE_MAX];
    char nativeBridgeLibrary[sizeof("-XX:NativeBridge=") + PROPERTY_VALUE_MAX];

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
    ALOGD("CheckJNI is %s\n", checkJni ? "ON" : "OFF");
    if (checkJni) {
        /* extended JNI checking */
        addOption("-Xcheck:jni");

        /* set a cap on JNI global references */
        addOption("-Xjnigreflimit:2000");

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

    parseRuntimeOption("dalvik.vm.stack-trace-file", stackTraceFileBuf, "-Xstacktracefile:");

    property_get("dalvik.vm.check-dex-sum", propBuf, "");
    if (strcmp(propBuf, "true") == 0) {
        /* perform additional DEX checksum tests */
        addOption("-Xcheckdexsum");
    }

    property_get("log.redirect-stdio", propBuf, "");
    if (strcmp(propBuf, "true") == 0) {
        /* convert stdout/stderr to log messages */
        addOption("-Xlog-stdio");
    }

    strcpy(enableAssertBuf, "-ea:");
    property_get("dalvik.vm.enableassertions", enableAssertBuf+sizeof("-ea:")-1, "");
    if (enableAssertBuf[sizeof("-ea:")-1] != '\0') {
        /* accept "all" to mean "all classes and packages" */
        if (strcmp(enableAssertBuf+sizeof("-ea:")-1, "all") == 0)
            enableAssertBuf[3] = '\0'; // truncate to "-ea"
        ALOGI("Assertions enabled: '%s'\n", enableAssertBuf);
        addOption(enableAssertBuf);
    } else {
        ALOGV("Assertions disabled\n");
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

    // Increase the main thread's interpreter stack size for bug 6315322.
    addOption("-XX:mainThreadStackSize=24K");

    // Set the max jit code cache size.  Note: size of 0 will disable the JIT.
    parseRuntimeOption("dalvik.vm.jit.codecachesize",
                       jitcodecachesizeOptsBuf,
                       "-Xjitcodecachesize:");

    parseRuntimeOption("dalvik.vm.heapgrowthlimit", heapgrowthlimitOptsBuf, "-XX:HeapGrowthLimit=");
    parseRuntimeOption("dalvik.vm.heapminfree", heapminfreeOptsBuf, "-XX:HeapMinFree=");
    parseRuntimeOption("dalvik.vm.heapmaxfree", heapmaxfreeOptsBuf, "-XX:HeapMaxFree=");
    parseRuntimeOption("dalvik.vm.heaptargetutilization",
                       heaptargetutilizationOptsBuf,
                       "-XX:HeapTargetUtilization=");

    property_get("ro.config.low_ram", propBuf, "");
    if (strcmp(propBuf, "true") == 0) {
      addOption("-XX:LowMemoryMode");
    }

    parseRuntimeOption("dalvik.vm.gctype", gctypeOptsBuf, "-Xgc:");
    parseRuntimeOption("dalvik.vm.backgroundgctype", backgroundgcOptsBuf, "-XX:BackgroundGC=");

    /*
     * Enable or disable dexopt features, such as bytecode verification and
     * calculation of register maps for precise GC.
     */
    property_get("dalvik.vm.dexopt-flags", dexoptFlagsBuf, "");
    if (dexoptFlagsBuf[0] != '\0') {
        const char* opc;
        const char* val;

        opc = strstr(dexoptFlagsBuf, "v=");     /* verification */
        if (opc != NULL) {
            switch (*(opc+2)) {
            case 'n':   val = "-Xverify:none";      break;
            case 'r':   val = "-Xverify:remote";    break;
            case 'a':   val = "-Xverify:all";       break;
            default:    val = NULL;                 break;
            }

            if (val != NULL) {
                addOption(val);
            }
        }

        opc = strstr(dexoptFlagsBuf, "o=");     /* optimization */
        if (opc != NULL) {
            switch (*(opc+2)) {
            case 'n':   val = "-Xdexopt:none";      break;
            case 'v':   val = "-Xdexopt:verified";  break;
            case 'a':   val = "-Xdexopt:all";       break;
            case 'f':   val = "-Xdexopt:full";      break;
            default:    val = NULL;                 break;
            }

            if (val != NULL) {
                addOption(val);
            }
        }

        opc = strstr(dexoptFlagsBuf, "m=y");    /* register map */
        if (opc != NULL) {
            addOption("-Xgenregmap");

            /* turn on precise GC while we're at it */
            addOption("-Xgc:precise");
        }
    }

    /* enable debugging; set suspend=y to pause during VM init */
    /* use android ADB transport */
    addOption("-agentlib:jdwp=transport=dt_android_adb,suspend=n,server=y");

    parseRuntimeOption("dalvik.vm.lockprof.threshold",
                       lockProfThresholdBuf,
                       "-Xlockprofthreshold:");

    /* Force interpreter-only mode for selected opcodes. Eg "1-0a,3c,f1-ff" */
    parseRuntimeOption("dalvik.vm.jit.op", jitOpBuf, "-Xjitop:");

    /* Force interpreter-only mode for selected methods */
    parseRuntimeOption("dalvik.vm.jit.method", jitMethodBuf, "-Xjitmethod:");

    if (executionMode == kEMIntPortable) {
        addOption("-Xint:portable");
    } else if (executionMode == kEMIntFast) {
        addOption("-Xint:fast");
    } else if (executionMode == kEMJitCompiler) {
        addOption("-Xint:jit");
    }

    // libart tolerates libdvm flags, but not vice versa, so only pass some options if libart.
    property_get("persist.sys.dalvik.vm.lib.2", dalvikVmLibBuf, "libart.so");
    bool libart = (strncmp(dalvikVmLibBuf, "libart", 6) == 0);

    if (libart) {
        // If we booting without the real /data, don't spend time compiling.
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
            addOption("--compiler-filter=verify-none");
        } else {
            parseCompilerOption("dalvik.vm.image-dex2oat-filter", dex2oatImageCompilerFilterBuf,
                                "--compiler-filter=", "-Ximage-compiler-option");
        }
        addOption("-Ximage-compiler-option");
        addOption("--image-classes-zip=/system/framework/framework.jar");
        addOption("-Ximage-compiler-option");
        addOption("--image-classes=preloaded-classes");
        property_get("dalvik.vm.image-dex2oat-flags", dex2oatImageFlagsBuf, "");
        parseExtraOpts(dex2oatImageFlagsBuf, "-Ximage-compiler-option");

        // Extra options for DexClassLoader.
        parseCompilerRuntimeOption("dalvik.vm.dex2oat-Xms", dex2oatXmsFlagsBuf,
                                   "-Xms", "-Xcompiler-option");
        parseCompilerRuntimeOption("dalvik.vm.dex2oat-Xmx", dex2oatXmxFlagsBuf,
                                   "-Xmx", "-Xcompiler-option");
        if (skip_compilation) {
            addOption("-Xcompiler-option");
            addOption("--compiler-filter=interpret-only");
        } else {
            parseCompilerOption("dalvik.vm.dex2oat-filter", dex2oatCompilerFilterBuf,
                                "--compiler-filter=", "-Xcompiler-option");
        }
        property_get("dalvik.vm.dex2oat-flags", dex2oatFlagsBuf, "");
        parseExtraOpts(dex2oatFlagsBuf, "-Xcompiler-option");

    }

    /* extra options; parse this late so it overrides others */
    property_get("dalvik.vm.extra-opts", extraOptsBuf, "");
    parseExtraOpts(extraOptsBuf, NULL);

    /* Set the properties for locale */
    {
        strcpy(langOption, "-Duser.language=");
        strcpy(regionOption, "-Duser.region=");
        readLocale(langOption, regionOption);
        addOption(langOption);
        addOption(regionOption);
    }

    /*
     * Set profiler options
     */
    if (libart) {
        // Whether or not the profiler should be enabled.
        property_get("dalvik.vm.profiler", propBuf, "0");
        if (propBuf[0] == '1') {
            addOption("-Xenable-profiler");
        }

        // Whether the profile should start upon app startup or be delayed by some random offset
        // (in seconds) that is bound between 0 and a fixed value.
        property_get("dalvik.vm.profile.start-immed", propBuf, "0");
        if (propBuf[0] == '1') {
            addOption("-Xprofile-start-immediately");
        }

        // Number of seconds during profile runs.
        parseRuntimeOption("dalvik.vm.profile.period-secs", profilePeriod, "-Xprofile-period:");

        // Length of each profile run (seconds).
        parseRuntimeOption("dalvik.vm.profile.duration-secs",
                           profileDuration,
                           "-Xprofile-duration:");

        // Polling interval during profile run (microseconds).
        parseRuntimeOption("dalvik.vm.profile.interval-us", profileInterval, "-Xprofile-interval:");

        // Coefficient for period backoff.  The the period is multiplied
        // by this value after each profile run.
        parseRuntimeOption("dalvik.vm.profile.backoff-coeff", profileBackoff, "-Xprofile-backoff:");

        // Top K% of samples that are considered relevant when
        // deciding if the app should be recompiled.
        parseRuntimeOption("dalvik.vm.profile.top-k-thr",
                           profileTopKThreshold,
                           "-Xprofile-top-k-threshold:");

        // The threshold after which a change in the structure of the
        // top K% profiled samples becomes significant and triggers
        // recompilation. A change in profile is considered
        // significant if X% (top-k-change-threshold) of the top K%
        // (top-k-threshold property) samples has changed.
        parseRuntimeOption("dalvik.vm.profile.top-k-ch-thr",
                           profileTopKChangeThreshold,
                           "-Xprofile-top-k-change-threshold:");

        // Type of profile data.
        parseRuntimeOption("dalvik.vm.profiler.type", profileType, "-Xprofile-type:");

        // Depth of bounded stack data
        parseRuntimeOption("dalvik.vm.profile.max-stack-depth",
                           profileMaxStackDepth,
                           "-Xprofile-max-stack-depth:");
    }

    parseRuntimeOption("ro.dalvik.vm.native.bridge", nativeBridgeLibrary, "-XX:NativeBridge=");

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
        goto bail;
    }

    result = 0;

bail:
    return result;
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

/*
 * Start the Android runtime.  This involves starting the virtual machine
 * and calling the "static void main(String[] args)" method in the class
 * named by "className".
 *
 * Passes the main function two arguments, the class name and the specified
 * options string.
 */
void AndroidRuntime::start(const char* className, const Vector<String8>& options)
{
    ALOGD("\n>>>>>> AndroidRuntime START %s <<<<<<\n",
            className != NULL ? className : "(unknown)");

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
            LOG_FATAL("No root directory specified, and /android does not exist.");
            return;
        }
        setenv("ANDROID_ROOT", rootDir, 1);
    }

    //const char* kernelHack = getenv("LD_ASSUME_KERNEL");
    //ALOGD("Found LD_ASSUME_KERNEL='%s'\n", kernelHack);

    /* start the virtual machine */
    JniInvocation jni_invocation;
    jni_invocation.Init(NULL);
    JNIEnv* env;
    if (startVm(&mJavaVM, &env) != 0) {
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
    char* slashClassName = toSlashClassName(className);
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

    assert(threadName != NULL);

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

static void register_jam_procs(const RegJAMProc array[], size_t count)
{
    for (size_t i = 0; i < count; i++) {
        array[i]();
    }
}

static const RegJNIRec gRegJNI[] = {
    REG_JNI(register_com_android_internal_os_RuntimeInit),
    REG_JNI(register_android_os_SystemClock),
    REG_JNI(register_android_util_EventLog),
    REG_JNI(register_android_util_Log),
    REG_JNI(register_android_util_FloatMath),
    REG_JNI(register_android_content_AssetManager),
    REG_JNI(register_android_content_StringBlock),
    REG_JNI(register_android_content_XmlBlock),
    REG_JNI(register_android_emoji_EmojiFactory),
    REG_JNI(register_android_text_AndroidCharacter),
    REG_JNI(register_android_text_AndroidBidi),
    REG_JNI(register_android_view_InputDevice),
    REG_JNI(register_android_view_KeyCharacterMap),
    REG_JNI(register_android_os_Process),
    REG_JNI(register_android_os_SystemProperties),
    REG_JNI(register_android_os_Binder),
    REG_JNI(register_android_os_Parcel),
    REG_JNI(register_android_view_DisplayEventReceiver),
    REG_JNI(register_android_nio_utils),
    REG_JNI(register_android_graphics_Graphics),
    REG_JNI(register_android_view_GraphicBuffer),
    REG_JNI(register_android_view_GLES20DisplayList),
    REG_JNI(register_android_view_GLES20Canvas),
    REG_JNI(register_android_view_HardwareRenderer),
    REG_JNI(register_android_view_Surface),
    REG_JNI(register_android_view_SurfaceControl),
    REG_JNI(register_android_view_SurfaceSession),
    REG_JNI(register_android_view_TextureView),
    REG_JNI(register_com_google_android_gles_jni_EGLImpl),
    REG_JNI(register_com_google_android_gles_jni_GLImpl),
    REG_JNI(register_android_opengl_jni_EGL14),
    REG_JNI(register_android_opengl_jni_EGLExt),
    REG_JNI(register_android_opengl_jni_GLES10),
    REG_JNI(register_android_opengl_jni_GLES10Ext),
    REG_JNI(register_android_opengl_jni_GLES11),
    REG_JNI(register_android_opengl_jni_GLES11Ext),
    REG_JNI(register_android_opengl_jni_GLES20),
    REG_JNI(register_android_opengl_jni_GLES30),

    REG_JNI(register_android_graphics_Bitmap),
    REG_JNI(register_android_graphics_BitmapFactory),
    REG_JNI(register_android_graphics_BitmapRegionDecoder),
    REG_JNI(register_android_graphics_Camera),
    REG_JNI(register_android_graphics_CreateJavaOutputStreamAdaptor),
    REG_JNI(register_android_graphics_Canvas),
    REG_JNI(register_android_graphics_ColorFilter),
    REG_JNI(register_android_graphics_DrawFilter),
    REG_JNI(register_android_graphics_Interpolator),
    REG_JNI(register_android_graphics_LayerRasterizer),
    REG_JNI(register_android_graphics_MaskFilter),
    REG_JNI(register_android_graphics_Matrix),
    REG_JNI(register_android_graphics_Movie),
    REG_JNI(register_android_graphics_NinePatch),
    REG_JNI(register_android_graphics_Paint),
    REG_JNI(register_android_graphics_Path),
    REG_JNI(register_android_graphics_PathMeasure),
    REG_JNI(register_android_graphics_PathEffect),
    REG_JNI(register_android_graphics_Picture),
    REG_JNI(register_android_graphics_PorterDuff),
    REG_JNI(register_android_graphics_Rasterizer),
    REG_JNI(register_android_graphics_Region),
    REG_JNI(register_android_graphics_Shader),
    REG_JNI(register_android_graphics_SurfaceTexture),
    REG_JNI(register_android_graphics_Typeface),
    REG_JNI(register_android_graphics_Xfermode),
    REG_JNI(register_android_graphics_YuvImage),
    REG_JNI(register_android_graphics_pdf_PdfDocument),

    REG_JNI(register_android_database_CursorWindow),
    REG_JNI(register_android_database_SQLiteConnection),
    REG_JNI(register_android_database_SQLiteGlobal),
    REG_JNI(register_android_database_SQLiteDebug),
    REG_JNI(register_android_os_Debug),
    REG_JNI(register_android_os_FileObserver),
    REG_JNI(register_android_os_MessageQueue),
    REG_JNI(register_android_os_SELinux),
    REG_JNI(register_android_os_Trace),
    REG_JNI(register_android_os_UEventObserver),
    REG_JNI(register_android_net_LocalSocketImpl),
    REG_JNI(register_android_net_NetworkUtils),
    REG_JNI(register_android_net_TrafficStats),
    REG_JNI(register_android_net_wifi_WifiNative),
    REG_JNI(register_android_os_MemoryFile),
    REG_JNI(register_com_android_internal_os_ZygoteInit),
    REG_JNI(register_com_android_internal_os_Zygote),
    REG_JNI(register_android_hardware_Camera),
    REG_JNI(register_android_hardware_camera2_CameraMetadata),
    REG_JNI(register_android_hardware_SensorManager),
    REG_JNI(register_android_hardware_SerialPort),
    REG_JNI(register_android_hardware_UsbDevice),
    REG_JNI(register_android_hardware_UsbDeviceConnection),
    REG_JNI(register_android_hardware_UsbRequest),
    REG_JNI(register_android_media_AudioRecord),
    REG_JNI(register_android_media_AudioSystem),
    REG_JNI(register_android_media_AudioTrack),
    REG_JNI(register_android_media_JetPlayer),
    REG_JNI(register_android_media_RemoteDisplay),
    REG_JNI(register_android_media_ToneGenerator),

    REG_JNI(register_android_opengl_classes),
    REG_JNI(register_android_server_NetworkManagementSocketTagger),
    REG_JNI(register_android_server_Watchdog),
    REG_JNI(register_android_ddm_DdmHandleNativeHeap),
    REG_JNI(register_android_backup_BackupDataInput),
    REG_JNI(register_android_backup_BackupDataOutput),
    REG_JNI(register_android_backup_FileBackupHelperBase),
    REG_JNI(register_android_backup_BackupHelperDispatcher),
    REG_JNI(register_android_app_backup_FullBackup),
    REG_JNI(register_android_app_ActivityThread),
    REG_JNI(register_android_app_NativeActivity),
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
    REG_JNI(register_com_android_internal_content_NativeLibraryHelper),
    REG_JNI(register_com_android_internal_net_NetworkStatsFactory),
};

/*
 * Register android native functions with the VM.
 */
/*static*/ int AndroidRuntime::startReg(JNIEnv* env)
{
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
 * Used by WithFramework to register native functions.
 */
extern "C"
jint Java_com_android_internal_util_WithFramework_registerNatives(
        JNIEnv* env, jclass clazz) {
    return register_jni_procs(gRegJNI, NELEM(gRegJNI), env);
}

/**
 * Used by LoadClass to register native functions.
 */
extern "C"
jint Java_LoadClass_registerNatives(JNIEnv* env, jclass clazz) {
    return register_jni_procs(gRegJNI, NELEM(gRegJNI), env);
}

}   // namespace android
