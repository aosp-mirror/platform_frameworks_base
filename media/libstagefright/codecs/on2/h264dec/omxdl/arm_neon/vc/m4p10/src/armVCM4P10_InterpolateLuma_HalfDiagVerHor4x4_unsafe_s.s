;//
;// 
;// File Name:  armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe_s.s
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

        EXPORT armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe

        M_VARIANTS CortexA8

    IF CortexA8
        M_START armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe, r11

;// Declare input registers
pSrc            RN 0
srcStep         RN 1
pDst            RN 2
dstStep         RN 3

;// Declare Neon registers
dTCoeff5        DN 30.U8
dTCoeff20       DN 31.U8
dCoeff5         DN 30.S16
dCoeff20        DN 31.S16

qSrcA01         QN 0.U8
qSrcB23         QN 1.U8
qSrcC45         QN 2.U8
qSrcD67         QN 3.U8
qSrcE89         QN 4.U8
qSrcF1011       QN 5.U8
qSrcG1213       QN 6.U8
qSrcH1415       QN 7.U8
qSrcI1617       QN 8.U8

dSrcA0          DN 0.U8
dSrcB2          DN 2.U8
dSrcC4          DN 4.U8
dSrcD6          DN 6.U8
dSrcE8          DN 8.U8
dSrcF10         DN 10.U8
dSrcG12         DN 12.U8
dSrcH14         DN 14.U8
dSrcI16         DN 16.U8

dSrcA1          DN 1.U8
dSrcB3          DN 3.U8
dSrcC5          DN 5.U8
dSrcD7          DN 7.U8
dSrcE9          DN 9.U8
dSrcF11         DN 11.U8
dSrcG13         DN 13.U8
dSrcH15         DN 15.U8
dSrcI17         DN 17.U8

qTempP01        QN 9.S16
qTempQ01        QN 10.S16
qTempR01        QN 11.S16
qTempS01        QN 12.S16

qTempP23        QN 0.S16
qTempQ23        QN 1.S16
qTempR23        QN 2.S16
qTempS23        QN 3.S16

dTempP0         DN 18.S16
dTempP1         DN 19.S16
dTempP2         DN 0.S16

dTempQ0         DN 20.S16
dTempQ1         DN 21.S16
dTempQ2         DN 2.S16

dTempR0         DN 22.S16
dTempR1         DN 23.S16
dTempR2         DN 4.S16

dTempS0         DN 24.S16
dTempS1         DN 25.S16
dTempS2         DN 6.S16
 
dTempB0         DN 26.S16
dTempC0         DN 27.S16
dTempD0         DN 28.S16
dTempF0         DN 29.S16

dTempAcc0       DN 0.U16
dTempAcc1       DN 2.U16
dTempAcc2       DN 4.U16
dTempAcc3       DN 6.U16

dAcc0           DN 0.U8
dAcc1           DN 2.U8
dAcc2           DN 4.U8
dAcc3           DN 6.U8

qAcc0           QN 0.S32
qAcc1           QN 1.S32
qAcc2           QN 2.S32
qAcc3           QN 3.S32

qTAcc0          QN 0.U16
qTAcc1          QN 1.U16
qTAcc2          QN 2.U16
qTAcc3          QN 3.U16                

