/*---------------------------------------------------------------------------*
 *  ParametersListener.java                                                  *
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
 * Listens for parameter events.
 */
public interface ParametersListener
{
  /**
   * Invoked if retrieving parameters has failed.
   *
   * @param parameters the parameters that could not be retrieved
   * @param e the failure reason
   */
  void onParametersGetError(Vector<String> parameters, Exception e);

  /**
   * Invoked if setting parameters has failed.
   *
   * @param parameters the parameters that could not be set
   * @param e the failure reason
   */
  void onParametersSetError(Hashtable<String, String> parameters, Exception e);

  /**
   * This method is called when the parameters specified in setParameters have
   * successfully been set. This method is guaranteed to be invoked after
   * onParametersSetError, even if count==0.
   *
   * @param parameters the set parameters
   */
  void onParametersSet(Hashtable<String, String> parameters);

  /**
   * This method is called when the parameters specified in getParameters have
   * successfully been retrieved. This method is guaranteed to be invoked after
   * onParametersGetError, even if count==0.
   *
   * @param parameters the retrieved parameters
   */
  void onParametersGet(Hashtable<String, String> parameters);
}
