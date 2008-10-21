/*---------------------------------------------------------------------------*
 *  WordItemImpl.java                                                        *
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

package android.speech.recognition.impl;

import android.speech.recognition.WordItem;

/**
 */
public class WordItemImpl extends WordItem implements Runnable
{
  /**
   * Empty array that gets reused whenever the code requests that the underlying
   * recognizer guess the pronunciations.
   */
  private static final String[] guessPronunciations = new String[0];
  /**
   * Reference to the native object.
   */
  private long nativeObject;

  /**
   * Creates a new WordItem.
   *
   * @param word the word to insert
   * @throws IllegalArgumentException if word or pronunciations are null
   */
  private WordItemImpl(String word, String[] pronunciations) throws IllegalArgumentException
  {
    initNativeObject(word, pronunciations);
  }

  public void run()
  {
    dispose();
  }

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
  public static WordItemImpl valueOf(String word, String[] pronunciations)
    throws IllegalArgumentException
  {
    if (word == null)
      throw new IllegalArgumentException("Word may not be null");
    else if (pronunciations == null)
      throw new IllegalArgumentException("Pronunciations may not be null");
    for (int i = 0, size = pronunciations.length; i < size; ++i)
    {
       if (pronunciations[i]==null)
       {
           throw new IllegalArgumentException(
                   "Pronunciations element may not be null");
       }
       else
       {
           if (pronunciations[i].trim().equals(""))
             throw new IllegalArgumentException(
                     "Pronunciations may not contain empty strings");
       }
    }
    return new WordItemImpl(word, pronunciations);
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
  public static WordItemImpl valueOf(String word, String pronunciation) 
          throws IllegalArgumentException
  {
    String[] pronunciations;
    if (word == null)
      throw new IllegalArgumentException("Word may not be null");
    else if (pronunciation == null)
      pronunciations = guessPronunciations;
    else if (pronunciation.trim().equals(""))
      throw new IllegalArgumentException(
              "Pronunciation may not be an empty string");
    else
      pronunciations = new String[]{pronunciation};
    return new WordItemImpl(word, pronunciations);
  }

  /**
   * Allocates a reference to the native object.
   *
   * @param word the word to insert
   */
  private native void initNativeObject(String word, String[] pronunciations);

  public long getNativeObject() 
  {
     synchronized (WordItemImpl.class)
     {
         return nativeObject;
     }
  }
  
  /**
   * Releases the native resources associated with the object.
   */
  private void dispose()
  {
    synchronized (WordItemImpl.class)
    {  
        if (nativeObject != 0)
        {
            deleteNativeObject(nativeObject);
            nativeObject = 0;
        }
    }
  }

  @Override
  protected void finalize() throws Throwable
  {
    dispose();
    super.finalize();
  }

  /**
   * Deletes a native object.
   *
   * @param nativeObject pointer to the native object
   */
  private native void deleteNativeObject(long nativeObject);
}
