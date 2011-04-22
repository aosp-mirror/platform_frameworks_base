/*
 ** Copyright 2003-2010, VisualOn, Inc.
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

/***********************************************************************
       File: bits.c

	   Description: Performs bit stream manipulation

************************************************************************/

#include <stdlib.h>
#include <stdio.h>
#include "typedef.h"
#include "basic_op.h"
#include "cnst.h"
#include "bits.h"
#include "acelp.h"
#include "dtx.h"
#include "mime_io.tab"


int PackBits(Word16 prms[],             /*  i: analysis parameters */
			 Word16 coding_mode,        /*  i: coding bit-stream ratio mode */
			 Word16 mode,               /*  i: coding bit-stream ratio mode*/ 
			 Coder_State *st            /*i/o: coder global parameters struct */           
			 )
{
	Word16 i, frame_type;
	UWord8 temp;
	UWord8 *stream_ptr;
	Word16 bitstreamformat = st->frameType;

	unsigned short* dataOut = st->outputStream;

	if (coding_mode == MRDTX)
	{	   
		st->sid_update_counter--;

		if (st->prev_ft == TX_SPEECH)
		{
			frame_type = TX_SID_FIRST;
			st->sid_update_counter = 3;
		} else
		{
			if ((st->sid_handover_debt > 0) && (st->sid_update_counter > 2))
			{
				/* ensure extra updates are  properly delayed after a possible SID_FIRST */
				frame_type = TX_SID_UPDATE;
				st->sid_handover_debt--;
			} else
			{
				if (st->sid_update_counter == 0)
				{
					frame_type = TX_SID_UPDATE;
					st->sid_update_counter = 8;
				} else
				{
					frame_type = TX_NO_DATA;
				}
			}
		}
	} else
	{
		st->sid_update_counter = 8;
		frame_type = TX_SPEECH;
	}
	st->prev_ft = frame_type;

	if(bitstreamformat == 0)				/* default file format */
	{
		*(dataOut) = TX_FRAME_TYPE;
		*(dataOut + 1) = frame_type;
		*(dataOut + 2) = mode;
		for (i = 0; i < nb_of_bits[coding_mode]; i++)
		{
			*(dataOut + 3 + i) = prms[i];
		}
		return  (3 + nb_of_bits[coding_mode])<<1;
	} else
	{
		if (bitstreamformat == 1)		/* ITU file format */
		{  						
			*(dataOut) = 0x6b21;
			if(frame_type != TX_NO_DATA && frame_type != TX_SID_FIRST)
			{
				*(dataOut + 1) = nb_of_bits[coding_mode];
				for (i = 0; i < nb_of_bits[coding_mode]; i++)
				{
					if(prms[i] == BIT_0){
						*(dataOut + 2 + i) = BIT_0_ITU;	 			
					}
					else{
						*(dataOut + 2 + i) = BIT_1_ITU;
					}
				}
				return (2 + nb_of_bits[coding_mode])<<1;   
			} else
			{
				*(dataOut + 1) = 0;
				return 2<<1;	   
			}
		} else							/* MIME/storage file format */
		{
#define MRSID 9
			/* change mode index in case of SID frame */
			if (coding_mode == MRDTX)
			{
				coding_mode = MRSID;
				if (frame_type == TX_SID_FIRST)
				{
					for (i = 0; i < NBBITS_SID; i++)	prms[i] = BIT_0;
				}
			}
			/* -> force NO_DATA frame */
			if (coding_mode < 0 || coding_mode > 15 || (coding_mode > MRSID && coding_mode < 14))
			{
				coding_mode = 15;
			}
			/* mark empty frames between SID updates as NO_DATA frames */
			if (coding_mode == MRSID && frame_type == TX_NO_DATA)
			{
				coding_mode = 15;
			}
			/* set pointer for packed frame, note that we handle data as bytes */
			stream_ptr = (UWord8*)dataOut;
			/* insert table of contents (ToC) byte at the beginning of the packet */
			*stream_ptr = toc_byte[coding_mode];
			stream_ptr++;
			temp = 0;
			/* sort and pack AMR-WB speech or SID bits */
			for (i = 1; i < unpacked_size[coding_mode] + 1; i++)
			{
				if (prms[sort_ptr[coding_mode][i-1]] == BIT_1)
				{
					temp++;
				}
				if (i&0x7)
				{
					temp <<= 1;
				}
				else
				{
					*stream_ptr = temp;
					stream_ptr++;
					temp = 0;
				}
			}
			/* insert SID type indication and speech mode in case of SID frame */
			if (coding_mode == MRSID)
			{
				if (frame_type == TX_SID_UPDATE)
				{
					temp++;
				}
				temp <<= 4;
				temp += mode & 0x000F;
			}
			/* insert unused bits (zeros) at the tail of the last byte */
			if (unused_size[coding_mode])
			{
				temp <<= (unused_size[coding_mode] - 1);
			}
			*stream_ptr = temp;
			/* write packed frame into file (1 byte added to cover ToC entry) */
			return (1 + packed_size[coding_mode]);
		}
	}
}

/*-----------------------------------------------------*
* Parm_serial -> convert parameters to serial stream  *
*-----------------------------------------------------*/

void Parm_serial(
		Word16 value,                         /* input : parameter value */
		Word16 no_of_bits,                    /* input : number of bits  */
		Word16 ** prms
		)
{
	Word16 i, bit;
	*prms += no_of_bits;                  
	for (i = 0; i < no_of_bits; i++)
	{
		bit = (Word16) (value & 0x0001);    /* get lsb */
		if (bit == 0)
			*--(*prms) = BIT_0;
		else
			*--(*prms) = BIT_1;
		value >>= 1;          
	}
	*prms += no_of_bits;                  
	return;
}




