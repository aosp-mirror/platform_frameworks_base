/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
/*

 Pathname: PVMP4AudioDecoderResetBuffer.c

------------------------------------------------------------------------------
 REVISION HISTORY

 Description: (1) add more comments (2) set pVars->bno = 1

 Description: perChan[] is an array of structures in tDec_Int_File. Made
              corresponding changes.

 Who:                                         Date:
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

 Inputs:

    pMem = void pointer to hide the internal implementation of the library
           It is cast back to a tDec_Int_File structure. This structure
           contains information that needs to persist between calls to
           PVMP4AudioDecodeFrame
           Data type void pointer, internally pointer to a tDec_Int_File
           structure.

 Local Stores/Buffers/Pointers Needed: None
           (The memory set aside in pMem performs this task)

 Global Stores/Buffers/Pointers Needed: None

 Outputs: None

 Pointers and Buffers Modified:
    pMem contents are modified.
    pMem->perChan[0].time_quant[0-1023]: contents are set to zero
    pMem->perChan[1].time_quant[0-1023]: contents are set to zero
    pMem->bno = 1

 Local Stores Modified: None.

 Global Stores Modified: None.

------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

  This function is called when the same audio clip will be played again from
  the begining. This situation happens when the "stop" button is pressed or
  the "loop-mode" is selected on PVPlayer. Since it is the same audio clip to
  be played again, the decoder does not need to reset the audioSpecificInfo.
  However, the overlap-and-add buffer of the filterbank output needs to be
  cleared, so that the decoder can re-start properly from the begining of
  the audio. The frame number counter, pVars->bno, is set to 1 because the
  audioSpecificInfo is decoded on pVars->bno==0

------------------------------------------------------------------------------
 REQUIREMENTS

 PacketVideo Document # CCC-AUD-AAC-ERS-0003

------------------------------------------------------------------------------
 REFERENCES

 (1) ISO/IEC 14496-3: 1999(E)
      subclause 1.6

------------------------------------------------------------------------------
 RESOURCES USED
   When the code is written for a specific target processor the
     the resources used should be documented below.

 STACK USAGE: [stack count for this module] + [variable to represent
          stack usage for each subroutine called]

     where: [stack usage variable] = stack usage for [subroutine
         name] (see [filename].ext)

 DATA MEMORY USED: x words

 PROGRAM MEMORY USED: x words

 CLOCK CYCLES: [cycle count equation for this module] + [variable
           used to represent cycle count for each subroutine
           called]

     where: [cycle count variable] = cycle count for [subroutine
        name] (see [filename].ext)

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/
#include "pv_audio_type_defs.h"
#include "s_tdec_int_file.h"
#include "pvmp4audiodecoder_api.h"   /* Where this function is declared */
#include "aac_mem_funcs.h"

#ifdef AAC_PLUS
#include    "s_sbr_frame_data.h"
#endif

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define LEFT  (0)
#define RIGHT (1)

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

