#ifndef __RS_MATH_RSH__
#define __RS_MATH_RSH__

#include "rs_cl.rsh"
#include "rs_core.rsh"



// Allocations
extern rs_allocation rsGetAllocation(const void *);
extern uint32_t rsAllocationGetDimX(rs_allocation);
extern uint32_t rsAllocationGetDimY(rs_allocation);
extern uint32_t rsAllocationGetDimZ(rs_allocation);
extern uint32_t rsAllocationGetDimLOD(rs_allocation);
extern uint32_t rsAllocationGetDimFaces(rs_allocation);

extern const void * __attribute__((overloadable))
    rsGetElementAt(rs_allocation, uint32_t x);
extern const void * __attribute__((overloadable))
    rsGetElementAt(rs_allocation, uint32_t x, uint32_t y);
extern const void * __attribute__((overloadable))
    rsGetElementAt(rs_allocation, uint32_t x, uint32_t y, uint32_t z);


// Debugging
extern void __attribute__((overloadable))rsDebug(const char *, float);
extern void __attribute__((overloadable))rsDebug(const char *, float2);
extern void __attribute__((overloadable))rsDebug(const char *, float3);
extern void __attribute__((overloadable))rsDebug(const char *, float4);
extern void __attribute__((overloadable))rsDebug(const char *, int);
extern void __attribute__((overloadable))rsDebug(const char *, const void *);
#define RS_DEBUG(a) rsDebug(#a, a)
#define RS_DEBUG_MARKER rsDebug(__FILE__, __LINE__)

// RS Math
extern int __attribute__((overloadable)) rsRand(int);
extern int __attribute__((overloadable)) rsRand(int, int);
extern float __attribute__((overloadable)) rsRand(float);
extern float __attribute__((overloadable)) rsRand(float, float);

extern float __attribute__((overloadable)) rsFrac(float);

// time
extern int32_t rsSecond();
extern int32_t rsMinute();
extern int32_t rsHour();
extern int32_t rsDay();
extern int32_t rsMonth();
extern int32_t rsYear();
extern int64_t rsUptimeMillis();
extern int64_t rsStartTimeMillis();
extern int64_t rsElapsedTimeMillis();

extern int rsSendToClient(void *data, int cmdID, int len, int waitForSpace);

// Script to Script
typedef struct rs_script_call {
    uint32_t xStart;
    uint32_t xEnd;
    uint32_t yStart;
    uint32_t yEnd;
    uint32_t zStart;
    uint32_t zEnd;
    uint32_t arrayStart;
    uint32_t arrayEnd;

} rs_script_call_t;

extern void __attribute__((overloadable))rsForEach(rs_script script,
                                                   rs_allocation input,
                                                   rs_allocation output,
                                                   const void * usrData);

extern void __attribute__((overloadable))rsForEach(rs_script script,
                                                   rs_allocation input,
                                                   rs_allocation output,
                                                   const void * usrData,
                                                   const rs_script_call_t *);

#endif
