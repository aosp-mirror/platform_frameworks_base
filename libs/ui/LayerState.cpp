/*
 * Copyright (C) 2008 The Android Open Source Project
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

#include <utils/Errors.h>
#include <utils/Parcel.h>
#include <private/ui/LayerState.h>

namespace android {

status_t layer_state_t::write(Parcel& output) const
{
    size_t size = sizeof(layer_state_t);

    //output.writeStrongBinder(surface->asBinder());
    //size -= sizeof(surface);

    transparentRegion.write(output);
    size -= sizeof(transparentRegion);
    
    output.write(this, size);
    
    return NO_ERROR;
}

status_t layer_state_t::read(const Parcel& input)
{
    size_t size = sizeof(layer_state_t);

    //surface = interface_cast<ISurface>(input.readStrongBinder());
    //size -= sizeof(surface);

    transparentRegion.read(input);
    size -= sizeof(transparentRegion);

    input.read(this, size);
    
    return NO_ERROR;
}

}; // namespace android
