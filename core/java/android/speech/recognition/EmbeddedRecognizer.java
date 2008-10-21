/*---------------------------------------------------------------------------*
 *  EmbeddedRecognizer.java                                                  *
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

import java.io.FileNotFoundException;
import java.io.IOException;
import android.speech.recognition.impl.EmbeddedRecognizerImpl;

/**
 * Embedded recognizer.
 */
public abstract class EmbeddedRecognizer implements Recognizer
{
  private static EmbeddedRecognizer instance;

  /**
   * Returns the embedded recognizer.
   *
   * @return the embedded recognizer
   */
  public static EmbeddedRecognizer getInstance()
  {
    instance = EmbeddedRecognizerImpl.getInstance();
    return instance;
  }

  /**
   * Configures the recognizer.
   *
   * @param config recognizer configuration file
   * @throws IllegalArgumentException if config is null or an empty string
   * @throws FileNotFoundException if the specified file could not be found
   * @throws IOException if the specified file could not be opened
   * @throws UnsatisfiedLinkError if the recognizer plugin could not be loaded
   * @throws ClassNotFoundException if the recognizer plugin could not be found
   */
  public abstract void configure(String config) throws IllegalArgumentException,
    FileNotFoundException, IOException, UnsatisfiedLinkError,
    ClassNotFoundException;

   /**
   * The recognition accuracy improves over time as the recognizer adapts to
   * the surrounding environment. This method enables developers to reset the
   * adaptation when the environment is known to have changed.
   *
   * @throws IllegalArgumentException if recognizer instance is null
   */
  public abstract void resetAcousticState() throws IllegalArgumentException;
}
