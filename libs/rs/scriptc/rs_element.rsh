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

/** @file rs_element.rsh
 *  \brief Element routines
 *
 *
 */

#ifndef __RS_ELEMENT_RSH__
#define __RS_ELEMENT_RSH__

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

#endif // __RS_ELEMENT_RSH__

