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

#include "rsContext.h"

using namespace android;
using namespace android::renderscript;

#include <GLES/gl.h>
#include <GLES/glext.h>

Mesh::Mesh(Context *rsc) : ObjectBase(rsc)
{
    mAllocFile = __FILE__;
    mAllocLine = __LINE__;
    mVerticies = NULL;
    mVerticiesCount = 0;
    mPrimitives = NULL;
    mPrimitivesCount = 0;
}

Mesh::~Mesh()
{
}



MeshContext::MeshContext()
{
}

MeshContext::~MeshContext()
{
}

