;//
;// 
;// File Name:  armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//

        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
       
        M_VARIANTS CortexA8
       
        EXPORT armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe

    IF CortexA8
        
        M_START armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe, r11

;// Declare input registers
pSrc            RN 0
srcStep         RN 1
pDst            RN 2
dstStep         RN 3

Temp            RN 12

;// Declare Neon registers
dCoeff5         DN 30.S16
dCoeff20        DN 31.S16

dSrc0           DN 7.U8
dSrc1           DN 8.U8
dSrc2           DN 9.U8
dSrc3           DN 10.U8
dSrc4           DN 11.U8
dSrc5           DN 12.U8
dSrc6           DN 13.U8
dSrc7           DN 14.U8
dSrc8           DN 15.U8

qSumBE01        QN 8.S16
qSumCD01        QN 9.S16
dSumBE0         DN 16.S16
dSumCD0         DN 18.S16

qAcc01          QN 0.S16
qAcc23          QN 1.S16
qAcc45          QN 2.S16
qAcc67          QN 3.S16

dRes0           DN 0.S16
dRes1           DN 2.S16
dRes2           DN 4.S16
dRes3           DN 6.S16

dAcc0           DN 0.U8
dAcc1           DN 2.U8
dAcc2           DN 4.U8
dAcc3           DN 6.U8        
        

dTmp0           DN 20.S16
dTmp1           DN 21.S16
dTmp2           DN 22.S16
dTmp3           DN 23.S16


        VLD1        dSrc0, [pSrc], srcStep     ;// [a0 a1 a2 a3 .. ] 
        ADD         Temp, pSrc, srcStep, LSL #2
        VLD1        dSrc1, [pSrc], srcStep     ;// [b0 b1 b2 b3 .. ]
        ;// One cycle stall
        VLD1        dSrc5, [Temp], srcStep        
        ;// One cycle stall
        VLD1        dSrc2, [pSrc], srcStep     ;// [c0 c1 c2 c3 .. ]
        VADDL       qAcc01, dSrc0, dSrc5       ;// Acc = a+f
        VLD1        dSrc3, [pSrc], srcStep
        ;// One cycle stall
        VLD1        dSrc6, [Temp], srcStep ;// TeRi
        
        VLD1        dSrc4, [pSrc], srcStep
        VLD1        dSrc7, [Temp], srcStep ;// TeRi
        VADDL       qSumBE01, dSrc1, dSrc4     ;// b+e
        VADDL       qSumCD01, dSrc2, dSrc3     ;// c+d        
        VLD1        dSrc8, [Temp], srcStep ;// TeRi
        VMLS        dRes0, dSumBE0, dCoeff5    ;// Acc -= 20*(b+e)        
;        VMLA        dRes0, dSumCD0, dCoeff20   ;// Acc += 20*(c+d)
        VMUL        dTmp0, dSumCD0, dCoeff20   ;// Acc += 20*(c+d)
        
;        VLD1        dSrc6, [Temp], srcStep
        VADDL       qSumBE01, dSrc2, dSrc5     ;// b+e
        VADDL       qSumCD01, dSrc3, dSrc4     ;// c+d
        VADDL       qAcc23, dSrc1, dSrc6       ;// Acc = a+f
        VMLS        dRes1, dSumBE0, dCoeff5    ;// Acc -= 20*(b+e)
;        VMLA        dRes1, dSumCD0, dCoeff20   ;// Acc += 20*(c+d)
        VMUL        dTmp1, dSumCD0, dCoeff20   ;// Acc += 20*(c+d)

;        VLD1        dSrc7, [Temp], srcStep
        VADDL       qSumBE01, dSrc3, dSrc6     ;// b+e
        VADDL       qSumCD01, dSrc4, dSrc5     ;// c+d
        VADDL       qAcc45, dSrc2, dSrc7       ;// Acc = a+f
        VMLS        dRes2, dSumBE0, dCoeff5    ;// Acc -= 20*(b+e)        
;        VMLA        dRes2, dSumCD0, dCoeff20   ;// Acc += 20*(c+d)
        VMUL        dTmp2, dSumCD0, dCoeff20   ;// Acc += 20*(c+d)

;        VLD1        dSrc8, [Temp], srcStep     ;// [i0 i1 i2 i3 .. ]        
        VADDL       qSumBE01, dSrc4, dSrc7     ;// b+e
        VADDL       qAcc67, dSrc3, dSrc8       ;// Acc = a+f
        VADDL       qSumCD01, dSrc5, dSrc6     ;// c+d
        VMLS        dRes3, dSumBE0, dCoeff5    ;// Acc -= 20*(b+e)        
        VADD        dRes0, dRes0, dTmp0
        VADD        dRes1, dRes1, dTmp1
        VADD        dRes2, dRes2, dTmp2
        VMLA        dRes3, dSumCD0, dCoeff20   ;// Acc += 20*(c+d)
;        VMUL        dTmp3, dSumCD0, dCoeff20   ;// Acc += 20*(c+d)
;        VADD        dRes3, dRes3, dTmp3

        VQRSHRUN    dAcc0, qAcc01, #5        
        VQRSHRUN    dAcc1, qAcc23, #5        
        VQRSHRUN    dAcc2, qAcc45, #5        
        VQRSHRUN    dAcc3, qAcc67, #5        

        M_END
    
    ENDIF

    
    
    END
    
