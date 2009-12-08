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
------------------------------------------------------------------------------

   PacketVideo Corp.
   MP3 Decoder Library

   Filename: pvmp3_framedecoder.cpp

   Functions:
    pvmp3_framedecoder
    pvmp3_InitDecoder
    pvmp3_resetDecoder

    Date: 09/21/2007

------------------------------------------------------------------------------
 REVISION HISTORY


 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS

Input
    pExt = pointer to the external interface structure. See the file
           pvmp3decoder_api.h for a description of each field.
           Data type of pointer to a tPVMP3DecoderExternal
           structure.

    pMem = void pointer to hide the internal implementation of the library
           It is cast back to a tmp3dec_file structure. This structure
           contains information that needs to persist between calls to
           this function, or is too big to be placed on the stack, even
           though the data is only needed during execution of this function
           Data type void pointer, internally pointer to a tmp3dec_file
           structure.


 Outputs:
     status = ERROR condition.  see structure  ERROR_CODE

 Pointers and Buffers Modified:
    pMem contents are modified.
    pExt: (more detail in the file pvmp3decoder_api.h)
    inputBufferUsedLength - number of array elements used up by the stream.
    samplingRate - sampling rate in samples per sec
    bitRate - bit rate in bits per second, varies frame to frame.



------------------------------------------------------------------------------
 FUNCTIONS DESCRIPTION

    pvmp3_framedecoder
        frame decoder library driver
    pvmp3_InitDecoder
        Decoder Initialization
    pvmp3_resetDecoder
        Reset Decoder

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

 [1] ISO MPEG Audio Subgroup Software Simulation Group (1996)
     ISO 13818-3 MPEG-2 Audio Decoder - Lower Sampling Frequency Extension

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/


#include "pvmp3_framedecoder.h"
#include "pvmp3_dec_defs.h"
#include "pvmp3_poly_phase_synthesis.h"
#include "pvmp3_tables.h"
#include "pvmp3_imdct_synth.h"
#include "pvmp3_alias_reduction.h"
#include "pvmp3_reorder.h"
#include "pvmp3_dequantize_sample.h"
#include "pvmp3_stereo_proc.h"
#include "pvmp3_mpeg2_stereo_proc.h"
#include "pvmp3_get_side_info.h"
#include "pvmp3_get_scale_factors.h"
#include "pvmp3_mpeg2_get_scale_factors.h"
#include "pvmp3_decode_header.h"
#include "pvmp3_get_main_data_size.h"
#include "s_tmp3dec_file.h"
#include "pvmp3_getbits.h"
#include "mp3_mem_funcs.h"


/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

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

