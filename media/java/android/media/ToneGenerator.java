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

package android.media;



/**
 * This class provides methods to play DTMF tones (ITU-T Recommendation Q.23),
 * call supervisory tones (3GPP TS 22.001, CEPT) and proprietary tones (3GPP TS 31.111).
 * Depending on call state and routing options, tones are mixed to the downlink audio
 * or output to the speaker phone or headset.
 * This API is not for generating tones over the uplink audio path.
 */
public class ToneGenerator
{

    /* Values for toneType parameter of ToneGenerator() constructor */
    /*
     * List of all available tones: These constants must be kept consistant with
     * the enum in ToneGenerator C++ class.     */

    /**
     * DTMF tone for key 0: 1336Hz, 941Hz, continuous</p>
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_0 = 0;
    /**
     * DTMF tone for key 1: 1209Hz, 697Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_1 = 1;
    /**
     * DTMF tone for key 2: 1336Hz, 697Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
   public static final int TONE_DTMF_2 = 2;
   /**
    * DTMF tone for key 3: 1477Hz, 697Hz, continuous
    *
    * @see #ToneGenerator(int, int)
    */
    public static final int TONE_DTMF_3 = 3;
    /**
     * DTMF tone for key 4: 1209Hz, 770Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_4 = 4;
    /**
     * DTMF tone for key 5: 1336Hz, 770Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_5 = 5;
    /**
     * DTMF tone for key 6: 1477Hz, 770Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_6 = 6;
    /**
     * DTMF tone for key 7: 1209Hz, 852Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_7 = 7;
    /**
     * DTMF tone for key 8: 1336Hz, 852Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_8 = 8;
    /**
     * DTMF tone for key 9: 1477Hz, 852Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_9 = 9;
    /**
     * DTMF tone for key *: 1209Hz, 941Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_S = 10;
    /**
     * DTMF tone for key #: 1477Hz, 941Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_P = 11;
    /**
     * DTMF tone for key A: 1633Hz, 697Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_A = 12;
    /**
     * DTMF tone for key B: 1633Hz, 770Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_B = 13;
    /**
     * DTMF tone for key C: 1633Hz, 852Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_C = 14;
    /**
     * DTMF tone for key D: 1633Hz, 941Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_DTMF_D = 15;
    /**
     * Call supervisory tone, Dial tone:
     *      CEPT:           425Hz, continuous
     *      ANSI (IS-95):   350Hz+440Hz, continuous
     *      JAPAN:          400Hz, continuous
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_DIAL = 16;
    /**
     * Call supervisory tone, Busy:
     *      CEPT:           425Hz, 500ms ON, 500ms OFF...
     *      ANSI (IS-95):   480Hz+620Hz, 500ms ON, 500ms OFF...
     *      JAPAN:          400Hz, 500ms ON, 500ms OFF...
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_BUSY = 17;
    /**
     * Call supervisory tone, Congestion:
     *      CEPT, JAPAN:    425Hz, 200ms ON, 200ms OFF...
     *      ANSI (IS-95):   480Hz+620Hz, 250ms ON, 250ms OFF...
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_CONGESTION = 18;
    /**
     * Call supervisory tone, Radio path acknowlegment :
     *      CEPT, ANSI:    425Hz, 200ms ON
     *      JAPAN:         400Hz, 1s ON, 2s OFF...
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_RADIO_ACK = 19;
    /**
     * Call supervisory tone, Radio path not available: 425Hz, 200ms ON, 200 OFF 3 bursts
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_RADIO_NOTAVAIL = 20;
    /**
     * Call supervisory tone, Error/Special info: 950Hz+1400Hz+1800Hz, 330ms ON, 1s OFF...
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_ERROR = 21;
    /**
     * Call supervisory tone, Call Waiting:
     *      CEPT, JAPAN:    425Hz, 200ms ON, 600ms OFF, 200ms ON, 3s OFF...
     *      ANSI (IS-95):   440 Hz, 300 ms ON, 9.7 s OFF,
     *                      (100 ms ON, 100 ms OFF, 100 ms ON, 9.7s OFF ...)
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_CALL_WAITING = 22;
    /**
     * Call supervisory tone, Ring Tone:
     *      CEPT, JAPAN:    425Hz, 1s ON, 4s OFF...
     *      ANSI (IS-95):   440Hz + 480Hz, 2s ON, 4s OFF...
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_RINGTONE = 23;
    /**
     * Proprietary tone, general beep: 400Hz+1200Hz, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_PROP_BEEP = 24;
    /**
     * Proprietary tone, positive acknowlegement: 1200Hz, 100ms ON, 100ms OFF 2 bursts
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_PROP_ACK = 25;
    /**
     * Proprietary tone, negative acknowlegement: 300Hz+400Hz+500Hz, 400ms ON
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_PROP_NACK = 26;
    /**
     * Proprietary tone, prompt tone: 400Hz+1200Hz, 200ms ON
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int  TONE_PROP_PROMPT = 27;
    /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_PROP_BEEP2 = 28;
    /**
     * Call supervisory tone (IS-95), intercept tone: alternating 440 Hz and 620 Hz tones,
     * each on for 250 ms
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_INTERCEPT = 29;
    /**
     * Call supervisory tone (IS-95), abbreviated intercept: intercept tone limited to 4 seconds
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_INTERCEPT_ABBREV = 30;
    /**
     * Call supervisory tone (IS-95), abbreviated congestion: congestion tone limited to 4 seconds
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_CONGESTION_ABBREV = 31;
    /**
     * Call supervisory tone (IS-95), confirm tone: a 350 Hz tone added to a 440 Hz tone
     * repeated 3 times in a 100 ms on, 100 ms off cycle
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_CONFIRM = 32;
    /**
     * Call supervisory tone (IS-95), pip tone: four bursts of 480 Hz tone (0.1 s on, 0.1 s off).
     *
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_PIP = 33;


    /**
     * CDMA SPECIFIC TONES START
     */

