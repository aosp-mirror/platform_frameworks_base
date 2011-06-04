;//
;// 
;// File Name:  armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe_s.s
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
        
        EXPORT armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe

        M_VARIANTS CortexA8

    IF CortexA8

        M_START armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe, r11

;// Declare input registers
pSrc            RN 0
srcStep         RN 1
pDst            RN 2
dstStep         RN 3

;// Declare Neon registers
dCoeff5         DN 30.S16
dCoeff20        DN 31.S16
qCoeff5         QN 14.S32
qCoeff20        QN 15.S32
        
qSrc01          QN 0.U8
dSrc0           DN 0.U8
dSrc1           DN 1.U8                
                
dSrcb           DN 4.U8
dSrcc           DN 2.U8
dSrcd           DN 3.U8
dSrce           DN 5.U8
dSrcf           DN 1.U8

qSrcb           QN 2.S16
qSrcc           QN 1.S16
dSrcB           DN 4.S16
dSrcC           DN 2.S16

qRes0           QN 5.S16
qRes1           QN 6.S16
qRes2           QN 7.S16
qRes3           QN 8.S16
qRes4           QN 9.S16
qRes5           QN 10.S16
qRes6           QN 11.S16
qRes7           QN 12.S16
qRes8           QN 13.S16
    
dRes0           DN 10.S16
dRes1           DN 12.S16
dRes2           DN 14.S16
dRes3           DN 16.S16
dRes4           DN 18.S16
dRes5           DN 20.S16
dRes6           DN 22.S16
dRes7           DN 24.S16
dRes8           DN 26.S16
    
qAcc01          QN 5.S32
qAcc23          QN 6.S32
qAcc45          QN 2.S32
qAcc67          QN 3.S32
qSumBE          QN 0.S32
qSumCD          QN 1.S32

dTempAcc0       DN 0.U16
dTempAcc1       DN 2.U16
dTempAcc2       DN 4.U16
dTempAcc3       DN 6.U16

qTAcc0          QN 0.U16
qTAcc1          QN 1.U16
qTAcc2          QN 2.U16
qTAcc3          QN 3.U16

dAcc0           DN 0.U8
dAcc1           DN 2.U8
dAcc2           DN 4.U8
dAcc3           DN 6.U8

dTmp0           DN 8.S16
dTmp1           DN 9.S16
qTmp0           QN 4.S32

        VLD1        qSrc01, [pSrc], srcStep     ;// [a0 a1 a2 a3 ..]
        VMOV        dCoeff20, #20
        VMOV        dCoeff5, #5

        ;// Row0
        VEXT        dSrcb, dSrc0, dSrc1, #1     ;// [b0 b1 b2 b3 ..]
        VEXT        dSrcc, dSrc0, dSrc1, #2
        VEXT        dSrcd, dSrc0, dSrc1, #3
        VEXT        dSrce, dSrc0, dSrc1, #4
        VEXT        dSrcf, dSrc0, dSrc1, #5     ;// [f0 f1 f2 f3 ..]
        VADDL       qSrcc, dSrcc, dSrcd         ;// c+d                
        VADDL       qSrcb, dSrcb, dSrce         ;// b+e        
        VADDL       qRes0, dSrc0, dSrcf         ;// Acc=a+f
        VLD1        qSrc01, [pSrc], srcStep     ;// [a0 a1 a2 a3 ..]
        VMLA        dRes0, dSrcC, dCoeff20      ;// Acc += 20*(c+d)
;        VMLS        dRes0, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)
        VMUL        dTmp0, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)
        
        ;// Row1
        VEXT        dSrcb, dSrc0, dSrc1, #1     ;// [b0 b1 b2 b3 ..]
        VEXT        dSrcc, dSrc0, dSrc1, #2
        VEXT        dSrcd, dSrc0, dSrc1, #3
        VEXT        dSrce, dSrc0, dSrc1, #4
        VEXT        dSrcf, dSrc0, dSrc1, #5     ;// [f0 f1 f2 f3 ..]
        VADDL       qSrcc, dSrcc, dSrcd         ;// c+d                
        VADDL       qSrcb, dSrcb, dSrce         ;// b+e        
        VADDL       qRes1, dSrc0, dSrcf         ;// Acc=a+f
        VLD1        qSrc01, [pSrc], srcStep     ;// [a0 a1 a2 a3 ..]
        
        VSUB        dRes0, dRes0, dTmp0 ;// TeRi
        
        VMLA        dRes1, dSrcC, dCoeff20      ;// Acc += 20*(c+d)
