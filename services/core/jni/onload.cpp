/*
 * Copyright (C) 2009 The Android Open Source Project
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

#include <android/graphics/jni_runtime.h>
#include <nativehelper/JNIHelp.h>
#include "jni.h"
#include "utils/Log.h"
#include "utils/misc.h"

#include "BroadcastRadio/BroadcastRadioService.h"
#include "BroadcastRadio/Tuner.h"

namespace android {
int register_android_server_BatteryStatsService(JNIEnv* env);
int register_android_server_ConsumerIrService(JNIEnv *env);
int register_android_server_InputManager(JNIEnv* env);
int register_android_server_LightsService(JNIEnv* env);
int register_android_server_PowerManagerService(JNIEnv* env);
int register_android_server_PowerStatsService(JNIEnv* env);
int register_android_server_HintManagerService(JNIEnv* env);
int register_android_server_storage_AppFuse(JNIEnv* env);
int register_android_server_SerialService(JNIEnv* env);
int register_android_server_SystemServer(JNIEnv* env);
int register_android_server_UsbAlsaJackDetector(JNIEnv* env);
int register_android_server_UsbAlsaMidiDevice(JNIEnv* env);
int register_android_server_UsbDeviceManager(JNIEnv* env);
int register_android_server_UsbHostManager(JNIEnv* env);
int register_android_server_vr_VrManagerService(JNIEnv* env);
int register_android_server_vibrator_VibratorController(JavaVM* vm, JNIEnv* env);
int register_android_server_vibrator_VibratorManagerService(JavaVM* vm, JNIEnv* env);
int register_android_server_location_GnssLocationProvider(JNIEnv* env);
int register_android_server_connectivity_Vpn(JNIEnv* env);
int register_android_server_devicepolicy_CryptoTestHelper(JNIEnv*);
int register_android_server_tv_TvUinputBridge(JNIEnv* env);
int register_android_server_tv_TvInputHal(JNIEnv* env);
int register_android_server_pdb_PersistentDataBlockService(JNIEnv* env);
int register_android_server_Watchdog(JNIEnv* env);
int register_android_server_HardwarePropertiesManagerService(JNIEnv* env);
int register_android_server_SyntheticPasswordManager(JNIEnv* env);
int register_android_hardware_display_DisplayViewport(JNIEnv* env);
int register_android_server_am_CachedAppOptimizer(JNIEnv* env);
int register_android_server_am_LowMemDetector(JNIEnv* env);
int register_com_android_server_soundtrigger_middleware_AudioSessionProviderImpl(JNIEnv* env);
int register_com_android_server_soundtrigger_middleware_ExternalCaptureStateTracker(JNIEnv* env);
int register_android_server_com_android_server_pm_PackageManagerShellCommandDataLoader(JNIEnv* env);
int register_android_server_AdbDebuggingManager(JNIEnv* env);
int register_android_server_FaceService(JNIEnv* env);
int register_android_server_GpuService(JNIEnv* env);
int register_android_server_stats_pull_StatsPullAtomService(JNIEnv* env);
int register_android_server_sensor_SensorService(JavaVM* vm, JNIEnv* env);
int register_android_server_companion_virtual_InputController(JNIEnv* env);
int register_android_server_app_GameManagerService(JNIEnv* env);
int register_com_android_server_BootReceiver(JNIEnv* env);
int register_com_android_server_wm_TaskFpsCallbackController(JNIEnv* env);
int register_com_android_server_display_DisplayControl(JNIEnv* env);
int register_com_android_server_SystemClockTime(JNIEnv* env);
int register_android_server_display_smallAreaDetectionController(JNIEnv* env);
};

using namespace android;

extern "C" jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)
{
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("GetEnv failed!");
        return result;
    }
    ALOG_ASSERT(env, "Could not retrieve the env!");

    register_android_server_broadcastradio_BroadcastRadioService(env);
    register_android_server_broadcastradio_Tuner(vm, env);
    register_android_server_PowerManagerService(env);
    register_android_server_PowerStatsService(env);
    register_android_server_HintManagerService(env);
    register_android_server_SerialService(env);
    register_android_server_InputManager(env);
    register_android_server_LightsService(env);
    register_android_server_UsbDeviceManager(env);
    register_android_server_UsbAlsaJackDetector(env);
    register_android_server_UsbAlsaMidiDevice(env);
    register_android_server_UsbHostManager(env);
    register_android_server_vr_VrManagerService(env);
    register_android_server_vibrator_VibratorController(vm, env);
    register_android_server_vibrator_VibratorManagerService(vm, env);
    register_android_server_SystemServer(env);
    register_android_server_location_GnssLocationProvider(env);
    register_android_server_connectivity_Vpn(env);
    register_android_server_devicepolicy_CryptoTestHelper(env);
    register_android_server_ConsumerIrService(env);
    register_android_server_BatteryStatsService(env);
    register_android_server_tv_TvUinputBridge(env);
    register_android_server_tv_TvInputHal(env);
    register_android_server_pdb_PersistentDataBlockService(env);
    register_android_server_HardwarePropertiesManagerService(env);
    register_android_server_storage_AppFuse(env);
    register_android_server_SyntheticPasswordManager(env);
    register_android_hardware_display_DisplayViewport(env);
    register_android_server_am_CachedAppOptimizer(env);
    register_android_server_am_LowMemDetector(env);
    register_com_android_server_soundtrigger_middleware_AudioSessionProviderImpl(env);
    register_com_android_server_soundtrigger_middleware_ExternalCaptureStateTracker(env);
    register_android_server_com_android_server_pm_PackageManagerShellCommandDataLoader(env);
    register_android_server_AdbDebuggingManager(env);
    register_android_server_FaceService(env);
    register_android_server_GpuService(env);
    register_android_server_stats_pull_StatsPullAtomService(env);
    register_android_server_sensor_SensorService(vm, env);
    register_android_server_companion_virtual_InputController(env);
    register_android_server_app_GameManagerService(env);
    register_com_android_server_BootReceiver(env);
    register_com_android_server_wm_TaskFpsCallbackController(env);
    register_com_android_server_display_DisplayControl(env);
    register_com_android_server_SystemClockTime(env);
    register_android_server_display_smallAreaDetectionController(env);
    return JNI_VERSION_1_4;
}
