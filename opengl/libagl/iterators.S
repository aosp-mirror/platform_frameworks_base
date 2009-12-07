/* libs/opengles/iterators.S
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/


    .text
    .align
    .arm
    
    .global iterators0032
    .type iterators0032, %function

/*
 * iterators0032
 *
 * MUST BE CALLED FROM ARM CODE
 *
 * r0: const compute_iterators_t* (this)
 *      r0 + 0: m_dx01 
 *      r0 + 4: m_dy10
 *      r0 + 8: m_dx20
 *      r0 +12: m_dy02
 *      r0 +16: m_x0
 *      r0 +20: m_y0
 *      r0 +24: m_area
 *		r0 +28: m_scale
 *		r0 +29: m_area_scale;
 * r1: int32_t* (out)
 *      r1 + 0: c
 *      r1 + 4: dcdx
 *      r1 + 8: dcdy
 *   r2: c0
 *   r3: c1
 * [sp]: c2
 */
 
iterators0032:
        stmfd	sp!, {r4, r5, r6, r7, r8, lr}
        ldr     r4, [sp, #4*6]

        ldrb    r12, [r0, #29]
        sub     r3, r3, r2
        sub     r4, r4, r2
        sub     r12, r12, #16
        mov     r3, r3, asr r12
        mov     r4, r4, asr r12
        
        ldr     r5, [r0, #0]
        ldr     r12, [r0, #4]
        smull   r8, lr, r4, r5
        ldr     r5, [r0, #8]
        smull   r6, r7, r4, r12
        ldr     r12, [r0, #12]
        smlal   r8, lr, r3, r5
        smlal   r6, r7, r3, r12

        ldr     r3, [r0, #16]        // m_x0
        ldr     r4, [r0, #20]        // m_x1
        
        str     r6, [r1, #4]
        str     r8, [r1, #8]

        umull   r6, r5, r3, r6
        umull   r8, r0, r4, r8
        mla     r7, r3, r7, r5
        mla     lr, r4, lr, r0
        adds    r6, r6, r8
        adc     r7, r7, lr

        movs    r6, r6, lsr #4
        adc     r6, r6, r7, lsl #28
        rsb     r6, r6, r2, lsl #16
        str     r6, [r1, #0]

        ldmfd	sp!, {r4, r5, r6, r7, r8, pc}

