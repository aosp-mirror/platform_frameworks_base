//
// Copyright 2005 The Android Open Source Project
//
// Main entry point for runtime.
//

#include "ServiceManager.h"
#include "SignalHandler.h"

#include <utils/threads.h>
#include <utils/Errors.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <utils/Log.h>  
#include <cutils/zygote.h>

#include <cutils/properties.h>

#include <private/utils/Static.h>

#include <surfaceflinger/ISurfaceComposer.h>

#include <android_runtime/AndroidRuntime.h>

#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <getopt.h>
#include <signal.h>
#include <errno.h>
#include <sys/stat.h>
#include <linux/capability.h>
#include <linux/ioctl.h>
#ifdef HAVE_ANDROID_OS
# include <linux/android_alarm.h>
#endif

#undef LOG_TAG
#define LOG_TAG "runtime"

static const char* ZYGOTE_ARGV[] = { 
    "--setuid=1000",
    "--setgid=1000",
    "--setgroups=1001,1002,1003,1004,1005,1006,1007,1008,1009,1010,3001,3002,3003",
    /* CAP_SYS_TTY_CONFIG & CAP_SYS_RESOURCE & CAP_NET_BROADCAST &
     * CAP_NET_ADMIN & CAP_NET_RAW & CAP_NET_BIND_SERVICE  & CAP_KILL &
     * CAP_SYS_BOOT CAP_SYS_NICE
     */
    "--capabilities=96549920,96549920",
    "--runtime-init",
    "--nice-name=system_server",
    "com.android.server.SystemServer"
};

using namespace android;

extern "C" status_t system_init();

enum {
    SYSTEM_PROCESS_TAG = DEFAULT_PROCESS_TAG+1
};

extern Mutex gEventQMutex;
extern Condition gEventQCondition;

namespace android {

extern status_t app_init(const char* className);
extern void set_finish_init_func(void (*func)());


/**
 * This class is used to kill this process (runtime) when the system_server dies.
 */
class GrimReaper : public IBinder::DeathRecipient {
public: 
    GrimReaper() { }

    virtual void binderDied(const wp<IBinder>& who)
    {
        LOGI("Grim Reaper killing runtime...");
        kill(getpid(), SIGKILL);
    }
};

extern void QuickTests();

/*
 * Print usage info.
 */
static void usage(const char* argv0)
{
    fprintf(stderr,
        "Usage: runtime [-g gamma] [-l logfile] [-n] [-s]\n"
        "               [-j app-component] [-v app-verb] [-d app-data]\n"
        "\n"
        "-l: File to send log messages to\n"
        "-n: Don't print to stdout/stderr\n"
        "-s: Force single-process mode\n"
        "-j: Custom home app component name\n"
        "-v: Custom home app intent verb\n"
        "-d: Custom home app intent data\n"
    );
    exit(1);
}

// Selected application to run.
static const char* gInitialApplication = NULL;
static const char* gInitialVerb = NULL;
static const char* gInitialData = NULL;

static void writeStringToParcel(Parcel& parcel, const char* str)
{
    if (str) {
        parcel.writeString16(String16(str));
    } else {
        parcel.writeString16(NULL, 0);
    }
}

/*
 * Starting point for program logic.
 *
 * Returns with an exit status code (0 on success, nonzero on error).
 */
static int run(sp<ProcessState>& proc)
{
    // Temporary hack to call startRunning() on the activity manager.
    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> am;
    while ((am = sm->getService(String16("activity"))) == NULL) {
        LOGI("Waiting for activity manager...");
    }
    Parcel data, reply;
    // XXX Need to also supply a package name for this to work again.
    // IActivityManager::getInterfaceDescriptor() is the token for invoking on this interface;
    // hardcoding it here avoids having to link with the full Activity Manager library
    data.writeInterfaceToken(String16("android.app.IActivityManager"));
    writeStringToParcel(data, NULL);
    writeStringToParcel(data, gInitialApplication);
    writeStringToParcel(data, gInitialVerb);
    writeStringToParcel(data, gInitialData);
LOGI("run() sending FIRST_CALL_TRANSACTION to activity manager");
    am->transact(IBinder::FIRST_CALL_TRANSACTION, data, &reply);

    if (proc->supportsProcesses()) {
        // Now we link to the Activity Manager waiting for it to die. If it does kill ourself.
        // initd will restart this process and bring the system back up.
        sp<GrimReaper> grim = new GrimReaper();
        am->linkToDeath(grim, grim.get(), 0);

        // Now join the thread pool. Note this is needed so that the message enqueued in the driver
        // for the linkToDeath gets processed.
        IPCThreadState::self()->joinThreadPool();
    } else {
        // Keep this thread running forever...
        while (1) {
            usleep(100000);
        }
    }
    return 1;
}


};  // namespace android


/*
 * Post-system-process initialization.
 * 
 * This function continues initialization after the system process
 * has been initialized.  It needs to be separate because the system
 * initialization needs to care of starting the Android runtime if it is not
 * running in its own process, which doesn't return until the runtime is
 * being shut down.  So it will call back to here from inside of Dalvik,
 * to allow us to continue booting up.
 */
