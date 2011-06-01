;//
;// This confidential and proprietary software may be used only as
;// authorised by a licensing agreement from ARM Limited
;//   (C) COPYRIGHT 2004 ARM Limited
;//       ALL RIGHTS RESERVED
;// The entire notice above must be reproduced on all authorised
;// copies and copies may only be made to the extent permitted
;// by a licensing agreement from ARM Limited.
;//
;// IDCT_s.s
;//
;// Inverse DCT module
;//
;// 
;// ALGORITHM DESCRIPTION
;//
;// The 8x8 2D IDCT is performed by calculating a 1D IDCT for each
;// column and then a 1D IDCT for each row.
;//
;// The 8-point 1D IDCT is defined by
;//   f(x) = (C(0)*T(0)*c(0,x) + ... + C(7)*T(7)*c(7,x))/2
;//
;//   C(u) = 1/sqrt(2) if u=0 or 1 if u!=0
;//   c(u,x) = cos( (2x+1)*u*pi/16 )
;//
;// We compute the 8-point 1D IDCT using the reverse of
;// the Arai-Agui-Nakajima flow graph which we split into
;// 5 stages named in reverse order to identify with the
;// forward DCT. Direct inversion of the forward formulae
;// in file FDCT_s.s gives:
;//
;// IStage 5:   j(u) = T(u)*A(u)  [ A(u)=4*C(u)*c(u,0) ]
;//             [ A(0) = 2*sqrt(2)
;//               A(u) = 4*cos(u*pi/16)  for (u!=0) ]
;//
;// IStage 4:   i0 = j0             i1 = j4
;//             i3 = (j2+j6)/2      i2 = (j2-j6)/2
;//             i7 = (j5+j3)/2      i4 = (j5-j3)/2
;//             i5 = (j1+j7)/2      i6 = (j1-j7)/2
;//
;// IStage 3:   h0 = (i0+i1)/2      h1 = (i0-i1)/2
;//             h2 = (i2*sqrt2)-i3  h3 = i3
;//             h4 =  cos(pi/8)*i4 + sin(pi/8)*i6
;//             h6 = -sin(pi/8)*i4 + cos(pi/8)*i6
;//             [ The above two lines rotate by -(pi/8) ]
;//             h5 = (i5-i7)/sqrt2  h7 = (i5+i7)/2 
;//             
;// IStage 2:   g0 = (h0+h3)/2      g3 = (h0-h3)/2
;//             g1 = (h1+h2)/2      g2 = (h1-h2)/2
;//             g7 = h7             g6 = h6 - h7
;//             g5 = h5 - g6        g4 = h4 - g5
;//
;// IStage 1:   f0 = (g0+g7)/2      f7 = (g0-g7)/2
;//             f1 = (g1+g6)/2      f6 = (g1-g6)/2
;//             f2 = (g2+g5)/2      f5 = (g2-g5)/2
;//             f3 = (g3+g4)/2      f4 = (g3-g4)/2
;//
;// Note that most coefficients are halved 3 times during the
;// above calculation. We can rescale the algorithm dividing
;// the input by 8 to remove the halvings.
;//
;// IStage 5:   j(u) = T(u)*A(u)/8
;//
;// IStage 4:   i0 = j0             i1 = j4
;//             i3 = j2 + j6        i2 = j2 - j6
;//             i7 = j5 + j3        i4 = j5 - j3
;//             i5 = j1 + j7        i6 = j1 - j7
;//
;// IStage 3:   h0 = i0 + i1        h1 = i0 - i1
;//             h2 = (i2*sqrt2)-i3  h3 = i3
;//             h4 = 2*( cos(pi/8)*i4 + sin(pi/8)*i6)
;//             h6 = 2*(-sin(pi/8)*i4 + cos(pi/8)*i6)
;//             h5 = (i5-i7)*sqrt2  h7 = i5 + i7 
;//             
;// IStage 2:   g0 = h0 + h3        g3 = h0 - h3
;//             g1 = h1 + h2        g2 = h1 - h2
;//             g7 = h7             g6 = h6 - h7
;//             g5 = h5 - g6        g4 = h4 - g5
;//
;// IStage 1:   f0 = g0 + g7        f7 = g0 - g7
;//             f1 = g1 + g6        f6 = g1 - g6
;//             f2 = g2 + g5        f5 = g2 - g5
;//             f3 = g3 + g4        f4 = g3 - g4
;//
;// Note:
;// 1. The scaling by A(u)/8 can often be combined with inverse
;//    quantization. The column and row scalings can be combined.
;// 2. The flowgraph in the AAN paper has h4,g6 negated compared
;//    to the above code but is otherwise identical.
;// 3. The rotation by -pi/8 can be peformed using three multiplies
;//    Eg  c*i4+s*i6 = (i6-i4)*s + (c+s)*i4
;//       -s*i4+c*i6 = (i6-i4)*s + (c-s)*i6
;// 4. If |T(u)|<=1 then from the IDCT definition,
;//    |f(x)| <= ((1/sqrt2) + |c(1,x)| + .. + |c(7,x)|)/2
;//            = ((1/sqrt2) + cos(pi/16) + ... + cos(7*pi/16))/2
;//            = ((1/sqrt2) + (cot(pi/32)-1)/2)/2
;//            = (1 + cos(pi/16) + cos(2pi/16) + cos(3pi/16))/sqrt(2)
;//            = (approx)2.64
;//    So the max gain of the 2D IDCT is ~x7.0 = 3 bits.
;//    The table below shows input patterns generating the maximum
;//    value of |f(u)| for input in the range |T(x)|<=1. M=-1, P=+1
;//    InputPattern      Max |f(x)|
;//      PPPPPPPP        |f0| =  2.64
;//      PPPMMMMM        |f1| =  2.64
;//      PPMMMPPP        |f2| =  2.64
;//      PPMMPPMM        |f3| =  2.64
;//      PMMPPMMP        |f4| =  2.64
;//      PMMPMMPM        |f5| =  2.64
;//      PMPPMPMP        |f6| =  2.64
;//      PMPMPMPM        |f7| =  2.64
;//   Note that this input pattern is the transpose of the
;//   corresponding max input patter for the FDCT.

;// Arguments

pSrc    RN 0    ;// source data buffer
Stride  RN 1    ;// destination stride in bytes
pDest   RN 2    ;// destination data buffer
pScale  RN 3    ;// pointer to scaling table


        ;// DCT Inverse Macro
        ;// The DCT code should be parametrized according
        ;// to the following inputs:
        ;// $outsize = "u8"  :  8-bit unsigned data saturated (0 to +255)
        ;//            "s9"  : 16-bit signed data saturated to 9-bit (-256 to +255)
        ;//            "s16" : 16-bit signed data not saturated (max size ~+/-14273)
        ;// $inscale = "s16" : signed 16-bit aan-scale table, Q15 format, with 4 byte alignment
        ;//            "s32" : signed 32-bit aan-scale table, Q23 format, with 4 byte alignment
        ;//
        ;// Inputs:
        ;// pSrc   = r0 = Pointer to input data
        ;//               Range is -256 to +255 (9-bit)
        ;// Stride = r1 = Stride between input lines
        ;// pDest  = r2 = Pointer to output data
        ;// pScale = r3 = Pointer to aan-scale table in the format defined by $inscale
        
        
        
        MACRO
        M_IDCT  $outsize, $inscale, $stride
        LCLA    SHIFT
        
        
        IF ARM1136JS
        
