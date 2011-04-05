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
/*******************************************************************************
	File:		bitbuffer.c

	Content:	Bit Buffer Management functions

*******************************************************************************/

#include "bitbuffer.h"

/*****************************************************************************
*
* function name: updateBitBufWordPtr
* description:  update Bit Buffer pointer
*
*****************************************************************************/
static void updateBitBufWordPtr(HANDLE_BIT_BUF hBitBuf,
                                UWord8 **pBitBufWord,
                                Word16   cnt)
{
  *pBitBufWord += cnt;


  if(*pBitBufWord > hBitBuf->pBitBufEnd) {
    *pBitBufWord -= (hBitBuf->pBitBufEnd - hBitBuf->pBitBufBase + 1);
  }

  if(*pBitBufWord < hBitBuf->pBitBufBase) {
    *pBitBufWord += (hBitBuf->pBitBufEnd - hBitBuf->pBitBufBase + 1);
  }
}


/*****************************************************************************
*
* function name: CreateBitBuffer
* description:  create and init Bit Buffer Management
*
*****************************************************************************/
HANDLE_BIT_BUF CreateBitBuffer(HANDLE_BIT_BUF hBitBuf,
                               UWord8 *pBitBufBase,
                               Word16  bitBufSize)
{
  assert(bitBufSize*8 <= 32768);

  hBitBuf->pBitBufBase = pBitBufBase;
  hBitBuf->pBitBufEnd  = pBitBufBase + bitBufSize - 1;

  hBitBuf->pWriteNext  = pBitBufBase;

  hBitBuf->cache       = 0;

  hBitBuf->wBitPos     = 0;
  hBitBuf->cntBits     = 0;

  hBitBuf->size        = (bitBufSize << 3);
  hBitBuf->isValid     = 1;

  return hBitBuf;
}

/*****************************************************************************
*
* function name: DeleteBitBuffer
* description:  uninit Bit Buffer Management
*
*****************************************************************************/
void DeleteBitBuffer(HANDLE_BIT_BUF *hBitBuf)
{
  if(*hBitBuf)
	(*hBitBuf)->isValid = 0;
  *hBitBuf = NULL;
}

/*****************************************************************************
*
* function name: ResetBitBuf
* description:  reset Bit Buffer Management
*
*****************************************************************************/
void ResetBitBuf(HANDLE_BIT_BUF hBitBuf,
                 UWord8 *pBitBufBase,
                 Word16  bitBufSize)
{
  hBitBuf->pBitBufBase = pBitBufBase;
  hBitBuf->pBitBufEnd  = pBitBufBase + bitBufSize - 1;


  hBitBuf->pWriteNext  = pBitBufBase;

  hBitBuf->wBitPos     = 0;
  hBitBuf->cntBits     = 0;

  hBitBuf->cache	   = 0;
}

/*****************************************************************************
*
* function name: CopyBitBuf
* description:  copy Bit Buffer Management
*
*****************************************************************************/
void CopyBitBuf(HANDLE_BIT_BUF hBitBufSrc,
                HANDLE_BIT_BUF hBitBufDst)
{
  *hBitBufDst = *hBitBufSrc;
}

/*****************************************************************************
*
* function name: GetBitsAvail
* description:  get available bits
*
*****************************************************************************/
Word16 GetBitsAvail(HANDLE_BIT_BUF hBitBuf)
{
  return hBitBuf->cntBits;
}

/*****************************************************************************
*
* function name: WriteBits
* description:  write bits to the buffer
*
*****************************************************************************/
Word16 WriteBits(HANDLE_BIT_BUF hBitBuf,
                 Word32 writeValue,
                 Word16 noBitsToWrite)
{
  Word16 wBitPos;

  assert(noBitsToWrite <= (Word16)sizeof(Word32)*8);

  if(noBitsToWrite == 0)
	  return noBitsToWrite;

  hBitBuf->cntBits += noBitsToWrite;

  wBitPos = hBitBuf->wBitPos;
  wBitPos += noBitsToWrite;
  writeValue <<= 32 - wBitPos;
  writeValue |= hBitBuf->cache;

  while (wBitPos >= 8)
  {
	  UWord8 tmp;
	  tmp = (UWord8)((writeValue >> 24) & 0xFF);

	  *hBitBuf->pWriteNext++ = tmp;
	  writeValue <<= 8;
	  wBitPos -= 8;
  }

  hBitBuf->wBitPos = wBitPos;
  hBitBuf->cache = writeValue;

  return noBitsToWrite;
}
