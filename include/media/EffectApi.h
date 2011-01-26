/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_EFFECTAPI_H_
#define ANDROID_EFFECTAPI_H_

#include <errno.h>
#include <stdint.h>
#include <sys/types.h>

#if __cplusplus
extern "C" {
#endif

/////////////////////////////////////////////////
//      Effect control interface
/////////////////////////////////////////////////

// The effect control interface is exposed by each effect engine implementation. It consists of
// a set of functions controlling the configuration, activation and process of the engine.
// The functions are grouped in a structure of type effect_interface_s:
//    struct effect_interface_s {
//        effect_process_t process;
//        effect_command_t command;
//    };


// effect_interface_t: Effect control interface handle.
// The effect_interface_t serves two purposes regarding the implementation of the effect engine:
// - 1 it is the address of a pointer to an effect_interface_s structure where the functions
// of the effect control API for a particular effect are located.
// - 2 it is the address of the context of a particular effect instance.
// A typical implementation in the effect library would define a structure as follows:
// struct effect_module_s {
//        const struct effect_interface_s *itfe;
//        effect_config_t config;
//        effect_context_t context;
// }
// The implementation of EffectCreate() function would then allocate a structure of this
// type and return its address as effect_interface_t
typedef struct effect_interface_s **effect_interface_t;


// Effect API version 1.0
#define EFFECT_API_VERSION 0x0100 // Format 0xMMmm MM: Major version, mm: minor version

// Maximum length of character strings in structures defines by this API.
#define EFFECT_STRING_LEN_MAX 64

//
//--- Effect descriptor structure effect_descriptor_t
//

// Unique effect ID (can be generated from the following site:
//  http://www.itu.int/ITU-T/asn1/uuid.html)
// This format is used for both "type" and "uuid" fields of the effect descriptor structure.
// - When used for effect type and the engine is implementing and effect corresponding to a standard
// OpenSL ES interface, this ID must be the one defined in OpenSLES_IID.h for that interface.
// - When used as uuid, it should be a unique UUID for this particular implementation.
typedef struct effect_uuid_s {
    uint32_t timeLow;
    uint16_t timeMid;
    uint16_t timeHiAndVersion;
    uint16_t clockSeq;
    uint8_t node[6];
} effect_uuid_t;

// NULL UUID definition (matches SL_IID_NULL_)
#define EFFECT_UUID_INITIALIZER { 0xec7178ec, 0xe5e1, 0x4432, 0xa3f4, \
                                  { 0x46, 0x57, 0xe6, 0x79, 0x52, 0x10 } }
static const effect_uuid_t EFFECT_UUID_NULL_ = EFFECT_UUID_INITIALIZER;
const effect_uuid_t * const EFFECT_UUID_NULL = &EFFECT_UUID_NULL_;
const char * const EFFECT_UUID_NULL_STR = "ec7178ec-e5e1-4432-a3f4-4657e6795210";

// The effect descriptor contains necessary information to facilitate the enumeration of the effect
// engines present in a library.
typedef struct effect_descriptor_s {
    effect_uuid_t type;     // UUID of to the OpenSL ES interface implemented by this effect
    effect_uuid_t uuid;     // UUID for this particular implementation
    uint16_t apiVersion;    // Version of the effect API implemented: matches EFFECT_API_VERSION
    uint32_t flags;         // effect engine capabilities/requirements flags (see below)
    uint16_t cpuLoad;       // CPU load indication (see below)
    uint16_t memoryUsage;   // Data Memory usage (see below)
    char    name[EFFECT_STRING_LEN_MAX];   // human readable effect name
    char    implementor[EFFECT_STRING_LEN_MAX];    // human readable effect implementor name
} effect_descriptor_t;

// CPU load and memory usage indication: each effect implementation must provide an indication of
// its CPU and memory usage for the audio effect framework to limit the number of effects
// instantiated at a given time on a given platform.
// The CPU load is expressed in 0.1 MIPS units as estimated on an ARM9E core (ARMv5TE) with 0 WS.
// The memory usage is expressed in KB and includes only dynamically allocated memory

