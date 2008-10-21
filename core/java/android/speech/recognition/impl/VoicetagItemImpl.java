/*---------------------------------------------------------------------------*
 *  VoicetagItemImpl.java                                                    *
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

import android.speech.recognition.VoicetagItem;
import android.speech.recognition.VoicetagItemListener;
import java.io.FileNotFoundException;
import java.io.IOException;
/**
 */
public class VoicetagItemImpl  extends VoicetagItem implements Runnable
{
  /**
   * Reference to the native object.
   */
  private long nativeObject;
  /**
   * Voicetag has a filename need to be loaded before use it.
   */
  private boolean needToBeLoaded;
  
  /**
   * Creates a new VoicetagItemImpl.
   *
   * @param nativeObject the pointer to the native object
   */
  public VoicetagItemImpl(long nativeObject, boolean fromfile)
  {
    this.nativeObject = nativeObject;
    needToBeLoaded = fromfile;
  }

  public void run()
  {
    dispose();
  }

   /**
   * Creates a VoicetagItem from a file
   *
   * @param filename filename for Voicetag
   * @param listener listens for Voicetag events
   * @return the resulting VoicetagItem
   * @throws IllegalArgumentException if filename is null or an empty string.
   * @throws FileNotFoundException if the specified filename could not be found
   * @throws IOException if the specified filename could not be opened
   */
  public static VoicetagItem create(String filename, VoicetagItemListener listener) throws IllegalArgumentException,FileNotFoundException,IOException
  {
      if ((filename == null) || (filename.length() == 0))
        throw new IllegalArgumentException("Filename may not be null or empty string.");
       
      VoicetagItemImpl voicetag = null;
      long nativeVoicetag = createVoicetagProxy(filename,listener);
      if (nativeVoicetag!=0)
      {
        voicetag = new VoicetagItemImpl(nativeVoicetag,true);
      }
      return voicetag;
  }
  /**
   * Returns the audio used to construct the VoicetagItem.
   */
  public byte[] getAudio() throws IllegalStateException
  {
     synchronized (VoicetagItem.class)
     {
        if (nativeObject == 0)
         throw new IllegalStateException("Object was destroyed.");

        return getAudioProxy(nativeObject);
     }
  }

  /**
   * Sets the audio used to construct the Voicetag.
   */
  public void setAudio(byte[] waveform) throws IllegalArgumentException,IllegalStateException
  {
    synchronized (VoicetagItem.class)
    {
        if (nativeObject == 0)
         throw new IllegalStateException("Object was destroyed.");

         if ((waveform == null) || (waveform.length == 0))
            throw new IllegalArgumentException("Waveform may not be null or empty.");
         setAudioProxy(nativeObject,waveform);
    }
  }
  
  /**
  * Save the Voicetag.
  */
  public void save(String path) throws IllegalArgumentException
  {
    synchronized (VoicetagItem.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        if ((path == null) || (path.length() == 0))
            throw new IllegalArgumentException("Path may not be null or empty string.");
        saveVoicetagProxy(nativeObject,path);
    }
  }
  
  /**
  * Load a Voicetag.
  */
  public void load() throws IllegalStateException
  {
     synchronized (VoicetagItem.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        if (!needToBeLoaded) 
           throw new IllegalStateException("This Voicetag was not created from a file, does not need to be loaded.");
        loadVoicetagProxy(nativeObject);
     }
 }
  
  public long getNativeObject() 
  { 
     synchronized (VoicetagItem.class)
     {
      return nativeObject;
     }
  }
  
  /**
   * Releases the native resources associated with the object.
   */
  private void dispose()
  {
    synchronized (VoicetagItem.class)
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

  
  private static native long createVoicetagProxy(String filename, VoicetagItemListener listener);
  /**
  * (Optional operation) Returns the audio used to construct the Voicetag. The
  * audio is in PCM format and is start-pointed and end-pointed. The audio is
  * only generated if the enableGetWaveform recognition parameter is set
  * prior to recognition.
  *
  * @see RecognizerParameters.enableGetWaveform
  */
  private native byte[] getAudioProxy(long nativeObject);

  /**
  * (Optional operation) Sets the audio used to construct the Voicetag. The
  * audio is in PCM format and is start-pointed and end-pointed. The audio is
  * only generated if the enableGetWaveform recognition parameter is set
  * prior to recognition.
  *
  * @param waveform the endpointed waveform
  */
  private native void setAudioProxy(long nativeObject, byte[] waveform);
   
  /**
  * Save the Voicetag Item.
  */
  private native void saveVoicetagProxy(long nativeObject, String path);
   
  /**
  * Load a Voicetag Item.
  */
  private native void loadVoicetagProxy(long nativeObject);
  
  /**
   * Deletes a native object.
   *
   * @param nativeObject pointer to the native object
   */
  private native void deleteNativeObject(long nativeObject);
}