ERROR_CODE pvmp3_framedecoder(tPVMP3DecoderExternal *pExt,
                              void              *pMem)
{

    ERROR_CODE        errorCode  = NO_DECODING_ERROR;

    int32   crc_error_count = 0;
    uint32  sent_crc = 0;
    uint32  computed_crc = 0;

    tmp3dec_chan   *pChVars[CHAN];
    tmp3dec_file   *pVars = (tmp3dec_file *)pMem;

    mp3Header info_data;
    mp3Header *info = &info_data;

    pVars->inputStream.pBuffer  = pExt->pInputBuffer;


    pVars->inputStream.usedBits  = pExt->inputBufferUsedLength << 3;
    pVars->inputStream.inputBufferCurrentLength  = pExt->inputBufferCurrentLength;


    errorCode = pvmp3_decode_header(&pVars->inputStream,
                                    info,
                                    &computed_crc);

    if (errorCode != NO_DECODING_ERROR)
    {
        pExt->outputFrameSize = 0;
        return errorCode;
    }

    pVars->num_channels = (info->mode == MPG_MD_MONO) ? 1 : 2;
    pExt->num_channels = pVars->num_channels;

    int32 outputFrameSize = (info->version_x == MPEG_1) ?
                            2 * SUBBANDS_NUMBER * FILTERBANK_BANDS :
                            SUBBANDS_NUMBER * FILTERBANK_BANDS;

    outputFrameSize = (info->mode == MPG_MD_MONO) ? outputFrameSize : outputFrameSize << 1;


    /*
     *  Check if output buffer has enough room to hold output PCM
     */
    if (pExt->outputFrameSize >= outputFrameSize)
    {
        pExt->outputFrameSize = outputFrameSize;
    }
    else
    {
        pExt->outputFrameSize = 0;
        return OUTPUT_BUFFER_TOO_SMALL;
    }


    pChVars[ LEFT] = &pVars->perChan[ LEFT];
    pChVars[RIGHT] = &pVars->perChan[RIGHT];




    if (info->error_protection)
    {
        /*
         *  Get crc content
         */
        sent_crc = getUpTo17bits(&pVars->inputStream, 16);
    }


    if (info->layer_description == 3)
    {
        int32 gr;
        int32 ch;
        uint32 main_data_end;
        int32 bytes_to_discard;
        int16 *ptrOutBuffer = pExt->pOutputBuffer;

        /*
         * Side Information must be extracted from the bitstream and store for use
         * during the decoded of the associated frame
         */

        errorCode = pvmp3_get_side_info(&pVars->inputStream,
                                        &pVars->sideInfo,
                                        info,
                                        &computed_crc);

        if (errorCode != NO_DECODING_ERROR)
        {
            pExt->outputFrameSize = 0;
            return errorCode;
        }

        /*
         *  If CRC was sent, check that matches the one got while parsing data
         *  disable crc if this is the desired mode
         */
        if (info->error_protection)
        {
            if ((computed_crc != sent_crc) && pExt->crcEnabled)
            {
                crc_error_count++;
            }
        }

        /*
         * main data (scalefactors, Huffman coded, etc,) are not necessarily located
         * adjacent to the side-info. Beginning of main data is located using
         * field "main_data_begin" of the current frame. The length does not include
         * header and side info.
         * "main_data_begin" points to the first bit of main data of a frame. It is a negative
         * offset in bytes from the first byte of the sync word
         * main_data_begin = 0  <===> main data start rigth after side info.
         */

        int32 temp = pvmp3_get_main_data_size(info, pVars);


        /*
         *  Check if available data holds a full frame, if not flag an error
         */

        if ((uint32)pVars->predicted_frame_size > pVars->inputStream.inputBufferCurrentLength)
        {
            pExt->outputFrameSize = 0;
            return NO_ENOUGH_MAIN_DATA_ERROR;
        }

        /*
         *  Fill in internal circular buffer
         */
        fillMainDataBuf(pVars, temp);


        main_data_end = pVars->mainDataStream.usedBits >> 3; /* in bytes */
        if ((main_data_end << 3) < pVars->mainDataStream.usedBits)
        {
            main_data_end++;
            pVars->mainDataStream.usedBits = main_data_end << 3;
        }


        bytes_to_discard = pVars->frame_start - pVars->sideInfo.main_data_begin - main_data_end;


        if (main_data_end > BUFSIZE)   /* check overflow on the buffer */
        {
            pVars->frame_start -= BUFSIZE;

            pVars->mainDataStream.usedBits -= (BUFSIZE << 3);
        }

        pVars->frame_start += temp;


        if (bytes_to_discard < 0 || crc_error_count)
        {
            /*
             *  Not enough data to decode, then we should avoid reading this
             *  data ( getting/ignoring sido info and scale data)
             *  Main data could be located in the previous frame, so an unaccounted
             *  frame can cause incorrect processing
             *  Just run the polyphase filter to "clean" the history buffer
             */
            errorCode = NO_ENOUGH_MAIN_DATA_ERROR;

            /*
             *  Clear the input to these filters
             */

            pv_memset((void*)pChVars[RIGHT]->work_buf_int32,
                      0,
                      SUBBANDS_NUMBER*FILTERBANK_BANDS*sizeof(pChVars[RIGHT]->work_buf_int32[0]));

            pv_memset((void*)pChVars[LEFT]->work_buf_int32,
                      0,
                      SUBBANDS_NUMBER*FILTERBANK_BANDS*sizeof(pChVars[LEFT]->work_buf_int32[0]));

            /*  clear circular buffers, to avoid any glitch */
            pv_memset((void*)&pChVars[ LEFT]->circ_buffer[576],
                      0,
                      480*sizeof(pChVars[ LEFT]->circ_buffer[0]));
            pv_memset((void*)&pChVars[RIGHT]->circ_buffer[576],
                      0,
                      480*sizeof(pChVars[RIGHT]->circ_buffer[0]));

            pChVars[ LEFT]->used_freq_lines = 575;
            pChVars[RIGHT]->used_freq_lines = 575;

        }
        else
        {
            pVars->mainDataStream.usedBits += (bytes_to_discard << 3);
        }

        /*
         *  if (fr_ps->header->version_x == MPEG_1), use 2 granules, otherwise just 1
         */
        for (gr = 0; gr < (1 + !(info->version_x)); gr++)
        {
            if (errorCode != NO_ENOUGH_MAIN_DATA_ERROR)
            {
                for (ch = 0; ch < pVars->num_channels; ch++)
                {
                    int32 part2_start = pVars->mainDataStream.usedBits;

                    if (info->version_x == MPEG_1)
                    {

                        pvmp3_get_scale_factors(&pVars->scaleFactors[ch],
                                                &pVars->sideInfo,
                                                gr,
                                                ch,
                                                &pVars->mainDataStream);
                    }
                    else
                    {
                        int32 * tmp = pVars->Scratch_mem;
                        pvmp3_mpeg2_get_scale_factors(&pVars->scaleFactors[ch],
                                                      &pVars->sideInfo,
                                                      gr,
                                                      ch,
                                                      info,
                                                      (uint32 *)tmp,
                                                      &pVars->mainDataStream);
                    }

                    pChVars[ch]->used_freq_lines = pvmp3_huffman_parsing(pChVars[ch]->work_buf_int32,
                                                   &pVars->sideInfo.ch[ch].gran[gr],
                                                   pVars,
                                                   part2_start,
                                                   info);


                    pvmp3_dequantize_sample(pChVars[ch]->work_buf_int32,
                                            &pVars->scaleFactors[ch],
                                            &pVars->sideInfo.ch[ch].gran[gr],
                                            pChVars[ch]->used_freq_lines,
                                            info);




                }   /* for (ch=0; ch<stereo; ch++)  */

                if (pVars->num_channels == 2)
                {

                    int32 used_freq_lines = (pChVars[ LEFT]->used_freq_lines  >
                                             pChVars[RIGHT]->used_freq_lines) ?
                                            pChVars[ LEFT]->used_freq_lines  :
                                            pChVars[RIGHT]->used_freq_lines;

                    pChVars[ LEFT]->used_freq_lines = used_freq_lines;
                    pChVars[RIGHT]->used_freq_lines = used_freq_lines;

                    if (info->version_x == MPEG_1)
                    {
                        pvmp3_stereo_proc(pChVars[ LEFT]->work_buf_int32,
                                          pChVars[RIGHT]->work_buf_int32,
                                          &pVars->scaleFactors[RIGHT],
                                          &pVars->sideInfo.ch[LEFT].gran[gr],
                                          used_freq_lines,
                                          info);
                    }
                    else
                    {
                        int32 * tmp = pVars->Scratch_mem;
                        pvmp3_mpeg2_stereo_proc(pChVars[ LEFT]->work_buf_int32,
                                                pChVars[RIGHT]->work_buf_int32,
                                                &pVars->scaleFactors[RIGHT],
                                                &pVars->sideInfo.ch[ LEFT].gran[gr],
                                                &pVars->sideInfo.ch[RIGHT].gran[gr],
                                                (uint32 *)tmp,
                                                used_freq_lines,
                                                info);
                    }
                }

            } /* if ( errorCode != NO_ENOUGH_MAIN_DATA_ERROR) */

            for (ch = 0; ch < pVars->num_channels; ch++)
            {

                pvmp3_reorder(pChVars[ch]->work_buf_int32,
                              &pVars->sideInfo.ch[ch].gran[gr],
                              &pChVars[ ch]->used_freq_lines,
                              info,
                              pVars->Scratch_mem);

                pvmp3_alias_reduction(pChVars[ch]->work_buf_int32,
                                      &pVars->sideInfo.ch[ch].gran[gr],
                                      &pChVars[ ch]->used_freq_lines,
                                      info);


                /*
                 *   IMDCT
                 */
                /* set mxposition
                 * In case of mixed blocks, # of bands with long
                 * blocks (2 or 4) else 0
                 */
                uint16 mixedBlocksLongBlocks = 0; /*  0 = long or short, 2=mixed, 4=mixed 2.5@8000 */
                if (pVars->sideInfo.ch[ch].gran[gr].mixed_block_flag &&
                        pVars->sideInfo.ch[ch].gran[gr].window_switching_flag)
                {
                    if ((info->version_x == MPEG_2_5) && (info->sampling_frequency == 2))
                    {
                        mixedBlocksLongBlocks = 4; /* mpeg2.5 @ 8 KHz */
                    }
                    else
                    {
                        mixedBlocksLongBlocks = 2;
                    }
                }

                pvmp3_imdct_synth(pChVars[ch]->work_buf_int32,
                                  pChVars[ch]->overlap,
                                  pVars->sideInfo.ch[ch].gran[gr].block_type,
                                  mixedBlocksLongBlocks,
                                  pChVars[ ch]->used_freq_lines,
                                  pVars->Scratch_mem);


                /*
                 *   Polyphase synthesis
                 */

                pvmp3_poly_phase_synthesis(pChVars[ch],
                                           pVars->num_channels,
                                           pExt->equalizerType,
                                           &ptrOutBuffer[ch]);


            }/* end ch loop */

            ptrOutBuffer += pVars->num_channels * SUBBANDS_NUMBER * FILTERBANK_BANDS;
        }  /*   for (gr=0;gr<Max_gr;gr++)  */

        /* skip ancillary data */
        if (info->bitrate_index > 0)
        { /* if not free-format */

            int32 ancillary_data_lenght = pVars->predicted_frame_size << 3;

            ancillary_data_lenght  -= pVars->inputStream.usedBits;

            /* skip ancillary data */
            if (ancillary_data_lenght > 0)
            {
                pVars->inputStream.usedBits += ancillary_data_lenght;
            }

        }

        /*
         *  This overrides a possible NO_ENOUGH_MAIN_DATA_ERROR
         */
        errorCode = NO_DECODING_ERROR;

    }
    else
    {
        /*
         * The info on the header leads to an unsupported layer, more data
         * will not fix this, so this is a bad frame,
         */

        pExt->outputFrameSize = 0;
        return UNSUPPORTED_LAYER;
    }

    pExt->inputBufferUsedLength = pVars->inputStream.usedBits >> 3;
    pExt->totalNumberOfBitsUsed += pVars->inputStream.usedBits;
    pExt->version = info->version_x;
    pExt->samplingRate = mp3_s_freq[info->version_x][info->sampling_frequency];
    pExt->bitRate = mp3_bitrate[pExt->version][info->bitrate_index];


    /*
     *  Always verify buffer overrun condition
     */

    if (pExt->inputBufferUsedLength > pExt->inputBufferCurrentLength)
    {
        pExt->outputFrameSize = 0;
        errorCode = NO_ENOUGH_MAIN_DATA_ERROR;
    }

    return errorCode;

}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

