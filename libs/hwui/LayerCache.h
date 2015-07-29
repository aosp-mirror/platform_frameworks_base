/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_HWUI_LAYER_CACHE_H
#define ANDROID_HWUI_LAYER_CACHE_H

#include "Debug.h"
#include "Layer.h"

#include <set>

namespace android {
namespace uirenderer {

class RenderState;

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#if DEBUG_LAYERS
    #define LAYER_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define LAYER_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Cache
///////////////////////////////////////////////////////////////////////////////

class LayerCache {
public:
    LayerCache();
    ~LayerCache();

    /**
     * Returns a layer large enough for the specified dimensions. If no suitable
     * layer can be found, a new one is created and returned. If creating a new
     * layer fails, NULL is returned.
     *
     * When a layer is obtained from the cache, it is removed and the total
     * size of the cache goes down.
     *
     * @param width The desired width of the layer
     * @param height The desired height of the layer
     */
    Layer* get(RenderState& renderState, const uint32_t width, const uint32_t height);

    /**
     * Adds the layer to the cache. The layer will not be added if there is
     * not enough space available. Adding a layer can cause other layers to
     * be removed from the cache.
     *
     * @param layer The layer to add to the cache
     *
     * @return True if the layer was added, false otherwise.
     */
    bool put(Layer* layer);
    /**
     * Clears the cache. This causes all layers to be deleted.
     */
    void clear();

    /**
     * Sets the maximum size of the cache in bytes.
     */
    void setMaxSize(uint32_t maxSize);
    /**
     * Returns the maximum size of the cache in bytes.
     */
    uint32_t getMaxSize();
    /**
     * Returns the current size of the cache in bytes.
     */
    uint32_t getSize();

    size_t getCount();

    /**
     * Prints out the content of the cache.
     */
    void dump();

private:
    struct LayerEntry {
        LayerEntry():
            mLayer(nullptr), mWidth(0), mHeight(0) {
        }

        LayerEntry(const uint32_t layerWidth, const uint32_t layerHeight): mLayer(nullptr) {
            mWidth = Layer::computeIdealWidth(layerWidth);
            mHeight = Layer::computeIdealHeight(layerHeight);
        }

        LayerEntry(Layer* layer):
            mLayer(layer), mWidth(layer->getWidth()), mHeight(layer->getHeight()) {
        }

        static int compare(const LayerEntry& lhs, const LayerEntry& rhs);

        bool operator==(const LayerEntry& other) const {
            return compare(*this, other) == 0;
        }

        bool operator!=(const LayerEntry& other) const {
            return compare(*this, other) != 0;
        }

        bool operator<(const LayerEntry& other) const {
            return LayerEntry::compare(*this, other) < 0;
        }

        Layer* mLayer;
        uint32_t mWidth;
        uint32_t mHeight;
    }; // struct LayerEntry

    void deleteLayer(Layer* layer);

    std::multiset<LayerEntry> mCache;

    uint32_t mSize;
    uint32_t mMaxSize;
}; // class LayerCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_LAYER_CACHE_H
