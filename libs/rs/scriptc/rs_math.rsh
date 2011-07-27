#ifndef __RS_MATH_RSH__
#define __RS_MATH_RSH__

/**
 * Copy reference to the specified object.
 *
 * @param dst
 * @param src
 */
extern void __attribute__((overloadable))
    rsSetObject(rs_element *dst, rs_element src);
extern void __attribute__((overloadable))
    rsSetObject(rs_type *dst, rs_type src);
extern void __attribute__((overloadable))
    rsSetObject(rs_allocation *dst, rs_allocation src);
extern void __attribute__((overloadable))
    rsSetObject(rs_sampler *dst, rs_sampler src);
extern void __attribute__((overloadable))
    rsSetObject(rs_script *dst, rs_script src);
extern void __attribute__((overloadable))
    rsSetObject(rs_mesh *dst, rs_mesh src);
extern void __attribute__((overloadable))
    rsSetObject(rs_program_fragment *dst, rs_program_fragment src);
extern void __attribute__((overloadable))
    rsSetObject(rs_program_vertex *dst, rs_program_vertex src);
extern void __attribute__((overloadable))
    rsSetObject(rs_program_raster *dst, rs_program_raster src);
extern void __attribute__((overloadable))
    rsSetObject(rs_program_store *dst, rs_program_store src);
extern void __attribute__((overloadable))
    rsSetObject(rs_font *dst, rs_font src);

/**
 * Sets the object to NULL.
 *
 * @return bool
 */
extern void __attribute__((overloadable))
    rsClearObject(rs_element *dst);
extern void __attribute__((overloadable))
    rsClearObject(rs_type *dst);
extern void __attribute__((overloadable))
    rsClearObject(rs_allocation *dst);
extern void __attribute__((overloadable))
    rsClearObject(rs_sampler *dst);
extern void __attribute__((overloadable))
    rsClearObject(rs_script *dst);
extern void __attribute__((overloadable))
    rsClearObject(rs_mesh *dst);
extern void __attribute__((overloadable))
    rsClearObject(rs_program_fragment *dst);
extern void __attribute__((overloadable))
    rsClearObject(rs_program_vertex *dst);
extern void __attribute__((overloadable))
    rsClearObject(rs_program_raster *dst);
extern void __attribute__((overloadable))
    rsClearObject(rs_program_store *dst);
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
extern bool __attribute__((overloadable))
    rsIsObject(rs_type);
extern bool __attribute__((overloadable))
    rsIsObject(rs_allocation);
extern bool __attribute__((overloadable))
    rsIsObject(rs_sampler);
extern bool __attribute__((overloadable))
    rsIsObject(rs_script);
extern bool __attribute__((overloadable))
    rsIsObject(rs_mesh);
extern bool __attribute__((overloadable))
    rsIsObject(rs_program_fragment);
extern bool __attribute__((overloadable))
    rsIsObject(rs_program_vertex);
extern bool __attribute__((overloadable))
    rsIsObject(rs_program_raster);
extern bool __attribute__((overloadable))
    rsIsObject(rs_program_store);
extern bool __attribute__((overloadable))
    rsIsObject(rs_font);


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

// Extract a single element from an allocation.
extern const void * __attribute__((overloadable))
    rsGetElementAt(rs_allocation, uint32_t x);
extern const void * __attribute__((overloadable))
    rsGetElementAt(rs_allocation, uint32_t x, uint32_t y);
extern const void * __attribute__((overloadable))
    rsGetElementAt(rs_allocation, uint32_t x, uint32_t y, uint32_t z);

// Return a random value between 0 (or min_value) and max_malue.
extern int __attribute__((overloadable))
    rsRand(int max_value);
extern int __attribute__((overloadable))
    rsRand(int min_value, int max_value);
extern float __attribute__((overloadable))
    rsRand(float max_value);
extern float __attribute__((overloadable))
    rsRand(float min_value, float max_value);

// return the fractional part of a float
// min(v - ((int)floor(v)), 0x1.fffffep-1f);
extern float __attribute__((overloadable))
    rsFrac(float);

// Send a message back to the client.  Will not block and returns true
// if the message was sendable and false if the fifo was full.
// A message ID is required.  Data payload is optional.
extern bool __attribute__((overloadable))
    rsSendToClient(int cmdID);
extern bool __attribute__((overloadable))
    rsSendToClient(int cmdID, const void *data, uint len);

// Send a message back to the client, blocking until the message is queued.
// A message ID is required.  Data payload is optional.
extern void __attribute__((overloadable))
    rsSendToClientBlocking(int cmdID);
extern void __attribute__((overloadable))
    rsSendToClientBlocking(int cmdID, const void *data, uint len);


// Script to Script
enum rs_for_each_strategy {
    RS_FOR_EACH_STRATEGY_SERIAL,
    RS_FOR_EACH_STRATEGY_DONT_CARE,
    RS_FOR_EACH_STRATEGY_DST_LINEAR,
    RS_FOR_EACH_STRATEGY_TILE_SMALL,
    RS_FOR_EACH_STRATEGY_TILE_MEDIUM,
    RS_FOR_EACH_STRATEGY_TILE_LARGE
};

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

#if !defined(RS_VERSION) || (RS_VERSION < 14)
extern void __attribute__((overloadable))
    rsForEach(rs_script script, rs_allocation input,
              rs_allocation output, const void * usrData,
              const rs_script_call_t *);

extern void __attribute__((overloadable))
    rsForEach(rs_script script, rs_allocation input,
              rs_allocation output, const void * usrData);
#else
extern void __attribute__((overloadable))
    rsForEach(rs_script script, rs_allocation input, rs_allocation output);

extern void __attribute__((overloadable))
    rsForEach(rs_script script, rs_allocation input, rs_allocation output,
              const void * usrData, size_t usrDataLen);

extern void __attribute__((overloadable))
    rsForEach(rs_script script, rs_allocation input, rs_allocation output,
              const void * usrData, size_t usrDataLen, const rs_script_call_t *);
#endif


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
    rsAtomicCas(volatile uint32_t* addr, int32_t compareValue, int32_t newValue);


#endif
