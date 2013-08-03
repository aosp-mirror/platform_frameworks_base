/*
 * ---------------------------------------------------------------------------
 * Recognizer.java
 * 
 * Copyright 2007 Nuance Communciations, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the 'License'); you may not
 * use this file except in compliance with the License.
 * 
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 * 
 * ---------------------------------------------------------------------------
 */


package android.speech.srec;

import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.util.Locale;

/**
 * Simple, synchronous speech recognizer, using the Nuance SREC package.
 * Usages proceeds as follows:
 * 
 * <ul>
 * <li>Create a <code>Recognizer</code>.
 * <li>Create a <code>Recognizer.Grammar</code>.
 * <li>Setup the <code>Recognizer.Grammar</code>.
 * <li>Reset the <code>Recognizer.Grammar</code> slots, if needed.
 * <li>Fill the <code>Recognizer.Grammar</code> slots, if needed.
 * <li>Compile the <code>Recognizer.Grammar</code>, if needed.
 * <li>Save the filled <code>Recognizer.Grammar</code>, if needed.
 * <li>Start the <code>Recognizer</code>.
 * <li>Loop over <code>advance</code> and <code>putAudio</code> until recognition complete.
 * <li>Fetch and process results, or notify of failure.
 * <li>Stop the <code>Recognizer</code>.
 * <li>Destroy the <code>Recognizer</code>.
 * </ul>
 * 
 * <p>Below is example code</p>
 * 
 * <pre class="prettyprint">
 * 
 * // create and start audio input
 * InputStream audio = new MicrophoneInputStream(11025, 11025*5);
 * // create a Recognizer
 * String cdir = Recognizer.getConfigDir(null);
 * Recognizer recognizer = new Recognizer(cdir + "/baseline11k.par");
 * // create and load a Grammar
 * Recognizer.Grammar grammar = recognizer.new Grammar(cdir + "/grammars/VoiceDialer.g2g");
 * // setup the Grammar to work with the Recognizer
 * grammar.setupRecognizer();
 * // fill the Grammar slots with names and save, if required
 * grammar.resetAllSlots();
 * for (String name : names) grammar.addWordToSlot("@Names", name, null, 1, "V=1");
 * grammar.compile();
 * grammar.save(".../foo.g2g");
 * // start the Recognizer
 * recognizer.start();
 * // loop over Recognizer events
 * while (true) {
 *     switch (recognizer.advance()) {
 *     case Recognizer.EVENT_INCOMPLETE:
 *     case Recognizer.EVENT_STARTED:
 *     case Recognizer.EVENT_START_OF_VOICING:
 *     case Recognizer.EVENT_END_OF_VOICING:
 *         // let the Recognizer continue to run
 *         continue;
 *     case Recognizer.EVENT_RECOGNITION_RESULT:
 *         // success, so fetch results here!
 *         for (int i = 0; i < recognizer.getResultCount(); i++) {
 *             String result = recognizer.getResult(i, Recognizer.KEY_LITERAL);
 *         }
 *         break;
 *     case Recognizer.EVENT_NEED_MORE_AUDIO:
 *         // put more audio in the Recognizer
 *         recognizer.putAudio(audio);
 *         continue;
 *     default:
 *         notifyFailure();
 *         break;
 *     }
 *     break;
 * }
 * // stop the Recognizer
 * recognizer.stop();
 * // destroy the Recognizer
 * recognizer.destroy();
 * // stop the audio device
 * audio.close();
 * 
 * </pre>
 */
public final class Recognizer {
    static {
        System.loadLibrary("srec_jni");
    }

    private static String TAG = "Recognizer";
    
    /**
     * Result key corresponding to confidence score.
     */
    public static final String KEY_CONFIDENCE = "conf";
    
    /**
     * Result key corresponding to literal text.
     */
    public static final String KEY_LITERAL = "literal";
    
    /**
     * Result key corresponding to semantic meaning text.
     */
    public static final String KEY_MEANING = "meaning";

    // handle to SR_Vocabulary object
    private int mVocabulary = 0;
    
    // handle to SR_Recognizer object
    private int mRecognizer = 0;
    
    // Grammar currently associated with Recognizer via SR_GrammarSetupRecognizer
    private Grammar mActiveGrammar = null;
    
