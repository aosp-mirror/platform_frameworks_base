 ;/**
 ; * Function: omxVCCOMM_Copy16x16
 ; *
 ; * Description:
 ; * Copies the reference 16x16 block to the current block.
 ; * Parameters:
 ; * [in] pSrc         - pointer to the reference block in the source frame; must be aligned on an 16-byte boundary.
 ; * [in] step         - distance between the starts of consecutive lines in the reference frame, in bytes;
 ; *                     must be a multiple of 16 and must be larger than or equal to 16.
 ; * [out] pDst        - pointer to the destination block; must be aligned on an 8-byte boundary.
 ; * Return Value:
 ; * OMX_Sts_NoErr     - no error
 ; * OMX_Sts_BadArgErr - bad arguments; returned under any of the following conditions:
 ; *                   - one or more of the following pointers is NULL:  pSrc, pDst
 ; *                   - one or more of the following pointers is not aligned on an 16-byte boundary:  pSrc, pDst
 ; *                   - step <16 or step is not a multiple of 16.  
 ; */

   INCLUDE omxtypes_s.h
   
     
     M_VARIANTS ARM1136JS
     



     IF ARM1136JS

;//Input Arguments
pSrc    RN 0        
pDst    RN 1        
step    RN 2

;//Local Variables
Count   RN 14
X0      RN 2
X1      RN 4

Return  RN 0
     
     M_START omxVCCOMM_Copy16x16,r5
        
        
        
        SUB   Count,step,#8                 ;//Count=step-8
        LDRD  X0,[pSrc],#8                  ;//pSrc after loading pSrc=pSrc+8
        LDRD  X1,[pSrc],Count               ;//pSrc after loading pSrc=pSrc+step
        
        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8 
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count
        
        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count
        
        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count
        
        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count
        
        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count
       
        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count

        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count
        
        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8              
        LDRD  X0,[pSrc],#8 
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count
        
        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8 
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count

        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8 
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count

        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8 
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count

        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8 
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count

        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8 
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count

        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8 
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count
       
        ;// loading 16 bytes and storing
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],#8 
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count

        STRD  X0,[pDst],#8               
        MOV   Return,#OMX_Sts_NoErr
        STRD  X1,[pDst],#8               

       
        M_END
        ENDIF
        
        END
       