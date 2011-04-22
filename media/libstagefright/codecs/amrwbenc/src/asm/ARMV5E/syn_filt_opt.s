@/*
@ ** Copyright 2003-2010, VisualOn, Inc.
@ **
@ ** Licensed under the Apache License, Version 2.0 (the "License");
@ ** you may not use this file except in compliance with the License.
@ ** You may obtain a copy of the License at
@ **
@ **     http://www.apache.org/licenses/LICENSE-2.0
@ **
@ ** Unless required by applicable law or agreed to in writing, software
@ ** distributed under the License is distributed on an "AS IS" BASIS,
@ ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@ ** See the License for the specific language governing permissions and
@ ** limitations under the License.
@ */
@**********************************************************************/
@void Syn_filt(
@     Word16 a[],                           /* (i) Q12 : a[m+1] prediction coefficients           */
@     Word16 x[],                           /* (i)     : input signal                             */
@     Word16 y[],                           /* (o)     : output signal                            */
@     Word16 mem[],                         /* (i/o)   : memory associated with this filtering.   */
@)
@***********************************************************************
@ a[]    ---   r0
@ x[]    ---   r1
@ y[]    ---   r2
@ mem[]  ---   r3
@ m ---  16  lg --- 80  update --- 1

          .section  .text
	  .global   Syn_filt_asm
          .extern   voAWB_Copy

