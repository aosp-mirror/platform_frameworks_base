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

/** @file rs_object.rsh
 *  \brief Object routines
 *
 *
 */

#ifndef __RS_OBJECT_RSH__
#define __RS_OBJECT_RSH__


/**
 * Copy reference to the specified object.
 *
 * @param dst
 * @param src
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_element *dst, rs_element src);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_type *dst, rs_type src);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_allocation *dst, rs_allocation src);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_sampler *dst, rs_sampler src);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_script *dst, rs_script src);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_path *dst, rs_path src);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_mesh *dst, rs_mesh src);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_program_fragment *dst, rs_program_fragment src);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_program_vertex *dst, rs_program_vertex src);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_program_raster *dst, rs_program_raster src);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_program_store *dst, rs_program_store src);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_font *dst, rs_font src);

/**
 * Sets the object to NULL.
 *
 * @return bool
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_element *dst);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_type *dst);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_allocation *dst);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_sampler *dst);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_script *dst);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_path *dst);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_mesh *dst);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_program_fragment *dst);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_program_vertex *dst);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_program_raster *dst);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_program_store *dst);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_font *dst);



/**
 * Tests if the object is valid.  Returns true if the object is valid, false if
 * it is NULL.
 *
 * @return bool
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_element);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_type);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_allocation);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_sampler);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_script);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_path);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_mesh);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_program_fragment);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_program_vertex);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_program_raster);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_program_store);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsIsObject(rs_font);

#endif
