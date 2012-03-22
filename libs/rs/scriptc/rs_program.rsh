/*
 * Copyright (C) 2012 The Android Open Source Project
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

/** @file rs_program.rsh
 *  \brief Program object routines
 *
 *
 */

#ifndef __RS_PROGRAM_RSH__
#define __RS_PROGRAM_RSH__

#if (defined(RS_VERSION) && (RS_VERSION >= 16))

/**
 * @hide
 * Get program store depth function
 *
 * @param ps
 */
extern rs_depth_func __attribute__((overloadable))
    rsgProgramStoreGetDepthFunc(rs_program_store ps);

/**
 * @hide
 * Get program store depth mask
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetDepthMask(rs_program_store ps);
/**
 * @hide
 * Get program store red component color mask
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetColorMaskR(rs_program_store ps);

/**
 * @hide
 * Get program store green component color mask
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetColorMaskG(rs_program_store ps);

/**
 * @hide
 * Get program store blur component color mask
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetColorMaskB(rs_program_store ps);

/**
 * @hide
 * Get program store alpha component color mask
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetColorMaskA(rs_program_store ps);

/**
 * @hide
 * Get program store blend source function
 *
 * @param ps
 */
extern rs_blend_src_func __attribute__((overloadable))
        rsgProgramStoreGetBlendSrcFunc(rs_program_store ps);

/**
 * @hide
 * Get program store blend destination function
 *
 * @param ps
 */
extern rs_blend_dst_func __attribute__((overloadable))
    rsgProgramStoreGetBlendDstFunc(rs_program_store ps);

/**
 * @hide
 * Get program store dither state
 *
 * @param ps
 */
extern bool __attribute__((overloadable))
    rsgProgramStoreGetDitherEnabled(rs_program_store ps);

/**
 * @hide
 * Get program raster point sprite state
 *
 * @param pr
 */
extern bool __attribute__((overloadable))
    rsgProgramRasterGetPointSpriteEnabled(rs_program_raster pr);

/**
 * @hide
 * Get program raster cull mode
 *
 * @param pr
 */
extern rs_cull_mode __attribute__((overloadable))
    rsgProgramRasterGetCullMode(rs_program_raster pr);

#endif // (defined(RS_VERSION) && (RS_VERSION >= 16))

#endif // __RS_PROGRAM_RSH__

