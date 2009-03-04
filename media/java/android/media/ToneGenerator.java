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
     * Call supervisory tone, Dial tone: 425Hz, continuous
     * 
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_DIAL = 16;
    /**
     * Call supervisory tone, Busy: 425Hz, 500ms ON, 500ms OFF...
     * 
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_BUSY = 17;
    /**
     * Call supervisory tone, Congestion: 425Hz, 200ms ON, 200ms OFF...
     * 
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_CONGESTION = 18;
    /**
     * Call supervisory tone, Radio path acknowlegment : 425Hz, 200ms ON
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
     * Call supervisory tone, Call Waiting: 425Hz, 200ms ON, 600ms OFF, 200ms ON, 3s OFF...
     * 
     * @see #ToneGenerator(int, int)
     */
    public static final int TONE_SUP_CALL_WAITING = 22;
    /**
     * Call supervisory tone, Ring Tone: 425Hz, 1s ON, 4s OFF...
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
    protected void finalize() { native_finalize(); }

    private int mNativeContext; // accessed by native methods


}