    /**
     * Get the pathname of the SREC configuration directory corresponding to the
     * language indicated by the Locale.
     * This directory contains dictionaries, speech models,
     * configuration files, and other data needed by the Recognizer.
     * @param locale <code>Locale</code> corresponding to the desired language,
     * or null for default, currently <code>Locale.US</code>.
     * @return Pathname of the configuration directory.
     */
    public static String getConfigDir(Locale locale) {
        if (locale == null) locale = Locale.US;
        String dir = "/system/usr/srec/config/" +
                locale.toString().replace('_', '.').toLowerCase(Locale.ROOT);
        if ((new File(dir)).isDirectory()) return dir;
        return null;
    }

    /**
     * Create an instance of a SREC speech recognizer.
     * 
     * @param configFile pathname of the baseline*.par configuration file,
     * which in turn contains references to dictionaries, speech models,
     * and other data needed to configure and operate the recognizer.
     * A separate config file is needed for each audio sample rate.
     * Two files, baseline11k.par and baseline8k.par, which correspond to
     * 11025 and 8000 hz, are present in the directory indicated by
     * {@link #getConfigDir}.
     * @throws IOException
     */
    public Recognizer(String configFile) throws IOException {
        PMemInit();
        SR_SessionCreate(configFile);
        mRecognizer = SR_RecognizerCreate();
        SR_RecognizerSetup(mRecognizer);
        mVocabulary = SR_VocabularyLoad();
    }

    /**
     * Represents a grammar loaded into the Recognizer.
     */
    public class Grammar {
        private int mGrammar = 0;

        /**
         * Create a <code>Grammar</code> instance.
         * @param g2gFileName pathname of g2g file.
         */
        public Grammar(String g2gFileName) throws IOException {
            mGrammar = SR_GrammarLoad(g2gFileName);
            SR_GrammarSetupVocabulary(mGrammar, mVocabulary);
        }

        /**
         * Reset all slots.
         */
        public void resetAllSlots() {
            SR_GrammarResetAllSlots(mGrammar);
        }

        /**
         * Add a word to a slot.
         * 
         * @param slot slot name.
         * @param word word to insert.
         * @param pron pronunciation, or null to derive from word.
         * @param weight weight to give the word.  One is normal, 50 is low.
         * @param tag semantic meaning tag string.
         */
        public void addWordToSlot(String slot, String word, String pron, int weight, String tag) {
            SR_GrammarAddWordToSlot(mGrammar, slot, word, pron, weight, tag); 
        }

        /**
         * Compile all slots.
         */
        public void compile() {
            SR_GrammarCompile(mGrammar);
        }

        /**
         * Setup <code>Grammar</code> with <code>Recognizer</code>.
         */
        public void setupRecognizer() {
            SR_GrammarSetupRecognizer(mGrammar, mRecognizer);
            mActiveGrammar = this;
        }

        /**
         * Save <code>Grammar</code> to g2g file.
         * 
         * @param g2gFileName
         * @throws IOException
         */
        public void save(String g2gFileName) throws IOException {
            SR_GrammarSave(mGrammar, g2gFileName);
        }

        /**
         * Release resources associated with this <code>Grammar</code>.
         */
        public void destroy() {
            // TODO: need to do cleanup and disassociation with Recognizer
            if (mGrammar != 0) {
                SR_GrammarDestroy(mGrammar);
                mGrammar = 0;
            }
        }

        /**
         * Clean up resources.
         */
        protected void finalize() {
            if (mGrammar != 0) {
                destroy();
                throw new IllegalStateException("someone forgot to destroy Grammar");
            }
        }
    }

    /**
     * Start recognition
     */
    public void start() {
        // TODO: shouldn't be here?
        SR_RecognizerActivateRule(mRecognizer, mActiveGrammar.mGrammar, "trash", 1);
        SR_RecognizerStart(mRecognizer);
    }
    
    /**
     * Process some audio and return the current status.
     * @return recognition event, one of:
     * <ul>
     * <li><code>EVENT_INVALID</code>
     * <li><code>EVENT_NO_MATCH</code>
     * <li><code>EVENT_INCOMPLETE</code>
     * <li><code>EVENT_STARTED</code>
     * <li><code>EVENT_STOPPED</code>
     * <li><code>EVENT_START_OF_VOICING</code>
     * <li><code>EVENT_END_OF_VOICING</code>
     * <li><code>EVENT_SPOKE_TOO_SOON</code>
     * <li><code>EVENT_RECOGNITION_RESULT</code>
     * <li><code>EVENT_START_OF_UTTERANCE_TIMEOUT</code>
     * <li><code>EVENT_RECOGNITION_TIMEOUT</code>
     * <li><code>EVENT_NEED_MORE_AUDIO</code>
     * <li><code>EVENT_MAX_SPEECH</code>
     * </ul>
     */
    public int advance() {
        return SR_RecognizerAdvance(mRecognizer);
    }
    
