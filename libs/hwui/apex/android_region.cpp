/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "android/graphics/region.h"

#include "GraphicsJNI.h"

#include <SkRegion.h>

static inline SkRegion::Iterator* ARegionIter_to_SkRegionIter(ARegionIterator* iterator) {
    return reinterpret_cast<SkRegion::Iterator*>(iterator);
}

static inline ARegionIterator* SkRegionIter_to_ARegionIter(SkRegion::Iterator* iterator) {
    return reinterpret_cast<ARegionIterator*>(iterator);
}

ARegionIterator* ARegionIterator_acquireIterator(JNIEnv* env, jobject regionObj) {
    SkRegion* region = GraphicsJNI::getNativeRegion(env, regionObj);
    return (!region) ? nullptr : SkRegionIter_to_ARegionIter(new SkRegion::Iterator(*region));
}

void ARegionIterator_releaseIterator(ARegionIterator* iterator) {
    delete ARegionIter_to_SkRegionIter(iterator);
}

bool ARegionIterator_isComplex(ARegionIterator* iterator) {
    return ARegionIter_to_SkRegionIter(iterator)->rgn()->isComplex();
}

bool ARegionIterator_isDone(ARegionIterator* iterator) {
    return ARegionIter_to_SkRegionIter(iterator)->done();
}

void ARegionIterator_next(ARegionIterator* iterator) {
    ARegionIter_to_SkRegionIter(iterator)->next();
}

ARect ARegionIterator_getRect(ARegionIterator* iterator) {
    const SkIRect& rect = ARegionIter_to_SkRegionIter(iterator)->rect();
    return { rect.fLeft, rect.fTop, rect.fRight, rect.fBottom };
}

ARect ARegionIterator_getTotalBounds(ARegionIterator* iterator) {
    const SkIRect& bounds = ARegionIter_to_SkRegionIter(iterator)->rgn()->getBounds();
    return { bounds.fLeft, bounds.fTop, bounds.fRight, bounds.fBottom };
}
