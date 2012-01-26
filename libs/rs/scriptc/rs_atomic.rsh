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

/** @file rs_atomic.rsh
 *  \brief Atomic routines
 *
 *
 */

#ifndef __RS_ATOMIC_RSH__
#define __RS_ATOMIC_RSH__

#if (defined(RS_VERSION) && (RS_VERSION >= 14))

/**
 * Atomic add one to the value at addr.
 * Equal to rsAtomicAdd(addr, 1)
 *
 * @param addr Address of value to increment
 *
 * @return old value
 */
extern int32_t __attribute__((overloadable))
    rsAtomicInc(volatile int32_t* addr);
/**
 * Atomic add one to the value at addr.
 * Equal to rsAtomicAdd(addr, 1)
 *
 * @param addr Address of value to increment
 *
 * @return old value
 */
extern uint32_t __attribute__((overloadable))
    rsAtomicInc(volatile uint32_t* addr);

/**
 * Atomic subtract one from the value at addr. Equal to rsAtomicSub(addr, 1)
 *
 * @param addr Address of value to decrement
 *
 * @return old value
 */
extern int32_t __attribute__((overloadable))
    rsAtomicDec(volatile int32_t* addr);
/**
 * Atomic subtract one from the value at addr. Equal to rsAtomicSub(addr, 1)
 *
 * @param addr Address of value to decrement
 *
 * @return old value
 */
extern uint32_t __attribute__((overloadable))
    rsAtomicDec(volatile uint32_t* addr);

/**
 * Atomic add a value to the value at addr.  addr[0] += value
 *
 * @param addr Address of value to modify
 * @param value Amount to add to the value at addr
 *
 * @return old value
 */
extern int32_t __attribute__((overloadable))
    rsAtomicAdd(volatile int32_t* addr, int32_t value);
/**
 * Atomic add a value to the value at addr.  addr[0] += value
 *
 * @param addr Address of value to modify
 * @param value Amount to add to the value at addr
 *
 * @return old value
 */
extern uint32_t __attribute__((overloadable))
    rsAtomicAdd(volatile uint32_t* addr, uint32_t value);

/**
 * Atomic Subtract a value from the value at addr.  addr[0] -= value
 *
 * @param addr Address of value to modify
 * @param value Amount to subtract from the value at addr
 *
 * @return old value
 */
extern int32_t __attribute__((overloadable))
    rsAtomicSub(volatile int32_t* addr, int32_t value);
/**
 * Atomic Subtract a value from the value at addr.  addr[0] -= value
 *
 * @param addr Address of value to modify
 * @param value Amount to subtract from the value at addr
 *
 * @return old value
 */
extern uint32_t __attribute__((overloadable))
    rsAtomicSub(volatile uint32_t* addr, uint32_t value);

/**
 * Atomic Bitwise and a value from the value at addr.  addr[0] &= value
 *
 * @param addr Address of value to modify
 * @param value Amount to and with the value at addr
 *
 * @return old value
 */
extern int32_t __attribute__((overloadable))
    rsAtomicAnd(volatile int32_t* addr, int32_t value);
/**
 * Atomic Bitwise and a value from the value at addr.  addr[0] &= value
 *
 * @param addr Address of value to modify
 * @param value Amount to and with the value at addr
 *
 * @return old value
 */
extern uint32_t __attribute__((overloadable))
    rsAtomicAnd(volatile uint32_t* addr, uint32_t value);

/**
 * Atomic Bitwise or a value from the value at addr.  addr[0] |= value
 *
 * @param addr Address of value to modify
 * @param value Amount to or with the value at addr
 *
 * @return old value
 */
extern int32_t __attribute__((overloadable))
    rsAtomicOr(volatile int32_t* addr, int32_t value);
/**
 * Atomic Bitwise or a value from the value at addr.  addr[0] |= value
 *
 * @param addr Address of value to modify
 * @param value Amount to or with the value at addr
 *
 * @return old value
 */
extern uint32_t __attribute__((overloadable))
    rsAtomicOr(volatile uint32_t* addr, uint32_t value);

/**
 * Atomic Bitwise xor a value from the value at addr.  addr[0] ^= value
 *
 * @param addr Address of value to modify
 * @param value Amount to xor with the value at addr
 *
 * @return old value
 */
extern uint32_t __attribute__((overloadable))
    rsAtomicXor(volatile uint32_t* addr, uint32_t value);
/**
 * Atomic Bitwise xor a value from the value at addr.  addr[0] ^= value
 *
 * @param addr Address of value to modify
 * @param value Amount to xor with the value at addr
 *
 * @return old value
 */
extern int32_t __attribute__((overloadable))
    rsAtomicXor(volatile int32_t* addr, int32_t value);

/**
 * Atomic Set the value at addr to the min of addr and value
 * addr[0] = rsMin(addr[0], value)
 *
 * @param addr Address of value to modify
 * @param value comparison value
 *
 * @return old value
 */
extern uint32_t __attribute__((overloadable))
    rsAtomicMin(volatile uint32_t* addr, uint32_t value);
/**
 * Atomic Set the value at addr to the min of addr and value
 * addr[0] = rsMin(addr[0], value)
 *
 * @param addr Address of value to modify
 * @param value comparison value
 *
 * @return old value
 */
extern int32_t __attribute__((overloadable))
    rsAtomicMin(volatile int32_t* addr, int32_t value);

/**
 * Atomic Set the value at addr to the max of addr and value
 * addr[0] = rsMax(addr[0], value)
 *
 * @param addr Address of value to modify
 * @param value comparison value
 *
 * @return old value
 */
extern uint32_t __attribute__((overloadable))
    rsAtomicMax(volatile uint32_t* addr, uint32_t value);
/**
 * Atomic Set the value at addr to the max of addr and value
 * addr[0] = rsMin(addr[0], value)
 *
 * @param addr Address of value to modify
 * @param value comparison value
 *
 * @return old value
 */
extern int32_t __attribute__((overloadable))
    rsAtomicMax(volatile int32_t* addr, int32_t value);

/**
 * Compare-and-set operation with a full memory barrier.
 *
 * If the value at addr matches compareValue then newValue is written.
 *
 * @param addr The address to compare and replace if the compare passes.
 * @param compareValue The value to test addr[0] against.
 * @param newValue The value to write if the test passes.
 *
 * @return old value
 */
extern int32_t __attribute__((overloadable))
    rsAtomicCas(volatile int32_t* addr, int32_t compareValue, int32_t newValue);

/**
 * Compare-and-set operation with a full memory barrier.
 *
 * If the value at addr matches compareValue then newValue is written.
 *
 * @param addr The address to compare and replace if the compare passes.
 * @param compareValue The value to test addr[0] against.
 * @param newValue The value to write if the test passes.
 *
 * @return old value
 */
extern uint32_t __attribute__((overloadable))
    rsAtomicCas(volatile uint32_t* addr, uint32_t compareValue, uint32_t newValue);

#endif //defined(RS_VERSION) && (RS_VERSION >= 14)

#endif

