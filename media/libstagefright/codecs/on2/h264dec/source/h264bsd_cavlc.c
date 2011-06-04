/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*------------------------------------------------------------------------------

    Table of contents

     1. Include headers
     2. External compiler flags
     3. Module defines
     4. Local function prototypes
     5. Functions
          DecodeCoeffToken
          DecodeLevelPrefix
          DecodeTotalZeros
          DecodeRunBefore
          DecodeResidualBlockCavlc

------------------------------------------------------------------------------*/

/*------------------------------------------------------------------------------
    1. Include headers
------------------------------------------------------------------------------*/

#include "h264bsd_cavlc.h"
#include "h264bsd_util.h"

/*------------------------------------------------------------------------------
    2. External compiler flags
--------------------------------------------------------------------------------

--------------------------------------------------------------------------------
    3. Module defines
------------------------------------------------------------------------------*/

/* Following descriptions use term "information field" to represent combination
 * of certain decoded symbol value and the length of the corresponding variable
 * length code word. For example, total_zeros information field consists of
 * 4 bits symbol value (bits [4,7]) along with four bits to represent length
 * of the VLC code word (bits [0,3]) */

/* macro to obtain length of the coeff token information field, bits [0,4]  */
#define LENGTH_TC(vlc) ((vlc) & 0x1F)
/* macro to obtain length of the other information fields, bits [0,3] */
#define LENGTH(vlc) ((vlc) & 0xF)
/* macro to obtain code word from the information fields, bits [4,7] */
#define INFO(vlc) (((vlc) >> 4) & 0xF)  /* 4 MSB bits contain information */
/* macro to obtain trailing ones from the coeff token information word,
 * bits [5,10] */
#define TRAILING_ONES(coeffToken) ((coeffToken>>5) & 0x3F)
/* macro to obtain total coeff from the coeff token information word,
 * bits [11,15] */
#define TOTAL_COEFF(coeffToken) (((coeffToken) >> 11) & 0x1F)

#define VLC_NOT_FOUND 0xFFFFFFFEU

/* VLC tables for coeff_token. Because of long codes (max. 16 bits) some of the
 * tables have been splitted into multiple separate tables. Each array/table
 * element has the following structure:
 * [5 bits for tot.coeff.] [6 bits for tr.ones] [5 bits for VLC length]
 * If there is a 0x0000 value, it means that there is not corresponding VLC
 * codeword for that index. */

/* VLC lengths up to 6 bits, 0 <= nC < 2 */
static const u16 coeffToken0_0[32] = {
    0x0000,0x0000,0x0000,0x2066,0x1026,0x0806,0x1865,0x1865,
    0x1043,0x1043,0x1043,0x1043,0x1043,0x1043,0x1043,0x1043,
    0x0822,0x0822,0x0822,0x0822,0x0822,0x0822,0x0822,0x0822,
    0x0822,0x0822,0x0822,0x0822,0x0822,0x0822,0x0822,0x0822};

/* VLC lengths up to 10 bits, 0 <= nC < 2 */
static const u16 coeffToken0_1[48] = {
    0x0000,0x0000,0x0000,0x0000,0x406a,0x304a,0x282a,0x200a,
    0x3869,0x3869,0x2849,0x2849,0x2029,0x2029,0x1809,0x1809,
    0x3068,0x3068,0x3068,0x3068,0x2048,0x2048,0x2048,0x2048,
    0x1828,0x1828,0x1828,0x1828,0x1008,0x1008,0x1008,0x1008,
    0x2867,0x2867,0x2867,0x2867,0x2867,0x2867,0x2867,0x2867,
    0x1847,0x1847,0x1847,0x1847,0x1847,0x1847,0x1847,0x1847};

/* VLC lengths up to 14 bits, 0 <= nC < 2 */
static const u16 coeffToken0_2[56] = {
    0x606e,0x584e,0x502e,0x500e,0x586e,0x504e,0x482e,0x480e,
    0x400d,0x400d,0x484d,0x484d,0x402d,0x402d,0x380d,0x380d,
    0x506d,0x506d,0x404d,0x404d,0x382d,0x382d,0x300d,0x300d,
    0x486b,0x486b,0x486b,0x486b,0x486b,0x486b,0x486b,0x486b,
    0x384b,0x384b,0x384b,0x384b,0x384b,0x384b,0x384b,0x384b,
    0x302b,0x302b,0x302b,0x302b,0x302b,0x302b,0x302b,0x302b,
    0x280b,0x280b,0x280b,0x280b,0x280b,0x280b,0x280b,0x280b};

