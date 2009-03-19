#include "JNIHelp.h"
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

namespace android {
int register_android_server_AlarmManagerService(JNIEnv* env);
int register_android_server_BatteryService(JNIEnv* env);
int register_android_server_KeyInputQueue(JNIEnv* env);
int register_android_server_HardwareService(JNIEnv* env);
int register_android_server_SensorService(JNIEnv* env);
int register_android_server_SystemServer(JNIEnv* env);
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

    register_android_server_KeyInputQueue(env);
    register_android_server_HardwareService(env);
    register_android_server_AlarmManagerService(env);
    register_android_server_BatteryService(env);
    register_android_server_SensorService(env);
    register_android_server_SystemServer(env);

    return JNI_VERSION_1_4;
}
