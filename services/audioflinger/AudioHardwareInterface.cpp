/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#include <cutils/properties.h>
#include <string.h>
#include <unistd.h>
//#define LOG_NDEBUG 0

#define LOG_TAG "AudioHardwareInterface"
#include <utils/Log.h>
#include <utils/String8.h>

#include "AudioHardwareStub.h"
#include "AudioHardwareGeneric.h"
#ifdef WITH_A2DP
#include "A2dpAudioInterface.h"
#endif

#ifdef ENABLE_AUDIO_DUMP
#include "AudioDumpInterface.h"
#endif


// change to 1 to log routing calls
#define LOG_ROUTING_CALLS 1

namespace android {

#if LOG_ROUTING_CALLS
static const char* routingModeStrings[] =
{
    "OUT OF RANGE",
    "INVALID",
    "CURRENT",
    "NORMAL",
    "RINGTONE",
    "IN_CALL",
    "IN_COMMUNICATION"
};

static const char* routeNone = "NONE";

static const char* displayMode(int mode)
{
    if ((mode < AudioSystem::MODE_INVALID) || (mode >= AudioSystem::NUM_MODES))
        return routingModeStrings[0];
    return routingModeStrings[mode+3];
}
#endif

// ----------------------------------------------------------------------------

AudioHardwareInterface* AudioHardwareInterface::create()
{
    /*
     * FIXME: This code needs to instantiate the correct audio device
     * interface. For now - we use compile-time switches.
     */
    AudioHardwareInterface* hw = 0;
    char value[PROPERTY_VALUE_MAX];

#ifdef GENERIC_AUDIO
    hw = new AudioHardwareGeneric();
#else
    // if running in emulation - use the emulator driver
    if (property_get("ro.kernel.qemu", value, 0)) {
        LOGD("Running in emulation - using generic audio driver");
        hw = new AudioHardwareGeneric();
    }
    else {
        LOGV("Creating Vendor Specific AudioHardware");
        hw = createAudioHardware();
    }
#endif
    if (hw->initCheck() != NO_ERROR) {
        LOGW("Using stubbed audio hardware. No sound will be produced.");
        delete hw;
        hw = new AudioHardwareStub();
    }
    
#ifdef WITH_A2DP
    hw = new A2dpAudioInterface(hw);
#endif

#ifdef ENABLE_AUDIO_DUMP
    // This code adds a record of buffers in a file to write calls made by AudioFlinger.
    // It replaces the current AudioHardwareInterface object by an intermediate one which
    // will record buffers in a file (after sending them to hardware) for testing purpose.
    // This feature is enabled by defining symbol ENABLE_AUDIO_DUMP.
    // The output file is set with setParameters("test_cmd_file_name=<name>"). Pause are not recorded in the file.
    LOGV("opening PCM dump interface");
    hw = new AudioDumpInterface(hw);    // replace interface
#endif
    return hw;
}

AudioStreamOut::~AudioStreamOut()
{
}

AudioStreamIn::~AudioStreamIn() {}

AudioHardwareBase::AudioHardwareBase()
{
    mMode = 0;
}

status_t AudioHardwareBase::setMode(int mode)
{
#if LOG_ROUTING_CALLS
    LOGD("setMode(%s)", displayMode(mode));
#endif
    if ((mode < 0) || (mode >= AudioSystem::NUM_MODES))
        return BAD_VALUE;
    if (mMode == mode)
        return ALREADY_EXISTS;
    mMode = mode;
    return NO_ERROR;
}

// default implementation
status_t AudioHardwareBase::setParameters(const String8& keyValuePairs)
{
    return NO_ERROR;
}

// default implementation
String8 AudioHardwareBase::getParameters(const String8& keys)
{
    AudioParameter param = AudioParameter(keys);
    return param.toString();
}

// default implementation
size_t AudioHardwareBase::getInputBufferSize(uint32_t sampleRate, int format, int channelCount)
{
    if (sampleRate != 8000) {
        LOGW("getInputBufferSize bad sampling rate: %d", sampleRate);
        return 0;
    }
    if (format != AudioSystem::PCM_16_BIT) {
        LOGW("getInputBufferSize bad format: %d", format);
        return 0;
    }
    if (channelCount != 1) {
        LOGW("getInputBufferSize bad channel count: %d", channelCount);
        return 0;
    }

    return 320;
}

status_t AudioHardwareBase::dumpState(int fd, const Vector<String16>& args)
{
    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;
    snprintf(buffer, SIZE, "AudioHardwareBase::dumpState\n");
    result.append(buffer);
    snprintf(buffer, SIZE, "\tmMode: %d\n", mMode);
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    dump(fd, args);  // Dump the state of the concrete child.
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

}; // namespace android