/* VLC lengths up to 16 bits, 0 <= nC < 2 */
static const u16 coeffToken0_3[32] = {
    0x0000,0x0000,0x682f,0x682f,0x8010,0x8050,0x8030,0x7810,
    0x8070,0x7850,0x7830,0x7010,0x7870,0x7050,0x7030,0x6810,
    0x706f,0x706f,0x684f,0x684f,0x602f,0x602f,0x600f,0x600f,
    0x686f,0x686f,0x604f,0x604f,0x582f,0x582f,0x580f,0x580f};

/* VLC lengths up to 6 bits, 2 <= nC < 4 */
static const u16 coeffToken2_0[32] = {
    0x0000,0x0000,0x0000,0x0000,0x3866,0x2046,0x2026,0x1006,
    0x3066,0x1846,0x1826,0x0806,0x2865,0x2865,0x1025,0x1025,
    0x2064,0x2064,0x2064,0x2064,0x1864,0x1864,0x1864,0x1864,
    0x1043,0x1043,0x1043,0x1043,0x1043,0x1043,0x1043,0x1043};

/* VLC lengths up to 9 bits, 2 <= nC < 4 */
static const u16 coeffToken2_1[32] = {
    0x0000,0x0000,0x0000,0x0000,0x4869,0x3849,0x3829,0x3009,
    0x2808,0x2808,0x3048,0x3048,0x3028,0x3028,0x2008,0x2008,
    0x4067,0x4067,0x4067,0x4067,0x2847,0x2847,0x2847,0x2847,
    0x2827,0x2827,0x2827,0x2827,0x1807,0x1807,0x1807,0x1807};

/* VLC lengths up to 14 bits, 2 <= nC < 4 */
static const u16 coeffToken2_2[128] = {
    0x0000,0x0000,0x786d,0x786d,0x806e,0x804e,0x802e,0x800e,
    0x782e,0x780e,0x784e,0x702e,0x704d,0x704d,0x700d,0x700d,
    0x706d,0x706d,0x684d,0x684d,0x682d,0x682d,0x680d,0x680d,
    0x686d,0x686d,0x604d,0x604d,0x602d,0x602d,0x600d,0x600d,
    0x580c,0x580c,0x580c,0x580c,0x584c,0x584c,0x584c,0x584c,
    0x582c,0x582c,0x582c,0x582c,0x500c,0x500c,0x500c,0x500c,
    0x606c,0x606c,0x606c,0x606c,0x504c,0x504c,0x504c,0x504c,
    0x502c,0x502c,0x502c,0x502c,0x480c,0x480c,0x480c,0x480c,
    0x586b,0x586b,0x586b,0x586b,0x586b,0x586b,0x586b,0x586b,
    0x484b,0x484b,0x484b,0x484b,0x484b,0x484b,0x484b,0x484b,
    0x482b,0x482b,0x482b,0x482b,0x482b,0x482b,0x482b,0x482b,
    0x400b,0x400b,0x400b,0x400b,0x400b,0x400b,0x400b,0x400b,
    0x506b,0x506b,0x506b,0x506b,0x506b,0x506b,0x506b,0x506b,
    0x404b,0x404b,0x404b,0x404b,0x404b,0x404b,0x404b,0x404b,
    0x402b,0x402b,0x402b,0x402b,0x402b,0x402b,0x402b,0x402b,
    0x380b,0x380b,0x380b,0x380b,0x380b,0x380b,0x380b,0x380b};

/* VLC lengths up to 6 bits, 4 <= nC < 8 */
static const u16 coeffToken4_0[64] = {
    0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,0x0000,
    0x1806,0x3846,0x3826,0x1006,0x4866,0x3046,0x3026,0x0806,
    0x2825,0x2825,0x2845,0x2845,0x2025,0x2025,0x2045,0x2045,
    0x1825,0x1825,0x4065,0x4065,0x1845,0x1845,0x1025,0x1025,
    0x3864,0x3864,0x3864,0x3864,0x3064,0x3064,0x3064,0x3064,
    0x2864,0x2864,0x2864,0x2864,0x2064,0x2064,0x2064,0x2064,
    0x1864,0x1864,0x1864,0x1864,0x1044,0x1044,0x1044,0x1044,
    0x0824,0x0824,0x0824,0x0824,0x0004,0x0004,0x0004,0x0004};