static void finish_system_init(sp<ProcessState>& proc)
{
    // If we are running multiprocess, we now need to have the
    // thread pool started here.  We don't do this in boot_init()
    // because when running single process we need to start the
    // thread pool after the Android runtime has been started (so
    // the pool uses Dalvik threads).
    if (proc->supportsProcesses()) {
        proc->startThreadPool();
    }
}


// This function can be used to enforce security to different
// root contexts.  For now, we just give every access.
static bool contextChecker(
    const String16& name, const sp<IBinder>& caller, void* userData)
{
    return true;
}

/*
 * Initialization of boot services.
 *
 * This is where we perform initialization of all of our low-level
 * boot services.  Most importantly, here we become the context
 * manager and use that to publish the service manager that will provide
 * access to all other services.
 */
static void boot_init()
{
    LOGI("Entered boot_init()!\n");
    
    sp<ProcessState> proc(ProcessState::self());
    LOGD("ProcessState: %p\n", proc.get());
    proc->becomeContextManager(contextChecker, NULL);
    
    if (proc->supportsProcesses()) {
        LOGI("Binder driver opened.  Multiprocess enabled.\n");
    } else {
        LOGI("Binder driver not found.  Processes not supported.\n");
    }
    
    sp<BServiceManager> sm = new BServiceManager;
    proc->setContextObject(sm);
}

/*
 * Redirect stdin/stdout/stderr to /dev/null.
 */
