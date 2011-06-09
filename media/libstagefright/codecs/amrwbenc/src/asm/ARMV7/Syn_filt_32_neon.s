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
@**********************************************************************/
@void Syn_filt_32(
@     Word16 a[],                           /* (i) Q12 : a[m+1] prediction coefficients */
@     Word16 m,                             /* (i)     : order of LP filter             */
@     Word16 exc[],                         /* (i) Qnew: excitation (exc[i] >> Qnew)    */
@     Word16 Qnew,                          /* (i)     : exc scaling = 0(min) to 8(max) */
@     Word16 sig_hi[],                      /* (o) /16 : synthesis high                 */
@     Word16 sig_lo[],                      /* (o) /16 : synthesis low                  */
@     Word16 lg                             /* (i)     : size of filtering              */
@)
@***********************************************************************
@ a[]      --- r0
@ m        --- r1
@ exc[]    --- r2
@ Qnew     --- r3
@ sig_hi[] --- r4
@ sig_lo[] --- r5
@ lg       --- r6

          .section  .text 
          .global   Syn_filt_32_asm

Syn_filt_32_asm:

          STMFD   	r13!, {r4 - r12, r14} 
          LDR           r4,  [r13, #40]                  @ get sig_hi[] address
          LDR           r5,  [r13, #44]                  @ get sig_lo[] address

          LDRSH         r6,  [r0], #2                    @ load Aq[0]
          ADD           r7,  r3, #4                      @ 4 + Q_new
          MOV           r3, r6, ASR r7                   @ a0 = Aq[0] >> (4 + Q_new)

	  SUB           r10, r4, #32                     @ sig_hi[-16] address
	  SUB           r11, r5, #32                     @ sig_lo[-16] address

	  VLD1.S16      {D0, D1, D2, D3}, [r0]!          @a[1] ~ a[16] 
  
          MOV           r8, #0                           @ i = 0

	  VLD1.S16      {D4, D5, D6, D7}, [r10]!         @ sig_hi[-16] ~ sig_hi[-1]
          VREV64.16     D0, D0
          VREV64.16     D1, D1
	  VLD1.S16      {D8, D9, D10, D11}, [r11]!       @ sig_lo[-16] ~ sig_lo[-1]
          VREV64.16     D2, D2
          VREV64.16     D3, D3	
          VDUP.S32      Q15, r8
              
SYN_LOOP:

          LDRSH         r6, [r2], #2                     @exc[i]
	  @L_tmp = L_msu(L_tmp, sig_lo[i - j], a[j])@
	  VMULL.S16     Q10, D8, D3
	  VEXT.8        D8, D8, D9, #2
	  VMLAL.S16     Q10, D9, D2
	  VMLAL.S16     Q10, D10, D1
	  VMLAL.S16     Q10, D11, D0

	  VEXT.8        D9, D9, D10, #2
	  VEXT.8        D10, D10, D11, #2
	  
	  VPADD.S32     D28, D20, D21
          MUL           r12, r6, r3                      @exc[i] * a0
	  VPADD.S32     D29, D28, D28
	  VDUP.S32      Q10, D29[0]                      @result1
          
	  VMULL.S16     Q11, D4, D3
	  VMLAL.S16     Q11, D5, D2
          VSUB.S32      Q10, Q15, Q10
	  @L_tmp = L_msu(L_tmp, sig_hi[i - j], a[j])@

	  VMLAL.S16     Q11, D6, D1
	  VEXT.8        D4, D4, D5, #2
	  VMLAL.S16     Q11, D7, D0


	  VEXT.8        D5, D5, D6, #2
	  VEXT.8        D6, D6, D7, #2

	  VPADD.S32     D28, D22, D23
          VPADD.S32     D29, D28, D28
          MOV           r14, r12, LSL #1                 @exc[i] * a0 << 1
          VDUP.S32      Q11, D29[0]                      @result2



	  VSHR.S32      Q10, Q10, #11                    @result1 >>= 11
	  VSHL.S32      Q11, Q11, #1                     @result2 <<= 1
	  VDUP.S32      Q12, r14                         
	  VADD.S32      Q12, Q12, Q10                    @L_tmp = L_tmp - (result1 >>= 11) - (result2 <<= 1)
	  VSUB.S32      Q12, Q12, Q11

	  VSHL.S32      Q12, Q12, #3                     @L_tmp <<= 3


	  VSHRN.S32     D20, Q12, #16                    @sig_hi[i] = L_tmp >> 16@
	  VMOV.S16      r10, D20[0]
	  VSHR.S32      Q12, Q12, #4                     @L_tmp >>= 4
	  VEXT.8        D7, D7, D20, #2
	  STRH          r10, [r4], #2                    @store sig_hi[i]
          VMOV.S32      r11, D24[0]                      @r11 --- L_tmp >>= 4
	  ADD           r8, r8, #1
	  SUB           r12, r11, r10, LSL #12
	  @MOV           r11, r12, ASR #16                @sig_lo[i]
	  VDUP.S16      D21, r12
	  VEXT.8        D11, D11, D21, #2
	  STRH          r12, [r5], #2                    @stroe sig_lo[i]

          CMP           r8, #64
          BLT           SYN_LOOP                          
         
Syn_filt_32_end:
		     
          LDMFD   	    r13!, {r4 - r12, r15} 
          @ENDFUNC
          .END
 