/* VLC lengths up to 10 bits, 4 <= nC < 8 */
static const u16 coeffToken4_1[128] = {
    0x0000,0x800a,0x806a,0x804a,0x802a,0x780a,0x786a,0x784a,
    0x782a,0x700a,0x706a,0x704a,0x702a,0x680a,0x6829,0x6829,
    0x6009,0x6009,0x6849,0x6849,0x6029,0x6029,0x5809,0x5809,
    0x6869,0x6869,0x6049,0x6049,0x5829,0x5829,0x5009,0x5009,
    0x6068,0x6068,0x6068,0x6068,0x5848,0x5848,0x5848,0x5848,
    0x5028,0x5028,0x5028,0x5028,0x4808,0x4808,0x4808,0x4808,
    0x5868,0x5868,0x5868,0x5868,0x5048,0x5048,0x5048,0x5048,
    0x4828,0x4828,0x4828,0x4828,0x4008,0x4008,0x4008,0x4008,
    0x3807,0x3807,0x3807,0x3807,0x3807,0x3807,0x3807,0x3807,
    0x3007,0x3007,0x3007,0x3007,0x3007,0x3007,0x3007,0x3007,
    0x4847,0x4847,0x4847,0x4847,0x4847,0x4847,0x4847,0x4847,
    0x2807,0x2807,0x2807,0x2807,0x2807,0x2807,0x2807,0x2807,
    0x5067,0x5067,0x5067,0x5067,0x5067,0x5067,0x5067,0x5067,
    0x4047,0x4047,0x4047,0x4047,0x4047,0x4047,0x4047,0x4047,
    0x4027,0x4027,0x4027,0x4027,0x4027,0x4027,0x4027,0x4027,
    0x2007,0x2007,0x2007,0x2007,0x2007,0x2007,0x2007,0x2007};

/* fixed 6 bit length VLC, nC <= 8 */
static const u16 coeffToken8[64] = {
    0x0806,0x0826,0x0000,0x0006,0x1006,0x1026,0x1046,0x0000,
    0x1806,0x1826,0x1846,0x1866,0x2006,0x2026,0x2046,0x2066,
    0x2806,0x2826,0x2846,0x2866,0x3006,0x3026,0x3046,0x3066,
    0x3806,0x3826,0x3846,0x3866,0x4006,0x4026,0x4046,0x4066,
    0x4806,0x4826,0x4846,0x4866,0x5006,0x5026,0x5046,0x5066,
    0x5806,0x5826,0x5846,0x5866,0x6006,0x6026,0x6046,0x6066,
    0x6806,0x6826,0x6846,0x6866,0x7006,0x7026,0x7046,0x7066,
    0x7806,0x7826,0x7846,0x7866,0x8006,0x8026,0x8046,0x8066};

/* VLC lengths up to 3 bits, nC == -1 */
static const u16 coeffTokenMinus1_0[8] = {
    0x0000,0x1043,0x0002,0x0002,0x0821,0x0821,0x0821,0x0821};

/* VLC lengths up to 8 bits, nC == -1 */
static const u16 coeffTokenMinus1_1[32] = {
    0x2067,0x2067,0x2048,0x2028,0x1847,0x1847,0x1827,0x1827,
    0x2006,0x2006,0x2006,0x2006,0x1806,0x1806,0x1806,0x1806,
    0x1006,0x1006,0x1006,0x1006,0x1866,0x1866,0x1866,0x1866,
    0x1026,0x1026,0x1026,0x1026,0x0806,0x0806,0x0806,0x0806};

/* VLC tables for total_zeros. One table containing longer code, totalZeros_1,
 * has been broken into two separate tables. Table elements have the
 * following structure:
 * [4 bits for info] [4 bits for VLC length] */

/* VLC lengths up to 5 bits */
static const u8 totalZeros_1_0[32] = {
    0x00,0x00,0x65,0x55,0x44,0x44,0x34,0x34,
    0x23,0x23,0x23,0x23,0x13,0x13,0x13,0x13,
    0x01,0x01,0x01,0x01,0x01,0x01,0x01,0x01,
    0x01,0x01,0x01,0x01,0x01,0x01,0x01,0x01};

/* VLC lengths up to 9 bits */
static const u8 totalZeros_1_1[32] = {
    0x00,0xf9,0xe9,0xd9,0xc8,0xc8,0xb8,0xb8,
    0xa7,0xa7,0xa7,0xa7,0x97,0x97,0x97,0x97,
    0x86,0x86,0x86,0x86,0x86,0x86,0x86,0x86,
    0x76,0x76,0x76,0x76,0x76,0x76,0x76,0x76};

