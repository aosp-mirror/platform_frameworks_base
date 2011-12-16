/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef ANDROID_EFFECTREVERB_H_
#define ANDROID_EFFECTREVERB_H_

#include <audio_effects/effect_environmentalreverb.h>
#include <audio_effects/effect_presetreverb.h>


/*------------------------------------
 * defines
 *------------------------------------
*/

/*
CIRCULAR() calculates the array index using modulo arithmetic.
The "trick" is that modulo arithmetic is simplified by masking
the effective address where the mask is (2^n)-1. This only works
if the buffer size is a power of two.
*/
#define CIRCULAR(base,offset,size) (uint32_t)(               \
            (                                               \
                ((int32_t)(base)) + ((int32_t)(offset))     \
            )                                               \
            & size                                          \
                                            )

#define NUM_OUTPUT_CHANNELS 2
#define OUTPUT_CHANNELS AUDIO_CHANNEL_OUT_STEREO

#define REVERB_BUFFER_SIZE_IN_SAMPLES_MAX   16384

#define REVERB_NUM_PRESETS  REVERB_PRESET_PLATE   // REVERB_PRESET_NONE is not included
#define REVERB_MAX_NUM_REFLECTIONS      5   // max num reflections per channel


// xfade parameters
#define REVERB_XFADE_PERIOD_IN_SECONDS      (double) (100.0 / 1000.0)        // xfade once every this many seconds


/**********/
/* the entire synth uses various flags in a bit field */

/* if flag is set, synth reset has been requested */
#define REVERB_FLAG_RESET_IS_REQUESTED          0x01    /* bit 0 */
#define MASK_REVERB_RESET_IS_REQUESTED          0x01
#define MASK_REVERB_RESET_IS_NOT_REQUESTED      (uint32_t)(~MASK_REVERB_RESET_IS_REQUESTED)

/*
by default, we always want to update ALL channel parameters
when we reset the synth (e.g., during GM ON)
*/
#define DEFAULT_REVERB_FLAGS                    0x0

/* coefficients for generating sin, cos */
#define REVERB_PAN_G2   4294940151          /* -0.82842712474619 = 2 - 4/sqrt(2) */
/*
int32_t nPanG1 = +1.0 for sin
int32_t nPanG1 = -1.0 for cos
*/
#define REVERB_PAN_G0   23170               /* 0.707106781186547 = 1/sqrt(2) */

/*************************************************************/
// define the input injection points
#define GUARD               5                       // safety guard of this many samples

#define MAX_AP_TIME         (int) ((20*65536)/1000)  // delay time in time units (65536th of sec)
#define MAX_DELAY_TIME      (int) ((65*65536)/1000)  // delay time in time units
#define MAX_EARLY_TIME      (int) ((65*65536)/1000)  // delay time in time units

#define AP0_IN              0


#define REVERB_DEFAULT_ROOM_NUMBER      1       // default preset number
#define DEFAULT_AP0_GAIN                19400
#define DEFAULT_AP1_GAIN                -19400

#define REVERB_DEFAULT_WET              32767
#define REVERB_DEFAULT_DRY              0

#define REVERB_WET_MAX              32767
#define REVERB_WET_MIN              0
#define REVERB_DRY_MAX              32767
#define REVERB_DRY_MIN              0

// constants for reverb density
// The density expressed in permilles changes the Allpass delay in a linear manner in the range defined by
// AP0_TIME_BASE to AP0_TIME_BASE + AP0_TIME_RANGE
#define AP0_TIME_BASE (int)((9*65536)/1000)
#define AP0_TIME_RANGE (int)((4*65536)/1000)
#define AP1_TIME_BASE (int)((12*65536)/1000)
#define AP1_TIME_RANGE (int)((8*65536)/1000)

// constants for reverb diffusion
// The diffusion expressed in permilles changes the Allpass gain in a linear manner in the range defined by
// AP0_GAIN_BASE to AP0_GAIN_BASE + AP0_GAIN_RANGE
#define AP0_GAIN_BASE (int)(9830)
#define AP0_GAIN_RANGE (int)(19660-9830)
#define AP1_GAIN_BASE (int)(6553)
#define AP1_GAIN_RANGE (int)(22936-6553)


enum reverb_state_e {
    REVERB_STATE_UNINITIALIZED,
    REVERB_STATE_INITIALIZED,
    REVERB_STATE_ACTIVE,
};

/* parameters for each allpass */
typedef struct
{
    uint16_t             m_zApOut;       // delay offset for ap out

    int16_t             m_nApGain;      // gain for ap

    uint16_t             m_zApIn;        // delay offset for ap in

} allpass_object_t;


/* parameters for early reflections */
typedef struct
{
    uint16_t            m_zDelay[REVERB_MAX_NUM_REFLECTIONS];   // delay offset for ap out

    int16_t             m_nGain[REVERB_MAX_NUM_REFLECTIONS];    // gain for ap

} early_reflection_object_t;

