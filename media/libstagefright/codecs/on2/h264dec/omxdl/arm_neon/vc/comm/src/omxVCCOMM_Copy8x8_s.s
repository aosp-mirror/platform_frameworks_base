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
   
     
     M_VARIANTS CortexA8
     
     IF CortexA8
     
     
 ;//Input Arguments
pSrc    RN 0        
pDst    RN 1        
step    RN 2

;//Local Variables
Count   RN 3
Return  RN 0
;// Neon Registers

X0      DN D0.S8 
X1      DN D1.S8
X2      DN D2.S8
X3      DN D3.S8
     M_START omxVCCOMM_Copy8x8
        
            
        
        VLD1  {X0},[pSrc],step            ;// Load 8 bytes from 8 byte aligned pSrc, pSrc=pSrc+step after load
        VLD1  {X1},[pSrc],step
        VLD1  {X2},[pSrc],step
        VLD1  {X3},[pSrc],step
        
        VST1  {X0,X1},[pDst]!            ;// Store 16 bytes to 8 byte aligned pDst  
        VST1  {X2,X3},[pDst]!              
        
        VLD1  {X0},[pSrc],step
        VLD1  {X1},[pSrc],step
        VLD1  {X2},[pSrc],step
        VLD1  {X3},[pSrc],step
        
        VST1  {X0,X1},[pDst]!              
        VST1  {X2,X3},[pDst]!             
                
        MOV   Return,#OMX_Sts_NoErr
             
        M_END
        ENDIF



        
        END
        