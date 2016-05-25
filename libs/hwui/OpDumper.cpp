/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "OpDumper.h"

#include "RecordedOp.h"

namespace android {
namespace uirenderer {

#define STRINGIFY(n) #n,
static const char* sOpNameLut[] = BUILD_FULL_OP_LUT(STRINGIFY);

void OpDumper::dump(const RecordedOp& op, std::ostream& output, int level) {
    for (int i = 0; i < level; i++) {
        output << "  ";
    }

    Rect localBounds(op.unmappedBounds);
    op.localMatrix.mapRect(localBounds);
    output << sOpNameLut[op.opId] << " " << localBounds;

    if (op.localClip
            && (!op.localClip->rect.contains(localBounds) || op.localClip->intersectWithRoot)) {
        output << std::fixed << std::setprecision(0)
             << " clip=" << op.localClip->rect
             << " mode=" << (int)op.localClip->mode;

        if (op.localClip->intersectWithRoot) {
             output << " iwr";
        }
    }
}

const char* OpDumper::opName(const RecordedOp& op) {
    return sOpNameLut[op.opId];
}

} // namespace uirenderer
} // namespace android