// Definitions for flags field of effect descriptor.
//  +---------------------------+-----------+-----------------------------------
//  | description               | bits      | values
//  +---------------------------+-----------+-----------------------------------
//  | connection mode           | 0..1      | 0 insert: after track process
//  |                           |           | 1 auxiliary: connect to track auxiliary
//  |                           |           |  output and use send level
//  |                           |           | 2 replace: replaces track process function;
//  |                           |           |   must implement SRC, volume and mono to stereo.
//  |                           |           | 3 reserved
//  +---------------------------+-----------+-----------------------------------
//  | insertion preference      | 2..4      | 0 none
//  |                           |           | 1 first of the chain
//  |                           |           | 2 last of the chain
//  |                           |           | 3 exclusive (only effect in the insert chain)
//  |                           |           | 4..7 reserved
//  +---------------------------+-----------+-----------------------------------
//  | Volume management         | 5..6      | 0 none
//  |                           |           | 1 implements volume control
//  |                           |           | 2 requires volume indication
//  |                           |           | 3 reserved
//  +---------------------------+-----------+-----------------------------------
//  | Device indication         | 7..8      | 0 none
//  |                           |           | 1 requires device updates
//  |                           |           | 2..3 reserved
//  +---------------------------+-----------+-----------------------------------
//  | Sample input mode         | 9..10     | 0 direct: process() function or EFFECT_CMD_CONFIGURE
//  |                           |           |   command must specify a buffer descriptor
//  |                           |           | 1 provider: process() function uses the
//  |                           |           |   bufferProvider indicated by the
//  |                           |           |   EFFECT_CMD_CONFIGURE command to request input.
//  |                           |           |   buffers.
//  |                           |           | 2 both: both input modes are supported
//  |                           |           | 3 reserved
//  +---------------------------+-----------+-----------------------------------
//  | Sample output mode        | 11..12    | 0 direct: process() function or EFFECT_CMD_CONFIGURE
//  |                           |           |   command must specify a buffer descriptor
//  |                           |           | 1 provider: process() function uses the
//  |                           |           |   bufferProvider indicated by the
//  |                           |           |   EFFECT_CMD_CONFIGURE command to request output
//  |                           |           |   buffers.
//  |                           |           | 2 both: both output modes are supported
//  |                           |           | 3 reserved
//  +---------------------------+-----------+-----------------------------------
//  | Hardware acceleration     | 13..15    | 0 No hardware acceleration
//  |                           |           | 1 non tunneled hw acceleration: the process() function
//  |                           |           |   reads the samples, send them to HW accelerated
//  |                           |           |   effect processor, reads back the processed samples
//  |                           |           |   and returns them to the output buffer.
//  |                           |           | 2 tunneled hw acceleration: the process() function is
//  |                           |           |   transparent. The effect interface is only used to
//  |                           |           |   control the effect engine. This mode is relevant for
//  |                           |           |   global effects actually applied by the audio
//  |                           |           |   hardware on the output stream.
//  +---------------------------+-----------+-----------------------------------
//  | Audio Mode indication     | 16..17    | 0 none
//  |                           |           | 1 requires audio mode updates
//  |                           |           | 2..3 reserved
//  +---------------------------+-----------+-----------------------------------

// Insert mode
#define EFFECT_FLAG_TYPE_MASK           0x00000003
#define EFFECT_FLAG_TYPE_INSERT         0x00000000
#define EFFECT_FLAG_TYPE_AUXILIARY      0x00000001
#define EFFECT_FLAG_TYPE_REPLACE        0x00000002

// Insert preference
#define EFFECT_FLAG_INSERT_MASK         0x0000001C
#define EFFECT_FLAG_INSERT_ANY          0x00000000
#define EFFECT_FLAG_INSERT_FIRST        0x00000004
#define EFFECT_FLAG_INSERT_LAST         0x00000008
#define EFFECT_FLAG_INSERT_EXCLUSIVE    0x0000000C


// Volume control
#define EFFECT_FLAG_VOLUME_MASK         0x00000060
#define EFFECT_FLAG_VOLUME_CTRL         0x00000020
#define EFFECT_FLAG_VOLUME_IND          0x00000040
#define EFFECT_FLAG_VOLUME_NONE         0x00000000

