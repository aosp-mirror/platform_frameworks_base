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
#include <binder/Parcel.h>
#include <private/surfaceflinger/LayerState.h>
#include <surfaceflinger/ISurfaceComposerClient.h>

namespace android {

status_t layer_state_t::write(Parcel& output) const
{
    status_t err;

    size_t len = transparentRegion.write(NULL, 0);
    err = output.writeInt32(len);
    if (err < NO_ERROR) return err;

    void* buf = output.writeInplace(len);
    if (buf == NULL) return NO_MEMORY;

    err = transparentRegion.write(buf, len);
    if (err < NO_ERROR) return err;

    // NOTE: regions are at the end of the structure
    size_t size = sizeof(layer_state_t);
    size -= sizeof(transparentRegion);
    err = output.write(this, size);
    return err;
}

status_t layer_state_t::read(const Parcel& input)
{
    status_t err;
    size_t len = input.readInt32();
    void const* buf = input.readInplace(len);
    if (buf == NULL) return NO_MEMORY;

    err = transparentRegion.read(buf);
    if (err < NO_ERROR) return err;

    // NOTE: regions are at the end of the structure
    size_t size = sizeof(layer_state_t);
    size -= sizeof(transparentRegion);
    input.read(this, size);
    return NO_ERROR;
}

status_t ComposerState::write(Parcel& output) const {
    output.writeStrongBinder(client->asBinder());
    return state.write(output);
}

status_t ComposerState::read(const Parcel& input) {
    client = interface_cast<ISurfaceComposerClient>(input.readStrongBinder());
    return state.read(input);
}

}; // namespace android