;        VMLS        dRes1, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)
        VMUL        dTmp0, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)

        ;// Row2
        VEXT        dSrcb, dSrc0, dSrc1, #1     ;// [b0 b1 b2 b3 ..]
        VEXT        dSrcc, dSrc0, dSrc1, #2
        VEXT        dSrcd, dSrc0, dSrc1, #3
        VEXT        dSrce, dSrc0, dSrc1, #4
        VEXT        dSrcf, dSrc0, dSrc1, #5     ;// [f0 f1 f2 f3 ..]
        VADDL       qSrcc, dSrcc, dSrcd         ;// c+d                
        VADDL       qSrcb, dSrcb, dSrce         ;// b+e        
        VADDL       qRes2, dSrc0, dSrcf         ;// Acc=a+f
        VLD1        qSrc01, [pSrc], srcStep     ;// [a0 a1 a2 a3 ..]
        
        VSUB        dRes1, dRes1, dTmp0

        VMLA        dRes2, dSrcC, dCoeff20      ;// Acc += 20*(c+d)
;        VMLS        dRes2, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)
        VMUL        dTmp0, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)

        ;// Row3
        VEXT        dSrcb, dSrc0, dSrc1, #1     ;// [b0 b1 b2 b3 ..]
        VEXT        dSrcc, dSrc0, dSrc1, #2
        VEXT        dSrcd, dSrc0, dSrc1, #3
        VEXT        dSrce, dSrc0, dSrc1, #4
        VEXT        dSrcf, dSrc0, dSrc1, #5     ;// [f0 f1 f2 f3 ..]
        VADDL       qSrcc, dSrcc, dSrcd         ;// c+d                
        VADDL       qSrcb, dSrcb, dSrce         ;// b+e        
        VADDL       qRes3, dSrc0, dSrcf         ;// Acc=a+f
        VLD1        qSrc01, [pSrc], srcStep     ;// [a0 a1 a2 a3 ..]

        VSUB        dRes2, dRes2, dTmp0

        VMLA        dRes3, dSrcC, dCoeff20      ;// Acc += 20*(c+d)
;        VMLS        dRes3, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)
        VMUL        dTmp0, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)

        ;// Row4
        VEXT        dSrcb, dSrc0, dSrc1, #1     ;// [b0 b1 b2 b3 ..]
        VEXT        dSrcc, dSrc0, dSrc1, #2
        VEXT        dSrcd, dSrc0, dSrc1, #3
        VEXT        dSrce, dSrc0, dSrc1, #4
        VEXT        dSrcf, dSrc0, dSrc1, #5     ;// [f0 f1 f2 f3 ..]
        VADDL       qSrcc, dSrcc, dSrcd         ;// c+d                
        VADDL       qSrcb, dSrcb, dSrce         ;// b+e        
        VADDL       qRes4, dSrc0, dSrcf         ;// Acc=a+f
        VLD1        qSrc01, [pSrc], srcStep     ;// [a0 a1 a2 a3 ..]

        VSUB        dRes3, dRes3, dTmp0

        VMLA        dRes4, dSrcC, dCoeff20      ;// Acc += 20*(c+d)
;        VMLS        dRes4, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)
        VMUL        dTmp0, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)

        ;// Row5
        VEXT        dSrcb, dSrc0, dSrc1, #1     ;// [b0 b1 b2 b3 ..]
        VEXT        dSrcc, dSrc0, dSrc1, #2
        VEXT        dSrcd, dSrc0, dSrc1, #3
        VEXT        dSrce, dSrc0, dSrc1, #4
        VEXT        dSrcf, dSrc0, dSrc1, #5     ;// [f0 f1 f2 f3 ..]
        VADDL       qSrcc, dSrcc, dSrcd         ;// c+d                
        VADDL       qSrcb, dSrcb, dSrce         ;// b+e        
        VADDL       qRes5, dSrc0, dSrcf         ;// Acc=a+f
        VLD1        qSrc01, [pSrc], srcStep     ;// [a0 a1 a2 a3 ..]

        VSUB        dRes4, dRes4, dTmp0

        VMLA        dRes5, dSrcC, dCoeff20      ;// Acc += 20*(c+d)
;        VMLS        dRes5, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)
        VMUL        dTmp0, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)

        ;// Row6
        VEXT        dSrcb, dSrc0, dSrc1, #1     ;// [b0 b1 b2 b3 ..]
        VEXT        dSrcc, dSrc0, dSrc1, #2
        VEXT        dSrcd, dSrc0, dSrc1, #3
        VEXT        dSrce, dSrc0, dSrc1, #4
        VEXT        dSrcf, dSrc0, dSrc1, #5     ;// [f0 f1 f2 f3 ..]
        VADDL       qSrcc, dSrcc, dSrcd         ;// c+d                
        VADDL       qSrcb, dSrcb, dSrce         ;// b+e        
        VADDL       qRes6, dSrc0, dSrcf         ;// Acc=a+f
        VLD1        qSrc01, [pSrc], srcStep     ;// [a0 a1 a2 a3 ..]

        VSUB        dRes5, dRes5, dTmp0

        VMLA        dRes6, dSrcC, dCoeff20      ;// Acc += 20*(c+d)