static const u8 totalZeros_2[64] = {
    0xe6,0xd6,0xc6,0xb6,0xa5,0xa5,0x95,0x95,
    0x84,0x84,0x84,0x84,0x74,0x74,0x74,0x74,
    0x64,0x64,0x64,0x64,0x54,0x54,0x54,0x54,
    0x43,0x43,0x43,0x43,0x43,0x43,0x43,0x43,
    0x33,0x33,0x33,0x33,0x33,0x33,0x33,0x33,
    0x23,0x23,0x23,0x23,0x23,0x23,0x23,0x23,
    0x13,0x13,0x13,0x13,0x13,0x13,0x13,0x13,
    0x03,0x03,0x03,0x03,0x03,0x03,0x03,0x03};

static const u8 totalZeros_3[64] = {
    0xd6,0xb6,0xc5,0xc5,0xa5,0xa5,0x95,0x95,
    0x84,0x84,0x84,0x84,0x54,0x54,0x54,0x54,
    0x44,0x44,0x44,0x44,0x04,0x04,0x04,0x04,
    0x73,0x73,0x73,0x73,0x73,0x73,0x73,0x73,
    0x63,0x63,0x63,0x63,0x63,0x63,0x63,0x63,
    0x33,0x33,0x33,0x33,0x33,0x33,0x33,0x33,
    0x23,0x23,0x23,0x23,0x23,0x23,0x23,0x23,
    0x13,0x13,0x13,0x13,0x13,0x13,0x13,0x13};

static const u8 totalZeros_4[32] = {
    0xc5,0xb5,0xa5,0x05,0x94,0x94,0x74,0x74,
    0x34,0x34,0x24,0x24,0x83,0x83,0x83,0x83,
    0x63,0x63,0x63,0x63,0x53,0x53,0x53,0x53,
    0x43,0x43,0x43,0x43,0x13,0x13,0x13,0x13};

static const u8 totalZeros_5[32] = {
    0xb5,0x95,0xa4,0xa4,0x84,0x84,0x24,0x24,
    0x14,0x14,0x04,0x04,0x73,0x73,0x73,0x73,
    0x63,0x63,0x63,0x63,0x53,0x53,0x53,0x53,
    0x43,0x43,0x43,0x43,0x33,0x33,0x33,0x33};

static const u8 totalZeros_6[64] = {
    0xa6,0x06,0x15,0x15,0x84,0x84,0x84,0x84,
    0x93,0x93,0x93,0x93,0x93,0x93,0x93,0x93,
    0x73,0x73,0x73,0x73,0x73,0x73,0x73,0x73,
    0x63,0x63,0x63,0x63,0x63,0x63,0x63,0x63,
    0x53,0x53,0x53,0x53,0x53,0x53,0x53,0x53,
    0x43,0x43,0x43,0x43,0x43,0x43,0x43,0x43,
    0x33,0x33,0x33,0x33,0x33,0x33,0x33,0x33,
    0x23,0x23,0x23,0x23,0x23,0x23,0x23,0x23};

static const u8 totalZeros_7[64] = {
    0x96,0x06,0x15,0x15,0x74,0x74,0x74,0x74,
    0x83,0x83,0x83,0x83,0x83,0x83,0x83,0x83,
    0x63,0x63,0x63,0x63,0x63,0x63,0x63,0x63,
    0x43,0x43,0x43,0x43,0x43,0x43,0x43,0x43,
    0x33,0x33,0x33,0x33,0x33,0x33,0x33,0x33,
    0x23,0x23,0x23,0x23,0x23,0x23,0x23,0x23,
    0x52,0x52,0x52,0x52,0x52,0x52,0x52,0x52,
    0x52,0x52,0x52,0x52,0x52,0x52,0x52,0x52};

static const u8 totalZeros_8[64] = {
    0x86,0x06,0x25,0x25,0x14,0x14,0x14,0x14,
    0x73,0x73,0x73,0x73,0x73,0x73,0x73,0x73,
    0x63,0x63,0x63,0x63,0x63,0x63,0x63,0x63,
    0x33,0x33,0x33,0x33,0x33,0x33,0x33,0x33,
    0x52,0x52,0x52,0x52,0x52,0x52,0x52,0x52,
    0x52,0x52,0x52,0x52,0x52,0x52,0x52,0x52,
    0x42,0x42,0x42,0x42,0x42,0x42,0x42,0x42,
    0x42,0x42,0x42,0x42,0x42,0x42,0x42,0x42};

