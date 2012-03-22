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

/** @file rs_mesh.rsh
 *  \brief Mesh routines
 *
 *
 */

#ifndef __RS_MESH_RSH__
#define __RS_MESH_RSH__

#if (defined(RS_VERSION) && (RS_VERSION >= 16))

/**
 * @param m mesh to get data from
 * @return number of allocations in the mesh that contain vertex
 *         data
 */
extern uint32_t __attribute__((overloadable))
    rsgMeshGetVertexAllocationCount(rs_mesh m);

/**
 * @param m mesh to get data from
 * @return number of primitive groups in the mesh. This would
 *         include simple primitives as well as allocations
 *         containing index data
 */
extern uint32_t __attribute__((overloadable))
    rsgMeshGetPrimitiveCount(rs_mesh m);

/**
 * @param m mesh to get data from
 * @param index index of the vertex allocation
 * @return allocation containing vertex data
 */
extern rs_allocation __attribute__((overloadable))
    rsgMeshGetVertexAllocation(rs_mesh m, uint32_t index);

/**
 * @param m mesh to get data from
 * @param index index of the index allocation
 * @return allocation containing index data
 */
extern rs_allocation __attribute__((overloadable))
    rsgMeshGetIndexAllocation(rs_mesh m, uint32_t index);

/**
 * @param m mesh to get data from
 * @param index index of the primitive
 * @return primitive describing how the mesh is rendered
 */
extern rs_primitive __attribute__((overloadable))
    rsgMeshGetPrimitive(rs_mesh m, uint32_t index);

#endif // (defined(RS_VERSION) && (RS_VERSION >= 16))

#endif // __RS_MESH_RSH__

