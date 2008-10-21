/*---------------------------------------------------------------------------*
 *  WordItem.java                                                            *
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

import android.speech.recognition.impl.WordItemImpl;

/**
 * Word that may be inserted into an embedded grammar slot.
 */
public abstract class WordItem implements SlotItem
{
  /**
   * Creates a new WordItem.
   *
   * @param word the word to insert
   * @param pronunciations the pronunciations to associated with the item. If the list is
   * is empty (example:new String[0]) the recognizer will attempt to guess the pronunciations.
   * @return the WordItem
   * @throws IllegalArgumentException if word is null or if pronunciations is
   * null or pronunciations contains an element equal to null or empty string.
   */
  public static WordItem valueOf(String word, String[] pronunciations) throws IllegalArgumentException
  {
    return WordItemImpl.valueOf(word, pronunciations);
  }

  /**
   * Creates a new WordItem.
   *
   * @param word the word to insert
   * @param pronunciation the pronunciation to associate with the item. If it
   * is null the recognizer will attempt to guess the pronunciations.
   * @return the WordItem
   * @throws IllegalArgumentException if word is null or if pronunciation is
   * an empty string
   */
  public static WordItem valueOf(String word, String pronunciation) throws IllegalArgumentException
  {
    return WordItemImpl.valueOf(word, pronunciation);
  }
}