static const u8 totalZeros_9[64] = {
    0x16,0x06,0x75,0x75,0x24,0x24,0x24,0x24,
    0x53,0x53,0x53,0x53,0x53,0x53,0x53,0x53,
    0x62,0x62,0x62,0x62,0x62,0x62,0x62,0x62,
    0x62,0x62,0x62,0x62,0x62,0x62,0x62,0x62,
    0x42,0x42,0x42,0x42,0x42,0x42,0x42,0x42,
    0x42,0x42,0x42,0x42,0x42,0x42,0x42,0x42,
    0x32,0x32,0x32,0x32,0x32,0x32,0x32,0x32,
    0x32,0x32,0x32,0x32,0x32,0x32,0x32,0x32};

static const u8 totalZeros_10[32] = {
    0x15,0x05,0x64,0x64,0x23,0x23,0x23,0x23,
    0x52,0x52,0x52,0x52,0x52,0x52,0x52,0x52,
    0x42,0x42,0x42,0x42,0x42,0x42,0x42,0x42,
    0x32,0x32,0x32,0x32,0x32,0x32,0x32,0x32};

static const u8 totalZeros_11[16] = {
    0x04,0x14,0x23,0x23,0x33,0x33,0x53,0x53,
    0x41,0x41,0x41,0x41,0x41,0x41,0x41,0x41};

static const u8 totalZeros_12[16] = {
    0x04,0x14,0x43,0x43,0x22,0x22,0x22,0x22,
    0x31,0x31,0x31,0x31,0x31,0x31,0x31,0x31};

static const u8 totalZeros_13[8] = {0x03,0x13,0x32,0x32,0x21,0x21,0x21,0x21};

static const u8 totalZeros_14[4] = {0x02,0x12,0x21,0x21};

/* VLC tables for run_before. Table elements have the following structure:
 * [4 bits for info] [4bits for VLC length]
 */

static const u8 runBefore_6[8] = {0x13,0x23,0x43,0x33,0x63,0x53,0x02,0x02};

static const u8 runBefore_5[8] = {0x53,0x43,0x33,0x23,0x12,0x12,0x02,0x02};

static const u8 runBefore_4[8] = {0x43,0x33,0x22,0x22,0x12,0x12,0x02,0x02};

static const u8 runBefore_3[4] = {0x32,0x22,0x12,0x02};

static const u8 runBefore_2[4] = {0x22,0x12,0x01,0x01};

static const u8 runBefore_1[2] = {0x11,0x01};

/* following four macros are used to handle stream buffer "cache" in the CAVLC
 * decoding function */

/* macro to initialize stream buffer cache, fills the buffer (32 bits) */
#define BUFFER_INIT(value, bits) \
{ \
    bits = 32; \
    value = h264bsdShowBits32(pStrmData); \
}

/* macro to read numBits bits from the buffer, bits will be written to
 * outVal. Refills the buffer if not enough bits left */
#define BUFFER_SHOW(value, bits, outVal, numBits) \
{ \
    if (bits < (numBits)) \
    { \
        if(h264bsdFlushBits(pStrmData,32-bits) == END_OF_STREAM) \
            return(HANTRO_NOK); \
        value = h264bsdShowBits32(pStrmData); \
        bits = 32; \
    } \
    (outVal) = value >> (32 - (numBits)); \
}

/* macro to flush numBits bits from the buffer */
#define BUFFER_FLUSH(value, bits, numBits) \
{ \
    value <<= (numBits); \
    bits -= (numBits); \
}

/* macro to read and flush  numBits bits from the buffer, bits will be written
 * to outVal. Refills the buffer if not enough bits left */
#define BUFFER_GET(value, bits, outVal, numBits) \
{ \
    if (bits < (numBits)) \
    { \
        if(h264bsdFlushBits(pStrmData,32-bits) == END_OF_STREAM) \
            return(HANTRO_NOK); \
        value = h264bsdShowBits32(pStrmData); \
        bits = 32; \
    } \
    (outVal) = value >> (32 - (numBits)); \
    value <<= (numBits); \
    bits -= (numBits); \
}

/*------------------------------------------------------------------------------
    4. Local function prototypes
------------------------------------------------------------------------------*/

static u32 DecodeCoeffToken(u32 bits, u32 nc);

static u32 DecodeLevelPrefix(u32 bits);

static u32 DecodeTotalZeros(u32 bits, u32 totalCoeff, u32 isChromaDC);

static u32 DecodeRunBefore(u32 bits,u32 zerosLeft);