static void redirectStdFds(void)
{
    int fd = open("/dev/null", O_RDWR, 0);
    if (fd < 0) {
        LOGW("Unable to open /dev/null: %s\n", strerror(errno));
    } else {
        dup2(fd, 0);
        dup2(fd, 1);
        dup2(fd, 2);
        close(fd);
    }
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

static void validateTime()
{
#if HAVE_ANDROID_OS
    int fd;
    int res;
    time_t min_time = 1167652800; // jan 1 2007, type 'date -ud "1/1 12:00" +%s' to get value for current year
    struct timespec ts;
    
    fd = open("/dev/alarm", O_RDWR);
    if(fd < 0) {
        LOGW("Unable to open alarm driver: %s\n", strerror(errno));
        return;
    }
    res = ioctl(fd, ANDROID_ALARM_GET_TIME(ANDROID_ALARM_RTC_WAKEUP), &ts);
    if(res < 0) {
        LOGW("Unable to read rtc, %s\n", strerror(errno));
    }
    else if(ts.tv_sec >= min_time) {
        goto done;
    }
    LOGW("Invalid time detected, %ld set to %ld\n", ts.tv_sec, min_time);
    ts.tv_sec = min_time;
    ts.tv_nsec = 0;
    res = ioctl(fd, ANDROID_ALARM_SET_RTC, &ts);
    if(res < 0) {
        LOGW("Unable to set rtc to %ld: %s\n", ts.tv_sec, strerror(errno));
    }
done:
    close(fd);
#endif
}

#ifndef HAVE_ANDROID_OS
class QuickRuntime : public AndroidRuntime
{
public:
    QuickRuntime() {}

    virtual void onStarted()
    {
        printf("QuickRuntime: onStarted\n");
    }
};
#endif

static status_t start_process(const char* name);

static void restart_me(pid_t child, void* userData)
{
    start_process((const char*)userData);
}

static status_t start_process(const char* name)
{
    String8 path(name);
    Vector<const char*> args;
    String8 leaf(path.getPathLeaf());
    String8 parentDir(path.getPathDir());
    args.insertAt(leaf.string(), 0);
    args.add(parentDir.string());
    args.add(NULL);
    pid_t child = fork();
    if (child < 0) {
        status_t err = errno;
        LOGE("*** fork of child %s failed: %s", leaf.string(), strerror(err));
        return -errno;
    } else if (child == 0) {
        LOGI("Executing: %s", path.string());
        execv(path.string(), const_cast<char**>(args.array()));
        int err = errno;
        LOGE("Exec failed: %s\n", strerror(err));
        _exit(err);
    } else {
        SignalHandler::setChildHandler(child, DEFAULT_PROCESS_TAG,
                restart_me, (void*)name);
    }
    return -errno;
}

/*
 * Application entry point.
 *
 * Parse arguments, set some values, and pass control off to Run().
 *
 * This is redefined to "SDL_main" on SDL simulator builds, and
 * "runtime_main" on wxWidgets builds.
 */
extern "C"
int main(int argc, char* const argv[])
{
    bool singleProcess = false;
    const char* logFile = NULL;
    int ic;
    int result = 1;
    pid_t systemPid;
    
    sp<ProcessState> proc;

#ifndef HAVE_ANDROID_OS
    /* Set stdout/stderr to unbuffered for MinGW/MSYS. */
    //setvbuf(stdout, NULL, _IONBF, 0);
    //setvbuf(stderr, NULL, _IONBF, 0);
    
    LOGI("commandline args:\n");
    for (int i = 0; i < argc; i++)
        LOGI("  %2d: '%s'\n", i, argv[i]);
#endif

    while (1) {
        ic = getopt(argc, argv, "g:j:v:d:l:ns");
        if (ic < 0)
            break;

        switch (ic) {
        case 'g':
            break;
        case 'j':
            gInitialApplication = optarg;
            break;
        case 'v':
            gInitialVerb = optarg;
            break;
        case 'd':
            gInitialData = optarg;
            break;
        case 'l':
            logFile = optarg;
            break;
        case 'n':
            redirectStdFds();
            break;
        case 's':
            singleProcess = true;
            break;
        case '?':
        default:
            LOGE("runtime: unrecognized flag -%c\n", ic);
            usage(argv[0]);
            break;
        }
    }
    if (optind < argc) {
        LOGE("runtime: extra stuff: %s\n", argv[optind]);
        usage(argv[0]);
    }

    if (singleProcess) {
        ProcessState::setSingleProcess(true);
    }

    if (logFile != NULL) {
        android_logToFile(NULL, logFile);
    }

    /*
     * Set up ANDROID_* environment variables.
     *
     * TODO: the use of $ANDROID_PRODUCT_OUT will go away soon.
     */
    static const char* kSystemDir = "/system";
    static const char* kDataDir = "/data";
    static const char* kAppSubdir = "/app";
    const char* out = NULL;
#ifndef HAVE_ANDROID_OS
    //out = getenv("ANDROID_PRODUCT_OUT");
#endif
    if (out == NULL)
        out = "";

    char* systemDir = (char*) malloc(strlen(out) + strlen(kSystemDir) +1);
    char* dataDir = (char*) malloc(strlen(out) + strlen(kDataDir) +1);

    sprintf(systemDir, "%s%s", out, kSystemDir);
    sprintf(dataDir, "%s%s", out, kDataDir);
    setenv("ANDROID_ROOT", systemDir, 1);
    setenv("ANDROID_DATA", dataDir, 1);

    char* assetDir = (char*) malloc(strlen(systemDir) + strlen(kAppSubdir) +1);
    sprintf(assetDir, "%s%s", systemDir, kAppSubdir);

    LOGI("Startup: sys='%s' asset='%s' data='%s'\n",
        systemDir, assetDir, dataDir);
    free(systemDir);
    free(dataDir);

#ifdef HAVE_ANDROID_OS
    /* set up a process group for easier killing on the device */
    setpgid(0, getpid());
#endif

    // Change to asset dir.  This is only necessary if we've changed to
    // a different directory, but there's little harm in doing it regardless.
    //
    // Expecting assets to live in the current dir is not a great idea,
    // because some of our code or one of our libraries could change the
    // directory out from under us.  Preserve the behavior for now.
    if (chdir(assetDir) != 0) {
        LOGW("WARNING: could not change dir to '%s': %s\n",
             assetDir, strerror(errno));
    }
    free(assetDir);

#if 0
    // Hack to keep libc from beating the filesystem to death.  It's
    // hitting /etc/localtime frequently, 
    //
    // This statement locks us into Pacific time.  We could do better,
    // but there's not much point until we're sure that the library
    // can't be changed to do more along the lines of what we want.
#ifndef XP_WIN
    setenv("TZ", "PST+8PDT,M4.1.0/2,M10.5.0/2", true);
#endif
#endif

    /* track our progress through the boot sequence */
    const int LOG_BOOT_PROGRESS_START = 3000;
    LOG_EVENT_LONG(LOG_BOOT_PROGRESS_START, 
        ns2ms(systemTime(SYSTEM_TIME_MONOTONIC)));

    validateTime();

    proc = ProcessState::self();
    
    boot_init();
    
    /* If we are in multiprocess mode, have zygote spawn the system
     * server process and call system_init(). If we are running in
     * single process mode just call system_init() directly.
     */
    if (proc->supportsProcesses()) {
        // If stdio logging is on, system_server should not inherit our stdio
        // The dalvikvm instance will copy stdio to the log on its own
        char propBuf[PROPERTY_VALUE_MAX];
        bool logStdio = false;
        property_get("log.redirect-stdio", propBuf, "");
        logStdio = (strcmp(propBuf, "true") == 0);

        zygote_run_oneshot((int)(!logStdio), 
                sizeof(ZYGOTE_ARGV) / sizeof(ZYGOTE_ARGV[0]), 
                ZYGOTE_ARGV);

        //start_process("/system/bin/mediaserver");

    } else {
#ifndef HAVE_ANDROID_OS
        QuickRuntime* runt = new QuickRuntime();
        runt->start("com/android/server/SystemServer",
                    "" /* spontaneously fork system server from zygote */);
#endif
    }

    //printf("+++ post-zygote\n");

    finish_system_init(proc);
    run(proc);
    
bail:
    if (proc != NULL) {
        proc->setContextObject(NULL);
    }
    
    return 0;
}