;// REGISTER ALLOCATION
;// This is hard since we have 8 values, 9 free registers and each
;// butterfly requires a temporary register. We also want to 
;// maintain register order so we can use LDM/STM. The table below
;// summarises the register allocation that meets all these criteria.
;// a=1stcol, b=2ndcol, f,g,h,i are dataflow points described above.
;//
;// r1  a01     g0  h0
;// r4  b01 f0  g1  h1  i0
;// r5  a23 f1  g2      i1
;// r6  b23 f2  g3  h2  i2
;// r7  a45 f3      h3  i3
;// r8  b45 f4  g4  h4  i4
;// r9  a67 f5  g5  h5  i5
;// r10 b67 f6  g6  h6  i6
;// r11     f7  g7  h7  i7
;//
ra01    RN 1
rb01    RN 4
ra23    RN 5
rb23    RN 6
ra45    RN 7
rb45    RN 8
ra67    RN 9
rb67    RN 10
rtmp    RN 11
csPiBy8 RN 12   ;// [ (Sin(pi/8)@Q15), (Cos(pi/8)@Q15) ]
LoopRR2 RN 14   ;// [ LoopNumber<<13 , (1/Sqrt(2))@Q15 ]
;// Transpose allocation
xft     RN ra01
xf0     RN rb01
xf1     RN ra23
xf2     RN rb23
xf3     RN ra45
xf4     RN rb45
xf5     RN ra67
xf6     RN rb67
xf7     RN rtmp
;// IStage 1 allocation
xg0     RN xft
xg1     RN xf0
xg2     RN xf1
xg3     RN xf2
xgt     RN xf3
xg4     RN xf4
xg5     RN xf5
xg6     RN xf6
xg7     RN xf7
;// IStage 2 allocation
xh0     RN xg0
xh1     RN xg1
xht     RN xg2
xh2     RN xg3
xh3     RN xgt
xh4     RN xg4
xh5     RN xg5
xh6     RN xg6
xh7     RN xg7
;// IStage 3,4 allocation
xit     RN xh0
xi0     RN xh1
xi1     RN xht
xi2     RN xh2
xi3     RN xh3
xi4     RN xh4
xi5     RN xh5
xi6     RN xh6
xi7     RN xh7
        
        M_STR   pDest,  ppDest
        IF "$stride"="s"
            M_STR   Stride, pStride
        ENDIF
        M_ADR   pDest,  pBlk
        LDR     csPiBy8, =0x30fc7642
        LDR     LoopRR2, =0x00005a82
  