;        VMLS        dRes6, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)
        VMUL        dTmp0, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)

        ;// Row7
        VEXT        dSrcb, dSrc0, dSrc1, #1     ;// [b0 b1 b2 b3 ..]
        VEXT        dSrcc, dSrc0, dSrc1, #2
        VEXT        dSrcd, dSrc0, dSrc1, #3
        VEXT        dSrce, dSrc0, dSrc1, #4
        VEXT        dSrcf, dSrc0, dSrc1, #5     ;// [f0 f1 f2 f3 ..]
        VADDL       qSrcc, dSrcc, dSrcd         ;// c+d                
        VADDL       qSrcb, dSrcb, dSrce         ;// b+e        
        VADDL       qRes7, dSrc0, dSrcf         ;// Acc=a+f
        VLD1        qSrc01, [pSrc], srcStep     ;// [a0 a1 a2 a3 ..]

        VSUB        dRes6, dRes6, dTmp0

        VMLA        dRes7, dSrcC, dCoeff20      ;// Acc += 20*(c+d)
;        VMLS        dRes7, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)
        VMUL        dTmp0, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)

        ;// Row8
        VEXT        dSrcb, dSrc0, dSrc1, #1     ;// [b0 b1 b2 b3 ..]
        VEXT        dSrcc, dSrc0, dSrc1, #2
        VEXT        dSrcd, dSrc0, dSrc1, #3
        VEXT        dSrce, dSrc0, dSrc1, #4
        VEXT        dSrcf, dSrc0, dSrc1, #5     ;// [f0 f1 f2 f3 ..]
        VADDL       qSrcc, dSrcc, dSrcd         ;// c+d                
        VADDL       qSrcb, dSrcb, dSrce         ;// b+e        
        VADDL       qRes8, dSrc0, dSrcf         ;// Acc=a+f

        VSUB        dRes7, dRes7, dTmp0

        VMLA        dRes8, dSrcC, dCoeff20      ;// Acc += 20*(c+d)
;        VMLS        dRes8, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)
        VMUL        dTmp0, dSrcB, dCoeff5       ;// Acc -= 5*(b+e)

        VMOV        qCoeff20, #20
        VMOV        qCoeff5, #5

        ;// Col0
        VADDL       qAcc01, dRes0, dRes5        ;// Acc = a+f
        VADDL       qSumCD, dRes2, dRes3        ;// c+d
        VADDL       qSumBE, dRes1, dRes4        ;// b+e

        VSUB        dRes8, dRes8, dTmp0

        VMLA        qAcc01, qSumCD, qCoeff20    ;// Acc += 20*(c+d)
;        VMLS        qAcc01, qSumBE, qCoeff5     ;// Acc -= 20*(b+e)        
        VMUL        qTmp0, qSumBE, qCoeff5     ;// Acc -= 20*(b+e)        

        ;// Col1
        VADDL       qAcc23, dRes1, dRes6        ;// Acc = a+f
        VADDL       qSumCD, dRes3, dRes4        ;// c+d
        VADDL       qSumBE, dRes2, dRes5        ;// b+e
        VMLA        qAcc23, qSumCD, qCoeff20    ;// Acc += 20*(c+d)

        VSUB        qAcc01, qAcc01, qTmp0

;        VMLS        qAcc23, qSumBE, qCoeff5     ;// Acc -= 20*(b+e)        
        VMUL        qTmp0, qSumBE, qCoeff5     ;// Acc -= 20*(b+e)        

        ;// Col2
        VADDL       qAcc45, dRes2, dRes7        ;// Acc = a+f
        VADDL       qSumCD, dRes4, dRes5        ;// c+d
        VADDL       qSumBE, dRes3, dRes6        ;// b+e
        VMLA        qAcc45, qSumCD, qCoeff20    ;// Acc += 20*(c+d)

        VSUB        qAcc23, qAcc23, qTmp0

;        VMLS        qAcc45, qSumBE, qCoeff5     ;// Acc -= 20*(b+e)        
        VMUL        qTmp0, qSumBE, qCoeff5     ;// Acc -= 20*(b+e)        
        
        ;// Col3
        VADDL       qAcc67, dRes3, dRes8        ;// Acc = a+f
        VADDL       qSumCD, dRes5, dRes6        ;// c+d
        VADDL       qSumBE, dRes4, dRes7        ;// b+e
        VMLA        qAcc67, qSumCD, qCoeff20    ;// Acc += 20*(c+d)

        VSUB        qAcc45, qAcc45, qTmp0

        VMLS        qAcc67, qSumBE, qCoeff5     ;// Acc -= 20*(b+e)        

        VQRSHRUN    dTempAcc0, qAcc01, #10
        VQRSHRUN    dTempAcc1, qAcc23, #10
        VQRSHRUN    dTempAcc2, qAcc45, #10
        VQRSHRUN    dTempAcc3, qAcc67, #10
        
        VQMOVN      dAcc0, qTAcc0
        VQMOVN      dAcc1, qTAcc1
        VQMOVN      dAcc2, qTAcc2
        VQMOVN      dAcc3, qTAcc3
                
        M_END
    
    ENDIF


    
    END
    
