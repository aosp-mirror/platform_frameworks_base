/*---------------------------------------------------------------------------*
 *  EmbeddedRecognizerImpl.java                                              *
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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;
import android.speech.recognition.EmbeddedRecognizer;
import android.speech.recognition.Grammar;
import android.speech.recognition.AudioStream;
import android.speech.recognition.Grammar;
import android.speech.recognition.RecognizerListener;
import android.speech.recognition.GrammarListener;

/**
 */
public class EmbeddedRecognizerImpl extends EmbeddedRecognizer implements Runnable
{
  /**
   * Reference to the native object.
   */
  private long nativeObject;
  /**
   * The singleton instance.
   */
  private static EmbeddedRecognizerImpl instance;

  /**
   * Creates a new instance.
   */
  EmbeddedRecognizerImpl()
  {
    System system = System.getInstance();
    nativeObject = getInstanceProxy();
    if (nativeObject != 0)
      system.register(this);
  }

  /**
   * Returns the singleton instance.
   *
   * @return the singleton instance
   */
  public synchronized static EmbeddedRecognizerImpl getInstance()
  {
     synchronized (EmbeddedRecognizerImpl.class)
     {
        if (instance == null)
            instance = new EmbeddedRecognizerImpl();
        return instance;
     }
  }

  public void run()
  {
    dispose();
  }

  /**
   * Releases the native resources associated with the object.
   */
  private void dispose()
  {
     synchronized (EmbeddedRecognizerImpl.class)
     {
        if (instance != null)
        {
            deleteNativeObject(nativeObject);
            nativeObject = 0;
            instance = null;
            System.getInstance().unregister(this);
        }
     }
  }

  public void configure(String config) throws IllegalArgumentException,
    FileNotFoundException, IOException, UnsatisfiedLinkError,
    ClassNotFoundException
  {
     synchronized (EmbeddedRecognizerImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        if (config == null)
            throw new IllegalArgumentException("Configuration Is Null.");
        configureProxy(nativeObject,config);
     }
  }

  public void setListener(RecognizerListener listener)
  {
     synchronized (EmbeddedRecognizerImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        setListenerProxy(nativeObject,listener);
     }
  }

   public Grammar createGrammar(String value, GrammarListener listener) 
        throws IllegalArgumentException
  {
     synchronized (EmbeddedRecognizerImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        long nativeGrammar = createEmbeddedGrammarProxy(nativeObject,value.toString(), listener);
        return new SrecGrammarImpl(nativeGrammar);
     }
  }

  public void resetAcousticState()
  {
    synchronized (EmbeddedRecognizerImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        resetAcousticStateProxy(nativeObject);
    }
  }

  public void recognize(AudioStream audio,
    Vector<Grammar> grammars)
  {
    synchronized (EmbeddedRecognizerImpl.class)
    {
        if (nativeObject == 0)
         throw new IllegalStateException("Object was destroyed.");

        if (audio == null)
          throw new IllegalArgumentException("AudioStream cannot be null.");

        if (grammars == null || grammars.isEmpty() == true)
          throw new IllegalArgumentException("Grammars are null or empty.");
        int grammarCount = grammars.size();

        long[] nativeGrammars = new long[grammarCount];

        for (int i = 0; i < grammarCount; ++i)
          nativeGrammars[i] = ((GrammarImpl) grammars.get(i)).getNativeObject();

        recognizeProxy(nativeObject,((AudioStreamImpl)audio).getNativeObject(), nativeGrammars);
    }
  }

  public void recognize(AudioStream audio,
    Grammar grammar)
  {
    synchronized (EmbeddedRecognizerImpl.class)
    {
        if (nativeObject == 0)
         throw new IllegalStateException("Object was destroyed.");
    }
    Vector<Grammar> grammars = new Vector<Grammar>();
    grammars.add(grammar);
    recognize(audio, grammars);
  }

  public  void stop()
  {
    synchronized (EmbeddedRecognizerImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        stopProxy(nativeObject);
    }
  }

  public void setParameters(Hashtable<String, String> params)
  {
    synchronized (EmbeddedRecognizerImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        setParametersProxy(nativeObject,params);
    }
  }

  public void getParameters(Vector<String> params)
  {
    synchronized (EmbeddedRecognizerImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        getParametersProxy(nativeObject,params);
    }
  }

  /**
   * Returns the native EmbeddedRecognizer.
   *
   * @return a reference to the native object
   */
  private native long getInstanceProxy();

  /**
   * Configures the recognizer instance.
   *
   * @param config the recognizer configuration file
   */
  private native void configureProxy(long nativeObject, String config) throws IllegalArgumentException,
    FileNotFoundException, IOException, UnsatisfiedLinkError,
    ClassNotFoundException;

  /**
   * Sets the recognizer listener.
   *
   * @param listener listens for recognizer events
   */
  private native void setListenerProxy(long nativeObject, RecognizerListener listener);

  private native void recognizeProxy(long nativeObject, long audioNativeObject,
    long[] pGrammars);

  private native long createEmbeddedGrammarProxy(long nativeObject, String url,
    GrammarListener listener);

  private native void stopProxy(long nativeObject);

  private native void deleteNativeObject(long nativeObject);

  private native void setParametersProxy(long nativeObject, Hashtable<String, String> params);

  private native void getParametersProxy(long nativeObject, Vector<String> params);

  private native void resetAcousticStateProxy(long nativeObject);

}
