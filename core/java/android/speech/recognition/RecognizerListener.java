/*---------------------------------------------------------------------------*
 *  RecognizerListener.java                                                  *
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

/**
 * Listens for recognizer events.
 */
public interface RecognizerListener extends ParametersListener
{
  /**
   * Recognition failure.
   */
  public static class FailureReason
  {
    /**
     * The audio did not generate any results.
     */
    public static FailureReason NO_MATCH =
      new FailureReason("The audio did not generate any results");
    /**
     * Beginning of speech occured too soon.
     */
    public static FailureReason SPOKE_TOO_SOON =
      new FailureReason("Beginning of speech occurred too soon");
    /**
     * A timeout occured before the beginning of speech.
     */
    public static FailureReason BEGINNING_OF_SPEECH_TIMEOUT =
      new FailureReason("A timeout occurred before the beginning of " + "speech");
    /**
     * A timeout occured before the recognition could complete.
     */
    public static FailureReason RECOGNITION_TIMEOUT =
      new FailureReason("A timeout occurred before the recognition " +
      "could complete");
    /**
     * The recognizer encountered more audio than was acceptable according to
     * its configuration.
     */
    public static FailureReason TOO_MUCH_SPEECH =
      new FailureReason("The " +
      "recognizer encountered more audio than was acceptable according to " +
      "its configuration");

    public static FailureReason UNKNOWN =
      new FailureReason("unknown failure reason");

    private final String message;

    private FailureReason(String message)
    {
      this.message = message;
    }

    @Override
    public String toString()
    {
      return message;
    }
  }

  /**
   * Invoked after recognition begins.
   */
  void onStarted();

  /**
   * Invoked if the recognizer detects the beginning of speech.
   */
  void onBeginningOfSpeech();

  /**
   * Invoked if the recognizer detects the end of speech.
   */
  void onEndOfSpeech();

  /**
   * Invoked if the recognizer does not detect speech within the configured
   * timeout period.
   */
  void onStartOfSpeechTimeout();

  /**
   * Invoked when the recognizer acoustic state is reset.
   *
   * @see android.speech.recognition.EmbeddedRecognizer#resetAcousticState()
   */
  void onAcousticStateReset();

  /**
   * Invoked when a recognition result is generated.
   *
   * @param result the recognition result. The result object can not be
   * used outside of the scope of the onRecognitionSuccess() callback method.
   * To be able to do so, copy it's contents to an user-defined object.<BR>
   * An example of this object could be a vector of string arrays; where the
   * vector represents a list of recognition result entries and each entry
   * is an array of strings to hold the entry's values (the semantic
   * meaning, confidence score and literal meaning).
   */
  void onRecognitionSuccess(RecognitionResult result);

  /**
   * Invoked when a recognition failure occurs.
   *
   * @param reason the failure reason
   */
  void onRecognitionFailure(FailureReason reason);

  /**
   * Invoked when an unexpected error occurs. This is normally followed by
   * onStopped() if the component shuts down successfully.
   *
   * @param e the cause of the failure
   */
  void onError(Exception e);

  /**
   * Invoked when the recognizer stops (due to normal termination or an error).
   *
   * Invoking stop() on a recognizer that is already stopped will not result
   * in a onStopped() event.
   */
  void onStopped();
}