// Device indication
#define EFFECT_FLAG_DEVICE_MASK         0x00000180
#define EFFECT_FLAG_DEVICE_IND          0x00000080
#define EFFECT_FLAG_DEVICE_NONE         0x00000000

// Sample input modes
#define EFFECT_FLAG_INPUT_MASK          0x00000600
#define EFFECT_FLAG_INPUT_DIRECT        0x00000000
#define EFFECT_FLAG_INPUT_PROVIDER      0x00000200
#define EFFECT_FLAG_INPUT_BOTH          0x00000400

// Sample output modes
#define EFFECT_FLAG_OUTPUT_MASK          0x00001800
#define EFFECT_FLAG_OUTPUT_DIRECT        0x00000000
#define EFFECT_FLAG_OUTPUT_PROVIDER      0x00000800
#define EFFECT_FLAG_OUTPUT_BOTH          0x00001000

// Hardware acceleration mode
#define EFFECT_FLAG_HW_ACC_MASK          0x00006000
#define EFFECT_FLAG_HW_ACC_SIMPLE        0x00002000
#define EFFECT_FLAG_HW_ACC_TUNNEL        0x00004000

// Audio mode indication
#define EFFECT_FLAG_AUDIO_MODE_MASK      0x00018000
#define EFFECT_FLAG_AUDIO_MODE_IND       0x00008000
#define EFFECT_FLAG_AUDIO_MODE_NONE      0x00000000

// Forward definition of type audio_buffer_t
typedef struct audio_buffer_s audio_buffer_t;

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       process
//
//    Description:    Effect process function. Takes input samples as specified
//          (count and location) in input buffer descriptor and output processed
//          samples as specified in output buffer descriptor. If the buffer descriptor
//          is not specified the function must use either the buffer or the
//          buffer provider function installed by the EFFECT_CMD_CONFIGURE command.
//          The effect framework will call the process() function after the EFFECT_CMD_ENABLE
//          command is received and until the EFFECT_CMD_DISABLE is received. When the engine
//          receives the EFFECT_CMD_DISABLE command it should turn off the effect gracefully
//          and when done indicate that it is OK to stop calling the process() function by
//          returning the -ENODATA status.
//
//    NOTE: the process() function implementation should be "real-time safe" that is
//      it should not perform blocking calls: malloc/free, sleep, read/write/open/close,
//      pthread_cond_wait/pthread_mutex_lock...
//
//    Input:
//          effect_interface_t: handle to the effect interface this function
//              is called on.
//          inBuffer:   buffer descriptor indicating where to read samples to process.
//              If NULL, use the configuration passed by EFFECT_CMD_CONFIGURE command.
//
//          inBuffer:   buffer descriptor indicating where to write processed samples.
//              If NULL, use the configuration passed by EFFECT_CMD_CONFIGURE command.
//
//    Output:
//        returned value:    0 successful operation
//                          -ENODATA the engine has finished the disable phase and the framework
//                                  can stop calling process()
//                          -EINVAL invalid interface handle or
//                                  invalid input/output buffer description
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_process_t)(effect_interface_t self,
                                    audio_buffer_t *inBuffer,
                                    audio_buffer_t *outBuffer);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       command
//
//    Description:    Send a command and receive a response to/from effect engine.
//
//    Input:
//          effect_interface_t: handle to the effect interface this function
//              is called on.
//          cmdCode:    command code: the command can be a standardized command defined in
//              effect_command_e (see below) or a proprietary command.
//          cmdSize:    size of command in bytes
//          pCmdData:   pointer to command data
//          pReplyData: pointer to reply data
//
//    Input/Output:
//          replySize: maximum size of reply data as input
//                      actual size of reply data as output
//
//    Output:
//          returned value: 0       successful operation
//                          -EINVAL invalid interface handle or
//                                  invalid command/reply size or format according to command code
//              The return code should be restricted to indicate problems related to the this
//              API specification. Status related to the execution of a particular command should be
//              indicated as part of the reply field.
//
//          *pReplyData updated with command response
//
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_command_t)(effect_interface_t self,
                                    uint32_t cmdCode,
                                    uint32_t cmdSize,
                                    void *pCmdData,
                                    uint32_t *replySize,
                                    void *pReplyData);


