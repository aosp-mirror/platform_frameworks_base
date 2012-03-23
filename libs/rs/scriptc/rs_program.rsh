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

/*! \mainpage notitle
 *
 * Renderscript is a high-performance runtime that provides graphics rendering and
 * compute operations at the native level. Renderscript code is compiled on devices
 * at runtime to allow platform-independence as well.
 * This reference documentation describes the Renderscript runtime APIs, which you
 * can utilize to write Renderscript code in C99. The Renderscript header
 * files are automatically included for you, except for the rs_graphics.rsh header. If
 * you are doing graphics rendering, include the graphics header file like this:
 *
 * <code>#include "rs_graphics.rsh"</code>
 *
 * To use Renderscript, you need to utilize the Renderscript runtime APIs documented here
 * as well as the Android framework APIs for Renderscript.
 * For documentation on the Android framework APIs, see the <a target="_parent" href=
 * "http://developer.android.com/reference/android/renderscript/package-summary.html">
 * android.renderscript</a> package reference.
 * For more information on how to develop with Renderscript and how the runtime and
 * Android framework APIs interact, see the <a target="_parent" href=
 * "http://developer.android.com/guide/topics/renderscript/index.html">Renderscript
 * developer guide</a> and the <a target="_parent" href=
 * "http://developer.android.com/resources/samples/RenderScript/index.html">
 * Renderscript samples</a>.
 */

/** @file rs_program.rsh
 *  \brief Program object routines
 *
 *
 */

#ifndef __RS_PROGRAM_RSH__
#define __RS_PROGRAM_RSH__

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



#endif // __RS_PROGRAM_RSH__