qTmp            QN 4.S16
dTmp            DN 8.S16

        VLD1        qSrcA01, [pSrc], srcStep                 ;// [a0 a1 a2 a3 .. a15]   
        ADD         r12, pSrc, srcStep, LSL #2
        VMOV        dTCoeff5, #5
        VMOV        dTCoeff20, #20
        VLD1        qSrcF1011, [r12], srcStep
        VLD1        qSrcB23, [pSrc], srcStep                 ;// [b0 b1 b2 b3 .. b15]
        
        VLD1        qSrcG1213, [r12], srcStep
        VADDL       qTempP01, dSrcA0, dSrcF10           
        VLD1        qSrcC45, [pSrc], srcStep                 ;// [c0 c1 c2 c3 .. c15]
        VADDL       qTempP23, dSrcA1, dSrcF11   
        VLD1        qSrcD67, [pSrc], srcStep
        VADDL       qTempQ01, dSrcB2, dSrcG12                   
        VLD1        qSrcE89, [pSrc], srcStep
        
        ;//t0
        VMLAL       qTempP01, dSrcC4, dTCoeff20
        
        VLD1        qSrcH1415, [r12], srcStep

        VMLAL       qTempP23, dSrcC5, dTCoeff20
        
        VLD1        qSrcI1617, [r12], srcStep                 ;// [i0 i1 i2 i3 .. ]
        
        VMLAL       qTempP01, dSrcD6, dTCoeff20
        VMLAL       qTempQ01, dSrcD6, dTCoeff20
        VMLSL       qTempP23, dSrcB3, dTCoeff5
        
        VADDL       qTempR01, dSrcC4, dSrcH14   
        
        VMLSL       qTempP01, dSrcB2, dTCoeff5

        VADDL       qTempQ23, dSrcB3, dSrcG13   

        VMLAL       qTempP23, dSrcD7, dTCoeff20
        VMLAL       qTempQ01, dSrcE8, dTCoeff20

        VMLSL       qTempP01, dSrcE8, dTCoeff5
        VMLAL       qTempQ23, dSrcD7, dTCoeff20

        VMLSL       qTempP23, dSrcE9, dTCoeff5

        ;//t1

        VMLAL       qTempR01, dSrcE8, dTCoeff20
        VMLSL       qTempQ01, dSrcC4, dTCoeff5
        VMLSL       qTempQ23, dSrcC5, dTCoeff5
        VADDL       qTempR23, dSrcC5, dSrcH15   

        VMLAL       qTempR01, dSrcF10, dTCoeff20
        VMLSL       qTempQ01, dSrcF10, dTCoeff5
        VMLAL       qTempQ23, dSrcE9, dTCoeff20
        VMLAL       qTempR23, dSrcE9, dTCoeff20
        VADDL       qTempS01, dSrcD6, dSrcI16   


        VMLSL       qTempR01, dSrcD6, dTCoeff5
        VMLSL       qTempQ23, dSrcF11, dTCoeff5
        VMLSL       qTempR23, dSrcD7, dTCoeff5

        ;//t2
        VADDL       qTempS23, dSrcD7, dSrcI17   
        VMLAL       qTempS01, dSrcF10, dTCoeff20
        VMLSL       qTempR01, dSrcG12, dTCoeff5
        VMLSL       qTempR23, dSrcG13, dTCoeff5

        VMLAL       qTempS23, dSrcF11, dTCoeff20
        VMLAL       qTempS01, dSrcG12, dTCoeff20
        VEXT        dTempB0, dTempP0, dTempP1, #1
        VMLAL       qTempR23, dSrcF11, dTCoeff20


        ;//t3
        VMLAL       qTempS23, dSrcG13, dTCoeff20
        VMLSL       qTempS01, dSrcE8, dTCoeff5
        VEXT        dTempC0, dTempP0, dTempP1, #2
        VMOV        dCoeff20, #20
        VMLSL       qTempS23, dSrcE9, dTCoeff5
        VMLSL       qTempS01, dSrcH14, dTCoeff5
        VEXT        dTempF0, dTempP1, dTempP2, #1
        VEXT        dTempD0, dTempP0, dTempP1, #3
        VMLSL       qTempS23, dSrcH15, dTCoeff5
        
        VADDL       qAcc0, dTempP0, dTempF0
        VADD        dTempC0, dTempC0, dTempD0
        ;//h 
        VMOV        dCoeff5, #5
        
        ;// res0
        VADD        dTempB0, dTempB0, dTempP1
        VMLAL       qAcc0, dTempC0, dCoeff20
        VEXT        dTempC0, dTempQ0, dTempQ1, #2
        VEXT        dTempD0, dTempQ0, dTempQ1, #3
        VEXT        dTempF0, dTempQ1, dTempQ2, #1
        VMLSL       qAcc0, dTempB0, dCoeff5

        ;// res1
        VEXT        dTempB0, dTempQ0, dTempQ1, #1
        VADDL       qAcc1, dTempQ0, dTempF0
        VADD        dTempC0, dTempC0, dTempD0
        VADD        dTempB0, dTempB0, dTempQ1
        VEXT        dTempD0, dTempR0, dTempR1, #3
        VMLAL       qAcc1, dTempC0, dCoeff20
        VEXT        dTempF0, dTempR1, dTempR2, #1
        VEXT        dTempC0, dTempR0, dTempR1, #2
        VEXT        dTmp, dTempR0, dTempR1, #1
        VADDL       qAcc2, dTempR0, dTempF0
        VMLSL       qAcc1, dTempB0, dCoeff5
;        VEXT        dTempB0, dTempR0, dTempR1, #1
        VADD        dTempC0, dTempC0, dTempD0
        
        ;// res2
        VADD        dTempB0, dTmp, dTempR1
        VEXT        dTempD0, dTempS0, dTempS1, #3
        VMLAL       qAcc2, dTempC0, dCoeff20
;        VADD        dTempB0, dTempB0, dTempR1
        
        ;// res3
        VEXT        dTempC0, dTempS0, dTempS1, #2
        VEXT        dTempF0, dTempS1, dTempS2, #1
        VADD        dTempC0, dTempC0, dTempD0
        VEXT        dTmp, dTempS0, dTempS1, #1
        VADDL       qAcc3, dTempS0, dTempF0
        VMLSL       qAcc2, dTempB0, dCoeff5
        VMLAL       qAcc3, dTempC0, dCoeff20
        VADD        dTmp, dTmp, dTempS1
        VMLSL       qAcc3, dTmp, dCoeff5
                
        VQRSHRUN    dTempAcc0, qAcc0, #10
        VQRSHRUN    dTempAcc1, qAcc1, #10
        VQRSHRUN    dTempAcc2, qAcc2, #10
        VQRSHRUN    dTempAcc3, qAcc3, #10

        VQMOVN      dAcc0, qTAcc0
        VQMOVN      dAcc1, qTAcc1
        VQMOVN      dAcc2, qTAcc2
        VQMOVN      dAcc3, qTAcc3
        
        M_END
    
    ENDIF
    
    
    

    
    END
    