OSCL_EXPORT_REF void PVMP4AudioDecoderResetBuffer(void  *pMem)
{

    tDec_Int_File *pVars;           /* Helper pointer */

#ifdef AAC_PLUS
    SBR_FRAME_DATA * hFrameData_1;
    SBR_FRAME_DATA * hFrameData_2;
#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO
    SBRDECODER_DATA *sbrDecoderData;
#endif
#endif

#endif
    /*
     * Initialize "helper" pointers to existing memory.
     */
    pVars = (tDec_Int_File *)pMem;

    /*
     * Clear the overlap-and-add buffer of filterbank output. The audio
     * clip will be played again from the beginning.
     */
    pv_memset(pVars->perChan[LEFT].time_quant,
              0,
              LONG_WINDOW*sizeof(pVars->perChan[LEFT].time_quant[0]));

    pv_memset(pVars->perChan[RIGHT].time_quant,
              0,
              LONG_WINDOW*sizeof(pVars->perChan[RIGHT].time_quant[0]));


#ifdef AAC_PLUS

    if (!pVars->sbrDecoderData.setStreamType)  /* reset only when stream type is defined */
    {
        if (pVars->aacPlusEnabled == true)  /* clear buffer only if they were used */
        {

            hFrameData_1   = (SBR_FRAME_DATA *) & pVars->sbrDecoderData.SbrChannel[LEFT].frameData;
            hFrameData_2   = (SBR_FRAME_DATA *) & pVars->sbrDecoderData.SbrChannel[RIGHT].frameData;
#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO
            sbrDecoderData = (SBRDECODER_DATA *) & pVars->sbrDecoderData;
            sbrDecoderData->hParametricStereoDec = (HANDLE_PS_DEC) & pVars->sbrDecoderData.ParametricStereoDec;
#endif
#endif


            pv_memset(&pVars->perChan[LEFT].ltp_buffer[0],
                      0,
                      288*sizeof(pVars->perChan[LEFT].ltp_buffer[0]));
            pv_memset(&pVars->perChan[LEFT].ltp_buffer[1024 + 288],
                      0,
                      288*sizeof(pVars->perChan[LEFT].ltp_buffer[0]));
            pv_memset(hFrameData_1->V,
                      0,
                      1152*sizeof(hFrameData_1->V[0]));
            pv_memset(hFrameData_1->prevNoiseLevel_man,
                      0,
                      MAX_NUM_NOISE_VALUES*sizeof(hFrameData_1->prevNoiseLevel_man[0]));


            pv_memset(&pVars->perChan[RIGHT].ltp_buffer[0],
                      0,
                      288*sizeof(pVars->perChan[RIGHT].ltp_buffer[0]));
            pv_memset(&pVars->perChan[RIGHT].ltp_buffer[1024 + 288],
                      0,
                      288*sizeof(pVars->perChan[RIGHT].ltp_buffer[0]));
            pv_memset(hFrameData_2->V,
                      0,
                      1152*sizeof(hFrameData_2->V[0]));

            pv_memset(hFrameData_2->prevNoiseLevel_man,
                      0,
                      MAX_NUM_NOISE_VALUES*sizeof(hFrameData_2->prevNoiseLevel_man[0]));


            int i;
            for (i = 0; i < 8; i++)
            {
                pv_memset((void *)&hFrameData_1->codecQmfBufferReal[i],
                          0,
                          sizeof(**hFrameData_1->codecQmfBufferReal) << 5);
            }


            /* ---- */
            pv_memset((void *)hFrameData_1->BwVectorOld,
                      0,
                      sizeof(*hFrameData_1->BwVectorOld)*MAX_NUM_PATCHES);

#ifdef HQ_SBR

            for (i = 0; i < 5; i++)
            {
                pv_memset((void *)&hFrameData_1->fBuffer_man[i],
                          0,
                          sizeof(**hFrameData_1->fBuffer_man)*64);
                pv_memset((void *)&hFrameData_1->fBufferN_man[i],
                          0,
                          sizeof(**hFrameData_1->fBufferN_man)*64);
            }
#endif


            /* ---- */



            pv_memset((void *)hFrameData_1->HistsbrQmfBufferReal,
                      0,
                      sizeof(*hFrameData_1->HistsbrQmfBufferReal)*6*SBR_NUM_BANDS);

#ifdef HQ_SBR
            pv_memset((void *)hFrameData_1->HistsbrQmfBufferImag,
                      0,
                      sizeof(*hFrameData_1->HistsbrQmfBufferImag)*6*SBR_NUM_BANDS);
#endif

            if (pVars->sbrDec.LC_aacP_DecoderFlag == 1)  /* clear buffer only for LC decoding */
            {

                for (i = 0; i < 8; i++)
                {
                    pv_memset((void *)&hFrameData_2->codecQmfBufferReal[i],
                              0,
                              sizeof(**hFrameData_1->codecQmfBufferReal) << 5);
                }

                pv_memset((void *)hFrameData_2->HistsbrQmfBufferReal,
                          0,
                          sizeof(*hFrameData_2->HistsbrQmfBufferReal)*6*SBR_NUM_BANDS);


                pv_memset((void *)hFrameData_2->BwVectorOld,
                          0,
                          sizeof(*hFrameData_2->BwVectorOld)*MAX_NUM_PATCHES);

#ifdef HQ_SBR

                for (i = 0; i < 5; i++)
                {
                    pv_memset((void *)&hFrameData_2->fBuffer_man[i],
                              0,
                              sizeof(**hFrameData_2->fBuffer_man)*64);
                    pv_memset((void *)&hFrameData_2->fBufferN_man[i],
                              0,
                              sizeof(**hFrameData_2->fBufferN_man)*64);
                }
#endif

            }

#ifdef HQ_SBR
#ifdef PARAMETRICSTEREO
            else if (pVars->mc_info.psPresentFlag == 1)
            {
                for (i = 0; i < 3; i++)
                {
                    pv_memset(sbrDecoderData->hParametricStereoDec->hHybrid->mQmfBufferReal[i],
                              0,
                              HYBRID_FILTER_LENGTH_m_1*sizeof(*sbrDecoderData->hParametricStereoDec->hHybrid->mQmfBufferReal));
                    pv_memset(sbrDecoderData->hParametricStereoDec->hHybrid->mQmfBufferImag[i],
                              0,
                              HYBRID_FILTER_LENGTH_m_1*sizeof(*sbrDecoderData->hParametricStereoDec->hHybrid->mQmfBufferImag));
                }
            }
#endif
#endif

            /*
             *  default to UPSAMPLING, as if the file is SBR_ACTIVE, this will be fine and will be
             *  fixed onced the new sbr header is found
             *  SBR headers contain SBT freq. range as well as control signals that do not require
             *  frequent changes.
             *  For streaming, the SBR header is sent twice per second. Also, an SBR header can be
             *  inserted at any time, if a change of parameters is needed.
             */

            pVars->sbrDecoderData.SbrChannel[LEFT].syncState = UPSAMPLING;
            pVars->sbrDecoderData.SbrChannel[RIGHT].syncState = UPSAMPLING;

        }
    }
#endif      /*  #ifdef AAC_PLUS */

    /* reset frame count to 1 */
    pVars->bno = 1;

    return ;

} /* PVMP4AudioDecoderDecodeFrame */