// Effect control interface definition
struct effect_interface_s {
    effect_process_t process;
    effect_command_t command;
};


//
//--- Standardized command codes for command() function
//
enum effect_command_e {
   EFFECT_CMD_INIT,                 // initialize effect engine
   EFFECT_CMD_CONFIGURE,            // configure effect engine (see effect_config_t)
   EFFECT_CMD_RESET,                // reset effect engine
   EFFECT_CMD_ENABLE,               // enable effect process
   EFFECT_CMD_DISABLE,              // disable effect process
   EFFECT_CMD_SET_PARAM,            // set parameter immediately (see effect_param_t)
   EFFECT_CMD_SET_PARAM_DEFERRED,   // set parameter deferred
   EFFECT_CMD_SET_PARAM_COMMIT,     // commit previous set parameter deferred
   EFFECT_CMD_GET_PARAM,            // get parameter
   EFFECT_CMD_SET_DEVICE,           // set audio device (see audio_device_e)
   EFFECT_CMD_SET_VOLUME,           // set volume
   EFFECT_CMD_SET_AUDIO_MODE,       // set the audio mode (normal, ring, ...)
   EFFECT_CMD_FIRST_PROPRIETARY = 0x10000 // first proprietary command code
};

//==================================================================================================
// command: EFFECT_CMD_INIT
//--------------------------------------------------------------------------------------------------
// description:
//  Initialize effect engine: All configurations return to default
//--------------------------------------------------------------------------------------------------
// command format:
//  size: 0
//  data: N/A
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: sizeof(int)
//  data: status
//==================================================================================================
// command: EFFECT_CMD_CONFIGURE
//--------------------------------------------------------------------------------------------------
// description:
//  Apply new audio parameters configurations for input and output buffers
//--------------------------------------------------------------------------------------------------
// command format:
//  size: sizeof(effect_config_t)
//  data: effect_config_t
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: sizeof(int)
//  data: status
//==================================================================================================
// command: EFFECT_CMD_RESET
//--------------------------------------------------------------------------------------------------
// description:
//  Reset the effect engine. Keep configuration but resets state and buffer content
//--------------------------------------------------------------------------------------------------
// command format:
//  size: 0
//  data: N/A
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: 0
//  data: N/A
//==================================================================================================
// command: EFFECT_CMD_ENABLE
//--------------------------------------------------------------------------------------------------
// description:
//  Enable the process. Called by the framework before the first call to process()
//--------------------------------------------------------------------------------------------------
// command format:
//  size: 0
//  data: N/A
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: sizeof(int)
//  data: status
//==================================================================================================
// command: EFFECT_CMD_DISABLE
//--------------------------------------------------------------------------------------------------
// description:
//  Disable the process. Called by the framework after the last call to process()
//--------------------------------------------------------------------------------------------------
// command format:
//  size: 0
//  data: N/A
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: sizeof(int)
//  data: status
//==================================================================================================
// command: EFFECT_CMD_SET_PARAM
//--------------------------------------------------------------------------------------------------
// description:
//  Set a parameter and apply it immediately
//--------------------------------------------------------------------------------------------------
// command format:
//  size: sizeof(effect_param_t) + size of param and value
//  data: effect_param_t + param + value. See effect_param_t definition below for value offset
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: sizeof(int)
//  data: status
//==================================================================================================
// command: EFFECT_CMD_SET_PARAM_DEFERRED
//--------------------------------------------------------------------------------------------------
// description:
//  Set a parameter but apply it only when receiving EFFECT_CMD_SET_PARAM_COMMIT command
//--------------------------------------------------------------------------------------------------
// command format:
//  size: sizeof(effect_param_t) + size of param and value
//  data: effect_param_t + param + value. See effect_param_t definition below for value offset
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: 0
//  data: N/A
//==================================================================================================
// command: EFFECT_CMD_SET_PARAM_COMMIT
//--------------------------------------------------------------------------------------------------
// description:
//  Apply all previously received EFFECT_CMD_SET_PARAM_DEFERRED commands
//--------------------------------------------------------------------------------------------------
// command format:
//  size: 0
//  data: N/A
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: sizeof(int)
//  data: status
//==================================================================================================
// command: EFFECT_CMD_GET_PARAM
//--------------------------------------------------------------------------------------------------
// description:
//  Get a parameter value
//--------------------------------------------------------------------------------------------------
// command format:
//  size: sizeof(effect_param_t) + size of param
//  data: effect_param_t + param
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: sizeof(effect_param_t) + size of param and value
//  data: effect_param_t + param + value. See effect_param_t definition below for value offset
//==================================================================================================
// command: EFFECT_CMD_SET_DEVICE
//--------------------------------------------------------------------------------------------------
// description:
//  Set the rendering device the audio output path is connected to. See audio_device_e for device
//  values.
//  The effect implementation must set EFFECT_FLAG_DEVICE_IND flag in its descriptor to receive this
//  command when the device changes
//--------------------------------------------------------------------------------------------------
// command format:
//  size: sizeof(uint32_t)
//  data: audio_device_e
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: 0
//  data: N/A
//==================================================================================================
// command: EFFECT_CMD_SET_VOLUME
//--------------------------------------------------------------------------------------------------
// description:
//  Set and get volume. Used by audio framework to delegate volume control to effect engine.
//  The effect implementation must set EFFECT_FLAG_VOLUME_IND or EFFECT_FLAG_VOLUME_CTRL flag in
//  its descriptor to receive this command before every call to process() function
//  If EFFECT_FLAG_VOLUME_CTRL flag is set in the effect descriptor, the effect engine must return
//  the volume that should be applied before the effect is processed. The overall volume (the volume
//  actually applied by the effect engine multiplied by the returned value) should match the value
//  indicated in the command.
//--------------------------------------------------------------------------------------------------
// command format:
//  size: n * sizeof(uint32_t)
//  data: volume for each channel defined in effect_config_t for output buffer expressed in
//      8.24 fixed point format
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: n * sizeof(uint32_t) / 0
//  data: - if EFFECT_FLAG_VOLUME_CTRL is set in effect descriptor:
//              volume for each channel defined in effect_config_t for output buffer expressed in
//              8.24 fixed point format
//        - if EFFECT_FLAG_VOLUME_CTRL is not set in effect descriptor:
//              N/A
//  It is legal to receive a null pointer as pReplyData in which case the effect framework has
//  delegated volume control to another effect
//==================================================================================================
// command: EFFECT_CMD_SET_AUDIO_MODE
//--------------------------------------------------------------------------------------------------
// description:
//  Set the audio mode. The effect implementation must set EFFECT_FLAG_AUDIO_MODE_IND flag in its
//  descriptor to receive this command when the audio mode changes.
//--------------------------------------------------------------------------------------------------
// command format:
//  size: sizeof(uint32_t)
//  data: audio_mode_e
//--------------------------------------------------------------------------------------------------
// reply format:
//  size: 0
//  data: N/A
//==================================================================================================
// command: EFFECT_CMD_FIRST_PROPRIETARY
//--------------------------------------------------------------------------------------------------
// description:
//  All proprietary effect commands must use command codes above this value. The size and format of
//  command and response fields is free in this case
//==================================================================================================