v6_idct_col$_F
        ;// Load even values
        LDR     xi4, [pSrc], #4  ;// j0
        LDR     xi5, [pSrc, #4*16-4]  ;// j4
        LDR     xi6, [pSrc, #2*16-4]  ;// j2
        LDR     xi7, [pSrc, #6*16-4]  ;// j6
        
        ;// Scale Even Values
        IF "$inscale"="s16" ;// 16x16 mul
SHIFT       SETA    12
            LDR     xi0, [pScale], #4
            LDR     xi1, [pScale, #4*16-4]        
            LDR     xi2, [pScale, #2*16-4]
            MOV     xit, #1<<(SHIFT-1)
            SMLABB  xi3, xi0, xi4, xit
            SMLATT  xi4, xi0, xi4, xit
            SMLABB  xi0, xi1, xi5, xit
            SMLATT  xi5, xi1, xi5, xit
            MOV     xi3, xi3, ASR #SHIFT
            PKHBT   xi4, xi3, xi4, LSL #(16-SHIFT)
            LDR     xi3, [pScale, #6*16-4]
            SMLABB  xi1, xi2, xi6, xit
            SMLATT  xi6, xi2, xi6, xit
            MOV     xi0, xi0, ASR #SHIFT
            PKHBT   xi5, xi0, xi5, LSL #(16-SHIFT)
            SMLABB  xi2, xi3, xi7, xit
            SMLATT  xi7, xi3, xi7, xit
            MOV     xi1, xi1, ASR #SHIFT
            PKHBT   xi6, xi1, xi6, LSL #(16-SHIFT)
            MOV     xi2, xi2, ASR #SHIFT
            PKHBT   xi7, xi2, xi7, LSL #(16-SHIFT)
        ENDIF
        IF "$inscale"="s32" ;// 32x16 mul
SHIFT       SETA    (12+8-16)
            MOV     xit, #1<<(SHIFT-1)
            LDR     xi0, [pScale], #8
            LDR     xi1, [pScale, #0*32+4-8]
            LDR     xi2, [pScale, #4*32-8]
            LDR     xi3, [pScale, #4*32+4-8]            
            SMLAWB  xi0, xi0, xi4, xit
            SMLAWT  xi1, xi1, xi4, xit
            SMLAWB  xi2, xi2, xi5, xit
            SMLAWT  xi3, xi3, xi5, xit            
            MOV     xi0, xi0, ASR #SHIFT
            PKHBT   xi4, xi0, xi1, LSL #(16-SHIFT)
            MOV     xi2, xi2, ASR #SHIFT            
            PKHBT   xi5, xi2, xi3, LSL #(16-SHIFT)
            LDR     xi0, [pScale, #2*32-8]
            LDR     xi1, [pScale, #2*32+4-8]
            LDR     xi2, [pScale, #6*32-8]
            LDR     xi3, [pScale, #6*32+4-8]            
            SMLAWB  xi0, xi0, xi6, xit
            SMLAWT  xi1, xi1, xi6, xit
            SMLAWB  xi2, xi2, xi7, xit
            SMLAWT  xi3, xi3, xi7, xit            
            MOV     xi0, xi0, ASR #SHIFT
            PKHBT   xi6, xi0, xi1, LSL #(16-SHIFT)
            MOV     xi2, xi2, ASR #SHIFT            
            PKHBT   xi7, xi2, xi3, LSL #(16-SHIFT)
        ENDIF
                
        ;// Load odd values
        LDR     xi0, [pSrc, #1*16-4]      ;// j1
        LDR     xi1, [pSrc, #7*16-4]      ;// j7
        LDR     xi2, [pSrc, #5*16-4]      ;// j5
        LDR     xi3, [pSrc, #3*16-4]      ;// j3
        
        IF  {TRUE}
            ;// shortcut if odd values 0
            TEQ     xi0, #0
            TEQEQ   xi1, #0
            TEQEQ   xi2, #0
            TEQEQ   xi3, #0
            BEQ     v6OddZero$_F
        ENDIF
        
        ;// Store scaled even values
        STMIA   pDest, {xi4, xi5, xi6, xi7}
        
        ;// Scale odd values
        IF "$inscale"="s16"
            ;// Perform AAN Scale
            LDR     xi4, [pScale, #1*16-4]
            LDR     xi5, [pScale, #7*16-4]        
            LDR     xi6, [pScale, #5*16-4]
            SMLABB  xi7, xi0, xi4, xit
            SMLATT  xi0, xi0, xi4, xit
            SMLABB  xi4, xi1, xi5, xit
            SMLATT  xi1, xi1, xi5, xit
            MOV     xi7, xi7, ASR #SHIFT
            PKHBT   xi0, xi7, xi0, LSL #(16-SHIFT)
            LDR     xi7, [pScale, #3*16-4]
            SMLABB  xi5, xi2, xi6, xit
            SMLATT  xi2, xi2, xi6, xit
            MOV     xi4, xi4, ASR #SHIFT
            PKHBT   xi1, xi4, xi1, LSL #(16-SHIFT)
            SMLABB  xi6, xi3, xi7, xit
            SMLATT  xi3, xi3, xi7, xit
            MOV     xi5, xi5, ASR #SHIFT
            PKHBT   xi2, xi5, xi2, LSL #(16-SHIFT)
            MOV     xi6, xi6, ASR #SHIFT
            PKHBT   xi3, xi6, xi3, LSL #(16-SHIFT)
        ENDIF
        IF "$inscale"="s32" ;// 32x16 mul
            LDR     xi4, [pScale, #1*32-8]
            LDR     xi5, [pScale, #1*32+4-8]
            LDR     xi6, [pScale, #7*32-8]
            LDR     xi7, [pScale, #7*32+4-8]            
            SMLAWB  xi4, xi4, xi0, xit
            SMLAWT  xi5, xi5, xi0, xit
            SMLAWB  xi6, xi6, xi1, xit
            SMLAWT  xi7, xi7, xi1, xit            
            MOV     xi4, xi4, ASR #SHIFT
            PKHBT   xi0, xi4, xi5, LSL #(16-SHIFT)
            MOV     xi6, xi6, ASR #SHIFT            
            PKHBT   xi1, xi6, xi7, LSL #(16-SHIFT)
            LDR     xi4, [pScale, #5*32-8]
            LDR     xi5, [pScale, #5*32+4-8]
            LDR     xi6, [pScale, #3*32-8]
            LDR     xi7, [pScale, #3*32+4-8]            
            SMLAWB  xi4, xi4, xi2, xit
            SMLAWT  xi5, xi5, xi2, xit
            SMLAWB  xi6, xi6, xi3, xit
            SMLAWT  xi7, xi7, xi3, xit            
            MOV     xi4, xi4, ASR #SHIFT
            PKHBT   xi2, xi4, xi5, LSL #(16-SHIFT)
            MOV     xi6, xi6, ASR #SHIFT            
            PKHBT   xi3, xi6, xi7, LSL #(16-SHIFT)
        ENDIF
        
        LDR     xit, =0x00010001        ;// rounding constant
        SADD16 xi5, xi0, xi1           ;// (j1+j7)/2
        SHADD16 xi5, xi5, xit
        
        SSUB16  xi6, xi0, xi1           ;// j1-j7
        SADD16 xi7, xi2, xi3           ;// (j5+j3)/2
        SHADD16 xi7, xi7, xit
        
        SSUB16  xi4, xi2, xi3           ;// j5-j3
        
        SSUB16  xi3, xi5, xi7           ;// (i5-i7)/2
        
        PKHBT   xi0, xi6, xi4, LSL#16   ;// [i4,i6] row a
        PKHTB   xi1, xi4, xi6, ASR#16   ;// [i4,i6] row b
        
        SMUADX  xi2, xi0, csPiBy8       ;// rowa by [c,s]
        SMUADX  xi4, xi1, csPiBy8       ;// rowb by [c,s]
        SMUSD   xi0, xi0, csPiBy8       ;// rowa by [-s,c]   
        SMUSD   xi6, xi1, csPiBy8       ;// rowb by [-s,c]
                
        SMULBB  xi1, xi3, LoopRR2
        SMULTB  xi3, xi3, LoopRR2
                
        PKHTB   xh4, xi4, xi2, ASR#16   ;// h4/4
        PKHTB   xh6, xi6, xi0, ASR#16   ;// h6/4
        SHADD16 xh7, xi5, xi7           ;// (i5+i7)/4
                
        ;// xi0,xi1,xi2,xi3 now free
        ;// IStage 4,3, rows 2to3 x1/2
        
        MOV     xi3, xi3, LSL #1
        PKHTB   xh5, xi3, xi1, ASR#15   ;// h5/4
        LDRD    xi0, [pDest, #8]        ;// j2,j6 scaled
                
        ;// IStage 2, rows4to7
        SSUB16  xg6, xh6, xh7
        SSUB16  xg5, xh5, xg6        
        SSUB16  xg4, xh4, xg5
                
        SSUB16  xi2, xi0, xi1           ;// (j2-j6)
        
        SHADD16 xi3, xi0, xi1           ;// (j2+j6)/2
        
        SMULBB  xi0, xi2, LoopRR2
        SMULTB  xi2, xi2, LoopRR2
        
        MOV     xi2, xi2, LSL #1
        PKHTB   xh2, xi2, xi0, ASR#15   ;// i2*sqrt(2)/4
        
        ;// xi0, xi1 now free
        ;// IStage 4,3 rows 0to1 x 1/2
        LDRD    xi0, [pDest]            ;// j0, j4 scaled
        SSUB16  xh2, xh2, xi3
        ADDS    LoopRR2, LoopRR2, #2<<29    ;// done two rows
        
        SHADD16 xh0, xi0, xi1
        SHSUB16 xh1, xi0, xi1                
        
        ;// IStage 2 rows 0to3 x 1/2
        SHSUB16 xg2, xh1, xh2
        SHADD16 xg1, xh1, xh2
        SHSUB16 xg3, xh0, xh3
        SHADD16 xg0, xh0, xh3
        
        ;// IStage 1 all rows
        SADD16  xf3, xg3, xg4
        SSUB16  xf4, xg3, xg4
        SADD16  xf2, xg2, xg5
        SSUB16  xf5, xg2, xg5
        SADD16  xf1, xg1, xg6
        SSUB16  xf6, xg1, xg6
        SADD16  xf0, xg0, xg7
        SSUB16  xf7, xg0, xg7
        
        ;// Transpose, store and loop
        PKHBT   ra01, xf0, xf1, LSL #16
        PKHTB   rb01, xf1, xf0, ASR #16
        
        PKHBT   ra23, xf2, xf3, LSL #16
        PKHTB   rb23, xf3, xf2, ASR #16
        
        PKHBT   ra45, xf4, xf5, LSL #16
        PKHTB   rb45, xf5, xf4, ASR #16
        
        PKHBT   ra67, xf6, xf7, LSL #16
        STMIA   pDest!, {ra01, ra23, ra45, ra67}      
        PKHTB   rb67, xf7, xf6, ASR #16
        STMIA   pDest!, {rb01, rb23, rb45, rb67}                              
        BCC     v6_idct_col$_F
        
        SUB     pSrc, pDest, #(64*2)
        M_LDR   pDest, ppDest
        IF "$stride"="s"
            M_LDR   pScale, pStride 
        ENDIF
        B       v6_idct_row$_F
        
v6OddZero$_F
        SSUB16  xi2, xi6, xi7           ;// (j2-j6)
        SHADD16 xi3, xi6, xi7           ;// (j2+j6)/2
        
        SMULBB  xi0, xi2, LoopRR2
        SMULTB  xi2, xi2, LoopRR2
        
        MOV     xi2, xi2, LSL #1
        PKHTB   xh2, xi2, xi0, ASR#15   ;// i2*sqrt(2)/4
        SSUB16  xh2, xh2, xi3
        
        ;// xi0, xi1 now free
        ;// IStage 4,3 rows 0to1 x 1/2
        
        SHADD16 xh0, xi4, xi5
        SHSUB16 xh1, xi4, xi5                
        
        ;// IStage 2 rows 0to3 x 1/2
        SHSUB16 xg2, xh1, xh2
        SHADD16 xg1, xh1, xh2
        SHSUB16 xg3, xh0, xh3
        SHADD16 xg0, xh0, xh3
               
        ;// IStage 1 all rows
        MOV  xf3, xg3
        MOV  xf4, xg3
        MOV  xf2, xg2
        MOV  xf5, xg2
        MOV  xf1, xg1
        MOV  xf6, xg1
        MOV  xf0, xg0
        MOV  xf7, xg0
        
        ;// Transpose
        PKHBT   ra01, xf0, xf1, LSL #16
        PKHTB   rb01, xf1, xf0, ASR #16
        
        PKHBT   ra23, xf2, xf3, LSL #16
        PKHTB   rb23, xf3, xf2, ASR #16
        
        PKHBT   ra45, xf4, xf5, LSL #16
        PKHTB   rb45, xf5, xf4, ASR #16
        
        PKHBT   ra67, xf6, xf7, LSL #16
        PKHTB   rb67, xf7, xf6, ASR #16
                
        STMIA   pDest!, {ra01, ra23, ra45, ra67}      
        ADDS    LoopRR2, LoopRR2, #2<<29    ;// done two rows
        STMIA   pDest!, {rb01, rb23, rb45, rb67}      
        
        BCC     v6_idct_col$_F
        SUB     pSrc, pDest, #(64*2)
        M_LDR   pDest, ppDest
        IF "$stride"="s"
            M_LDR   pScale, pStride 
        ENDIF
               
        
v6_idct_row$_F
        ;// IStage 4,3, rows4to7 x1/4
        LDR     xit, =0x00010001        ;// rounding constant
        LDR     xi0, [pSrc, #1*16]      ;// j1
        LDR     xi1, [pSrc, #7*16]      ;// 4*j7
        LDR     xi2, [pSrc, #5*16]      ;// j5
        LDR     xi3, [pSrc, #3*16]      ;// j3
        
        SHADD16 xi1, xi1, xit           ;// 2*j7
        SHADD16 xi1, xi1, xit           ;// j7                
        
        SHADD16 xi5, xi0, xi1           ;// (j1+j7)/2
        SSUB16  xi6, xi0, xi1           ;// j1-j7
        SHADD16 xi7, xi2, xi3           ;// (j5+j3)/2
        SSUB16  xi4, xi2, xi3           ;// j5-j3
        
        SSUB16  xi3, xi5, xi7           ;// (i5-i7)/2
        
        PKHBT   xi0, xi6, xi4, LSL#16   ;// [i4,i6] row a
        PKHTB   xi1, xi4, xi6, ASR#16   ;// [i4,i6] row b
        
        SMUADX  xi2, xi0, csPiBy8       ;// rowa by [c,s]
        SMUADX  xi4, xi1, csPiBy8       ;// rowb by [c,s]
        SMUSD   xi0, xi0, csPiBy8       ;// rowa by [-s,c]   
        SMUSD   xi6, xi1, csPiBy8       ;// rowb by [-s,c]
                
        SMULBB  xi1, xi3, LoopRR2
        SMULTB  xi3, xi3, LoopRR2
                
        PKHTB   xh4, xi4, xi2, ASR#16   ;// h4/4
        PKHTB   xh6, xi6, xi0, ASR#16   ;// h6/4
        SHADD16 xh7, xi5, xi7           ;// (i5+i7)/4
        
        MOV     xi3, xi3, LSL #1
        PKHTB   xh5, xi3, xi1, ASR#15   ;// h5/4
               
        ;// xi0,xi1,xi2,xi3 now free
        ;// IStage 4,3, rows 2to3 x1/2
        
        LDR     xi0, [pSrc, #2*16]      ;// j2
        LDR     xi1, [pSrc, #6*16]      ;// 2*j6
        
        ;// IStage 2, rows4to7
        SSUB16  xg6, xh6, xh7
        SSUB16  xg5, xh5, xg6
        SSUB16  xg4, xh4, xg5
        
        SHADD16 xi1, xi1, xit           ;// j6
        SSUB16  xi2, xi0, xi1           ;// (j2-j6)        
        SHADD16 xi3, xi0, xi1           ;// (j2+j6)/2
        
        SMULBB  xi0, xi2, LoopRR2
        SMULTB  xi2, xi2, LoopRR2
        
        MOV     xi2, xi2, LSL #1
        
        PKHTB   xh2, xi2, xi0, ASR#15   ;// i2*sqrt(2)/4
        
        ;// xi0, xi1 now free
        ;// IStage 4,3 rows 0to1 x 1/2
        LDR     xi1, [pSrc, #4*16]      ;// j4
        LDR     xi0, [pSrc], #4         ;// j0

        SSUB16  xh2, xh2, xi3
        ADDS    LoopRR2, LoopRR2, #2<<29    ;// done two rows
        
        ADD     xi0, xi0, xit, LSL #2   ;// ensure correct round
        SHADD16 xh0, xi0, xi1           ;// of DC result
        SHSUB16 xh1, xi0, xi1
                
        ;// IStage 2 rows 0to3 x 1/2
        SHSUB16 xg2, xh1, xh2
        SHADD16 xg1, xh1, xh2
        SHSUB16 xg3, xh0, xh3
        SHADD16 xg0, xh0, xh3
        
        ;// IStage 1 all rows
        SHADD16 xf3, xg3, xg4
        SHSUB16 xf4, xg3, xg4
        SHADD16 xf2, xg2, xg5
        SHSUB16 xf5, xg2, xg5
        SHADD16 xf1, xg1, xg6
        SHSUB16 xf6, xg1, xg6
        SHADD16 xf0, xg0, xg7
        SHSUB16 xf7, xg0, xg7
        
        ;// Saturate
        IF ("$outsize"="u8")
            USAT16  xf0, #8, xf0
            USAT16  xf1, #8, xf1
            USAT16  xf2, #8, xf2
            USAT16  xf3, #8, xf3
            USAT16  xf4, #8, xf4
            USAT16  xf5, #8, xf5
            USAT16  xf6, #8, xf6
            USAT16  xf7, #8, xf7        
        ENDIF
        IF ("$outsize"="s9")
            SSAT16  xf0, #9, xf0
            SSAT16  xf1, #9, xf1
            SSAT16  xf2, #9, xf2
            SSAT16  xf3, #9, xf3
            SSAT16  xf4, #9, xf4
            SSAT16  xf5, #9, xf5
            SSAT16  xf6, #9, xf6
            SSAT16  xf7, #9, xf7        
        ENDIF
        
        ;// Transpose to Row, Pack and store
        IF ("$outsize"="u8")
            ORR     xf0, xf0, xf1, LSL #8 ;// [ b1 b0 a1 a0 ]
            ORR     xf2, xf2, xf3, LSL #8 ;// [ b3 b2 a3 a2 ]
            ORR     xf4, xf4, xf5, LSL #8 ;// [ b5 b4 a5 a4 ]
            ORR     xf6, xf6, xf7, LSL #8 ;// [ b7 b6 a7 a6 ]
            PKHBT   ra01, xf0, xf2, LSL #16
            PKHTB   rb01, xf2, xf0, ASR #16
            PKHBT   ra23, xf4, xf6, LSL #16
            PKHTB   rb23, xf6, xf4, ASR #16
            STMIA   pDest, {ra01, ra23}
            IF "$stride"="s"
                ADD     pDest, pDest, pScale
                STMIA   pDest, {rb01, rb23}
                ADD     pDest, pDest, pScale
            ELSE                
                ADD     pDest, pDest, #($stride)
                STMIA   pDest, {rb01, rb23}
                ADD     pDest, pDest, #($stride)
            ENDIF
        ENDIF
        IF ("$outsize"="s9"):LOR:("$outsize"="s16")        
            PKHBT   ra01, xf0, xf1, LSL #16
            PKHTB   rb01, xf1, xf0, ASR #16
        
            PKHBT   ra23, xf2, xf3, LSL #16
            PKHTB   rb23, xf3, xf2, ASR #16
            
            PKHBT   ra45, xf4, xf5, LSL #16
            PKHTB   rb45, xf5, xf4, ASR #16
            
            PKHBT   ra67, xf6, xf7, LSL #16
            PKHTB   rb67, xf7, xf6, ASR #16
            
            STMIA   pDest, {ra01, ra23, ra45, ra67}      
            IF "$stride"="s"
                ADD     pDest, pDest, pScale
                STMIA   pDest, {rb01, rb23, rb45, rb67}      
                ADD     pDest, pDest, pScale
            ELSE                
                ADD     pDest, pDest, #($stride)
                STMIA   pDest, {rb01, rb23, rb45, rb67}      
                ADD     pDest, pDest, #($stride)
            ENDIF
        ENDIF
        
        BCC     v6_idct_row$_F
        ENDIF ;// ARM1136JS


        IF CortexA8
        
Src0            EQU  7              
Src1            EQU  8              
Src2            EQU  9              
Src3            EQU  10              
Src4            EQU  11              
Src5            EQU  12              
Src6            EQU  13
Src7            EQU  14
Tmp             EQU  15

qXj0            QN Src0.S16 
qXj1            QN Src1.S16
qXj2            QN Src2.S16
qXj3            QN Src3.S16
qXj4            QN Src4.S16
qXj5            QN Src5.S16
qXj6            QN Src6.S16
qXj7            QN Src7.S16
qXjt            QN Tmp.S16

dXj0lo          DN (Src0*2).S16
dXj0hi          DN (Src0*2+1).S16
dXj1lo          DN (Src1*2).S16
dXj1hi          DN (Src1*2+1).S16
dXj2lo          DN (Src2*2).S16
dXj2hi          DN (Src2*2+1).S16
dXj3lo          DN (Src3*2).S16
dXj3hi          DN (Src3*2+1).S16
dXj4lo          DN (Src4*2).S16
dXj4hi          DN (Src4*2+1).S16
dXj5lo          DN (Src5*2).S16
dXj5hi          DN (Src5*2+1).S16
dXj6lo          DN (Src6*2).S16
dXj6hi          DN (Src6*2+1).S16
dXj7lo          DN (Src7*2).S16
dXj7hi          DN (Src7*2+1).S16
dXjtlo          DN (Tmp*2).S16
dXjthi          DN (Tmp*2+1).S16

qXi0            QN qXj0
qXi1            QN qXj4
qXi2            QN qXj2
qXi3            QN qXj7
qXi4            QN qXj5
qXi5            QN qXjt
qXi6            QN qXj1
qXi7            QN qXj6
qXit            QN qXj3

dXi0lo          DN dXj0lo
dXi0hi          DN dXj0hi
dXi1lo          DN dXj4lo
dXi1hi          DN dXj4hi
dXi2lo          DN dXj2lo
dXi2hi          DN dXj2hi
dXi3lo          DN dXj7lo
dXi3hi          DN dXj7hi
dXi4lo          DN dXj5lo
dXi4hi          DN dXj5hi
dXi5lo          DN dXjtlo
dXi5hi          DN dXjthi
dXi6lo          DN dXj1lo
dXi6hi          DN dXj1hi
dXi7lo          DN dXj6lo
dXi7hi          DN dXj6hi
dXitlo          DN dXj3lo
dXithi          DN dXj3hi

qXh0            QN qXit
qXh1            QN qXi0
qXh2            QN qXi2
qXh3            QN qXi3
qXh4            QN qXi7
qXh5            QN qXi5
qXh6            QN qXi4
qXh7            QN qXi1
qXht            QN qXi6

dXh0lo          DN dXitlo
dXh0hi          DN dXithi
dXh1lo          DN dXi0lo
dXh1hi          DN dXi0hi
dXh2lo          DN dXi2lo
dXh2hi          DN dXi2hi
dXh3lo          DN dXi3lo
dXh3hi          DN dXi3hi
dXh4lo          DN dXi7lo
dXh4hi          DN dXi7hi
dXh5lo          DN dXi5lo
dXh5hi          DN dXi5hi
dXh6lo          DN dXi4lo
dXh6hi          DN dXi4hi
dXh7lo          DN dXi1lo
dXh7hi          DN dXi1hi
dXhtlo          DN dXi6lo
dXhthi          DN dXi6hi

qXg0            QN qXh2
qXg1            QN qXht
qXg2            QN qXh1
qXg3            QN qXh0
qXg4            QN qXh4
qXg5            QN qXh5
qXg6            QN qXh6
qXg7            QN qXh7
qXgt            QN qXh3

qXf0            QN qXg6
qXf1            QN qXg5
qXf2            QN qXg4
qXf3            QN qXgt
qXf4            QN qXg3
qXf5            QN qXg2
qXf6            QN qXg1
qXf7            QN qXg0
qXft            QN qXg7


qXt0            QN 1.S32
qXt1            QN 2.S32
qT0lo           QN 1.S32         
qT0hi           QN 2.S32         
qT1lo           QN 3.S32         
qT1hi           QN 4.S32         
qScalelo        QN 5.S32        ;// used to read post scale values
qScalehi        QN 6.S32
qTemp0          QN 5.S32         
qTemp1          QN 6.S32    


Scale1          EQU 6
Scale2          EQU 15
qScale1         QN Scale1.S16     
qScale2         QN Scale2.S16     
dScale1lo       DN (Scale1*2).S16     
dScale1hi       DN (Scale1*2+1).S16
dScale2lo       DN (Scale2*2).S16     
dScale2hi       DN (Scale2*2+1).S16

dCoefs          DN 0.S16        ;// Scale coefficients in format {[0] [C] [S] [InvSqrt2]}
InvSqrt2        DN dCoefs[0]    ;// 1/sqrt(2) in Q15
S               DN dCoefs[1]    ;// Sin(PI/8) in Q15
C               DN dCoefs[2]    ;// Cos(PI/8) in Q15

pTemp           RN 12

                
        IMPORT  armCOMM_IDCTCoef
                    
        VLD1        {qXj0,qXj1}, [pSrc @64]!
        VLD1        {qXj2,qXj3}, [pSrc @64]!
        VLD1        {qXj4,qXj5}, [pSrc @64]!
        VLD1        {qXj6,qXj7}, [pSrc @64]!
        
        ;// Load PreScale and multiply with Src
        ;// IStage 4
        
        IF "$inscale"="s16"                         ;// 16X16 Mul
            M_IDCT_PRESCALE16
        ENDIF
        
        IF "$inscale"="s32"                         ;// 32X32 ,ul
            M_IDCT_PRESCALE32
        ENDIF

        ;// IStage 3
        VQDMULH     qXi2, qXi2, InvSqrt2            ;// i2/sqrt(2)
        VHADD       qXh0, qXi0, qXi1                ;// (i0+i1)/2
        VHSUB       qXh1, qXi0, qXi1                ;// (i0-i1)/2
        VHADD       qXh7, qXi5, qXi7                ;// (i5+i7)/4
        VSUB        qXh5, qXi5, qXi7                ;// (i5-i7)/2
        VQDMULH     qXh5, qXh5, InvSqrt2            ;// h5/sqrt(2)
        VSUB        qXh2, qXi2, qXi3                ;// h2, h3

        VMULL       qXt0, dXi4lo, C                 ;// c*i4
        VMLAL       qXt0, dXi6lo, S                 ;// c*i4+s*i6
        VMULL       qXt1, dXi4hi, C
        VMLAL       qXt1, dXi6hi, S
        VSHRN       dXh4lo, qXt0, #16               ;// h4
        VSHRN       dXh4hi, qXt1, #16
        
        VMULL       qXt0, dXi6lo, C                 ;// c*i6
        VMLSL       qXt0, dXi4lo, S                 ;// -s*i4 + c*h6
        VMULL       qXt1, dXi6hi, C
        VMLSL       qXt1, dXi4hi, S
        VSHRN       dXh6lo, qXt0, #16               ;// h6
        VSHRN       dXh6hi, qXt1, #16
        
        ;// IStage 2
        VSUB        qXg6, qXh6, qXh7
        VSUB        qXg5, qXh5, qXg6
        VSUB        qXg4, qXh4, qXg5
        VHADD       qXg1, qXh1, qXh2        ;// (h1+h2)/2
        VHSUB       qXg2, qXh1, qXh2        ;// (h1-h2)/2
        VHADD       qXg0, qXh0, qXh3        ;// (h0+h3)/2
        VHSUB       qXg3, qXh0, qXh3        ;// (h0-h3)/2

        ;// IStage 1 all rows
        VADD        qXf3, qXg3, qXg4        
        VSUB        qXf4, qXg3, qXg4        
        VADD        qXf2, qXg2, qXg5        
        VSUB        qXf5, qXg2, qXg5        
        VADD        qXf1, qXg1, qXg6
        VSUB        qXf6, qXg1, qXg6        
        VADD        qXf0, qXg0, qXg7
        VSUB        qXf7, qXg0, qXg7      

        ;// Transpose, store and loop
XTR0            EQU Src5
XTR1            EQU Tmp
XTR2            EQU Src6
XTR3            EQU Src7
XTR4            EQU Src3
XTR5            EQU Src0
XTR6            EQU Src1
XTR7            EQU Src2
XTRt            EQU Src4
                
qA0             QN  XTR0.S32  ;// for XTRpose
qA1             QN  XTR1.S32
qA2             QN  XTR2.S32
qA3             QN  XTR3.S32
qA4             QN  XTR4.S32
qA5             QN  XTR5.S32
qA6             QN  XTR6.S32
qA7             QN  XTR7.S32

dB0             DN  XTR0*2+1      ;// for using VSWP
dB1             DN  XTR1*2+1
dB2             DN  XTR2*2+1
dB3             DN  XTR3*2+1
dB4             DN  XTR4*2
dB5             DN  XTR5*2
dB6             DN  XTR6*2
dB7             DN  XTR7*2

          
        VTRN        qXf0, qXf1
        VTRN        qXf2, qXf3
        VTRN        qXf4, qXf5
        VTRN        qXf6, qXf7
        VTRN        qA0, qA2
        VTRN        qA1, qA3
        VTRN        qA4, qA6
        VTRN        qA5, qA7        
        VSWP        dB0, dB4
        VSWP        dB1, dB5
        VSWP        dB2, dB6
        VSWP        dB3, dB7
        

qYj0            QN qXf0
qYj1            QN qXf1
qYj2            QN qXf2
qYj3            QN qXf3
qYj4            QN qXf4
qYj5            QN qXf5
qYj6            QN qXf6
qYj7            QN qXf7
qYjt            QN qXft

dYj0lo          DN (XTR0*2).S16
dYj0hi          DN (XTR0*2+1).S16
dYj1lo          DN (XTR1*2).S16
dYj1hi          DN (XTR1*2+1).S16
dYj2lo          DN (XTR2*2).S16
dYj2hi          DN (XTR2*2+1).S16
dYj3lo          DN (XTR3*2).S16
dYj3hi          DN (XTR3*2+1).S16
dYj4lo          DN (XTR4*2).S16
dYj4hi          DN (XTR4*2+1).S16
dYj5lo          DN (XTR5*2).S16
dYj5hi          DN (XTR5*2+1).S16
dYj6lo          DN (XTR6*2).S16
dYj6hi          DN (XTR6*2+1).S16
dYj7lo          DN (XTR7*2).S16
dYj7hi          DN (XTR7*2+1).S16
dYjtlo          DN (XTRt*2).S16
dYjthi          DN (XTRt*2+1).S16

qYi0            QN qYj0
qYi1            QN qYj4
qYi2            QN qYj2
qYi3            QN qYj7
qYi4            QN qYj5
qYi5            QN qYjt
qYi6            QN qYj1
qYi7            QN qYj6
qYit            QN qYj3

dYi0lo          DN dYj0lo
dYi0hi          DN dYj0hi
dYi1lo          DN dYj4lo
dYi1hi          DN dYj4hi
dYi2lo          DN dYj2lo
dYi2hi          DN dYj2hi
dYi3lo          DN dYj7lo
dYi3hi          DN dYj7hi
dYi4lo          DN dYj5lo
dYi4hi          DN dYj5hi
dYi5lo          DN dYjtlo
dYi5hi          DN dYjthi
dYi6lo          DN dYj1lo
dYi6hi          DN dYj1hi
dYi7lo          DN dYj6lo
dYi7hi          DN dYj6hi
dYitlo          DN dYj3lo
dYithi          DN dYj3hi

qYh0            QN qYit
qYh1            QN qYi0
qYh2            QN qYi2
qYh3            QN qYi3
qYh4            QN qYi7
qYh5            QN qYi5
qYh6            QN qYi4
qYh7            QN qYi1
qYht            QN qYi6

dYh0lo          DN dYitlo
dYh0hi          DN dYithi
dYh1lo          DN dYi0lo
dYh1hi          DN dYi0hi
dYh2lo          DN dYi2lo
dYh2hi          DN dYi2hi
dYh3lo          DN dYi3lo
dYh3hi          DN dYi3hi
dYh4lo          DN dYi7lo
dYh4hi          DN dYi7hi
dYh5lo          DN dYi5lo
dYh5hi          DN dYi5hi
dYh6lo          DN dYi4lo
dYh6hi          DN dYi4hi
dYh7lo          DN dYi1lo
dYh7hi          DN dYi1hi
dYhtlo          DN dYi6lo
dYhthi          DN dYi6hi

qYg0            QN qYh2
qYg1            QN qYht
qYg2            QN qYh1
qYg3            QN qYh0
qYg4            QN qYh4
qYg5            QN qYh5
qYg6            QN qYh6
qYg7            QN qYh7
qYgt            QN qYh3

qYf0            QN qYg6
qYf1            QN qYg5
qYf2            QN qYg4
qYf3            QN qYgt
qYf4            QN qYg3
qYf5            QN qYg2
qYf6            QN qYg1
qYf7            QN qYg0
qYft            QN qYg7

        VRSHR       qYj7, qYj7, #2
        VRSHR       qYj6, qYj6, #1
        
        VHADD       qYi5, qYj1, qYj7        ;// i5 = (j1+j7)/2
        VSUB        qYi6, qYj1, qYj7        ;// i6 = j1-j7
        VHADD       qYi3, qYj2, qYj6        ;// i3 = (j2+j6)/2
        VSUB        qYi2, qYj2, qYj6        ;// i2 = j2-j6
        VHADD       qYi7, qYj5, qYj3        ;// i7 = (j5+j3)/2
        VSUB        qYi4, qYj5, qYj3        ;// i4 = j5-j3

        VQDMULH     qYi2, qYi2, InvSqrt2    ;// i2/sqrt(2)
        ;// IStage 4,3 rows 0to1 x 1/2
        
        MOV         pTemp, #0x4             ;// ensure correct round
        VDUP        qScale1, pTemp           ;// of DC result
        VADD        qYi0, qYi0, qScale1
        
        VHADD       qYh0, qYi0, qYi1        ;// (i0+i1)/2
        VHSUB       qYh1, qYi0, qYi1        ;// (i0-i1)/2

        VHADD       qYh7, qYi5, qYi7        ;// (i5+i7)/4
        VSUB        qYh5, qYi5, qYi7        ;// (i5-i7)/2
        VSUB        qYh2, qYi2, qYi3        ;// h2, h3
        VQDMULH     qYh5, qYh5, InvSqrt2    ;// h5/sqrt(2)

        VMULL       qXt0, dYi4lo, C         ;// c*i4
        VMLAL       qXt0, dYi6lo, S         ;// c*i4+s*i6
        VMULL       qXt1, dYi4hi, C
        VMLAL       qXt1, dYi6hi, S
        VSHRN       dYh4lo, qXt0, #16       ;// h4
        VSHRN       dYh4hi, qXt1, #16
        
        VMULL       qXt0, dYi6lo, C         ;// c*i6
        VMLSL       qXt0, dYi4lo, S         ;// -s*i4 + c*h6
        VMULL       qXt1, dYi6hi, C
        VMLSL       qXt1, dYi4hi, S
        VSHRN       dYh6lo, qXt0, #16       ;// h6
        VSHRN       dYh6hi, qXt1, #16
        
        VSUB        qYg6, qYh6, qYh7
        VSUB        qYg5, qYh5, qYg6
        VSUB        qYg4, qYh4, qYg5
        
        ;// IStage 2 rows 0to3 x 1/2
        VHADD       qYg1, qYh1, qYh2        ;// (h1+h2)/2
        VHSUB       qYg2, qYh1, qYh2        ;// (h1-h2)/2
        VHADD       qYg0, qYh0, qYh3        ;// (h0+h3)/2
        VHSUB       qYg3, qYh0, qYh3        ;// (h0-h3)/2
        

        ;// IStage 1 all rows
        VHADD        qYf3, qYg3, qYg4        
        VHSUB        qYf4, qYg3, qYg4        
        VHADD        qYf2, qYg2, qYg5        
        VHSUB        qYf5, qYg2, qYg5        
        VHADD        qYf1, qYg1, qYg6
        VHSUB        qYf6, qYg1, qYg6        
        VHADD        qYf0, qYg0, qYg7
        VHSUB        qYf7, qYg0, qYg7      

YTR0            EQU Src0
YTR1            EQU Src4
YTR2            EQU Src1
YTR3            EQU Src2
YTR4            EQU Src7
YTR5            EQU Src5
YTR6            EQU Tmp
YTR7            EQU Src6
YTRt            EQU Src3

qC0             QN  YTR0.S32                ;// for YTRpose
qC1             QN  YTR1.S32
qC2             QN  YTR2.S32
qC3             QN  YTR3.S32
qC4             QN  YTR4.S32
qC5             QN  YTR5.S32
qC6             QN  YTR6.S32
qC7             QN  YTR7.S32

dD0             DN  YTR0*2+1                ;// for using VSWP
dD1             DN  YTR1*2+1
dD2             DN  YTR2*2+1
dD3             DN  YTR3*2+1
dD4             DN  YTR4*2
dD5             DN  YTR5*2
dD6             DN  YTR6*2
dD7             DN  YTR7*2
          
        VTRN        qYf0, qYf1
        VTRN        qYf2, qYf3
        VTRN        qYf4, qYf5
        VTRN        qYf6, qYf7
        VTRN        qC0, qC2
        VTRN        qC1, qC3
        VTRN        qC4, qC6
        VTRN        qC5, qC7        
        VSWP        dD0, dD4
        VSWP        dD1, dD5
        VSWP        dD2, dD6
        VSWP        dD3, dD7

        
dYf0U8          DN YTR0*2.U8
dYf1U8          DN YTR1*2.U8
dYf2U8          DN YTR2*2.U8
dYf3U8          DN YTR3*2.U8
dYf4U8          DN YTR4*2.U8
dYf5U8          DN YTR5*2.U8
dYf6U8          DN YTR6*2.U8
dYf7U8          DN YTR7*2.U8
        
        ;//
        ;// Do saturation if outsize is other than S16
        ;//
        
        IF ("$outsize"="u8")
            ;// Output range [0-255]
            VQMOVN            dYf0U8, qYf0
            VQMOVN            dYf1U8, qYf1
            VQMOVN            dYf2U8, qYf2
            VQMOVN            dYf3U8, qYf3
            VQMOVN            dYf4U8, qYf4
            VQMOVN            dYf5U8, qYf5
            VQMOVN            dYf6U8, qYf6
            VQMOVN            dYf7U8, qYf7
        ENDIF
        
        IF ("$outsize"="s9")
            ;// Output range [-256 to +255]
            VQSHL            qYf0, qYf0, #16-9
            VQSHL            qYf1, qYf1, #16-9
            VQSHL            qYf2, qYf2, #16-9
            VQSHL            qYf3, qYf3, #16-9
            VQSHL            qYf4, qYf4, #16-9
            VQSHL            qYf5, qYf5, #16-9
            VQSHL            qYf6, qYf6, #16-9
            VQSHL            qYf7, qYf7, #16-9
            
            VSHR             qYf0, qYf0, #16-9
            VSHR             qYf1, qYf1, #16-9
            VSHR             qYf2, qYf2, #16-9
            VSHR             qYf3, qYf3, #16-9
            VSHR             qYf4, qYf4, #16-9
            VSHR             qYf5, qYf5, #16-9
            VSHR             qYf6, qYf6, #16-9
            VSHR             qYf7, qYf7, #16-9
        ENDIF

        ;// Store output depending on the Stride size
        IF "$stride"="s"
            VST1        qYf0, [pDest @64], Stride
            VST1        qYf1, [pDest @64], Stride
            VST1        qYf2, [pDest @64], Stride
            VST1        qYf3, [pDest @64], Stride
            VST1        qYf4, [pDest @64], Stride
            VST1        qYf5, [pDest @64], Stride
            VST1        qYf6, [pDest @64], Stride
            VST1        qYf7, [pDest @64]            
        ELSE
            IF ("$outsize"="u8")
                VST1        dYf0U8, [pDest @64], #8
                VST1        dYf1U8, [pDest @64], #8
                VST1        dYf2U8, [pDest @64], #8
                VST1        dYf3U8, [pDest @64], #8
                VST1        dYf4U8, [pDest @64], #8
                VST1        dYf5U8, [pDest @64], #8
                VST1        dYf6U8, [pDest @64], #8
                VST1        dYf7U8, [pDest @64]
            ELSE
                ;// ("$outsize"="s9") or ("$outsize"="s16")
                VST1        qYf0, [pDest @64], #16
                VST1        qYf1, [pDest @64], #16
                VST1        qYf2, [pDest @64], #16
                VST1        qYf3, [pDest @64], #16
                VST1        qYf4, [pDest @64], #16
                VST1        qYf5, [pDest @64], #16
                VST1        qYf6, [pDest @64], #16
                VST1        qYf7, [pDest @64]
            ENDIF
        
        ENDIF



        ENDIF ;// CortexA8



        MEND        

        ;// Scale TWO input rows with TWO rows of 16 bit scale values
        ;//
        ;// This macro is used by M_IDCT_PRESCALE16 to pre-scale one row
        ;// input (Eight input values) with one row of scale values. Also 
        ;// Loads next scale values from pScale, if $LastRow flag is not set.
        ;//
        ;// Input Registers:
        ;//
        ;// $dAlo           - Input D register with first four S16 values of row n
        ;// $dAhi           - Input D register with next four S16 values of row n
        ;// $dBlo           - Input D register with first four S16 values of row n+1
        ;// $dBhi           - Input D register with next four S16 values of row n+1
        ;// pScale          - Pointer to next row of scale values
        ;// qT0lo           - Temporary scratch register
        ;// qT0hi           - Temporary scratch register
        ;// qT1lo           - Temporary scratch register
        ;// qT1hi           - Temporary scratch register
        ;// dScale1lo       - Scale value of row n
        ;// dScale1hi       - Scale value of row n
        ;// dScale2lo       - Scale value of row n+1
        ;// dScale2hi       - Scale value of row n+1
        ;//
        ;// Input Flag
        ;//
        ;// $LastRow        - Flag to indicate whether current row is last row
        ;//
        ;// Output Registers:
        ;//
        ;// $dAlo           - Scaled output values (first four S16 of row n)
        ;// $dAhi           - Scaled output values (next four S16 of row n)
        ;// $dBlo           - Scaled output values (first four S16 of row n+1)
        ;// $dBhi           - Scaled output values (next four S16 of row n+1)
        ;// qScale1         - Scale values for next row
        ;// qScale2         - Scale values for next row+1
        ;// pScale          - Pointer to next row of scale values
        ;//
        MACRO
        M_IDCT_SCALE16 $dAlo, $dAhi, $dBlo, $dBhi, $LastRow
        VMULL       qT0lo, $dAlo, dScale1lo
        VMULL       qT0hi, $dAhi, dScale1hi
        VMULL       qT1lo, $dBlo, dScale2lo
        VMULL       qT1hi, $dBhi, dScale2hi
        IF "$LastRow"="0"
            VLD1        qScale1, [pScale], #16  ;// Load scale for row n+1
            VLD1        qScale2, [pScale], #16  ;// Load scale for row n+2
        ENDIF
        VQRSHRN       $dAlo, qT0lo, #12        
        VQRSHRN       $dAhi, qT0hi, #12        
        VQRSHRN       $dBlo, qT1lo, #12        
        VQRSHRN       $dBhi, qT1hi, #12        
        MEND

        ;// Scale 8x8 block input values with 16 bit scale values
        ;//
        ;// This macro is used to pre-scale block of 8x8 input.
        ;// This also do the Ist stage transformations of IDCT.
        ;//
        ;// Input Registers:
        ;//
        ;// dXjnlo          - n th input D register with first four S16 values
        ;// dXjnhi          - n th input D register with next four S16 values
        ;// qXjn            - n th input Q register with eight S16 values
        ;// pScale          - Pointer to scale values
        ;//
        ;// Output Registers:
        ;//
        ;// qXin            - n th output Q register with eight S16 output values of 1st stage
        ;//
        MACRO
        M_IDCT_PRESCALE16
        VLD1        qScale1, [pScale], #16      ;// Load Pre scale for row 0
        VLD1        qScale2, [pScale], #16      ;// Load Pre scale for row 0
        M_IDCT_SCALE16 dXj0lo, dXj0hi, dXj1lo, dXj1hi, 0        ;// Pre scale row 0 & 1
        M_IDCT_SCALE16 dXj2lo, dXj2hi, dXj3lo, dXj3hi, 0        
        M_IDCT_SCALE16 dXj4lo, dXj4hi, dXj5lo, dXj5hi, 0        
        M_IDCT_SCALE16 dXj6lo, dXj6hi, dXj7lo, dXj7hi, 1        
        VHADD       qXi5, qXj1, qXj7            ;// (j1+j7)/2
        VSUB        qXi6, qXj1, qXj7            ;// j1-j7
        LDR         pSrc, =armCOMM_IDCTCoef ;// Address of DCT inverse AAN constants
        VHADD       qXi3, qXj2, qXj6            ;// (j2+j6)/2
        VSUB        qXi2, qXj2, qXj6            ;// j2-j6
        VLDR        dCoefs, [pSrc]              ;// Load DCT inverse AAN constants
        VHADD       qXi7, qXj5, qXj3            ;// (j5+j3)/2
        VSUB        qXi4, qXj5, qXj3            ;// j5-j3
        MEND    
        
        
        ;// Scale 8x8 block input values with 32 bit scale values
        ;//
        ;// This macro is used to pre-scale block of 8x8 input.
        ;// This also do the Ist stage transformations of IDCT.
        ;//
        ;// Input Registers:
        ;//
        ;// dXjnlo          - n th input D register with first four S16 values
        ;// dXjnhi          - n th input D register with next four S16 values
        ;// qXjn            - n th input Q register with eight S16 values
        ;// pScale          - Pointer to 32bit scale values in Q23 format
        ;//
        ;// Output Registers:
        ;//
        ;// dXinlo          - n th output D register with first four S16 output values of 1st stage
        ;// dXinhi          - n th output D register with next four S16 output values of 1st stage
        ;//
        MACRO
        M_IDCT_PRESCALE32
qScale0lo       QN 0.S32
qScale0hi       QN 1.S32
qScale1lo       QN 2.S32
qScale1hi       QN 3.S32
qScale2lo       QN qScale1lo
qScale2hi       QN qScale1hi
qScale3lo       QN qScale1lo
qScale3hi       QN qScale1hi
qScale4lo       QN qScale1lo
qScale4hi       QN qScale1hi
qScale5lo       QN qScale0lo
qScale5hi       QN qScale0hi
qScale6lo       QN qScale0lo
qScale6hi       QN qScale0hi
qScale7lo       QN qScale0lo
qScale7hi       QN qScale0hi

qSrc0lo         QN 4.S32
qSrc0hi         QN 5.S32
qSrc1lo         QN 6.S32
qSrc1hi         QN Src4.S32
qSrc2lo         QN qSrc0lo
qSrc2hi         QN qSrc0hi
qSrc3lo         QN qSrc0lo
qSrc3hi         QN qSrc0hi
qSrc4lo         QN qSrc0lo
qSrc4hi         QN qSrc0hi
qSrc5lo         QN qSrc1lo
qSrc5hi         QN qSrc1hi
qSrc6lo         QN qSrc1lo
qSrc6hi         QN qSrc1hi
qSrc7lo         QN qSrc0lo
qSrc7hi         QN qSrc0hi

qRes17lo        QN qScale0lo
qRes17hi        QN qScale0hi
qRes26lo        QN qScale0lo
qRes26hi        QN qScale0hi
qRes53lo        QN qScale0lo
qRes53hi        QN qScale0hi

            ADD         pTemp, pScale, #4*8*7           ;// Address of  pScale[7]
            
            ;// Row 0
            VLD1        {qScale0lo, qScale0hi}, [pScale]!
            VSHLL       qSrc0lo, dXj0lo, #(12-1)
            VSHLL       qSrc0hi, dXj0hi, #(12-1)            
            VLD1        {qScale1lo, qScale1hi}, [pScale]!
            VQRDMULH    qSrc0lo, qScale0lo, qSrc0lo
            VQRDMULH    qSrc0hi, qScale0hi, qSrc0hi
            VLD1        {qScale7lo, qScale7hi}, [pTemp]!
            VSHLL       qSrc1lo, dXj1lo, #(12-1)
            VSHLL       qSrc1hi, dXj1hi, #(12-1)            
            VMOVN       dXi0lo, qSrc0lo                 ;// Output i0
            VMOVN       dXi0hi, qSrc0hi
            VSHLL       qSrc7lo, dXj7lo, #(12-1)
            VSHLL       qSrc7hi, dXj7hi, #(12-1)
            SUB         pTemp, pTemp, #((16*2)+(4*8*1))
            VQRDMULH    qSrc1lo, qScale1lo, qSrc1lo
            VQRDMULH    qSrc1hi, qScale1hi, qSrc1hi
            VQRDMULH    qSrc7lo, qScale7lo, qSrc7lo
            VQRDMULH    qSrc7hi, qScale7hi, qSrc7hi
            VLD1        {qScale2lo, qScale2hi}, [pScale]!

            ;// Row 1 & 7
            VHADD       qRes17lo, qSrc1lo, qSrc7lo      ;// (j1+j7)/2
            VHADD       qRes17hi, qSrc1hi, qSrc7hi      ;// (j1+j7)/2
            VMOVN       dXi5lo, qRes17lo                ;// Output i5
            VMOVN       dXi5hi, qRes17hi              
            VSUB        qRes17lo, qSrc1lo, qSrc7lo      ;// j1-j7
            VSUB        qRes17hi, qSrc1hi, qSrc7hi      ;// j1-j7
            VMOVN       dXi6lo, qRes17lo                ;// Output i6
            VMOVN       dXi6hi, qRes17hi      
            VSHLL       qSrc2lo, dXj2lo, #(12-1)
            VSHLL       qSrc2hi, dXj2hi, #(12-1)
            VLD1        {qScale6lo, qScale6hi}, [pTemp]!
            VSHLL       qSrc6lo, dXj6lo, #(12-1)
            VSHLL       qSrc6hi, dXj6hi, #(12-1)
            SUB         pTemp, pTemp, #((16*2)+(4*8*1))
            VQRDMULH    qSrc2lo, qScale2lo, qSrc2lo
            VQRDMULH    qSrc2hi, qScale2hi, qSrc2hi
            VQRDMULH    qSrc6lo, qScale6lo, qSrc6lo
            VQRDMULH    qSrc6hi, qScale6hi, qSrc6hi
            VLD1        {qScale3lo, qScale3hi}, [pScale]!

            ;// Row 2 & 6
            VHADD       qRes26lo, qSrc2lo, qSrc6lo      ;// (j2+j6)/2
            VHADD       qRes26hi, qSrc2hi, qSrc6hi      ;// (j2+j6)/2
            VMOVN       dXi3lo, qRes26lo                ;// Output i3
            VMOVN       dXi3hi, qRes26hi              
            VSUB        qRes26lo, qSrc2lo, qSrc6lo      ;// j2-j6
            VSUB        qRes26hi, qSrc2hi, qSrc6hi      ;// j2-j6
            VMOVN       dXi2lo, qRes26lo                ;// Output i2
            VMOVN       dXi2hi, qRes26hi      
            VSHLL       qSrc3lo, dXj3lo, #(12-1)
            VSHLL       qSrc3hi, dXj3hi, #(12-1)
            VLD1        {qScale5lo, qScale5hi}, [pTemp]!
            VSHLL       qSrc5lo, dXj5lo, #(12-1)
            VSHLL       qSrc5hi, dXj5hi, #(12-1)
            VQRDMULH    qSrc3lo, qScale3lo, qSrc3lo
            VQRDMULH    qSrc3hi, qScale3hi, qSrc3hi
            VQRDMULH    qSrc5lo, qScale5lo, qSrc5lo
            VQRDMULH    qSrc5hi, qScale5hi, qSrc5hi
            
            ;// Row 3 & 5
            VHADD       qRes53lo, qSrc5lo, qSrc3lo      ;// (j5+j3)/2
            VHADD       qRes53hi, qSrc5hi, qSrc3hi      ;// (j5+j3)/2
            SUB         pSrc, pSrc, #16*2*2
            VMOVN       dXi7lo, qRes53lo                ;// Output i7
            VMOVN       dXi7hi, qRes53hi              
            VSUB        qRes53lo, qSrc5lo, qSrc3lo      ;// j5-j3
            VSUB        qRes53hi, qSrc5hi, qSrc3hi      ;// j5-j3
            VLD1        qXj4, [pSrc @64]
            VMOVN       dXi4lo, qRes53lo                ;// Output i4
            VMOVN       dXi4hi, qRes53hi                              
            VSHLL       qSrc4lo, dXj4lo, #(12-1)
            VSHLL       qSrc4hi, dXj4hi, #(12-1)
            VLD1        {qScale4lo, qScale4hi}, [pScale]            
            LDR         pSrc, =armCOMM_IDCTCoef     ;// Address of DCT inverse AAN constants
            VQRDMULH    qSrc4lo, qScale4lo, qSrc4lo
            VQRDMULH    qSrc4hi, qScale4hi, qSrc4hi
            VLDR        dCoefs, [pSrc]                  ;// Load DCT inverse AAN constants
            ;// Row 4
            VMOVN       dXi1lo, qSrc4lo                 ;// Output i1
            VMOVN       dXi1hi, qSrc4hi              
        
        MEND
                                                
        END