    /** TODO(Moto): Change "Proprietary" below with an appropriate specification reference */

    /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_DIAL_TONE_LITE = 34;

     /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_NETWORK_USA_RINGBACK = 35;

    /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_REORDER = 36;

   /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
     public static final int TONE_CDMA_ABBR_REORDER = 37;

   /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
     public static final int TONE_CDMA_NETWORK_BUSY = 38;


   /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_ANSWER = 39;

   /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_NETWORK_CALLWAITING = 40;

   /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_PIP = 41;


    /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_CALL_SIGNAL_ISDN_NORMAL = 42;

    /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_CALL_SIGNAL_ISDN_INTERGROUP = 43;

     /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_CALL_SIGNAL_SP_PRI = 44;

    /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_CALL_SIGNAL_ISDN_PAT3 = 45;

    /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_CALL_SIGNAL_ISDN_RING_RING = 46;

    /**
     * Proprietary tone, general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_CALL_SIGNAL_ISDN_PAT5 = 47;

    /**
     * general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_CALL_SIGNAL_ISDN_PAT6 = 48;

    /**
     * general double beep: twice 400Hz+1200Hz, 35ms ON, 200ms OFF, 35ms ON
     *
     * @see #ToneGenerator(int, int)
     *
     * @hide
     */
    public static final int TONE_CDMA_CALL_SIGNAL_ISDN_PAT7 = 49;

    // TODO(Moto): Need comments for each one and we need ToneGenerator.cpp/ToneGenerator.h

    /** @hide */
    public static final int TONE_CDMA_HIGH_L = 50;

    /** @hide */
    public static final int TONE_CDMA_LOW_L = 51;
    /** @hide */
    public static final int TONE_CDMA_HIGH_SS = 52;
    /** @hide */
    public static final int TONE_CDMA_MED_SS = 53;
    /** @hide */
    public static final int TONE_CDMA_LOW_SS = 54;
    /** @hide */
    public static final int TONE_CDMA_HIGH_SSL = 55;


    /** @hide */
    public static final int TONE_CDMA_MED_SSL = 56;
    /** @hide */
    public static final int TONE_CDMA_LOW_SSL = 57;
    /** @hide */
    public static final int TONE_CDMA_HIGH_SS_2 = 58;
    /** @hide */
    public static final int TONE_CDMA_MED_SS_2 = 59;
    /** @hide */
    public static final int TONE_CDMA_LOW_SS_2 = 60;
    /** @hide */
    public static final int TONE_CDMA_HIGH_SLS = 61;
    /** @hide */
    public static final int TONE_CDMA_MED_SLS = 62;
    /** @hide */
    public static final int TONE_CDMA_LOW_SLS = 63;
    /** @hide */
    public static final int TONE_CDMA_HIGH_S_X4 = 64;
    /** @hide */
    public static final int TONE_CDMA_MED_S_X4 = 65;
    /** @hide */
    public static final int TONE_CDMA_LOW_S_X4 = 66;
    /** @hide */
    public static final int TONE_CDMA_HIGH_PBX_L = 67;
    /** @hide */
    public static final int TONE_CDMA_MED_PBX_L = 68;
    /** @hide */
    public static final int TONE_CDMA_LOW_PBX_L = 69;
    /** @hide */
    public static final int TONE_CDMA_HIGH_PBX_SS = 70;
    /** @hide */
    public static final int TONE_CDMA_MED_PBX_SS = 71;
    /** @hide */
    public static final int TONE_CDMA_LOW_PBX_SS = 72;
    /** @hide */
    public static final int TONE_CDMA_HIGH_PBX_SSL = 73;
    /** @hide */
    public static final int TONE_CDMA_MED_PBX_SSL = 74;