// Audio buffer descriptor used by process(), bufferProvider() functions and buffer_config_t
// structure. Multi-channel audio is always interleaved. The channel order is from LSB to MSB with
// regard to the channel mask definition in audio_channels_e e.g :
// Stereo: left, right
// 5 point 1: front left, front right, front center, low frequency, back left, back right
// The buffer size is expressed in frame count, a frame being composed of samples for all
// channels at a given time. Frame size for unspecified format (AUDIO_FORMAT_OTHER) is 8 bit by
// definition
struct audio_buffer_s {
    size_t   frameCount;        // number of frames in buffer
    union {
        void*       raw;        // raw pointer to start of buffer
        int32_t*    s32;        // pointer to signed 32 bit data at start of buffer
        int16_t*    s16;        // pointer to signed 16 bit data at start of buffer
        uint8_t*    u8;         // pointer to unsigned 8 bit data at start of buffer
    };
};

// The buffer_provider_s structure contains functions that can be used
// by the effect engine process() function to query and release input
// or output audio buffer.
// The getBuffer() function is called to retrieve a buffer where data
// should read from or written to by process() function.
// The releaseBuffer() function MUST be called when the buffer retrieved
// with getBuffer() is not needed anymore.
// The process function should use the buffer provider mechanism to retrieve
// input or output buffer if the inBuffer or outBuffer passed as argument is NULL
// and the buffer configuration (buffer_config_t) given by the EFFECT_CMD_CONFIGURE
// command did not specify an audio buffer.

