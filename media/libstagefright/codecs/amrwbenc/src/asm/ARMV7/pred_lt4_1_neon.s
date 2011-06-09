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
@
@void Pred_lt4(
@     Word16 exc[],                         /* in/out: excitation buffer */
@     Word16 T0,                            /* input : integer pitch lag */
@     Word16 frac,                          /* input : fraction of lag   */
@     Word16 L_subfr                        /* input : subframe size     */
@)
@***********************************************************************
@ r0    ---  exc[]
@ r1    ---  T0
@ r2    ---  frac
@ r3    ---  L_subfr
 
          .section  .text 
          .global   pred_lt4_asm
          .extern   inter4_2

pred_lt4_asm:

          STMFD   	r13!, {r4 - r12, r14} 
          SUB           r4, r0, r1, LSL #1                        @ x = exc - T0
          RSB           r2, r2, #0                                @ frac = - frac
          SUB           r4, r4, #30                               @ x -= L_INTERPOL2 - 1
          CMP           r2, #0
          ADDLT         r2, r2, #4                                @ frac += UP_SAMP
          SUBLT         r4, r4, #2                                @ x--

          LDR           r11, Lable1
          RSB           r2, r2, #3                                @ k = UP_SAMP - 1 - frac
          MOV           r8, #0                                    @ j = 0
	  ADD           r11, r11, r2, LSL #6                      @ get inter4_2[k][]

	  VLD1.S16      {Q0, Q1}, [r11]!
	  VLD1.S16      {Q2, Q3}, [r11]!
          
	  MOV           r6, #0x8000 

          VLD1.S16      {Q4, Q5}, [r4]!                           @load 16 x[]
          VLD1.S16      {Q6, Q7}, [r4]!                           @load 16 x[]

LOOP:
          VQDMULL.S16   Q15, D8, D0
          VQDMLAL.S16   Q15, D9, D1
          VQDMLAL.S16   Q15, D10, D2
          VQDMLAL.S16   Q15, D11, D3
        
          VQDMLAL.S16   Q15, D12, D4
          VQDMLAL.S16   Q15, D13, D5
          VQDMLAL.S16   Q15, D14, D6
          VQDMLAL.S16   Q15, D15, D7

          LDRSH         r12, [r4], #2                
          
          VEXT.S16      D8, D8, D9, #1
          VEXT.S16      D9, D9, D10, #1
          VEXT.S16      D10, D10, D11, #1
          VEXT.S16      D11, D11, D12, #1
          VDUP.S16      D24, r12
          VEXT.S16      D12, D12, D13, #1
          VEXT.S16      D13, D13, D14, #1
     
          VQADD.S32     D30, D30, D31
	  MOV           r11, #0x8000          
          VPADD.S32     D30, D30, D30
          ADD           r8, r8, #1
          VMOV.S32      r12, D30[0]
          VEXT.S16      D14, D14, D15, #1          

          QADD          r1, r12, r12                              @ L_sum = (L_sum << 2)
          VEXT.S16      D15, D15, D24, #1
          QADD          r5, r1, r6                         
          MOV           r1, r5, ASR #16
          CMP           r8, r3
          STRH          r1, [r0], #2                              @ exc[j] = (L_sum + 0x8000) >> 16
          BLT           LOOP
                    
pred_lt4_end:
		     
          LDMFD   	r13!, {r4 - r12, r15} 
 
Lable1:
          .word   	inter4_2
          @ENDFUNC
          .END