    /**
     * Put audio samples into the <code>Recognizer</code>.
     * @param buf holds the audio samples.
     * @param offset offset of the first sample.
     * @param length number of bytes containing samples.
     * @param isLast indicates no more audio data, normally false.
     * @return number of bytes accepted.
     */
    public int putAudio(byte[] buf, int offset, int length, boolean isLast) {
        return SR_RecognizerPutAudio(mRecognizer, buf, offset, length, isLast);
    }
    
    /**
     * Read audio samples from an <code>InputStream</code> and put them in the
     * <code>Recognizer</code>.
     * @param audio <code>InputStream</code> containing PCM audio samples.
     */
    public void putAudio(InputStream audio) throws IOException {
        // make sure the audio buffer is allocated
        if (mPutAudioBuffer == null) mPutAudioBuffer = new byte[512];
        // read some data
        int nbytes = audio.read(mPutAudioBuffer);
        // eof, so signal Recognizer
        if (nbytes == -1) {
            SR_RecognizerPutAudio(mRecognizer, mPutAudioBuffer, 0, 0, true);
        }
        // put it into the Recognizer
        else if (nbytes != SR_RecognizerPutAudio(mRecognizer, mPutAudioBuffer, 0, nbytes, false)) {
            throw new IOException("SR_RecognizerPutAudio failed nbytes=" + nbytes);
        }
    }
    
    // audio buffer for putAudio(InputStream)
    private byte[] mPutAudioBuffer = null;

    /**
     * Get the number of recognition results.  Must be called after
     * <code>EVENT_RECOGNITION_RESULT</code> is returned by
     * <code>advance</code>, but before <code>stop</code>.
     * 
     * @return number of results in nbest list.
     */
    public int getResultCount() {
        return SR_RecognizerResultGetSize(mRecognizer);
    }

    /**
     * Get a set of keys for the result.  Must be called after
     * <code>EVENT_RECOGNITION_RESULT</code> is returned by
     * <code>advance</code>, but before <code>stop</code>.
     * 
     * @param index index of result.
     * @return array of keys.
     */
    public String[] getResultKeys(int index) {
        return SR_RecognizerResultGetKeyList(mRecognizer, index);
    }

    /**
     * Get a result value.  Must be called after
     * <code>EVENT_RECOGNITION_RESULT</code> is returned by
     * <code>advance</code>, but before <code>stop</code>.
     * 
     * @param index index of the result.
     * @param key key of the result.  This is typically one of
     * <code>KEY_CONFIDENCE</code>, <code>KEY_LITERAL</code>, or
     * <code>KEY_MEANING</code>, but the user can also define their own keys
     * in a grxml file, or in the <code>tag</code> slot of
     * <code>Grammar.addWordToSlot</code>.
     * @return the result.
     */
    public String getResult(int index, String key) {
        return SR_RecognizerResultGetValue(mRecognizer, index, key);
    }

    /**
     * Stop the <code>Recognizer</code>.
     */
    public void stop() {
        SR_RecognizerStop(mRecognizer);
        SR_RecognizerDeactivateRule(mRecognizer, mActiveGrammar.mGrammar, "trash");
    }
    
    /**
     * Reset the acoustic state vectorto it's default value.
     * 
     * @hide
     */
    public void resetAcousticState() {
        SR_AcousticStateReset(mRecognizer);
    }
    
    /**
     * Set the acoustic state vector.
     * @param state String containing the acoustic state vector.
     * 
     * @hide
     */
    public void setAcousticState(String state) {
        SR_AcousticStateSet(mRecognizer, state);
    }
    
    /**
     * Get the acoustic state vector.
     * @return String containing the acoustic state vector.
     * 
     * @hide
     */
    public String getAcousticState() {
        return SR_AcousticStateGet(mRecognizer);
    }

    /**
     * Clean up resources.
     */
    public void destroy() {
        try {
            if (mVocabulary != 0) SR_VocabularyDestroy(mVocabulary);
        } finally {
            mVocabulary = 0;
            try {
                if (mRecognizer != 0) SR_RecognizerUnsetup(mRecognizer);
            } finally {
                try {
                    if (mRecognizer != 0) SR_RecognizerDestroy(mRecognizer);
                } finally {
                    mRecognizer = 0;
                    try {
                        SR_SessionDestroy();
                    } finally {
                        PMemShutdown();
                    }
                }
            }
        }
    }

