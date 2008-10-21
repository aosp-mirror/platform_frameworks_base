/*---------------------------------------------------------------------------*
 *  NBestRecognitionResultImpl.java                                          *
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

import android.speech.recognition.NBestRecognitionResult;
import android.speech.recognition.VoicetagItem;
import android.speech.recognition.VoicetagItemListener;
/**
 */
public class NBestRecognitionResultImpl implements NBestRecognitionResult
{
  /**
   * Reference to the native object.
   */
  private long nativeObject;

  /**
   * Creates a new NBestRecognitionResultImpl.
   *
   * @param nativeObject a reference to the native object
   */
  public NBestRecognitionResultImpl(long nativeObject)
  {
    this.nativeObject = nativeObject;
  }

  public int getSize()
  {
    synchronized (NBestRecognitionResultImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        return getSizeProxy(nativeObject);
    }
  }

  public Entry getEntry(int index)
  {
    synchronized (NBestRecognitionResultImpl.class)
    {
        if (nativeObject == 0)
          throw new IllegalStateException("Object has been disposed");
        long nativeEntryObject = getEntryProxy(nativeObject,index);
        if (nativeEntryObject==0)
          return null;
        else
          return new EntryImpl(nativeEntryObject);
    }
  }

  public VoicetagItem createVoicetagItem(String VoicetagId, VoicetagItemListener listener)
  {
    synchronized (NBestRecognitionResultImpl.class)
    {
        if (nativeObject == 0)
          throw new IllegalStateException("Object has been disposed");
        if ((VoicetagId == null) || (VoicetagId.length() == 0))
          throw new IllegalArgumentException("VoicetagId may not be null or empty string.");
        return new VoicetagItemImpl(createVoicetagItemProxy(nativeObject,VoicetagId,listener),false);
     }
  }

  /**
   * Releases the native resources associated with the object.
   */
  private void dispose()
  {
    synchronized (NBestRecognitionResultImpl.class)
    {
        nativeObject = 0;
    }
  }

  @Override
  protected void finalize() throws Throwable
  {
    dispose();
    super.finalize();
  }

  /**
   * Returns a reference to the native VoicetagItem.
   */
  private native long createVoicetagItemProxy(long nativeObject, String VoicetagId, VoicetagItemListener listener);

  private native long getEntryProxy(long nativeObject, int index);

  private native int getSizeProxy(long nativeObject);
}
