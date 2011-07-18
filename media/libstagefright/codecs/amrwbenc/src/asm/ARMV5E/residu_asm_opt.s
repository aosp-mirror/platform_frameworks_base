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
@void Residu(
@	    Word16 a[],                           /* (i) Q12 : prediction coefficients */
@	    Word16 x[],                           /* (i)     : speech (values x[-m..-1] are needed */
@	    Word16 y[],                           /* (o) x2  : residual signal       */
@	    Word16 lg                             /* (i)     : size of filtering     */
@	   )
@a[] --- r0
@x[] --- r1
@y[] --- r2
@lg  --- r3

        .section  .text
	.global   Residu_opt

Residu_opt:

        STMFD   r13!, {r4 - r12, r14}

        LDRH    r5, [r0], #2
        LDRH    r6, [r0], #2
        ORR     r5, r6, r5, LSL #16                  @r5 --- a0, a1

        LDRH    r6, [r0], #2
	LDRH    r7, [r0], #2
	ORR     r6, r7, r6, LSL #16                  @r6 --- a2, a3

        LDRH    r7, [r0], #2
	LDRH    r8, [r0], #2
	ORR     r7, r8, r7, LSL #16                  @r7 --- a4, a5

	LDRH    r8, [r0], #2
	LDRH    r9, [r0], #2
	ORR     r8, r9, r8, LSL #16                  @r8 --- a6, a7

	LDRH    r9, [r0], #2
	LDRH    r10, [r0], #2
	ORR     r9, r10, r9, LSL #16                 @r9 --- a8, a9

	LDRH    r10, [r0], #2
	LDRH    r11, [r0], #2
	ORR     r10, r11, r10, LSL #16               @r10 --- a10, a11

        LDRH    r11, [r0], #2
	LDRH    r12, [r0], #2
	ORR     r11, r12, r11, LSL #16               @r11 --- a12, a13

	LDRH    r12, [r0], #2
	LDRH    r4, [r0], #2
	ORR     r12, r4, r12, LSL #16                @r12 --- a14, a15


	STMFD   r13!, {r8 - r12}                     @store r8-r12
	LDRH    r4, [r0], #2                         @load a16
        MOV     r14, r3, ASR #2                      @one loop get 4 outputs
	ADD     r1, r1, #4
	MOV     r0, r2
	ORR     r14, r4, r14, LSL #16                @r14 --- loopnum, a16