//demo
typedef struct
{
    int16_t             m_nRvbLpfFbk;
    int16_t             m_nRvbLpfFwd;
    int16_t             m_nRoomLpfFbk;
    int16_t             m_nRoomLpfFwd;

    int16_t             m_nEarlyGain;
    int16_t             m_nEarlyDelay;
    int16_t             m_nLateGain;
    int16_t             m_nLateDelay;

    early_reflection_object_t m_sEarlyL;
    early_reflection_object_t m_sEarlyR;

    uint16_t            m_nMaxExcursion; //28
    int16_t             m_nXfadeInterval;

    int16_t             m_nAp0_ApGain; //30
    int16_t             m_nAp0_ApOut;
    int16_t             m_nAp1_ApGain;
    int16_t             m_nAp1_ApOut;
    int16_t             m_nDiffusion;

    int16_t             m_rfu4;
    int16_t             m_rfu5;
    int16_t             m_rfu6;
    int16_t             m_rfu7;
    int16_t             m_rfu8;
    int16_t             m_rfu9;
    int16_t             m_rfu10; //43

} reverb_preset_t;

typedef struct
{
    reverb_preset_t     m_sPreset[REVERB_NUM_PRESETS]; // array of presets(does not include REVERB_PRESET_NONE)

} reverb_preset_bank_t;


/* parameters for each reverb */
typedef struct
{
    /* update counter keeps track of when synth params need updating */
    /* only needs to be as large as REVERB_UPDATE_PERIOD_IN_SAMPLES */
    int16_t             m_nUpdateCounter;

    uint16_t             m_nBaseIndex;                                   // base index for circular buffer

    // reverb delay line offsets, allpass parameters, etc:

    short             m_nRevFbkR;              // combine feedback reverb right out with dry left in
    short             m_zOutLpfL;              // left reverb output

    allpass_object_t    m_sAp0;                     // allpass 0 (left channel)

    uint16_t             m_zD0In;                    // delay offset for delay line D0 in

    short             m_nRevFbkL;              // combine feedback reverb left out with dry right in
    short             m_zOutLpfR;              // right reverb output

    allpass_object_t    m_sAp1;                     // allpass 1 (right channel)

    uint16_t             m_zD1In;                    // delay offset for delay line D1 in

    // delay output taps, notice criss cross order
    uint16_t             m_zD0Self;                  // self feeds forward d0 --> d0

    uint16_t             m_zD1Cross;                 // cross feeds across d1 --> d0

    uint16_t             m_zD1Self;                  // self feeds forward d1 --> d1

    uint16_t             m_zD0Cross;                 // cross feeds across d0 --> d1

    int16_t             m_nSin;                     // gain for self taps

    int16_t             m_nCos;                     // gain for cross taps

    int16_t             m_nSinIncrement;            // increment for gain

    int16_t             m_nCosIncrement;            // increment for gain

    int16_t             m_nRvbLpfFwd;                  // reverb feedback lpf forward gain (includes scaling for mixer)

    int16_t             m_nRvbLpfFbk;                  // reverb feedback lpf feedback gain

    int16_t             m_nRoomLpfFwd;                  // room lpf forward gain (includes scaling for mixer)

    int16_t             m_nRoomLpfFbk;                  // room lpf feedback gain

    uint16_t            m_nXfadeInterval;           // update/xfade after this many samples

    uint16_t            m_nXfadeCounter;            // keep track of when to xfade

    int16_t             m_nPhase;                   // -1 <= m_nPhase < 1
                                                    // but during sin,cos calculations
                                                    // use m_nPhase/2

    int16_t             m_nPhaseIncrement;          // add this to m_nPhase each frame

    int16_t             m_nNoise;                   // random noise sample

    uint16_t            m_nMaxExcursion;            // the taps can excurse +/- this amount

    uint16_t            m_bUseNoise;                // if TRUE, use noise as input signal

    uint16_t            m_bBypass;                  // if TRUE, then bypass reverb and copy input to output

    int16_t             m_nCurrentRoom;             // preset number for current room

    int16_t             m_nNextRoom;                // preset number for next room

    int16_t             m_nEarlyGain;               // gain for early (widen) signal
    int16_t             m_nEarlyDelay;              // initial dealy for early (widen) signal
    int16_t             m_nEarly0in;
    int16_t             m_nEarly1in;
    int16_t             m_nLateGain;               // gain for late reverb
    int16_t             m_nLateDelay;

    int16_t             m_nDiffusion;

    early_reflection_object_t   m_sEarlyL;          // left channel early reflections
    early_reflection_object_t   m_sEarlyR;          // right channel early reflections

    short             m_nDelayLine[REVERB_BUFFER_SIZE_IN_SAMPLES_MAX];    // one large delay line for all reverb elements

    reverb_preset_t     pPreset;

    reverb_preset_bank_t  m_sPreset;

    //int8_t            preset;
    uint32_t            m_nSamplingRate;
    int32_t             m_nUpdatePeriodInBits;
    int32_t             m_nBufferMask;
    int32_t             m_nUpdatePeriodInSamples;
    int32_t             m_nDelay0Out;
    int32_t             m_nDelay1Out;
    int16_t             m_nCosWT_5KHz;

    uint16_t            m_Aux;                // if TRUE, is connected as auxiliary effect
    uint16_t            m_Preset;             // if TRUE, expose preset revert interface

    uint32_t            mState;
} reverb_object_t;



