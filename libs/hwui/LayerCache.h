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

#ifndef ANDROID_UI_LAYER_CACHE_H
#define ANDROID_UI_LAYER_CACHE_H

#include "Layer.h"
#include "GenerationCache.h"

namespace android {
namespace uirenderer {

class LayerCache: public OnEntryRemoved<LayerSize, Layer*> {
public:
    LayerCache(uint32_t maxByteSize);
    ~LayerCache();

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(LayerSize& bitmap, Layer*& texture);

    /**
     * Returns the layer of specified dimensions, NULL if cannot be found.
     */
    Layer* get(LayerSize& size);
    /**
     * Adds the layer to the cache. The layer will not be added if there is
     * not enough space available.
     *
     * @return True if the layer was added, false otherwise.
     */
    bool put(LayerSize& size, Layer* layer);
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

private:
    void deleteLayer(Layer* layer);

    GenerationMultiCache<LayerSize, Layer*> mCache;

    uint32_t mSize;
    uint32_t mMaxSize;
}; // class LayerCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_LAYER_CACHE_H
