;//
;// 
;// File Name:  omxVCM4P2_IDCT8x8blk_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   12290
;// Date:       Wednesday, April 9, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//

;// Function:
;//     omxVCM4P2_IDCT8x8blk
;//
        ;// Include headers
        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h

        ;// Define cpu variants
        M_VARIANTS CortexA8

        INCLUDE armCOMM_IDCT_s.h        
        
        IMPORT armCOMM_IDCTPreScale
        ;//
        ;// Function prototype
        ;//
        ;//     OMXResult
        ;//     omxVCM4P2_IDCT8x8blk(const OMX_S16* pSrc,
        ;//                                       OMX_S16* pDst)
        ;//    
        
    IF CortexA8
        M_ALLOC4  ppDest, 4
        M_ALLOC4  pStride, 4
        M_ALLOC8  pBlk, 2*8*8
    ENDIF
    
    
    IF CortexA8
        M_START omxVCM4P2_IDCT8x8blk, r11, d15
    ENDIF
        
    IF CortexA8
        
;// Declare input registers
pSrc            RN 0
pDst            RN 1

;// Declare other intermediate registers
Result          RN 0

;// Prototype for macro M_IDCT
;// pSrc            RN 0  ;// source data buffer
;// Stride          RN 1  ;// destination stride in bytes
;// pDest           RN 2  ;// destination data buffer
;// pScale          RN 3  ;// pointer to scaling table

pSrc    RN 0    
Stride  RN 1    
pDest   RN 2    
pScale  RN 3    
                
        MOV         pDest, pDst
        LDR         pScale, =armCOMM_IDCTPreScale        
        M_IDCT      s9, s16, 16      
        MOV         Result, #OMX_Sts_NoErr
        M_END       
    ENDIF  
        ;// ARM1136JS :LOR: CortexA8

    END
