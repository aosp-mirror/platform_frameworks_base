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

/** @file rs_mesh.rsh
 *  \brief Mesh routines
 *
 *
 */

#ifndef __RS_MESH_RSH__
#define __RS_MESH_RSH__

/**
 * @param m mesh to get data from
 * @return number of allocations in the mesh that contain vertex
 *         data
 */
extern uint32_t __attribute__((overloadable))
    rsMeshGetVertexAllocationCount(rs_mesh m);

/**
 * @param m mesh to get data from
 * @return number of primitive groups in the mesh. This would
 *         include simple primitives as well as allocations
 *         containing index data
 */
extern uint32_t __attribute__((overloadable))
    rsMeshGetPrimitiveCount(rs_mesh m);

/**
 * @param m mesh to get data from
 * @param index index of the vertex allocation
 * @return allocation containing vertex data
 */
extern rs_allocation __attribute__((overloadable))
    rsMeshGetVertexAllocation(rs_mesh m, uint32_t index);

/**
 * @param m mesh to get data from
 * @param index index of the index allocation
 * @return allocation containing index data
 */
extern rs_allocation __attribute__((overloadable))
    rsMeshGetIndexAllocation(rs_mesh m, uint32_t index);

/**
 * @param m mesh to get data from
 * @param index index of the primitive
 * @return primitive describing how the mesh is rendered
 */
extern rs_primitive __attribute__((overloadable))
    rsMeshGetPrimitive(rs_mesh m, uint32_t index);

#endif // __RS_MESH_RSH__