    /**
     * Clean up resources.
     */
    protected void finalize() throws Throwable {
        if (mVocabulary != 0 || mRecognizer != 0) {
            destroy();
            throw new IllegalStateException("someone forgot to destroy Recognizer");
        }
    }
    
    /* an example session captured, for reference
    void doall() {
        if (PMemInit ( )
           || lhs_audioinOpen ( WAVE_MAPPER, SREC_TEST_DEFAULT_AUDIO_FREQUENCY, &audio_in_handle )
           || srec_test_init_application_data ( &applicationData, argc, argv )
           || SR_SessionCreate ( "/system/usr/srec/config/en.us/baseline11k.par" )
           || SR_RecognizerCreate ( &applicationData.recognizer )
           || SR_RecognizerSetup ( applicationData.recognizer)
           || ESR_SessionGetLCHAR ( L("cmdline.vocabulary"), filename, &flen )
           || SR_VocabularyLoad ( filename, &applicationData.vocabulary )
           || SR_VocabularyGetLanguage ( applicationData.vocabulary, &applicationData.locale )
           || (applicationData.nametag = NULL)
           || SR_NametagsCreate ( &applicationData.nametags )
           || (LSTRCPY ( applicationData.grammars [0].grammar_path, "/system/usr/srec/config/en.us/grammars/VoiceDialer.g2g" ), 0)
           || (LSTRCPY ( applicationData.grammars [0].grammarID, "BothTags" ), 0)
           || (LSTRCPY ( applicationData.grammars [0].ruleName, "trash" ), 0)
           || (applicationData.grammars [0].is_ve_grammar = ESR_FALSE, 0)
           || SR_GrammarLoad (applicationData.grammars [0].grammar_path, &applicationData.grammars [applicationData.grammarCount].grammar )
           || SR_GrammarSetupVocabulary ( applicationData.grammars [0].grammar, applicationData.vocabulary )
           || SR_GrammarSetupRecognizer( applicationData.grammars [0].grammar, applicationData.recognizer )
           || SR_GrammarSetDispatchFunction ( applicationData.grammars [0].grammar, L("myDSMCallback"), NULL, myDSMCallback )
           || (applicationData.grammarCount++, 0)
           || SR_RecognizerActivateRule ( applicationData.recognizer, applicationData.grammars [0].grammar,
                           applicationData.grammars [0].ruleName, 1 )
           || (applicationData.active_grammar_num = 0, 0)
           || lhs_audioinStart ( audio_in_handle )
           || SR_RecognizerStart ( applicationData.recognizer )
           || strl ( applicationData.grammars [0].grammar, &applicationData, audio_in_handle, &recognition_count )
           || SR_RecognizerStop ( applicationData.recognizer )
           || lhs_audioinStop ( audio_in_handle )
           || SR_RecognizerDeactivateRule ( applicationData.recognizer, applicationData.grammars [0].grammar, applicationData.grammars [0].ruleName )
           || (applicationData.active_grammar_num = -1, 0)
           || SR_GrammarDestroy ( applicationData.grammars [0].grammar )
           || (applicationData.grammarCount--, 0)
           || SR_NametagsDestroy ( applicationData.nametags )
           || (applicationData.nametags = NULL, 0)
           || SR_VocabularyDestroy ( applicationData.vocabulary )
           || (applicationData.vocabulary = NULL)
           || SR_RecognizerUnsetup ( applicationData.recognizer) // releases acoustic models
           || SR_RecognizerDestroy ( applicationData.recognizer )
           || (applicationData.recognizer = NULL)
           || SR_SessionDestroy ( )
           || srec_test_shutdown_application_data ( &applicationData )
           || lhs_audioinClose ( &audio_in_handle )
           || PMemShutdown ( )
    }
    */


    //
    // PMem native methods
    //
    private static native void PMemInit();
    private static native void PMemShutdown();


    //
    // SR_Session native methods
    //
    private static native void SR_SessionCreate(String filename);
    private static native void SR_SessionDestroy();


    //
    // SR_Recognizer native methods
    //
    
    /**
     * Reserved value.
     */
    public final static int EVENT_INVALID = 0;
    
    /**
     * <code>Recognizer</code> could not find a match for the utterance.
     */
    public final static int EVENT_NO_MATCH = 1;
    
    /**
     * <code>Recognizer</code> processed one frame of audio.
     */
    public final static int EVENT_INCOMPLETE = 2;
    
