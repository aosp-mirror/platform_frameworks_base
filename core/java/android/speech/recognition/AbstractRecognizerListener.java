/*---------------------------------------------------------------------------*
 *  AbstractRecognizerListener.java                                          *
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
 * A RecognizerListener whose methods are empty. This class exists as
 * convenience for creating listener objects.
 */
public abstract class AbstractRecognizerListener implements RecognizerListener
{
  public void onBeginningOfSpeech()
  {
  }

  public void onEndOfSpeech()
  {
  }

  public void onRecognitionSuccess(RecognitionResult result)
  {
  }

  public void onRecognitionFailure(FailureReason reason)
  {
  }

  public void onError(Exception e)
  {
  }

  public void onParametersGetError(Vector<String> parameters, Exception e)
  {
  }

  public void onParametersSetError(Hashtable<String, String> parameters,
    Exception e)
  {
  }

  public void onParametersGet(Hashtable<String, String> parameters)
  {
  }

  public void onParametersSet(Hashtable<String, String> parameters)
  {
  }

  public void onStartOfSpeechTimeout()
  {
  }

  public void onAcousticStateReset()
  {
  }

  public void onStarted()
  {
  }

  public void onStopped()
  {
  }
}