    /** @hide */
    public static final int TONE_CDMA_LOW_PBX_SSL = 75;
    /** @hide */
    public static final int TONE_CDMA_HIGH_PBX_SLS = 76;
    /** @hide */
    public static final int TONE_CDMA_MED_PBX_SLS = 77;
    /** @hide */
    public static final int TONE_CDMA_LOW_PBX_SLS = 78;
    /** @hide */
    public static final int TONE_CDMA_HIGH_PBX_S_X4 = 79;
    /** @hide */
    public static final int TONE_CDMA_MED_PBX_S_X4 = 80;
    /** @hide */
    public static final int TONE_CDMA_LOW_PBX_S_X4 = 81;
    /** @hide */
    public static final int TONE_CDMA_INTERCEPT_ONE_SHOT = TONE_SUP_INTERCEPT_ABBREV;
    /** @hide */
    public static final int TONE_CDMA_REORDER_ONE_SHOT = TONE_CDMA_ABBR_REORDER;
    /** @hide */
    public static final int TONE_CDMA_NETWORK_BUSY_ONE_SHOT = 82;
    /** @hide */
    public static final int TONE_CDMA_ABBR_ALERT = 83;
    /** @hide */
    public static final int TONE_CDMA_SIGNAL_OFF = 84;
    /** @hide */
    public static final int TONE_CDMA_INVALID = 85;

    /** Maximum volume, for use with {@link #ToneGenerator(int,int)} */
    public static final int MAX_VOLUME = AudioSystem.MAX_VOLUME;
    /** Minimum volume setting, for use with {@link #ToneGenerator(int,int)} */
    public static final int MIN_VOLUME = AudioSystem.MIN_VOLUME;


    /**
     * ToneGenerator class contructor specifying output stream type and volume.
     *
     * @param streamType The streame type used for tone playback (e.g. STREAM_MUSIC).
     * @param volume     The volume of the tone, given in percentage of maximum volume (from 0-100).
     *
     */
    public ToneGenerator(int streamType, int volume) {
        native_setup(streamType, volume);
    }

    /**
     * This method starts the playback of a tone of the specified type.
     * only one tone can play at a time: if a tone is playing while this method is called,
     * this tone is stopped and replaced by the one requested.
     * @param toneType   The type of tone generate chosen from the following list:
     * <ul>
     * <li>{@link #TONE_DTMF_0}
     * <li>{@link #TONE_DTMF_1}
     * <li>{@link #TONE_DTMF_2}
     * <li>{@link #TONE_DTMF_3}
     * <li>{@link #TONE_DTMF_4}
     * <li>{@link #TONE_DTMF_5}
     * <li>{@link #TONE_DTMF_6}
     * <li>{@link #TONE_DTMF_7}
     * <li>{@link #TONE_DTMF_8}
     * <li>{@link #TONE_DTMF_9}
     * <li>{@link #TONE_DTMF_A}
     * <li>{@link #TONE_DTMF_B}
     * <li>{@link #TONE_DTMF_C}
     * <li>{@link #TONE_DTMF_D}
     * <li>{@link #TONE_SUP_DIAL}
     * <li>{@link #TONE_SUP_BUSY}
     * <li>{@link #TONE_SUP_CONGESTION}
     * <li>{@link #TONE_SUP_RADIO_ACK}
     * <li>{@link #TONE_SUP_RADIO_NOTAVAIL}
     * <li>{@link #TONE_SUP_ERROR}
     * <li>{@link #TONE_SUP_CALL_WAITING}
     * <li>{@link #TONE_SUP_RINGTONE}
     * <li>{@link #TONE_PROP_BEEP}
     * <li>{@link #TONE_PROP_ACK}
     * <li>{@link #TONE_PROP_NACK}
     * <li>{@link #TONE_PROP_PROMPT}
     * <li>{@link #TONE_PROP_BEEP2}
     * <li>{@link #TONE_SUP_INTERCEPT}
     * <li>{@link #TONE_SUP_INTERCEPT_ABBREV}
     * <li>{@link #TONE_SUP_CONGESTION_ABBREV}
     * <li>{@link #TONE_SUP_CONFIRM}
     * <li>{@link #TONE_SUP_PIP}
     * </ul>
     * @see #ToneGenerator(int, int)
    */
    public native boolean startTone(int toneType);

    /**
     * This method stops the tone currently playing playback.
     * @see #ToneGenerator(int, int)
     */
    public native void stopTone();

    /**
     * Releases resources associated with this ToneGenerator object. It is good
     * practice to call this method when you're done using the ToneGenerator.
     */
    public native void release();

    private native final void native_setup(int streamType, int volume);

    private native final void native_finalize();
    
    @Override
    protected void finalize() { native_finalize(); }

    @SuppressWarnings("unused")
    private int mNativeContext; // accessed by native methods
}