/*------------------------------------------------------------------------------

    Function: DecodeCoeffToken

        Functional description:
          Function to decode coeff_token information field from the stream.

        Inputs:
          u32 bits                  next 16 stream bits
          u32 nc                    nC, see standard for details

        Outputs:
          u32  information field (11 bits for value, 5 bits for length)

------------------------------------------------------------------------------*/

u32 DecodeCoeffToken(u32 bits, u32 nc)
{

/* Variables */

    u32 value;

/* Code */

    /* standard defines that nc for decoding of chroma dc coefficients is -1,
     * represented by u32 here -> -1 maps to 2^32 - 1 */
    ASSERT(nc <= 16 || nc == (u32)(-1));

    if (nc < 2)
    {
        if (bits >= 0x8000)
        {
            value = 0x0001;
        }
        else if (bits >= 0x0C00)
            value = coeffToken0_0[bits >> 10];
        else if (bits >= 0x0100)
            value = coeffToken0_1[bits >> 6];
        else if (bits >= 0x0020)
            value = coeffToken0_2[(bits>>2)-8];
        else
            value = coeffToken0_3[bits];
    }
    else if (nc < 4)
    {
        if (bits >= 0x8000)
        {
            value = bits & 0x4000 ? 0x0002 : 0x0822;
        }
        else if (bits >= 0x1000)
            value = coeffToken2_0[bits >> 10];
        else if (bits >= 0x0200)
            value = coeffToken2_1[bits >> 7];
        else
            value = coeffToken2_2[bits>>2];
    }
    else if (nc < 8)
    {
        value = coeffToken4_0[bits >> 10];
        if (!value)
            value = coeffToken4_1[bits>>6];
    }
    else if (nc <= 16)
    {
        value = coeffToken8[bits>>10];
    }
    else
    {
        value = coeffTokenMinus1_0[bits >> 13];
        if (!value)
            value = coeffTokenMinus1_1[bits>>8];
    }

    return(value);

}

/*------------------------------------------------------------------------------

    Function: DecodeLevelPrefix

        Functional description:
          Function to decode level_prefix information field from the stream

        Inputs:
          u32 bits      next 16 stream bits

        Outputs:
          u32  level_prefix information field or VLC_NOT_FOUND

------------------------------------------------------------------------------*/

u32 DecodeLevelPrefix(u32 bits)
{

/* Variables */

    u32 numZeros;

/* Code */

    if (bits >= 0x8000)
        numZeros = 0;
    else if (bits >= 0x4000)
        numZeros = 1;
    else if (bits >= 0x2000)
        numZeros = 2;
    else if (bits >= 0x1000)
        numZeros = 3;
    else if (bits >= 0x0800)
        numZeros = 4;
    else if (bits >= 0x0400)
        numZeros = 5;
    else if (bits >= 0x0200)
        numZeros = 6;
    else if (bits >= 0x0100)
        numZeros = 7;
    else if (bits >= 0x0080)
        numZeros = 8;
    else if (bits >= 0x0040)
        numZeros = 9;
    else if (bits >= 0x0020)
        numZeros = 10;
    else if (bits >= 0x0010)
        numZeros = 11;
    else if (bits >= 0x0008)
        numZeros = 12;
    else if (bits >= 0x0004)
        numZeros = 13;
    else if (bits >= 0x0002)
        numZeros = 14;
    else if (bits >= 0x0001)
        numZeros = 15;
    else /* more than 15 zeros encountered which is an error */
        return(VLC_NOT_FOUND);

    return(numZeros);

}

/*------------------------------------------------------------------------------

    Function: DecodeTotalZeros

        Functional description:
          Function to decode total_zeros information field from the stream

        Inputs:
          u32 bits                  next 9 stream bits
          u32 totalCoeff            total number of coefficients for the block
                                    being decoded
          u32 isChromaDC           flag to indicate chroma DC block

        Outputs:
          u32  information field (4 bits value, 4 bits length)

------------------------------------------------------------------------------*/