__inline void fillDataBuf(tmp3Bits *pMainData,
                          uint32 val)       /* val to write into the buffer */
{
    pMainData->pBuffer[module(pMainData->offset++, BUFSIZE)] = (uint8)val;
}


void fillMainDataBuf(void  *pMem, int32 temp)
{
    tmp3dec_file   *pVars = (tmp3dec_file *)pMem;


    int32 offset = (pVars->inputStream.usedBits) >> INBUF_ARRAY_INDEX_SHIFT;

    /*
     *  Check if input circular buffer boundaries need to be enforced
     */
    if ((offset + temp) < BUFSIZE)
    {
        uint8 * ptr = pVars->inputStream.pBuffer + offset;

        offset = pVars->mainDataStream.offset;

        /*
         *  Check if main data circular buffer boundaries need to be enforced
         */
        if ((offset + temp) < BUFSIZE)
        {
            pv_memcpy((pVars->mainDataStream.pBuffer + offset), ptr, temp*sizeof(uint8));
            pVars->mainDataStream.offset += temp;
        }
        else
        {
            int32 tmp1 = *(ptr++);
            for (int32 nBytes = temp >> 1; nBytes != 0; nBytes--)  /* read main data. */
            {
                int32 tmp2 = *(ptr++);
                fillDataBuf(&pVars->mainDataStream, tmp1);
                fillDataBuf(&pVars->mainDataStream, tmp2);
                tmp1 = *(ptr++);
            }

            if (temp&1)
            {
                fillDataBuf(&pVars->mainDataStream, tmp1);
            }

            /* adjust circular buffer counter */
            pVars->mainDataStream.offset = module(pVars->mainDataStream.offset, BUFSIZE);
        }
    }
    else
    {
        for (int32 nBytes = temp >> 1; nBytes != 0; nBytes--)  /* read main data. */
        {
            fillDataBuf(&pVars->mainDataStream, *(pVars->inputStream.pBuffer + module(offset++  , BUFSIZE)));
            fillDataBuf(&pVars->mainDataStream, *(pVars->inputStream.pBuffer + module(offset++  , BUFSIZE)));
        }
        if (temp&1)
        {
            fillDataBuf(&pVars->mainDataStream, *(pVars->inputStream.pBuffer + module(offset  , BUFSIZE)));
        }
    }


    pVars->inputStream.usedBits += (temp) << INBUF_ARRAY_INDEX_SHIFT;
}




