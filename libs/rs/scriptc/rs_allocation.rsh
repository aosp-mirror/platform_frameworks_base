/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * For documentation on the Android framework APIs, see the <a href=
 * "http://developer.android.com/reference/android/renderscript/package-summary.html">
 * android.renderscript</a> package reference.
 * For more information on how to develop with Renderscript and how the runtime and
 * Android framework APIs interact, see the <a href=
 * "http://developer.android.com/guide/topics/renderscript/index.html">Renderscript
 * developer guide</a> and the <a href=
 * "http://developer.android.com/resources/samples/RenderScript/index.html">
 * Renderscript samples</a>.
 */

/** @file rs_allocation.rsh
 *  \brief Allocation routines
 *
 *
 */

#ifndef __RS_ALLOCATION_RSH__
#define __RS_ALLOCATION_RSH__

/**
 * Returns the Allocation for a given pointer.  The pointer should point within
 * a valid allocation.  The results are undefined if the pointer is not from a
 * valid allocation.
 */
extern rs_allocation __attribute__((overloadable))
    rsGetAllocation(const void *);

/**
 * Query the dimension of an allocation.
 *
 * @return uint32_t The X dimension of the allocation.
 */
extern uint32_t __attribute__((overloadable))
    rsAllocationGetDimX(rs_allocation);

/**
 * Query the dimension of an allocation.
 *
 * @return uint32_t The Y dimension of the allocation.
 */
extern uint32_t __attribute__((overloadable))
    rsAllocationGetDimY(rs_allocation);

/**
 * Query the dimension of an allocation.
 *
 * @return uint32_t The Z dimension of the allocation.
 */
extern uint32_t __attribute__((overloadable))
    rsAllocationGetDimZ(rs_allocation);

/**
 * Query an allocation for the presence of more than one LOD.
 *
 * @return uint32_t Returns 1 if more than one LOD is present, 0 otherwise.
 */
extern uint32_t __attribute__((overloadable))
    rsAllocationGetDimLOD(rs_allocation);

/**
 * Query an allocation for the presence of more than one face.
 *
 * @return uint32_t Returns 1 if more than one face is present, 0 otherwise.
 */
extern uint32_t __attribute__((overloadable))
    rsAllocationGetDimFaces(rs_allocation);

#if (defined(RS_VERSION) && (RS_VERSION >= 14))

/**
 * Copy part of an allocation from another allocation.
 *
 * @param dstAlloc Allocation to copy data into.
 * @param dstOff The offset of the first element to be copied in
 *               the destination allocation.
 * @param dstMip Mip level in the destination allocation.
 * @param count The number of elements to be copied.
 * @param srcAlloc The source data allocation.
 * @param srcOff The offset of the first element in data to be
 *               copied in the source allocation.
 * @param srcMip Mip level in the source allocation.
 */
extern void __attribute__((overloadable))
    rsAllocationCopy1DRange(rs_allocation dstAlloc,
                            uint32_t dstOff, uint32_t dstMip,
                            uint32_t count,
                            rs_allocation srcAlloc,
                            uint32_t srcOff, uint32_t srcMip);

/**
 * Copy a rectangular region into the allocation from another
 * allocation.
 *
 * @param dstAlloc allocation to copy data into.
 * @param dstXoff X offset of the region to update in the
 *                destination allocation.
 * @param dstYoff Y offset of the region to update in the
 *                destination allocation.
 * @param dstMip Mip level in the destination allocation.
 * @param dstFace Cubemap face of the destination allocation,
 *                ignored for allocations that aren't cubemaps.
 * @param width Width of the incoming region to update.
 * @param height Height of the incoming region to update.
 * @param srcAlloc The source data allocation.
 * @param srcXoff X offset in data of the source allocation.
 * @param srcYoff Y offset in data of the source allocation.
 * @param srcMip Mip level in the source allocation.
 * @param srcFace Cubemap face of the source allocation,
 *                ignored for allocations that aren't cubemaps.
 */
extern void __attribute__((overloadable))
    rsAllocationCopy2DRange(rs_allocation dstAlloc,
                            uint32_t dstXoff, uint32_t dstYoff,
                            uint32_t dstMip,
                            rs_allocation_cubemap_face dstFace,
                            uint32_t width, uint32_t height,
                            rs_allocation srcAlloc,
                            uint32_t srcXoff, uint32_t srcYoff,
                            uint32_t srcMip,
                            rs_allocation_cubemap_face srcFace);

#endif //defined(RS_VERSION) && (RS_VERSION >= 14)

/**
 * Extract a single element from an allocation.
 */
extern const void * __attribute__((overloadable))
    rsGetElementAt(rs_allocation, uint32_t x);
/**
 * \overload
 */
extern const void * __attribute__((overloadable))
    rsGetElementAt(rs_allocation, uint32_t x, uint32_t y);
/**
 * \overload
 */
extern const void * __attribute__((overloadable))
    rsGetElementAt(rs_allocation, uint32_t x, uint32_t y, uint32_t z);

/**
 * @param a allocation to get data from
 * @return element describing allocation layout
 */
extern rs_element __attribute__((overloadable))
    rsAllocationGetElement(rs_allocation a);

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

/**
 * @param e element to get data from
 * @return number of sub-elements in this element
 */
extern uint32_t __attribute__((overloadable))
    rsElementGetSubElementCount(rs_element e);

/**
 * @param e element to get data from
 * @param index index of the sub-element to return
 * @return sub-element in this element at given index
 */
extern rs_element __attribute__((overloadable))
    rsElementGetSubElement(rs_element, uint32_t index);

/**
 * @param e element to get data from
 * @param index index of the sub-element to return
 * @return length of the sub-element name including the null
 *         terminator (size of buffer needed to write the name)
 */
extern uint32_t __attribute__((overloadable))
    rsElementGetSubElementNameLength(rs_element e, uint32_t index);

/**
 * @param e element to get data from
 * @param index index of the sub-element
 * @param name array to store the name into
 * @param nameLength length of the provided name array
 * @return number of characters actually written, excluding the
 *         null terminator
 */
extern uint32_t __attribute__((overloadable))
    rsElementGetSubElementName(rs_element e, uint32_t index, char *name, uint32_t nameLength);

/**
 * @param e element to get data from
 * @param index index of the sub-element
 * @return array size of sub-element in this element at given
 *         index
 */
extern uint32_t __attribute__((overloadable))
    rsElementGetSubElementArraySize(rs_element e, uint32_t index);

/**
 * @param e element to get data from
 * @param index index of the sub-element
 * @return offset in bytes of sub-element in this element at
 *         given index
 */
extern uint32_t __attribute__((overloadable))
    rsElementGetSubElementOffsetBytes(rs_element e, uint32_t index);

/**
 * @param e element to get data from
 * @return total size of the element in bytes
 */
extern uint32_t __attribute__((overloadable))
    rsElementGetSizeBytes(rs_element e);

/**
 * @param e element to get data from
 * @return element's data type
 */
extern rs_data_type __attribute__((overloadable))
    rsElementGetDataType(rs_element e);

/**
 * @param e element to get data from
 * @return element's data size
 */
extern rs_data_kind __attribute__((overloadable))
    rsElementGetDataKind(rs_element e);

/**
 * @param e element to get data from
 * @return length of the element vector (for float2, float3,
 *         etc.)
 */
extern uint32_t __attribute__((overloadable))
    rsElementGetVectorSize(rs_element e);

#endif

