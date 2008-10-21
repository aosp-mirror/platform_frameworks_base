/*---------------------------------------------------------------------------*
 *  Recognizer.java                                                          *
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

import java.util.Hashtable;
import java.util.Vector;

/**
 * Speech recognizer interface.
 */
public interface Recognizer
{
 /**
   * Sets the recognizer event listener.
   *
   * @param listener listens for recognizer events
   */
  void setListener(RecognizerListener listener);

  /**
   * Creates an embedded grammar.
   *
   * @param value value of that grammarType. Could be a URL or an inline grammar.
   * @return a grammar
   * @throws IllegalArgumentException if value is null or listener is not of type
   * GrammarListener.
   */
  Grammar createGrammar(String value, GrammarListener listener) throws IllegalArgumentException;
  
  /**
   * Begins speech recognition.
   *
   * @param audio the audio stream to recognizer
   * @param grammars a collection of grammar sets to recognize against
   * @see #recognize(AudioStream, Grammar)
   * @throws IllegalStateException if any of the grammars are not loaded
   * @throws IllegalArgumentException if audio is null, in-use by another
   * component or empty. Or if grammars is null or grammars count is less than
   * one. Or if the audio codec differs from recognizer codec.
   * @throws UnsupportedOperationException if the recognizer does not support
   * the number of grammars specified.
   */
  void recognize(AudioStream audio,
    Vector<Grammar> grammars) throws IllegalStateException,
    IllegalArgumentException, UnsupportedOperationException;

  /**
   * This convenience method is equivalent to invoking
   * recognize(audio, grammars) with a single grammar.
   *
   * @param audio the audio to recognizer
   * @param grammar a grammar to recognize against
   * @see #recognize(AudioStream, Vector)
   * @throws IllegalStateException if grammar is not loaded
   * @throws IllegalArgumentException if audio is null, in-use by another
   * component or is empty. Or if grammar is null or if the audio codec differs
   * from the recognizer codec.
   */
  void recognize(AudioStream audio, Grammar grammar) throws IllegalStateException,
    IllegalArgumentException;

  /**
   * Terminates a recognition if one is in-progress.
   * This must not be called until the recognize method
   * returns; otherwise the result is not defined.
   *
   * @see RecognizerListener#onStopped
   */
  void stop();

  /**
   * Sets the values of recognition parameters.
   *
   * @param parameters the parameter key-value pairs to set
   */
  void setParameters(Hashtable<String, String> parameters);

  /**
   * Retrieves the values of recognition parameters.
   *
   * @param parameters the names of the parameters to retrieve
   */
  void getParameters(Vector<String> parameters);

}