/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

uint32 pvmp3_decoderMemRequirements(void)
{
    uint32 size;

    size = (uint32) sizeof(tmp3dec_file);
    return (size);
}



/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

#include "pvmp3_decode_huff_cw.h"

void pvmp3_InitDecoder(tPVMP3DecoderExternal *pExt,
                       void  *pMem)
{

    tmp3dec_file      *pVars;
    huffcodetab       *pHuff;

    pVars = (tmp3dec_file *)pMem;

    pVars->num_channels = 0;

    pExt->totalNumberOfBitsUsed = 0;
    pExt->inputBufferCurrentLength = 0;
    pExt->inputBufferUsedLength    = 0;

    pVars->mainDataStream.offset = 0;

    pv_memset((void*)pVars->mainDataBuffer,
              0,
              BUFSIZE*sizeof(*pVars->mainDataBuffer));


    pVars->inputStream.pBuffer = pExt->pInputBuffer;

    /*
     *  Initialize huffman decoding table
     */

    pHuff = pVars->ht;
    pHuff[0].linbits = 0;
    pHuff[0].pdec_huff_tab = pvmp3_decode_huff_cw_tab0;
    pHuff[1].linbits = 0;
    pHuff[1].pdec_huff_tab = pvmp3_decode_huff_cw_tab1;
    pHuff[2].linbits = 0;
    pHuff[2].pdec_huff_tab = pvmp3_decode_huff_cw_tab2;
    pHuff[3].linbits = 0;
    pHuff[3].pdec_huff_tab = pvmp3_decode_huff_cw_tab3;
    pHuff[4].linbits = 0;
    pHuff[4].pdec_huff_tab = pvmp3_decode_huff_cw_tab0; /* tbl 4 is not used */
    pHuff[5].linbits = 4;
    pHuff[5].pdec_huff_tab = pvmp3_decode_huff_cw_tab5;
    pHuff[6].linbits = 0;
    pHuff[6].pdec_huff_tab = pvmp3_decode_huff_cw_tab6;
    pHuff[7].linbits = 0;
    pHuff[7].pdec_huff_tab = pvmp3_decode_huff_cw_tab7;
    pHuff[8].linbits = 0;
    pHuff[8].pdec_huff_tab = pvmp3_decode_huff_cw_tab8;
    pHuff[9].linbits = 0;
    pHuff[9].pdec_huff_tab = pvmp3_decode_huff_cw_tab9;
    pHuff[10].linbits = 0;
    pHuff[10].pdec_huff_tab = pvmp3_decode_huff_cw_tab10;
    pHuff[11].linbits = 0;
    pHuff[11].pdec_huff_tab = pvmp3_decode_huff_cw_tab11;
    pHuff[12].linbits = 0;
    pHuff[12].pdec_huff_tab = pvmp3_decode_huff_cw_tab12;
    pHuff[13].linbits = 0;
    pHuff[13].pdec_huff_tab = pvmp3_decode_huff_cw_tab13;
    pHuff[14].linbits = 0;
    pHuff[14].pdec_huff_tab = pvmp3_decode_huff_cw_tab0; /* tbl 14 is not used */
    pHuff[15].linbits = 0;
    pHuff[15].pdec_huff_tab = pvmp3_decode_huff_cw_tab15;
    pHuff[16].linbits = 1;
    pHuff[16].pdec_huff_tab = pvmp3_decode_huff_cw_tab16;
    pHuff[17].linbits = 2;
    pHuff[17].pdec_huff_tab = pvmp3_decode_huff_cw_tab16;
    pHuff[18].linbits = 3;
    pHuff[18].pdec_huff_tab = pvmp3_decode_huff_cw_tab16;
    pHuff[19].linbits = 4;
    pHuff[19].pdec_huff_tab = pvmp3_decode_huff_cw_tab16;
    pHuff[20].linbits = 6;
    pHuff[20].pdec_huff_tab = pvmp3_decode_huff_cw_tab16;
    pHuff[21].linbits = 8;
    pHuff[21].pdec_huff_tab = pvmp3_decode_huff_cw_tab16;
    pHuff[22].linbits = 10;
    pHuff[22].pdec_huff_tab = pvmp3_decode_huff_cw_tab16;
    pHuff[23].linbits = 13;
    pHuff[23].pdec_huff_tab = pvmp3_decode_huff_cw_tab16;
    pHuff[24].linbits = 4;
    pHuff[24].pdec_huff_tab = pvmp3_decode_huff_cw_tab24;
    pHuff[25].linbits = 5;
    pHuff[25].pdec_huff_tab = pvmp3_decode_huff_cw_tab24;
    pHuff[26].linbits = 6;
    pHuff[26].pdec_huff_tab = pvmp3_decode_huff_cw_tab24;
    pHuff[27].linbits = 7;
    pHuff[27].pdec_huff_tab = pvmp3_decode_huff_cw_tab24;
    pHuff[28].linbits = 8;
    pHuff[28].pdec_huff_tab = pvmp3_decode_huff_cw_tab24;
    pHuff[29].linbits = 9;
    pHuff[29].pdec_huff_tab = pvmp3_decode_huff_cw_tab24;
    pHuff[30].linbits = 11;
    pHuff[30].pdec_huff_tab = pvmp3_decode_huff_cw_tab24;
    pHuff[31].linbits = 13;
    pHuff[31].pdec_huff_tab = pvmp3_decode_huff_cw_tab24;
    pHuff[32].linbits = 0;
    pHuff[32].pdec_huff_tab = pvmp3_decode_huff_cw_tab32;
    pHuff[33].linbits = 0;
    pHuff[33].pdec_huff_tab = pvmp3_decode_huff_cw_tab33;

    /*
     *  Initialize polysynthesis circular buffer mechanism
     */
    /* clear buffers */

    pvmp3_resetDecoder(pMem);

}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/


