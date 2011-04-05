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
@static void cor_h_vec_012(
@		Word16 h[],                           /* (i) scaled impulse response                 */
@		Word16 vec[],                         /* (i) scaled vector (/8) to correlate with h[] */
@		Word16 track,                         /* (i) track to use                            */
@		Word16 sign[],                        /* (i) sign vector                             */
@		Word16 rrixix[][NB_POS],              /* (i) correlation of h[x] with h[x]      */
@		Word16 cor_1[],                       /* (o) result of correlation (NB_POS elements) */
@		Word16 cor_2[]                        /* (o) result of correlation (NB_POS elements) */
@)
@r0 ---- h[]
@r1 ---- vec[]
@r2 ---- track
@r3 ---- sign[]
@r4 ---- rrixix[][NB_POS]
@r5 ---- cor_1[]
@r6 ---- cor_2[]


          .section  .text
	  .global  cor_h_vec_012_asm

cor_h_vec_012_asm:

         STMFD         r13!, {r4 - r12, r14}
	 LDR           r4, [r13, #40]                    @load rrixix[][NB_POS]
	 ADD           r7, r4, r2, LSL #5                @r7 --- p0 = rrixix[track]
         MOV           r4, #0                            @i=0

	 @r0 --- h[], r1 --- vec[],  r2 --- pos
	 @r3 --- sign[], r4 --- i, r7 --- p0
LOOPi:
         MOV           r5, #0                            @L_sum1 = 0
         MOV           r6, #0                            @L_sum2 = 0
         ADD           r9, r1, r2, LSL #1                @p2 = &vec[pos]
         MOV           r10, r0                           @p1 = h
         RSB           r11, r2, #62                      @j=62-pos

LOOPj1:
	 LDRSH         r12, [r10], #2
	 LDRSH         r8,  [r9], #2
	 LDRSH         r14, [r9]
	 SUBS          r11, r11, #1
         MLA           r5, r12, r8, r5
         MLA           r6, r12, r14, r6
	 BGE           LOOPj1

	 LDRSH         r12, [r10], #2                     @*p1++
	 MOV           r6, r6, LSL #2                     @L_sum2 = (L_sum2 << 2)
         MLA           r5, r12, r14, r5
         MOV           r14, #0x8000
         MOV           r5, r5, LSL #2                     @L_sum1 = (L_sum1 << 2)
         ADD           r10, r6, r14
         ADD           r9, r5, r14
         MOV           r5, r9, ASR #16
         MOV           r6, r10, ASR #16
         ADD           r9, r3, r2, LSL #1                 @address of sign[pos]
         ADD           r8, r7, #32
         LDRSH         r10, [r9], #2                 	  @sign[pos]
	 LDRSH         r11, [r9]                          @sign[pos + 1]
	 MUL           r12, r5, r10
	 MUL           r14, r6, r11
	 MOV           r5, r12, ASR #15
	 MOV           r6, r14, ASR #15
	 LDR           r9,  [r13, #44]
	 LDR           r12, [r13, #48]
         LDRSH         r10, [r7], #2                      @*p0++
	 LDRSH         r11, [r8]                          @*p3++
         ADD           r9, r9, r4, LSL #1
	 ADD           r12, r12, r4, LSL #1
	 ADD           r5, r5, r10
	 ADD           r6, r6, r11
	 STRH          r5, [r9]
	 STRH          r6, [r12]

         ADD           r2, r2, #4

         MOV           r5, #0                            @L_sum1 = 0
	 MOV           r6, #0                            @L_sum2 = 0
	 ADD           r9, r1, r2, LSL #1                @p2 = &vec[pos]
	 MOV           r10, r0                           @p1 = h
	 RSB           r11, r2, #62                      @j=62-pos
	 ADD           r4, r4, #1                        @i++

LOOPj2:
	 LDRSH         r12, [r10], #2
	 LDRSH         r8,  [r9], #2
	 LDRSH         r14, [r9]
	 SUBS          r11, r11, #1
         MLA           r5, r12, r8, r5
         MLA           r6, r12, r14, r6
	 BGE           LOOPj2

	 LDRSH         r12, [r10], #2                     @*p1++
	 MOV           r6, r6, LSL #2                     @L_sum2 = (L_sum2 << 2)
         MLA           r5, r12, r14, r5
         MOV           r14, #0x8000
         MOV           r5, r5, LSL #2                     @L_sum1 = (L_sum1 << 2)
         ADD           r10, r6, r14
         ADD           r9, r5, r14

         MOV           r5, r9, ASR #16
         MOV           r6, r10, ASR #16
         ADD           r9, r3, r2, LSL #1                 @address of sign[pos]
         ADD           r8, r7, #32
         LDRSH         r10, [r9], #2                 	  @sign[pos]
	 LDRSH         r11, [r9]                          @sign[pos + 1]
	 MUL           r12, r5, r10
	 MUL           r14, r6, r11
	 MOV           r5, r12, ASR #15
	 MOV           r6, r14, ASR #15
	 LDR           r9,  [r13, #44]
	 LDR           r12, [r13, #48]
         LDRSH         r10, [r7], #2                      @*p0++
	 LDRSH         r11, [r8]                          @*p3++
         ADD           r9, r9, r4, LSL #1
	 ADD           r12, r12, r4, LSL #1
	 ADD           r5, r5, r10
	 ADD           r6, r6, r11
	 STRH          r5, [r9]
	 STRH          r6, [r12]
	 ADD           r4, r4, #1                         @i+1
	 ADD           r2, r2, #4                         @pos += STEP
	 CMP           r4, #16

	 BLT           LOOPi

the_end:
         LDMFD         r13!, {r4 - r12, r15}

         @ENDFUNC
         .END