u32 DecodeTotalZeros(u32 bits, u32 totalCoeff, u32 isChromaDC)
{

/* Variables */

    u32 value = 0x0;

/* Code */

    ASSERT(totalCoeff);

    if (!isChromaDC)
    {
        ASSERT(totalCoeff < 16);
        switch (totalCoeff)
        {
            case 1:
                value = totalZeros_1_0[bits >> 4];
                if (!value)
                    value = totalZeros_1_1[bits];
                break;

            case 2:
                value = totalZeros_2[bits >> 3];
                break;

            case 3:
                value = totalZeros_3[bits >> 3];
                break;

            case 4:
                value = totalZeros_4[bits >> 4];
                break;

            case 5:
                value = totalZeros_5[bits >> 4];
                break;

            case 6:
                value = totalZeros_6[bits >> 3];
                break;

            case 7:
                value = totalZeros_7[bits >> 3];
                break;

            case 8:
                value = totalZeros_8[bits >> 3];
                break;

            case 9:
                value = totalZeros_9[bits >> 3];
                break;

            case 10:
                value = totalZeros_10[bits >> 4];
                break;

            case 11:
                value = totalZeros_11[bits >> 5];
                break;

            case 12:
                value = totalZeros_12[bits >> 5];
                break;

            case 13:
                value = totalZeros_13[bits >> 6];
                break;

            case 14:
                value = totalZeros_14[bits >> 7];
                break;

            default: /* case 15 */
                value = (bits >> 8) ? 0x11 : 0x01;
                break;
        }
    }
    else
    {
        ASSERT(totalCoeff < 4);
        bits >>= 6;
        if (bits > 3)
            value = 0x01;
        else
        {
            if (totalCoeff == 3)
                value = 0x11;
            else if (bits > 1)
            {
                value = 0x12;
            }
            else if (totalCoeff == 2)
                value = 0x22;
            else if (bits)
                value = 0x23;
            else
                value = 0x33;
        }
    }

    return(value);

}

/*------------------------------------------------------------------------------

    Function: DecodeRunBefore

        Functional description:
          Function to decode run_before information field from the stream

        Inputs:
          u32 bits                  next 11 stream bits
          u32 zerosLeft             number of zeros left for the current block

        Outputs:
          u32  information field (4 bits value, 4 bits length)

------------------------------------------------------------------------------*/

u32 DecodeRunBefore(u32 bits, u32 zerosLeft)
{

/* Variables */

    u32 value = 0x0;

/* Code */

    switch (zerosLeft)
    {
        case 1:
            value = runBefore_1[bits>>10];
            break;

        case 2:
            value = runBefore_2[bits>>9];
            break;

        case 3:
            value = runBefore_3[bits>>9];
            break;

        case 4:
            value = runBefore_4[bits>>8];
            break;

        case 5:
            value = runBefore_5[bits>>8];
            break;

        case 6:
            value = runBefore_6[bits>>8];
            break;

        default:
            if (bits >= 0x100)
                value = ((7-(bits>>8))<<4)+0x3;
            else if (bits >= 0x80)
                value = 0x74;
            else if (bits >= 0x40)
                value = 0x85;
            else if (bits >= 0x20)
                value = 0x96;
            else if (bits >= 0x10)
                value = 0xa7;
            else if (bits >= 0x8)
                value = 0xb8;
            else if (bits >= 0x4)
                value = 0xc9;
            else if (bits >= 0x2)
                value = 0xdA;
            else if (bits)
                value = 0xeB;
            if (INFO(value) > zerosLeft)
                value = 0;
            break;
    }

    return(value);

}

/*------------------------------------------------------------------------------

    Function: DecodeResidualBlockCavlc

        Functional description:
          Function to decode one CAVLC coded block. This corresponds to
          syntax elements residual_block_cavlc() in the standard.

        Inputs:
          pStrmData             pointer to stream data structure
          nc                    nC value
          maxNumCoeff           maximum number of residual coefficients

        Outputs:
          coeffLevel            stores decoded coefficient levels

        Returns:
          numCoeffs             on bits [4,11] if successful
          coeffMap              on bits [16,31] if successful, this is bit map
                                where each bit indicates if the corresponding
                                coefficient was zero (0) or non-zero (1)
          HANTRO_NOK            end of stream or error in stream

------------------------------------------------------------------------------*/