typedef int32_t (* buffer_function_t)(void *cookie, audio_buffer_t *buffer);

typedef struct buffer_provider_s {
    buffer_function_t getBuffer;       // retrieve next buffer
    buffer_function_t releaseBuffer;   // release used buffer
    void       *cookie;                // for use by client of buffer provider functions
} buffer_provider_t;


// The buffer_config_s structure specifies the input or output audio format
// to be used by the effect engine. It is part of the effect_config_t
// structure that defines both input and output buffer configurations and is
// passed by the EFFECT_CMD_CONFIGURE command.
typedef struct buffer_config_s {
    audio_buffer_t  buffer;     // buffer for use by process() function if not passed explicitly
    uint32_t   samplingRate;    // sampling rate
    uint32_t   channels;        // channel mask (see audio_channels_e)
    buffer_provider_t bufferProvider;   // buffer provider
    uint8_t    format;          // Audio format  (see audio_format_e)
    uint8_t    accessMode;      // read/write or accumulate in buffer (effect_buffer_access_e)
    uint16_t   mask;            // indicates which of the above fields is valid
} buffer_config_t;

// Sample format
enum audio_format_e {
    SAMPLE_FORMAT_PCM_S15,   // PCM signed 16 bits
    SAMPLE_FORMAT_PCM_U8,    // PCM unsigned 8 bits
    SAMPLE_FORMAT_PCM_S7_24, // PCM signed 7.24 fixed point representation
    SAMPLE_FORMAT_OTHER      // other format (e.g. compressed)
};

// Channel mask
enum audio_channels_e {
    CHANNEL_FRONT_LEFT = 0x1,                   // front left channel
    CHANNEL_FRONT_RIGHT = 0x2,                  // front right channel
    CHANNEL_FRONT_CENTER = 0x4,                // front center channel
    CHANNEL_LOW_FREQUENCY = 0x8,               // low frequency channel
    CHANNEL_BACK_LEFT = 0x10,                   // back left channel
    CHANNEL_BACK_RIGHT = 0x20,                  // back right channel
    CHANNEL_FRONT_LEFT_OF_CENTER = 0x40,       // front left of center channel
    CHANNEL_FRONT_RIGHT_OF_CENTER = 0x80,      // front right of center channel
    CHANNEL_BACK_CENTER = 0x100,                // back center channel
    CHANNEL_MONO = CHANNEL_FRONT_LEFT,
    CHANNEL_STEREO = (CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT),
    CHANNEL_QUAD = (CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT |
            CHANNEL_BACK_LEFT | CHANNEL_BACK_RIGHT),
    CHANNEL_SURROUND = (CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT |
            CHANNEL_FRONT_CENTER | CHANNEL_BACK_CENTER),
    CHANNEL_5POINT1 = (CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT |
            CHANNEL_FRONT_CENTER | CHANNEL_LOW_FREQUENCY | CHANNEL_BACK_LEFT | CHANNEL_BACK_RIGHT),
    CHANNEL_7POINT1 = (CHANNEL_FRONT_LEFT | CHANNEL_FRONT_RIGHT |
            CHANNEL_FRONT_CENTER | CHANNEL_LOW_FREQUENCY | CHANNEL_BACK_LEFT | CHANNEL_BACK_RIGHT |
            CHANNEL_FRONT_LEFT_OF_CENTER | CHANNEL_FRONT_RIGHT_OF_CENTER),
};

