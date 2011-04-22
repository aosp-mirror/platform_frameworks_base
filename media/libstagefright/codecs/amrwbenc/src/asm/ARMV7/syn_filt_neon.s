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

Syn_filt_asm:

          STMFD   	r13!, {r4 - r12, r14} 
          SUB           r13, r13, #700                   @ y_buf[L_FRAME16k + M16k]
   
          MOV           r4, r3                           @ copy mem[] address
          MOV           r5, r13                          @ copy yy = y_buf address

          @ for(i = 0@ i < m@ i++)
          @{
          @    *yy++ = mem[i]@
          @} 
          VLD1.S16      {D0, D1, D2, D3}, [r4]!          @load 16 mems
	  VST1.S16      {D0, D1, D2, D3}, [r5]!          @store 16 mem[] to *yy

          LDRSH         r5, [r0], #2                     @ load a[0]
          MOV           r8, #0                           @ i = 0
          MOV           r5, r5, ASR #1                   @ a0 = a[0] >> 1
          VMOV.S16      D8[0], r5
          @ load all a[]
          VLD1.S16      {D0, D1, D2, D3}, [r0]!          @ load a[1] ~ a[16]
	  VREV64.16     D0, D0
	  VREV64.16     D1, D1
	  VREV64.16     D2, D2
	  VREV64.16     D3, D3 
	  MOV           r8, #0                           @ loop times
	  MOV           r10, r13                         @ temp = y_buf
	  ADD           r4, r13, #32                     @ yy[i] address

          VLD1.S16      {D4, D5, D6, D7}, [r10]!         @ first 16 temp_p

SYN_LOOP:

          LDRSH         r6, [r1], #2                     @ load x[i]
	  MUL           r12, r6, r5                      @ L_tmp = x[i] * a0
	  ADD           r10, r4, r8, LSL #1              @ y[i], yy[i] address

	  VDUP.S32      Q10, r12
	  VMULL.S16     Q5, D3, D4                    
          VMLAL.S16     Q5, D2, D5
          VMLAL.S16     Q5, D1, D6
          VMLAL.S16     Q5, D0, D7
          VEXT.8        D4, D4, D5, #2
          VEXT.8        D5, D5, D6, #2
          VEXT.8        D6, D6, D7, #2
          VPADD.S32     D12, D10, D11
          ADD           r8, r8, #1
          VPADD.S32     D10, D12, D12

	  VDUP.S32      Q7, D10[0]

	  VSUB.S32      Q9, Q10, Q7
          VQRSHRN.S32   D20, Q9, #12   
          VMOV.S16      r9, D20[0]
          VEXT.8        D7, D7, D20, #2
          CMP           r8, #80
          STRH          r9, [r10]                        @ yy[i]
          STRH          r9, [r2], #2                     @ y[i]          	         
	  
          BLT           SYN_LOOP
 
          @ update mem[]
          ADD           r5, r13, #160                    @ yy[64] address
	  VLD1.S16      {D0, D1, D2, D3}, [r5]!
	  VST1.S16      {D0, D1, D2, D3}, [r3]!              

Syn_filt_asm_end:
 
          ADD           r13, r13, #700		     
          LDMFD   	r13!, {r4 - r12, r15} 
          @ENDFUNC
          .END
 

