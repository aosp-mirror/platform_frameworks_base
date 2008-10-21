/*---------------------------------------------------------------------------*
 *  EmbeddedRecognizerImpl.java                                              *
 *                                                                           *
 *  Copyright 2007 Nuance Communciations, Inc.                               *
 *                                                                           *
 *  Licensed under the Apache License, Version 2.0 (the 'License');          *
 *  you may not use this file except in compliance with the License.         *
 *                                                                           *
 *  You may obtain a copy of the License at                                  *
 *      http://www.apache.org/licenses/LICENSE-2.0                           *
 *                                                                           *
 *  Unless required by applicable law or agreed to in writing, software      *
 *  distributed under the License is distributed on an 'AS IS' BASIS,        *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. *
 *  See the License for the specific language governing permissions and      *
 *  limitations under the License.                                           *
 *                                                                           *
 *---------------------------------------------------------------------------*/

package android.speech.srec;

import java.io.IOException;

/**
 * Simple, synchronous speech recognizer, using the SREC package.
 * 
 * @hide
 */
public class Srec
{
    private int mNative;
    
    
    /**
     * Create an instance of a SREC speech recognizer.
     * @param configFile pathname of the baseline*.par configuration file.
     * @throws IOException
     */
    Srec(String configFile) throws IOException {
        
    }
    
    /**
     * Creates a Srec recognizer.
     * @param g2gFileName pathname of a g2g grammar file.
     * @return
     * @throws IOException
     */
    public Grammar loadGrammar(String g2gFileName) throws IOException {
        return null;
    }
    
    /**
     * Represents a grammar loaded into the recognizer.
     */
    public class Grammar {
        private int mId = -1;
        
        /**
         * Add a word to a slot
         * @param slot slot name
         * @param word word
         * @param pron pronunciation, or null to derive from word
         * @param weight weight to give the word
         * @param meaning meaning string
         */
        public void addToSlot(String slot, String word, String pron, int weight, String meaning) {
            
        }
        
        /**
         * Compile all slots.
         */
        public void compileSlots() {
            
        }
        
        /**
         * Reset all slots.
         */
        public void resetAllSlots() {
            
        }
        
        /**
         * Save grammar to g2g file.
         * @param g2gFileName
         * @throws IOException
         */
        public void save(String g2gFileName) throws IOException {
            
        }
        
        /**
         * Release resources associated with this grammar.
         */
        public void unload() {
            
        }
    }
    
    /**
     * Start recognition
     */
    public void start() {
        
    }
    
    /**
     * Process some audio and return the next state.
     * @return true if complete
     */
    public boolean process() {
        return false;
    }
    
    /**
     * Get the number of recognition results.
     * @return
     */
    public int getResultCount() {
        return 0;
    }
    
    /**
     * Get a set of keys for the result.
     * @param index index of result.
     * @return array of keys.
     */
    public String[] getResultKeys(int index) {
        return null;
    }
    
    /**
     * Get a result value
     * @param index index of the result.
     * @param key key of the result.
     * @return the result.
     */
    public String getResult(int index, String key) {
        return null;
    }
    
    /**
     * Reset the recognizer to the idle state.
     */
    public void reset() {
        
    }
    
    /**
     * Clean up resources.
     */
    public void dispose() {
        
    }
    
    protected void finalize() {
        
    }

}