// Render device
enum audio_device_e {
    DEVICE_EARPIECE = 0x1,                      // earpiece
    DEVICE_SPEAKER = 0x2,                       // speaker
    DEVICE_WIRED_HEADSET = 0x4,                 // wired headset, with microphone
    DEVICE_WIRED_HEADPHONE = 0x8,               // wired headphone, without microphone
    DEVICE_BLUETOOTH_SCO = 0x10,                // generic bluetooth SCO
    DEVICE_BLUETOOTH_SCO_HEADSET = 0x20,        // bluetooth SCO headset
    DEVICE_BLUETOOTH_SCO_CARKIT = 0x40,         // bluetooth SCO car kit
    DEVICE_BLUETOOTH_A2DP = 0x80,               // generic bluetooth A2DP
    DEVICE_BLUETOOTH_A2DP_HEADPHONES = 0x100,   // bluetooth A2DP headphones
    DEVICE_BLUETOOTH_A2DP_SPEAKER = 0x200,      // bluetooth A2DP speakers
    DEVICE_AUX_DIGITAL = 0x400,                 // digital output
    DEVICE_EXTERNAL_SPEAKER = 0x800             // external speaker (stereo and High quality)
};

// Audio mode
enum audio_mode_e {
    AUDIO_MODE_NORMAL,      // device idle
    AUDIO_MODE_RINGTONE,    // device ringing
    AUDIO_MODE_IN_CALL      // audio call connected (VoIP or telephony)
};

// Values for "accessMode" field of buffer_config_t:
//   overwrite, read only, accumulate (read/modify/write)
enum effect_buffer_access_e {
    EFFECT_BUFFER_ACCESS_WRITE,
    EFFECT_BUFFER_ACCESS_READ,
    EFFECT_BUFFER_ACCESS_ACCUMULATE

};

// Values for bit field "mask" in buffer_config_t. If a bit is set, the corresponding field
// in buffer_config_t must be taken into account when executing the EFFECT_CMD_CONFIGURE command
#define EFFECT_CONFIG_BUFFER    0x0001  // buffer field must be taken into account
#define EFFECT_CONFIG_SMP_RATE  0x0002  // samplingRate field must be taken into account
#define EFFECT_CONFIG_CHANNELS  0x0004  // channels field must be taken into account
#define EFFECT_CONFIG_FORMAT    0x0008  // format field must be taken into account
#define EFFECT_CONFIG_ACC_MODE  0x0010  // accessMode field must be taken into account
#define EFFECT_CONFIG_PROVIDER  0x0020  // bufferProvider field must be taken into account
#define EFFECT_CONFIG_ALL (EFFECT_CONFIG_BUFFER | EFFECT_CONFIG_SMP_RATE | \
                           EFFECT_CONFIG_CHANNELS | EFFECT_CONFIG_FORMAT | \
                           EFFECT_CONFIG_ACC_MODE | EFFECT_CONFIG_PROVIDER)


// effect_config_s structure describes the format of the pCmdData argument of EFFECT_CMD_CONFIGURE
// command to configure audio parameters and buffers for effect engine input and output.
typedef struct effect_config_s {
    buffer_config_t   inputCfg;
    buffer_config_t   outputCfg;;
} effect_config_t;


// effect_param_s structure describes the format of the pCmdData argument of EFFECT_CMD_SET_PARAM
// command and pCmdData and pReplyData of EFFECT_CMD_GET_PARAM command.
// psize and vsize represent the actual size of parameter and value.
//
// NOTE: the start of value field inside the data field is always on a 32 bit boundary:
//
//  +-----------+
//  | status    | sizeof(int)
//  +-----------+
//  | psize     | sizeof(int)
//  +-----------+
//  | vsize     | sizeof(int)
//  +-----------+
//  |           |   |           |
//  ~ parameter ~   > psize     |
//  |           |   |           >  ((psize - 1)/sizeof(int) + 1) * sizeof(int)
//  +-----------+               |
//  | padding   |               |
//  +-----------+
//  |           |   |
//  ~ value     ~   > vsize
//  |           |   |
//  +-----------+

