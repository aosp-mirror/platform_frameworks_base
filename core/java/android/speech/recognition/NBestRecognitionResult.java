/*---------------------------------------------------------------------------*
 *  NBestRecognitionResult.java                                              *
 *                                                                           *
 *  Copyright 2007, 2008 Nuance Communciations, Inc.                               *
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

package android.speech.recognition;

import java.util.Enumeration;

/**
 * N-Best recognition results. Entries are sorted in decreasing order according
 * to their probability, from the most probable result to the least probable
 * result.
 */
public interface NBestRecognitionResult extends RecognitionResult
{
  /**
   * Recognition result entry
   */
  public static interface Entry
  {
    /**
     * Returns the semantic meaning of a recognition result (i.e.&nbsp;the application-specific value
     * associated with what the user said). In an example where a person's name is mapped
     * to a phone-number, the phone-number is the semantic meaning.
     *
     * @return the semantic meaning of a recognition result.
     * @throws IllegalStateException if the object has been disposed
     */
    String getSemanticMeaning() throws IllegalStateException;

    /**
     * The confidence score of a recognition result. Values range from 0 to 100
     * (inclusive).
     *
     * @return the confidence score of a recognition result.
     * @throws IllegalStateException if the object has been disposed
     */
    byte getConfidenceScore() throws IllegalStateException;

    /**
     * Returns the literal meaning of a recognition result (i.e.&nbsp;literally
     * what the user said). In an example where a person's name is mapped to a 
     * phone-number, the person's name is the literal meaning.
     *
     * @return the literal meaning of a recognition result.
     * @throws IllegalStateException if the object has been disposed
     */
    String getLiteralMeaning() throws IllegalStateException;
    
    /**
     * Returns the value associated with the specified key.
     *
     * @param key the key to look up
     * @return the associated value or null if this entry does not contain
     * any mapping for the key
     */
    String get(String key);
    
    /**
     * Returns an enumeration of the keys in this Entry.
     *
     * @return an enumeration of the keys in this Entry.
     */
    Enumeration keys();
  }

  /**
   * Returns the number of entries in the n-best list.
   *
   * @return the number of entries in the n-best list
   */
  int getSize();

  /**
   * Returns the n-best entry that contains key-value pairs associated with the
   * recognition result.
   *
   * @param index the index of the n-best entry
   * @return null if all active GrammarConfiguration.grammarToMeaning() return
   * null
   * @throws ArrayIndexOutOfBoundsException if index is greater than size of
   * entries
   */
  Entry getEntry(int index) throws ArrayIndexOutOfBoundsException;

  /**
   * Creates a new VoicetagItem if the last recognition was an enrollment
   * operation.
   *
   * @param VoicetagId string voicetag unique id value. 
   * @param listener listens for Voicetag events
   * @return the resulting VoicetagItem
   * @throws IllegalArgumentException if VoicetagId is null or an empty string.
   * @throws IllegalStateException if the last recognition was not an
   * enrollment operation
   */
  VoicetagItem createVoicetagItem(String VoicetagId, VoicetagItemListener listener) throws IllegalArgumentException,IllegalStateException;
}
