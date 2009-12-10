/* libs/opengles/fixed_asm.S
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
    
    .global gglFloatToFixed
    .type gglFloatToFixed, %function
    .global gglFloatToFixedFast
    .type gglFloatToFixedFast, %function


/*
 * Converts a float to a s15.16 fixed-point number.
 * this doesn't handle floats out of the [-32768, +32768[ range
 * and doesn't performs round-to-nearest.
 * however, it's very fast :-)
 */

gglFloatToFixedFast:
        movs    r1, r0, lsl #1          /* remove bit sign */
        mov     r2, #0x8E               /* 127 + 15 */
        sub     r1, r2, r1, lsr #24     /* compute shift */
        mov     r2, r0, lsl #8          /* mantissa<<8 */
        orr     r2, r2, #0x80000000     /* add the missing 1 */
        mov     r0, r2, lsr r1          /* scale to 16.16 */
        rsbcs   r0, r0, #0              /* negate if needed */
        bx      lr

/*
 * this version rounds-to-nearest and saturates numbers
 * outside the range (but not NaNs).
 */

gglFloatToFixed:
        mov     r1, r0, lsl #1          /* remove bit sign */
        mov     r2, #0x8E               /* 127 + 15 */
        subs    r1, r2, r1, lsr #24     /* compute shift */
        bls     0f                      /* too big */
        mov     r2, r0, lsl #8          /* mantissa<<8 */
        orr     r2, r2, #0x80000000     /* add the missing 1 */
        mov     r3, r0
        movs    r0, r2, lsr r1          /* scale to 16.16 */
        addcs   r0, r0, #1              /* round-to-nearest */
        tst     r3, #0x80000000         /* negative? */
        rsbne   r0, r0, #0              /* negate if needed */
        bx      lr 
 
0:      ands    r0, r0, #0x80000000     /* keep only the sign bit */
        moveq   r0, #0x7fffffff         /* positive, maximum value */
        bx      lr