residu_loop:

        LDR     r10, [r1], #-4                       @r10  --- x[3], x[2]
	LDR     r2,  [r1], #-4                       @r2   --- x[1], x[0]

	SMULTB  r3, r5, r2                           @i1(0)  --- r3 = x[0] * a0
        SMULTT  r4, r5, r2                           @i2(0)  --- r4 = x[1] * a0
	SMULTB  r11, r5, r10                         @i3(0)  --- r11 = x[2] * a0
	SMULTT  r12, r5, r10                         @i4(0)  --- r12 = x[3] * a0

        SMLABB  r4, r5, r2, r4                       @i2(1)  --- r4 += x[0] * a1
        SMLABT  r11, r5, r2, r11                     @i3(1)  --- r11 += x[1] * a0
        SMLABB  r12, r5, r10, r12                    @i4(1)  --- r12 += x[2] * a1

	SMLATB  r11, r6, r2, r11                     @i3(2)  --- r11 += x[0] * a2
	SMLATT  r12, r6, r2, r12                     @i4(2)  --- r12 += x[1] * a2
	SMLABB  r12, r6, r2, r12                     @i4(3)  --- r12 += x[0] * a3

	LDR     r2, [r1], #-4                        @r2 ---- x[-1], x[-2]

	SMLABT  r3, r5, r2, r3                       @i1(1) --- r3 += x[-1] * a1
	SMLATT  r4, r6, r2, r4                       @i2(2) --- r4 += x[-1] * a2
	SMLABT  r11, r6, r2, r11                     @i3(3) --- r11 += x[-1] * a3
	SMLATT  r12, r7, r2, r12                     @i4(4) --- r12 += x[-1] * a4
        SMLATB  r3, r6, r2, r3                       @i1(2) --- r3 += x[-2] * a2

	SMLABB	r4, r6, r2, r4				@ i2	(3)
	SMLATB  r11,r7, r2, r11				@ i3	(4)
	SMLABB	r12,r7, r2, r12				@ i4	(5)

	LDR	r2,[r1],#-4
	SMLABT	r3, r6, r2, r3				@ i1	(3)
	SMLATT	r4, r7, r2, r4				@ i2	(4)
	SMLABT	r11,r7, r2, r11				@ i3	(5)
	SMLATT	r12,r8, r2, r12				@ i4	(6)
	SMLATB	r3, r7, r2, r3				@ i1	(4)
	SMLABB	r4, r7, r2, r4				@ i2	(5)
	SMLATB	r11,r8, r2, r11				@ i3	(6)
	SMLABB	r12,r8, r2, r12				@ i4	(7)

	LDR	r2,[r1],#-4
	SMLABT	r3, r7, r2, r3				@ i1	(5)
	SMLATT	r4, r8, r2, r4				@ i2	(6)
	SMLABT	r11,r8, r2, r11				@ i3	(7)
	SMLATT	r12,r9, r2, r12				@ i4	(8)
	SMLATB	r3, r8, r2, r3				@ i1	(6)
	SMLABB	r4, r8, r2, r4				@ i2	(7)
	SMLATB	r11,r9, r2, r11				@ i3	(8)
	SMLABB	r12,r9, r2, r12				@ i4	(9)
	LDR	r10, [r13, #8]				@ [ a10 | a11]

	LDR	r2,[r1],#-4
	SMLABT	r3, r8, r2, r3				@ i1	(7)
	SMLATT	r4, r9, r2, r4				@ i2	(8)
	SMLABT	r11,r9, r2, r11				@ i3	(9)
	SMLATT	r12,r10, r2, r12			@ i4	(10)
	SMLATB	r3, r9, r2, r3				@ i1	(8)
	SMLABB	r4, r9, r2, r4				@ i2	(9)
	SMLATB	r11,r10, r2, r11			@ i3	(10)
	SMLABB	r12,r10, r2, r12			@ i4	(11)
	LDR	r8, [r13, #12]				@ [ a12 | a13 ]

	LDR	r2,[r1],#-4
	SMLABT	r3, r9, r2, r3				@ i1	(9)
	SMLATT	r4, r10, r2, r4				@ i2	(10)
	SMLABT	r11,r10, r2, r11			@ i3	(11)
	SMLATT	r12,r8, r2, r12				@ i4	(12)
	SMLATB	r3, r10, r2, r3				@ i1	(10)
	SMLABB	r4, r10, r2, r4				@ i2	(11)
	SMLATB	r11,r8, r2, r11				@ i3	(12)
	SMLABB	r12,r8, r2, r12				@ i4	(13)
	LDR	r9, [r13, #16]				@ [ a14 | a15 ]

	LDR	r2,[r1],#-4
	SMLABT	r3, r10, r2, r3				@ i1	(11)
	SMLATT	r4, r8, r2, r4				@ i2	(12)
	SMLABT	r11,r8, r2, r11				@ i3	(13)
	SMLATT	r12,r9, r2, r12				@ i4	(14)
	SMLATB	r3, r8, r2, r3				@ i1	(12)
	SMLABB	r4, r8, r2, r4				@ i2	(13)
	SMLATB	r11,r9, r2, r11				@ i3	(14)
	SMLABB	r12,r9, r2, r12				@ i4	(15)


	LDR	r2,[r1],#-4
	SMLABT	r3, r8, r2, r3				@ i1	(13)
	SMLATT	r4, r9, r2, r4				@ i2	(14)
	SMLABT	r11,r9, r2, r11				@ i3	(15)
	SMLABT	r12,r14, r2, r12			@ i4	(16)
	SMLATB	r3, r9, r2, r3				@ i1	(14)
	SMLABB	r4, r9, r2, r4				@ i2	(15)
	SMLABB	r11,r14, r2, r11			@ i3	(16)
	LDR		r8, [r13]					@ [ a6 | a7 ]

        LDR     r2,[r1],#44         		@ Change
	SMLABT	r3, r9, r2, r3
	SMLABB	r3, r14, r2, r3
	SMLABT	r4, r14, r2, r4
	LDR		r9, [r13, #4]				@ [ a8 | a9 ]


	QADD	r3,r3,r3
	QADD	r4,r4,r4
	QADD	r11,r11,r11
	QADD	r12,r12,r12

	QADD	r3,r3,r3
	QADD	r4,r4,r4
	QADD	r11,r11,r11
	QADD	r12,r12,r12

	QADD	r3,r3,r3
	QADD	r4,r4,r4
	QADD	r11,r11,r11
	QADD	r12,r12,r12

	QADD	r3,r3,r3
	QADD	r4,r4,r4
	QADD	r11,r11,r11
	QADD	r12,r12,r12

	MOV	r2,#32768

	QDADD	r3,r2,r3
	QDADD	r4,r2,r4
	QDADD	r11,r2,r11
	QDADD	r12,r2,r12


	MOV	r3,r3,asr #16
	MOV	r4,r4,asr #16
	MOV	r11,r11,asr #16
	MOV	r12,r12,asr #16

	STRH	r3,[r0],#2
	STRH	r4,[r0],#2
	STRH	r11,[r0],#2
	STRH	r12,[r0],#2

	MOV	r2,r14,asr #16
	SUB	r14, r14, #0x10000
	SUBS	r2,r2,#1
	BNE	residu_loop
end:
	LDMFD	r13!, {r8 -r12}
	LDMFD	r13!, {r4 -r12,pc}

        @ENDFUNC
        .END