    /**
     * <code>Recognizer</code> has just been started.
     */
    public final static int EVENT_STARTED = 3;
    
    /**
     * <code>Recognizer</code> is stopped.
     */
    public final static int EVENT_STOPPED = 4;
    
    /**
     * Beginning of speech detected.
     */
    public final static int EVENT_START_OF_VOICING = 5;
    
    /**
     * End of speech detected.
     */
    public final static int EVENT_END_OF_VOICING = 6;
    
    /**
     * Beginning of utterance occured too soon.
     */
    public final static int EVENT_SPOKE_TOO_SOON = 7;
    
    /**
     * Recognition match detected.
     */
    public final static int EVENT_RECOGNITION_RESULT = 8;
    
    /**
     * Timeout occured before beginning of utterance.
     */
    public final static int EVENT_START_OF_UTTERANCE_TIMEOUT = 9;
    
    /**
     * Timeout occured before speech recognition could complete.
     */
    public final static int EVENT_RECOGNITION_TIMEOUT = 10;
    
    /**
     * Not enough samples to process one frame.
     */
    public final static int EVENT_NEED_MORE_AUDIO = 11;
    
    /**
     * More audio encountered than is allowed by 'swirec_max_speech_duration'.
     */
    public final static int EVENT_MAX_SPEECH = 12;

    /**
     * Produce a displayable string from an <code>advance</code> event.
     * @param event
     * @return String representing the event.
     */
    public static String eventToString(int event) {
        switch (event) {
            case EVENT_INVALID:
                return "EVENT_INVALID";
            case EVENT_NO_MATCH:
                return "EVENT_NO_MATCH";
            case EVENT_INCOMPLETE:
                return "EVENT_INCOMPLETE";
            case EVENT_STARTED:
                return "EVENT_STARTED";
            case EVENT_STOPPED:
                return "EVENT_STOPPED";
            case EVENT_START_OF_VOICING:
                return "EVENT_START_OF_VOICING";
            case EVENT_END_OF_VOICING:
                return "EVENT_END_OF_VOICING";
            case EVENT_SPOKE_TOO_SOON:
                return "EVENT_SPOKE_TOO_SOON";
            case EVENT_RECOGNITION_RESULT:
                return "EVENT_RECOGNITION_RESULT";
            case EVENT_START_OF_UTTERANCE_TIMEOUT:
                return "EVENT_START_OF_UTTERANCE_TIMEOUT";
            case EVENT_RECOGNITION_TIMEOUT:
                return "EVENT_RECOGNITION_TIMEOUT";
            case EVENT_NEED_MORE_AUDIO:
                return "EVENT_NEED_MORE_AUDIO";
            case EVENT_MAX_SPEECH:
                return "EVENT_MAX_SPEECH";
        }
        return "EVENT_" + event;
    }

    //
    // SR_Recognizer methods
    //
    private static native void SR_RecognizerStart(int recognizer);
    private static native void SR_RecognizerStop(int recognizer);
    private static native int SR_RecognizerCreate();
    private static native void SR_RecognizerDestroy(int recognizer);
    private static native void SR_RecognizerSetup(int recognizer);
    private static native void SR_RecognizerUnsetup(int recognizer);
    private static native boolean SR_RecognizerIsSetup(int recognizer);
    private static native String SR_RecognizerGetParameter(int recognizer, String key);
    private static native int SR_RecognizerGetSize_tParameter(int recognizer, String key);
    private static native boolean SR_RecognizerGetBoolParameter(int recognizer, String key);
    private static native void SR_RecognizerSetParameter(int recognizer, String key, String value);
    private static native void SR_RecognizerSetSize_tParameter(int recognizer,
            String key, int value);
    private static native void SR_RecognizerSetBoolParameter(int recognizer, String key,
            boolean value);
    private static native void SR_RecognizerSetupRule(int recognizer, int grammar,
            String ruleName);
    private static native boolean SR_RecognizerHasSetupRules(int recognizer);
    private static native void SR_RecognizerActivateRule(int recognizer, int grammar,
            String ruleName, int weight);
    private static native void SR_RecognizerDeactivateRule(int recognizer, int grammar,
            String ruleName);
    private static native void SR_RecognizerDeactivateAllRules(int recognizer);
    private static native boolean SR_RecognizerIsActiveRule(int recognizer, int grammar,
            String ruleName);
    private static native boolean SR_RecognizerCheckGrammarConsistency(int recognizer,
            int grammar);
    private static native int SR_RecognizerPutAudio(int recognizer, byte[] buffer, int offset,
            int length, boolean isLast);
    private static native int SR_RecognizerAdvance(int recognizer);
    // private static native void SR_RecognizerLoadUtterance(int recognizer,
    //         const LCHAR* filename);
    // private static native void SR_RecognizerLoadWaveFile(int recognizer,
    //         const LCHAR* filename);
    // private static native void SR_RecognizerSetLockFunction(int recognizer,
    //         SR_RecognizerLockFunction function, void* data);
    private static native boolean SR_RecognizerIsSignalClipping(int recognizer);
    private static native boolean SR_RecognizerIsSignalDCOffset(int recognizer);
    private static native boolean SR_RecognizerIsSignalNoisy(int recognizer);
    private static native boolean SR_RecognizerIsSignalTooQuiet(int recognizer);
    private static native boolean SR_RecognizerIsSignalTooFewSamples(int recognizer);
    private static native boolean SR_RecognizerIsSignalTooManySamples(int recognizer);
    // private static native void SR_Recognizer_Change_Sample_Rate (size_t new_sample_rate);
    
    
    //
    // SR_AcousticState native methods
    //
    private static native void SR_AcousticStateReset(int recognizer);
    private static native void SR_AcousticStateSet(int recognizer, String state);
    private static native String SR_AcousticStateGet(int recognizer);