Syn_filt_asm:

          STMFD   	r13!, {r4 - r12, r14} 
          SUB           r13, r13, #700                   @ y_buf[L_FRAME16k + M16k]
   
          MOV           r4, r3                           @ copy mem[] address
          MOV           r5, r13                          @ copy yy = y_buf address

          @ for(i = 0@ i < m@ i++)
          @{
          @    *yy++ = mem[i]@
          @} 

          LDRH          r6,  [r4], #2          
          LDRH          r7,  [r4], #2
          LDRH          r8,  [r4], #2
          LDRH          r9,  [r4], #2
          LDRH          r10, [r4], #2
          LDRH          r11, [r4], #2
          LDRH          r12, [r4], #2
          LDRH          r14, [r4], #2

          STRH          r6,  [r5], #2
          STRH          r7,  [r5], #2
          STRH          r8,  [r5], #2
          STRH          r9,  [r5], #2
          STRH          r10, [r5], #2
          STRH          r11, [r5], #2
          STRH          r12, [r5], #2
          STRH          r14, [r5], #2

          LDRH          r6,  [r4], #2          
          LDRH          r7,  [r4], #2
          LDRH          r8,  [r4], #2
          LDRH          r9,  [r4], #2
          LDRH          r10, [r4], #2
          LDRH          r11, [r4], #2
          LDRH          r12, [r4], #2
          LDRH          r14, [r4], #2

          STRH          r6,  [r5], #2
          STRH          r7,  [r5], #2
          STRH          r8,  [r5], #2
          STRH          r9,  [r5], #2
          STRH          r10, [r5], #2
          STRH          r11, [r5], #2
          STRH          r12, [r5], #2
          STRH          r14, [r5], #2

          LDRSH         r5, [r0]                         @ load a[0]
          MOV           r8, #0                           @ i = 0
          MOV           r5, r5, ASR #1                   @ a0 = a[0] >> 1
          @MOV           r4, r13
          @ load all a[]

          LDR           r14, =0xffff
          LDRSH         r6, [r0, #2]                     @ load a[1]
          LDRSH         r7, [r0, #4]                     @ load a[2]
          LDRSH         r9, [r0, #6]                     @ load a[3]
          LDRSH         r11,[r0, #8]                     @ load a[4]
          AND           r6, r6, r14
          AND           r9, r9, r14          
          ORR           r10, r6, r7, LSL #16             @ -a[2] -- -a[1]
          ORR           r12, r9, r11, LSL #16            @ -a[4] -- -a[3]
          STR           r10, [r13, #-4]
          STR           r12, [r13, #-8]
          
          LDRSH         r6, [r0, #10]                    @ load a[5]
          LDRSH         r7, [r0, #12]                    @ load a[6]
          LDRSH         r9, [r0, #14]                    @ load a[7]
          LDRSH         r11,[r0, #16]                    @ load a[8]
          AND           r6, r6, r14
          AND           r9, r9, r14          
          ORR           r10, r6, r7, LSL #16             @ -a[6] -- -a[5]
          ORR           r12, r9, r11, LSL #16            @ -a[8] -- -a[7]
          STR           r10, [r13, #-12]
          STR           r12, [r13, #-16]          
           
          LDRSH         r6, [r0, #18]                    @ load a[9]
          LDRSH         r7, [r0, #20]                    @ load a[10]
          LDRSH         r9, [r0, #22]                    @ load a[11]
          LDRSH         r11,[r0, #24]                    @ load a[12]
          AND           r6, r6, r14
          AND           r9, r9, r14          
          ORR           r10, r6, r7, LSL #16             @ -a[10] -- -a[9]
          ORR           r12, r9, r11, LSL #16            @ -a[12] -- -a[11]
          STR           r10, [r13, #-20]
          STR           r12, [r13, #-24]    

          LDRSH         r6, [r0, #26]                    @ load a[13]
          LDRSH         r7, [r0, #28]                    @ load a[14]
          LDRSH         r9, [r0, #30]                    @ load a[15]
          LDRSH         r11,[r0, #32]                    @ load a[16]
          AND           r6, r6, r14
          AND           r9, r9, r14          
          ORR           r10, r6, r7, LSL #16             @ -a[14] -- -a[13]
          ORR           r12, r9, r11, LSL #16            @ -a[16] -- -a[15]
          STR           r10, [r13, #-28]
          STR           r12, [r13, #-32]                
                     
          ADD           r4, r13, #32
LOOP:
          LDRSH         r6,  [r1], #2                    @ load x[i]
          ADD           r10, r4, r8, LSL #1              @ temp_p = yy + i

          MUL           r0, r5, r6                      @ L_tmp = x[i] * a0
          @ for(j = 1@ j <= m, j+=8)
          LDR           r7,  [r13, #-4]                  @ -a[2]  -a[1]
          LDRSH         r9,  [r10, #-2]                  @ *(temp_p - 1)
          LDRSH         r12, [r10, #-4]                  @ *(temp_p - 2)


          SMULBB        r14, r9, r7                      @ -a[1] * (*(temp_p -1))

          LDRSH         r6,  [r10, #-6]                  @ *(temp_p - 3)

          SMLABT        r14, r12, r7, r14                @ -a[2] * (*(temp_p - 2))

          LDR           r7,  [r13, #-8]                  @ -a[4] -a[3]
          LDRSH         r11, [r10, #-8]                  @ *(temp_p - 4)

          SMLABB        r14, r6, r7, r14                 @ -a[3] * (*(temp_p -3))

          LDRSH         r9,  [r10, #-10]                 @ *(temp_p - 5)
   
          SMLABT        r14, r11, r7, r14                @ -a[4] * (*(temp_p -4))        

          LDR           r7,  [r13, #-12]                 @ -a[6]  -a[5]
          LDRSH         r12, [r10, #-12]                 @ *(temp_p - 6)

          SMLABB        r14, r9, r7, r14                 @ -a[5] * (*(temp_p -5))

          LDRSH         r6,  [r10, #-14]                 @ *(temp_p - 7)

          SMLABT        r14, r12, r7, r14                @ -a[6] * (*(temp_p - 6))

          LDR           r7,  [r13, #-16]                 @ -a[8] -a[7]
          LDRSH         r11, [r10, #-16]                 @ *(temp_p - 8)
         
          SMLABB        r14, r6, r7, r14                 @ -a[7] * (*(temp_p -7))

          LDRSH         r9,  [r10, #-18]                 @ *(temp_p - 9)

          SMLABT        r14, r11, r7, r14                @ -a[8] * (*(temp_p -8))          
 
          LDR           r7,  [r13, #-20]                 @ -a[10]  -a[9]
          LDRSH         r12, [r10, #-20]                 @ *(temp_p - 10)

          SMLABB        r14, r9, r7, r14                 @ -a[9] * (*(temp_p -9))

          LDRSH         r6,  [r10, #-22]                 @ *(temp_p - 11)

          SMLABT        r14, r12, r7, r14                @ -a[10] * (*(temp_p - 10))

          LDR           r7,  [r13, #-24]                 @ -a[12] -a[11]
          LDRSH         r11, [r10, #-24]                 @ *(temp_p - 12)

          SMLABB        r14, r6, r7, r14                 @ -a[11] * (*(temp_p -11))

          LDRSH         r9,  [r10, #-26]                 @ *(temp_p - 13)

          SMLABT        r14, r11, r7, r14                @ -a[12] * (*(temp_p -12))           

          LDR           r7,  [r13, #-28]                 @ -a[14] -a[13]
          LDRSH         r12, [r10, #-28]                 @ *(temp_p - 14)
 
          SMLABB        r14, r9, r7, r14                 @ -a[13] * (*(temp_p -13))

          LDRSH         r6,  [r10, #-30]                 @ *(temp_p - 15)

          SMLABT        r14, r12, r7, r14                @ -a[14] * (*(temp_p - 14))

          LDR           r7,  [r13, #-32]                 @ -a[16] -a[15]
          LDRSH         r11, [r10, #-32]                 @ *(temp_p - 16)

          SMLABB        r14, r6, r7, r14                 @ -a[15] * (*(temp_p -15))

          SMLABT        r14, r11, r7, r14                @ -a[16] * (*(temp_p -16))

          RSB           r14, r14, r0
                                  
          MOV           r7, r14, LSL #4                  @ L_tmp <<=4
          ADD           r8, r8, #1
          ADD           r14, r7, #0x8000                 
          MOV           r7, r14, ASR #16                 @ (L_tmp + 0x8000) >> 16
          CMP           r8, #80
          STRH          r7, [r10]                        @ yy[i]
          STRH          r7, [r2], #2                     @ y[i]
          BLT           LOOP
 
          @ update mem[]
          ADD           r5, r13, #160                    @ yy[64] address
          MOV           r1, r3
          MOV           r0, r5
          MOV           r2, #16
          BL            voAWB_Copy          

Syn_filt_asm_end:
 
          ADD           r13, r13, #700		     
          LDMFD   	r13!, {r4 - r12, r15} 
          @ENDFUNC
          .END
 