typedef struct effect_param_s {
    int32_t     status;     // Transaction status (unused for command, used for reply)
    uint32_t    psize;      // Parameter size
    uint32_t    vsize;      // Value size
    char        data[];     // Start of Parameter + Value data
} effect_param_t;


/////////////////////////////////////////////////
//      Effect library interface
/////////////////////////////////////////////////

// An effect library is required to implement and expose the following functions
// to enable effect enumeration and instantiation. The name of these functions must be as
// specified here as the effect framework will get the function address with dlsym():
//
// - effect_QueryNumberEffects_t EffectQueryNumberEffects;
// - effect_QueryEffect_t EffectQueryEffect;
// - effect_CreateEffect_t EffectCreate;
// - effect_ReleaseEffect_t EffectRelease;


////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectQueryNumberEffects
//
//    Description:    Returns the number of different effects exposed by the
//          library. Each effect must have a unique effect uuid (see
//          effect_descriptor_t). This function together with EffectQueryEffect()
//          is used to enumerate all effects present in the library.
//
//    Input/Output:
//          pNumEffects:    address where the number of effects should be returned.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     library failed to initialize
//                          -EINVAL     invalid pNumEffects
//        *pNumEffects:     updated with number of effects in library
//
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_QueryNumberEffects_t)(uint32_t *pNumEffects);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectQueryEffect
//
//    Description:    Returns the descriptor of the effect engine which index is
//          given as first argument.
//          See effect_descriptor_t for details on effect descriptors.
//          This function together with EffectQueryNumberEffects() is used to enumerate all
//          effects present in the library. The enumeration sequence is:
//              EffectQueryNumberEffects(&num_effects);
//              for (i = 0; i < num_effects; i++)
//                  EffectQueryEffect(i,...);
//
//    Input/Output:
//          index:          index of the effect
//          pDescriptor:    address where to return the effect descriptor.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     library failed to initialize
//                          -EINVAL     invalid pDescriptor or index
//                          -ENOSYS     effect list has changed since last execution of
//                                      EffectQueryNumberEffects()
//                          -ENOENT     no more effect available
//        *pDescriptor:     updated with the effect descriptor.
//
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_QueryEffect_t)(uint32_t index,
                                        effect_descriptor_t *pDescriptor);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectCreate
//
//    Description:    Creates an effect engine of the specified type and returns an
//          effect control interface on this engine. The function will allocate the
//          resources for an instance of the requested effect engine and return
//          a handle on the effect control interface.
//
//    Input:
//          uuid:    pointer to the effect uuid.
//          sessionId:  audio session to which this effect instance will be attached. All effects
//              created with the same session ID are connected in series and process the same signal
//              stream. Knowing that two effects are part of the same effect chain can help the
//              library implement some kind of optimizations.
//          ioId:   identifies the output or input stream this effect is directed to at audio HAL.
//              For future use especially with tunneled HW accelerated effects
//
//    Input/Output:
//          pInterface:    address where to return the effect interface.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     library failed to initialize
//                          -EINVAL     invalid pEffectUuid or pInterface
//                          -ENOENT     no effect with this uuid found
//        *pInterface:     updated with the effect interface handle.
//
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_CreateEffect_t)(effect_uuid_t *uuid,
                                         int32_t sessionId,
                                         int32_t ioId,
                                         effect_interface_t *pInterface);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectRelease
//
//    Description:    Releases the effect engine whose handle is given as argument.
//          All resources allocated to this particular instance of the effect are
//          released.
//
//    Input:
//          interface:    handle on the effect interface to be released.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     library failed to initialize
//                          -EINVAL     invalid interface handle
//
////////////////////////////////////////////////////////////////////////////////
typedef int32_t (*effect_ReleaseEffect_t)(effect_interface_t interface);


#if __cplusplus
}  // extern "C"
#endif


#endif /*ANDROID_EFFECTAPI_H_*/
