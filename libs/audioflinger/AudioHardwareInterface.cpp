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

#define LOG_TAG "AudioHardwareInterface"
#include <utils/Log.h>
#include <utils/String8.h>

#include "AudioHardwareStub.h"
#include "AudioHardwareGeneric.h"

//#define DUMP_FLINGER_OUT        // if defined allows recording samples in a file
#ifdef DUMP_FLINGER_OUT
#include "AudioDumpInterface.h"
#endif


// change to 1 to log routing calls
#define LOG_ROUTING_CALLS 0

namespace android {

#if LOG_ROUTING_CALLS
static const char* routingModeStrings[] =
{
    "OUT OF RANGE",
    "INVALID",
    "CURRENT",
    "NORMAL",
    "RINGTONE",
    "IN_CALL"
};

static const char* routeStrings[] =
{
    "EARPIECE ",
    "SPEAKER ",
    "BLUETOOTH ",
    "HEADSET "
    "BLUETOOTH_A2DP "
};
static const char* routeNone = "NONE";

static const char* displayMode(int mode)
{
    if ((mode < -2) || (mode > 2))
        return routingModeStrings[0];
    return routingModeStrings[mode+3];
}

static const char* displayRoutes(uint32_t routes)
{
    static char routeStr[80];
    if (routes == 0)
        return routeNone;
    routeStr[0] = 0;
    int bitMask = 1;
    for (int i = 0; i < 4; ++i, bitMask <<= 1) {
        if (routes & bitMask) {
            strcat(routeStr, routeStrings[i]);
        }
    }
    routeStr[strlen(routeStr)-1] = 0;
    return routeStr;
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
    
#ifdef DUMP_FLINGER_OUT
    // This code adds a record of buffers in a file to write calls made by AudioFlinger.
    // It replaces the current AudioHardwareInterface object by an intermediate one which
    // will record buffers in a file (after sending them to hardware) for testing purpose.
    // This feature is enabled by defining symbol DUMP_FLINGER_OUT.
    // The output file is FLINGER_DUMP_NAME. Pause are not recorded in the file.
    
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
    // force a routing update on initialization
    memset(&mRoutes, 0, sizeof(mRoutes));
    mMode = 0;
}

// generics for audio routing - the real work is done in doRouting
status_t AudioHardwareBase::setRouting(int mode, uint32_t routes)
{
#if LOG_ROUTING_CALLS
    LOGD("setRouting: mode=%s, routes=[%s]", displayMode(mode), displayRoutes(routes));
#endif
    if (mode == AudioSystem::MODE_CURRENT)
        mode = mMode;
    if ((mode < 0) || (mode >= AudioSystem::NUM_MODES))
        return BAD_VALUE;
    uint32_t old = mRoutes[mode];
    mRoutes[mode] = routes;
    if ((mode != mMode) || (old == routes))
        return NO_ERROR;
#if LOG_ROUTING_CALLS
    const char* oldRouteStr = strdup(displayRoutes(old));
    LOGD("doRouting: mode=%s, old route=[%s], new route=[%s]",
           displayMode(mode), oldRouteStr, displayRoutes(routes));
    delete oldRouteStr;
#endif
    return doRouting();
}

status_t AudioHardwareBase::getRouting(int mode, uint32_t* routes)
{
    if (mode == AudioSystem::MODE_CURRENT)
        mode = mMode;
    if ((mode < 0) || (mode >= AudioSystem::NUM_MODES))
        return BAD_VALUE;
    *routes = mRoutes[mode];
#if LOG_ROUTING_CALLS
    LOGD("getRouting: mode=%s, routes=[%s]",
           displayMode(mode), displayRoutes(*routes));
#endif
    return NO_ERROR;
}

status_t AudioHardwareBase::setMode(int mode)
{
#if LOG_ROUTING_CALLS
    LOGD("setMode(%s)", displayMode(mode));
#endif
    if ((mode < 0) || (mode >= AudioSystem::NUM_MODES))
        return BAD_VALUE;
    if (mMode == mode)
        return NO_ERROR;
#if LOG_ROUTING_CALLS
    LOGD("doRouting: old mode=%s, new mode=%s route=[%s]",
            displayMode(mMode), displayMode(mode), displayRoutes(mRoutes[mode]));
#endif
    mMode = mode;
    return doRouting();
}

status_t AudioHardwareBase::getMode(int* mode)
{
    // Implement: set audio routing
    *mode = mMode;
    return NO_ERROR;
}

status_t AudioHardwareBase::setParameter(const char* key, const char* value)
{
    // default implementation is to ignore
    return NO_ERROR;
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
    for (int i = 0, n = AudioSystem::NUM_MODES; i < n; ++i) {
        snprintf(buffer, SIZE, "\tmRoutes[%d]: %d\n", i, mRoutes[i]);
        result.append(buffer);
    }
    ::write(fd, result.string(), result.size());
    dump(fd, args);  // Dump the state of the concrete child.
    return NO_ERROR;
}

// ----------------------------------------------------------------------------

}; // namespace android
