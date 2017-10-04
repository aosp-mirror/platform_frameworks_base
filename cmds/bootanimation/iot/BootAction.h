/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef _BOOTANIMATION_BOOTACTION_H
#define _BOOTANIMATION_BOOTACTION_H

#include <string>
#include <vector>

#include <boot_action/boot_action.h>  // libandroidthings native API.
#include <utils/RefBase.h>

namespace android {

class BootAction : public RefBase {
public:
    ~BootAction();

    // libraryPath is a fully qualified path to the target .so library.
    bool init(const std::string& libraryPath,
              const std::vector<ABootActionParameter>& parameters);

    // The animation is going to start playing partNumber for the playCount'th
    // time, update the action as needed.
    // This is run in the same thread as the boot animation,
    // you must not block here.
    void startPart(int partNumber, int playCount);

    // Shutdown the boot action, this will be called shortly before the
    // process is shut down to allow time for cleanup.
    void shutdown();

private:
    typedef bool (*libInit)(const ABootActionParameter* parameters,
                            size_t num_parameters);
    typedef void (*libStartPart)(int partNumber, int playNumber);
    typedef void (*libShutdown)();

    bool loadSymbol(const char* symbol, void** loaded);

    void* mLibHandle = nullptr;
    libInit mLibInit = nullptr;
    libStartPart mLibStartPart = nullptr;
    libShutdown mLibShutdown = nullptr;
};

}  // namespace android


#endif  // _BOOTANIMATION_BOOTACTION_H
