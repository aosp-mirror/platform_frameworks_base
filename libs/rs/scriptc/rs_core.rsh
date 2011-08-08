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

/** @file rs_core.rsh
 *  \brief todo-jsams
 *
 *  todo-jsams
 *
 */

#ifndef __RS_CORE_RSH__
#define __RS_CORE_RSH__

#define _RS_RUNTIME extern

#include "rs_types.rsh"
#include "rs_allocation.rsh"
#include "rs_atomic.rsh"
#include "rs_cl.rsh"
#include "rs_debug.rsh"
#include "rs_math.rsh"
#include "rs_matrix.rsh"
#include "rs_object.rsh"
#include "rs_quaternion.rsh"
#include "rs_time.rsh"



/**
 * Send a message back to the client.  Will not block and returns true
 * if the message was sendable and false if the fifo was full.
 * A message ID is required.  Data payload is optional.
 */
extern bool __attribute__((overloadable))
    rsSendToClient(int cmdID);
/**
 * \overload
 */
extern bool __attribute__((overloadable))
    rsSendToClient(int cmdID, const void *data, uint len);
/**
 * Send a message back to the client, blocking until the message is queued.
 * A message ID is required.  Data payload is optional.
 */
extern void __attribute__((overloadable))
    rsSendToClientBlocking(int cmdID);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsSendToClientBlocking(int cmdID, const void *data, uint len);


/**
 * Launch order hint for rsForEach calls.  This provides a hint to the system to
 * determine in which order the root function of the target is called with each
 * cell of the allocation.
 *
 * This is a hint and implementations may not obey the order.
 */
enum rs_for_each_strategy {
    RS_FOR_EACH_STRATEGY_SERIAL,
    RS_FOR_EACH_STRATEGY_DONT_CARE,
    RS_FOR_EACH_STRATEGY_DST_LINEAR,
    RS_FOR_EACH_STRATEGY_TILE_SMALL,
    RS_FOR_EACH_STRATEGY_TILE_MEDIUM,
    RS_FOR_EACH_STRATEGY_TILE_LARGE
};


/**
 * Structure to provide extra information to a rsForEach call.  Primarly used to
 * restrict the call to a subset of cells in the allocation.
 */
typedef struct rs_script_call {
    enum rs_for_each_strategy strategy;
    uint32_t xStart;
    uint32_t xEnd;
    uint32_t yStart;
    uint32_t yEnd;
    uint32_t zStart;
    uint32_t zEnd;
    uint32_t arrayStart;
    uint32_t arrayEnd;
} rs_script_call_t;

/**
 * Make a script to script call to launch work. One of the input or output is
 * required to be a valid object. The input and output must be of the same
 * dimensions.
 * API 10-13
 *
 * @param script The target script to call
 * @param input The allocation to source data from
 * @param output the allocation to write date into
 * @param usrData The user definied params to pass to the root script.  May be
 *                NULL.
 * @param sc Extra control infomation used to select a sub-region of the
 *           allocation to be processed or suggest a walking strategy.  May be
 *           NULL.
 *
 *  */
#if !defined(RS_VERSION) || (RS_VERSION < 14)
extern void __attribute__((overloadable))
    rsForEach(rs_script script, rs_allocation input,
              rs_allocation output, const void * usrData,
              const rs_script_call_t *sc);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsForEach(rs_script script, rs_allocation input,
              rs_allocation output, const void * usrData);
#else

/**
 * Make a script to script call to launch work. One of the input or output is
 * required to be a valid object. The input and output must be of the same
 * dimensions.
 * API 14+
 *
 * @param script The target script to call
 * @param input The allocation to source data from
 * @param output the allocation to write date into
 * @param usrData The user definied params to pass to the root script.  May be
 *                NULL.
 * @param usrDataLen The size of the userData structure.  This will be used to
 *                   perform a shallow copy of the data if necessary.
 * @param sc Extra control infomation used to select a sub-region of the
 *           allocation to be processed or suggest a walking strategy.  May be
 *           NULL.
 *
 */
extern void __attribute__((overloadable))
    rsForEach(rs_script script, rs_allocation input, rs_allocation output,
              const void * usrData, size_t usrDataLen, const rs_script_call_t *);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsForEach(rs_script script, rs_allocation input, rs_allocation output,
              const void * usrData, size_t usrDataLen);
/**
 * \overload
 */
extern void __attribute__((overloadable))
    rsForEach(rs_script script, rs_allocation input, rs_allocation output);
#endif



#undef _RS_RUNTIME

#endif
