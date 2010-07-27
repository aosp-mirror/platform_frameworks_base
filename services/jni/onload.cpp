#include "JNIHelp.h"
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

namespace android {
int register_android_server_AlarmManagerService(JNIEnv* env);
int register_android_server_BatteryService(JNIEnv* env);
int register_android_server_InputManager(JNIEnv* env);
int register_android_server_LightsService(JNIEnv* env);
int register_android_server_PowerManagerService(JNIEnv* env);
int register_android_server_UsbObserver(JNIEnv* env);
int register_android_server_VibratorService(JNIEnv* env);
int register_android_server_SystemServer(JNIEnv* env);
int register_android_server_location_GpsLocationProvider(JNIEnv* env);
};

using namespace android;

extern "C" jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        LOGE("GetEnv failed!");
        return result;
    }
    LOG_ASSERT(env, "Could not retrieve the env!");

    register_android_server_PowerManagerService(env);
    register_android_server_InputManager(env);
    register_android_server_LightsService(env);
    register_android_server_AlarmManagerService(env);
    register_android_server_BatteryService(env);
    register_android_server_UsbObserver(env);
    register_android_server_VibratorService(env);
    register_android_server_SystemServer(env);
    register_android_server_location_GpsLocationProvider(env);

    return JNI_VERSION_1_4;
}