typedef struct reverb_module_s {
    const struct effect_interface_s *itfe;
    effect_config_t config;
    reverb_object_t context;
} reverb_module_t;

/*------------------------------------
 * Effect API
 *------------------------------------
*/
int EffectQueryNumberEffects(uint32_t *pNumEffects);
int EffectQueryEffect(uint32_t index,
                      effect_descriptor_t *pDescriptor);
int EffectCreate(effect_uuid_t *effectUID,
                 int32_t sessionId,
                 int32_t ioId,
                 effect_handle_t *pHandle);
int EffectRelease(effect_handle_t handle);
int EffectGetDescriptor(effect_uuid_t       *uuid,
                        effect_descriptor_t *pDescriptor);

static int Reverb_Process(effect_handle_t self,
                          audio_buffer_t *inBuffer,
                          audio_buffer_t *outBuffer);
static int Reverb_Command(effect_handle_t self,
                          uint32_t cmdCode,
                          uint32_t cmdSize,
                          void *pCmdData,
                          uint32_t *replySize,
                          void *pReplyData);
static int Reverb_GetDescriptor(effect_handle_t   self,
                                effect_descriptor_t *pDescriptor);

/*------------------------------------
 * internal functions
 *------------------------------------
*/

int Reverb_Init(reverb_module_t *pRvbModule, int aux, int preset);
int Reverb_setConfig(reverb_module_t *pRvbModule, effect_config_t *pConfig, bool init);
void Reverb_getConfig(reverb_module_t *pRvbModule, effect_config_t *pConfig);
void Reverb_Reset(reverb_object_t *pReverb, bool init);

int Reverb_setParameter (reverb_object_t *pReverb, int32_t param, size_t size, void *pValue);
int Reverb_getParameter(reverb_object_t *pReverb, int32_t param, size_t *pSize, void *pValue);

/*----------------------------------------------------------------------------
 * ReverbUpdateXfade
 *----------------------------------------------------------------------------
 * Purpose:
 * Update the xfade parameters as required
 *
 * Inputs:
 * nNumSamplesToAdd - number of samples to write to buffer
 *
 * Outputs:
 *
 *
 * Side Effects:
 * - xfade parameters will be changed
 *
 *----------------------------------------------------------------------------
*/
static int ReverbUpdateXfade(reverb_object_t* pReverbData, int nNumSamplesToAdd);

/*----------------------------------------------------------------------------
 * ReverbCalculateNoise
 *----------------------------------------------------------------------------
 * Purpose:
 * Calculate a noise sample and limit its value
 *
 * Inputs:
 * Pointer to reverb context
 *
 * Outputs:
 * new limited noise value
 *
 * Side Effects:
 * - pReverbData->m_nNoise value is updated
 *
 *----------------------------------------------------------------------------
*/
static uint16_t ReverbCalculateNoise(reverb_object_t *pReverbData);

/*----------------------------------------------------------------------------
 * ReverbCalculateSinCos
 *----------------------------------------------------------------------------
 * Purpose:
 * Calculate a new sin and cosine value based on the given phase
 *
 * Inputs:
 * nPhase   - phase angle
 * pnSin    - input old value, output new value
 * pnCos    - input old value, output new value
 *
 * Outputs:
 *
 * Side Effects:
 * - *pnSin, *pnCos are updated
 *
 *----------------------------------------------------------------------------
*/
static int ReverbCalculateSinCos(int16_t nPhase, int16_t *pnSin, int16_t *pnCos);

/*----------------------------------------------------------------------------
 * Reverb
 *----------------------------------------------------------------------------
 * Purpose:
 * apply reverb to the given signal
 *
 * Inputs:
 * nNu
 * pnSin    - input old value, output new value
 * pnCos    - input old value, output new value
 *
 * Outputs:
 * number of samples actually reverberated
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
*/
static int Reverb(reverb_object_t* pReverbData, int nNumSamplesToAdd, short *pOutputBuffer, short *pInputBuffer);

/*----------------------------------------------------------------------------
 * ReverbReadInPresets()
 *----------------------------------------------------------------------------
 * Purpose: sets global reverb preset bank to defaults
 *
 * Inputs:
 *
 * Outputs:
 *
 *----------------------------------------------------------------------------
*/
static int ReverbReadInPresets(reverb_object_t* pReverbData);


/*----------------------------------------------------------------------------
 * ReverbUpdateRoom
 *----------------------------------------------------------------------------
 * Purpose:
 * Update the room's preset parameters as required
 *
 * Inputs:
 *
 * Outputs:
 *
 *
 * Side Effects:
 * - reverb paramters (fbk, fwd, etc) will be changed
 * - m_nCurrentRoom := m_nNextRoom
 *----------------------------------------------------------------------------
*/
static int ReverbUpdateRoom(reverb_object_t* pReverbData, bool fullUpdate);


static int ReverbComputeConstants(reverb_object_t *pReverbData, uint32_t samplingRate);

#endif /*ANDROID_EFFECTREVERB_H_*/
