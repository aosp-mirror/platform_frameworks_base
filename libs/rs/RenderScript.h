/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef RENDER_SCRIPT_H
#define RENDER_SCRIPT_H

#include <stdint.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

//////////////////////////////////////////////////////
//

typedef void * RsAdapter1D;
typedef void * RsAdapter2D;
typedef void * RsAllocation;
typedef void * RsContext;
typedef void * RsDevice;
typedef void * RsElement;
typedef void * RsFile;
typedef void * RsSampler;
typedef void * RsScript;
typedef void * RsScriptBasicTemp;
typedef void * RsTriangleMesh;
typedef void * RsSimpleMesh;
typedef void * RsType;
typedef void * RsLight;

typedef void * RsProgramVertex;
typedef void * RsProgramFragment;
typedef void * RsProgramFragmentStore;

RsDevice rsDeviceCreate();
void rsDeviceDestroy(RsDevice);

RsContext rsContextCreate(RsDevice, void *, uint32_t version);
void rsContextDestroy(RsContext);
void rsObjDestroyOOB(RsContext, void *);

#define RS_MAX_TEXTURE 2

enum RsDataType {
    RS_TYPE_FLOAT,
    RS_TYPE_UNSIGNED,
    RS_TYPE_SIGNED
};

enum RsDataKind {
    RS_KIND_USER,
    RS_KIND_RED,
    RS_KIND_GREEN,
    RS_KIND_BLUE,
    RS_KIND_ALPHA,
    RS_KIND_LUMINANCE,
    RS_KIND_INTENSITY,
    RS_KIND_X,
    RS_KIND_Y,
    RS_KIND_Z,
    RS_KIND_W,
    RS_KIND_S,
    RS_KIND_T,
    RS_KIND_Q,
    RS_KIND_R,
    RS_KIND_NX,
    RS_KIND_NY,
    RS_KIND_NZ,
    RS_KIND_INDEX,
    RS_KIND_POINT_SIZE
};

enum RsElementPredefined {
    RS_ELEMENT_USER_U8,
    RS_ELEMENT_USER_I8,
    RS_ELEMENT_USER_U16,
    RS_ELEMENT_USER_I16,
    RS_ELEMENT_USER_U32,
    RS_ELEMENT_USER_I32,
    RS_ELEMENT_USER_FLOAT,

    RS_ELEMENT_A_8,          // 7
    RS_ELEMENT_RGB_565,      // 8
    RS_ELEMENT_RGBA_5551,    // 9
    RS_ELEMENT_RGBA_4444,    // 10
    RS_ELEMENT_RGB_888,      // 11
    RS_ELEMENT_RGBA_8888,    // 12

    RS_ELEMENT_INDEX_16, //13
    RS_ELEMENT_INDEX_32,
    RS_ELEMENT_XY_F32,
    RS_ELEMENT_XYZ_F32,
    RS_ELEMENT_ST_XY_F32,
    RS_ELEMENT_ST_XYZ_F32,
    RS_ELEMENT_NORM_XYZ_F32,
    RS_ELEMENT_NORM_ST_XYZ_F32,
};

enum RsSamplerParam {
    RS_SAMPLER_MIN_FILTER,
    RS_SAMPLER_MAG_FILTER,
    RS_SAMPLER_WRAP_S,
    RS_SAMPLER_WRAP_T,
    RS_SAMPLER_WRAP_R
};

enum RsSamplerValue {
    RS_SAMPLER_NEAREST,
    RS_SAMPLER_LINEAR,
    RS_SAMPLER_LINEAR_MIP_LINEAR,
    RS_SAMPLER_WRAP,
    RS_SAMPLER_CLAMP
};

enum RsDimension {
    RS_DIMENSION_X,
    RS_DIMENSION_Y,
    RS_DIMENSION_Z,
    RS_DIMENSION_LOD,
    RS_DIMENSION_FACE,

    RS_DIMENSION_ARRAY_0 = 100,
    RS_DIMENSION_ARRAY_1,
    RS_DIMENSION_ARRAY_2,
    RS_DIMENSION_ARRAY_3,
    RS_DIMENSION_MAX = RS_DIMENSION_ARRAY_3
};

enum RsDepthFunc {
    RS_DEPTH_FUNC_ALWAYS,
    RS_DEPTH_FUNC_LESS,
    RS_DEPTH_FUNC_LEQUAL,
    RS_DEPTH_FUNC_GREATER,
    RS_DEPTH_FUNC_GEQUAL,
    RS_DEPTH_FUNC_EQUAL,
    RS_DEPTH_FUNC_NOTEQUAL
};

enum RsBlendSrcFunc {
    RS_BLEND_SRC_ZERO,                  // 0
    RS_BLEND_SRC_ONE,                   // 1
    RS_BLEND_SRC_DST_COLOR,             // 2
    RS_BLEND_SRC_ONE_MINUS_DST_COLOR,   // 3
    RS_BLEND_SRC_SRC_ALPHA,             // 4
    RS_BLEND_SRC_ONE_MINUS_SRC_ALPHA,   // 5
    RS_BLEND_SRC_DST_ALPHA,             // 6
    RS_BLEND_SRC_ONE_MINUS_DST_ALPHA,   // 7
    RS_BLEND_SRC_SRC_ALPHA_SATURATE     // 8
};

enum RsBlendDstFunc {
    RS_BLEND_DST_ZERO,                  // 0
    RS_BLEND_DST_ONE,                   // 1
    RS_BLEND_DST_SRC_COLOR,             // 2
    RS_BLEND_DST_ONE_MINUS_SRC_COLOR,   // 3
    RS_BLEND_DST_SRC_ALPHA,             // 4
    RS_BLEND_DST_ONE_MINUS_SRC_ALPHA,   // 5
    RS_BLEND_DST_DST_ALPHA,             // 6
    RS_BLEND_DST_ONE_MINUS_DST_ALPHA    // 7
};

enum RsTexEnvMode {
    RS_TEX_ENV_MODE_REPLACE,
    RS_TEX_ENV_MODE_MODULATE,
    RS_TEX_ENV_MODE_DECAL
};

enum RsPrimitive {
    RS_PRIMITIVE_POINT,
    RS_PRIMITIVE_LINE,
    RS_PRIMITIVE_LINE_STRIP,
    RS_PRIMITIVE_TRIANGLE,
    RS_PRIMITIVE_TRIANGLE_STRIP,
    RS_PRIMITIVE_TRIANGLE_FAN
};


#include "rsgApiFuncDecl.h"

#ifdef __cplusplus
};
#endif

#endif // RENDER_SCRIPT_H