void pvmp3_resetDecoder(void  *pMem)
{

    tmp3dec_file      *pVars;
    tmp3dec_chan      *pChVars[CHAN];

    pVars = (tmp3dec_file *)pMem;
    pChVars[ LEFT] = &pVars->perChan[ LEFT];
    pChVars[RIGHT] = &pVars->perChan[RIGHT];

    pVars->frame_start = 0;

    pVars->mainDataStream.offset = 0;

    pVars->mainDataStream.pBuffer =  pVars->mainDataBuffer;
    pVars->mainDataStream.usedBits = 0;


    pVars->inputStream.usedBits = 0; // in bits


    pChVars[ LEFT]->used_freq_lines = 575;
    pChVars[RIGHT]->used_freq_lines = 575;


    /*
     *  Initialize polysynthesis circular buffer mechanism
     */

    pv_memset((void*)&pChVars[ LEFT]->circ_buffer[576],
              0,
              480*sizeof(pChVars[ LEFT]->circ_buffer[0]));
    pv_memset((void*)&pChVars[RIGHT]->circ_buffer[576],
              0,
              480*sizeof(pChVars[RIGHT]->circ_buffer[0]));


    pv_memset((void*)pChVars[ LEFT]->overlap,
              0,
              SUBBANDS_NUMBER*FILTERBANK_BANDS*sizeof(pChVars[ LEFT]->overlap[0]));


    pv_memset((void*)pChVars[ RIGHT]->overlap,
              0,
              SUBBANDS_NUMBER*FILTERBANK_BANDS*sizeof(pChVars[ RIGHT]->overlap[0]));





    /*
     *  Clear all the structures
     */


    pv_memset((void*)&pVars->scaleFactors[RIGHT],
              0,
              sizeof(mp3ScaleFactors));

    pv_memset((void*)&pVars->scaleFactors[LEFT],
              0,
              sizeof(mp3ScaleFactors));

    pv_memset((void*)&pVars->sideInfo,
              0,
              sizeof(mp3SideInfo));

    pv_memset((void*)&pVars->sideInfo,
              0,
              sizeof(mp3SideInfo));

}