    //
    // SR_Grammar native methods
    //
    private static native void SR_GrammarCompile(int grammar);
    private static native void SR_GrammarAddWordToSlot(int grammar, String slot,
            String word, String pronunciation, int weight, String tag);
    private static native void SR_GrammarResetAllSlots(int grammar);
    // private static native void SR_GrammarAddNametagToSlot(int grammar, String slot,
    // const struct SR_Nametag_t* nametag, int weight, String tag);
    private static native void SR_GrammarSetupVocabulary(int grammar, int vocabulary);
    // private static native void SR_GrammarSetupModels(int grammar, SR_AcousticModels* models);
    private static native void SR_GrammarSetupRecognizer(int grammar, int recognizer);
    private static native void SR_GrammarUnsetupRecognizer(int grammar);
    // private static native void SR_GrammarGetModels(int grammar,SR_AcousticModels** models);
    private static native int SR_GrammarCreate();
    private static native void SR_GrammarDestroy(int grammar);
    private static native int SR_GrammarLoad(String filename);
    private static native void SR_GrammarSave(int grammar, String filename);
    // private static native void SR_GrammarSetDispatchFunction(int grammar,
    //         const LCHAR* name, void* userData, SR_GrammarDispatchFunction function);
    // private static native void SR_GrammarSetParameter(int grammar, const
    //         LCHAR* key, void* value);
    // private static native void SR_GrammarSetSize_tParameter(int grammar,
    //         const LCHAR* key, size_t value);
    // private static native void SR_GrammarGetParameter(int grammar, const
    //         LCHAR* key, void** value);
    // private static native void SR_GrammarGetSize_tParameter(int grammar,
    //         const LCHAR* key, size_t* value);
    // private static native void SR_GrammarCheckParse(int grammar, const LCHAR*
    //         transcription, SR_SemanticResult** result, size_t* resultCount);
    private static native void SR_GrammarAllowOnly(int grammar, String transcription);
    private static native void SR_GrammarAllowAll(int grammar);


    //
    // SR_Vocabulary native methods
    //
    // private static native int SR_VocabularyCreate();
    private static native int SR_VocabularyLoad();
    // private static native void SR_VocabularySave(SR_Vocabulary* self,
    //         const LCHAR* filename);
    // private static native void SR_VocabularyAddWord(SR_Vocabulary* self,
    //         const LCHAR* word);
    // private static native void SR_VocabularyGetLanguage(SR_Vocabulary* self,
    //         ESR_Locale* locale);
    private static native void SR_VocabularyDestroy(int vocabulary);
    private static native String SR_VocabularyGetPronunciation(int vocabulary, String word);


    //
    // SR_RecognizerResult native methods
    //
    private static native byte[] SR_RecognizerResultGetWaveform(int recognizer);
    private static native int SR_RecognizerResultGetSize(int recognizer);
    private static native int SR_RecognizerResultGetKeyCount(int recognizer, int nbest);
    private static native String[] SR_RecognizerResultGetKeyList(int recognizer, int nbest);
    private static native String SR_RecognizerResultGetValue(int recognizer,
            int nbest, String key);
    // private static native void SR_RecognizerResultGetLocale(int recognizer, ESR_Locale* locale);
}
