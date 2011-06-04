 ;/**
 ; * Function: omxVCCOMM_Copy8x8
 ; *
 ; * Description:
 ; * Copies the reference 8x8 block to the current block.
 ; * Parameters:
 ; * [in] pSrc         - pointer to the reference block in the source frame; must be aligned on an 8-byte boundary.
 ; * [in] step         - distance between the starts of consecutive lines in the reference frame, in bytes;
 ; *                     must be a multiple of 8 and must be larger than or equal to 8.
 ; * [out] pDst        - pointer to the destination block; must be aligned on an 8-byte boundary.
 ; * Return Value:
 ; * OMX_Sts_NoErr     - no error
 ; * OMX_Sts_BadArgErr - bad arguments; returned under any of the following conditions:
 ; *                   - one or more of the following pointers is NULL:  pSrc, pDst
 ; *                   - one or more of the following pointers is not aligned on an 8-byte boundary:  pSrc, pDst
 ; *                   - step <8 or step is not a multiple of 8.  
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
     M_START omxVCCOMM_Copy8x8,r5
        
        
        
        MOV   Count,step                 ;//Count=step 
        
        LDRD  X0,[pSrc],Count            ;//pSrc after loading : pSrc=pSrc+step
        LDRD  X1,[pSrc],Count
        
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],Count 
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count
        
        STRD  X0,[pDst],#8               
        LDRD  X0,[pSrc],Count
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count
        
        STRD  X0,[pDst],#8              
        LDRD  X0,[pSrc],Count
        STRD  X1,[pDst],#8               
        LDRD  X1,[pSrc],Count
        
        STRD  X0,[pDst],#8               
        MOV   Return,#OMX_Sts_NoErr
        STRD  X1,[pDst],#8               
        
        
        M_END
        ENDIF
        
        END
        