u32 h264bsdDecodeResidualBlockCavlc(
  strmData_t *pStrmData,
  i32 *coeffLevel,
  i32 nc,
  u32 maxNumCoeff)
{

/* Variables */

    u32 i, tmp, totalCoeff, trailingOnes, suffixLength, levelPrefix;
    u32 levelSuffix, zerosLeft, bit;
    i32 level[16];
    u32 run[16];
    /* stream "cache" */
    u32 bufferValue;
    u32 bufferBits;

/* Code */

    ASSERT(pStrmData);
    ASSERT(coeffLevel);
    ASSERT(nc > -2);
    ASSERT(maxNumCoeff == 4 || maxNumCoeff == 15 || maxNumCoeff == 16);
    ASSERT(VLC_NOT_FOUND != END_OF_STREAM);

    /* assume that coeffLevel array has been "cleaned" by caller */

    BUFFER_INIT(bufferValue, bufferBits);

    /*lint -e774 disable lint warning on always false comparison */
    BUFFER_SHOW(bufferValue, bufferBits, bit, 16);
    /*lint +e774 */
    tmp = DecodeCoeffToken(bit, (u32)nc);
    if (!tmp)
        return(HANTRO_NOK);
    BUFFER_FLUSH(bufferValue, bufferBits, LENGTH_TC(tmp));

    totalCoeff = TOTAL_COEFF(tmp);
    if (totalCoeff > maxNumCoeff)
        return(HANTRO_NOK);
    trailingOnes = TRAILING_ONES(tmp);

    if (totalCoeff != 0)
    {
        i = 0;
        /* nonzero coefficients: +/- 1 */
        if (trailingOnes)
        {
            BUFFER_GET(bufferValue, bufferBits, bit, trailingOnes);
            tmp = 1 << (trailingOnes - 1);
            for (; tmp; i++)
            {
                level[i] = bit & tmp ? -1 : 1;
                tmp >>= 1;
            }
        }

        /* other levels */
        if (totalCoeff > 10 && trailingOnes < 3)
            suffixLength = 1;
        else
            suffixLength = 0;

        for (; i < totalCoeff; i++)
        {
            BUFFER_SHOW(bufferValue, bufferBits, bit, 16);
            levelPrefix = DecodeLevelPrefix(bit);
            if (levelPrefix == VLC_NOT_FOUND)
                return(HANTRO_NOK);
            BUFFER_FLUSH(bufferValue, bufferBits, levelPrefix+1);

            if (levelPrefix < 14)
                tmp = suffixLength;
            else if (levelPrefix == 14)
            {
                tmp = suffixLength ? suffixLength : 4;
            }
            else
            {
                /* setting suffixLength to 1 here corresponds to adding 15
                 * to levelCode value if levelPrefix == 15 and
                 * suffixLength == 0 */
                if (!suffixLength)
                    suffixLength = 1;
                tmp = 12;
            }

            if (suffixLength)
                levelPrefix <<= suffixLength;

            if (tmp)
            {
                BUFFER_GET(bufferValue, bufferBits, levelSuffix, tmp);
                levelPrefix += levelSuffix;
            }

            tmp = levelPrefix;

            if (i == trailingOnes && trailingOnes < 3)
                tmp += 2;

            level[i] = (tmp+2)>>1;

            if (suffixLength == 0)
                suffixLength = 1;

            if ((level[i] > (3 << (suffixLength - 1))) && suffixLength < 6)
                suffixLength++;

            if (tmp & 0x1)
                level[i] = -level[i];
        }

        /* zero runs */
        if (totalCoeff < maxNumCoeff)
        {
            BUFFER_SHOW(bufferValue, bufferBits, bit,9);
            zerosLeft = DecodeTotalZeros(bit, totalCoeff,
                                        (u32)(maxNumCoeff == 4));
            if (!zerosLeft)
                return(HANTRO_NOK);
            BUFFER_FLUSH(bufferValue, bufferBits, LENGTH(zerosLeft));
            zerosLeft = INFO(zerosLeft);
        }
        else
            zerosLeft = 0;

        for (i = 0; i < totalCoeff - 1; i++)
        {
            if (zerosLeft > 0)
            {
                BUFFER_SHOW(bufferValue, bufferBits, bit,11);
                tmp = DecodeRunBefore(bit, zerosLeft);
                if (!tmp)
                    return(HANTRO_NOK);
                BUFFER_FLUSH(bufferValue, bufferBits, LENGTH(tmp));
                run[i] = INFO(tmp);
                zerosLeft -= run[i]++;
            }
            else
            {
                run[i] = 1;
            }
        }

        /* combining level and run, levelSuffix variable used to hold coeffMap,
         * i.e. bit map indicating which coefficients had non-zero value. */

        /*lint -esym(771,level,run) level and run are always initialized */
        tmp = zerosLeft;
        coeffLevel[tmp] = level[totalCoeff-1];
        levelSuffix = 1 << tmp;
        for (i = totalCoeff-1; i--;)
        {
            tmp += run[i];
            levelSuffix |= 1 << tmp;
            coeffLevel[tmp] = level[i];
        }

    }
    else
        levelSuffix = 0;

    if (h264bsdFlushBits(pStrmData, 32-bufferBits) != HANTRO_OK)
        return(HANTRO_NOK);

    return((totalCoeff << 4) | (levelSuffix << 16));
}

