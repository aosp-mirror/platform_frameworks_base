/*---------------------------------------------------------------------------*
 *  LoggerImpl.java                                                          *
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

import android.speech.recognition.Logger;

/**
 */
public class LoggerImpl extends Logger implements Runnable
{
  private static LoggerImpl instance;
  /**
   * Reference to the native object.
   */
  private long nativeObject;

  /**
   * Creates a new instance of LoggerImpl.
   *
   * @param function the name of the enclosing function
   */
  private LoggerImpl()
  {
    System system = System.getInstance();
    nativeObject = initNativeObject();
    if (nativeObject!=0)
         system.register(this);
  }

  public void run()
  {
    dispose();
  }

  /**
   * Returns the singleton instance.
   *
   * @return the singleton instance
   */
  public static LoggerImpl getInstance()
  {
    synchronized (LoggerImpl.class)
    {
        if (instance == null)
            instance = new LoggerImpl();
        return instance;
    }
  }

  public void setLoggingLevel(LogLevel level)
  {
    synchronized (LoggerImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        setLoggingLevelProxy(nativeObject,level);
    }
  }

  public void setPath(String path)
  {
    synchronized (LoggerImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        setPathProxy(nativeObject,path);
    }
  }

  public void error(String message)
  {
    synchronized (LoggerImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        errorProxy(nativeObject,message);
    }
  }

  public void warn(String message)
  {
    synchronized (LoggerImpl.class)
    {
        if (nativeObject == 0)
          throw new IllegalStateException("Object has been disposed");
        warnProxy(nativeObject,message);
    }
  }

  public  void info(String message)
  {
    synchronized (LoggerImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        infoProxy(nativeObject,message);
    }
  }

  public void trace(String message)
  {
     synchronized (LoggerImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        traceProxy(nativeObject,message);
     }
  }

  /**
   * Releases the native resources associated with the object.
   */
  private void dispose()
  {
    synchronized (LoggerImpl.class)
    {
        if (nativeObject!=0) 
        {
            deleteNativeObject(nativeObject);
            System.getInstance().unregister(this);
        }
        nativeObject = 0;
        instance = null;
    }
  }
 
  @Override
  protected void finalize() throws Throwable
  {
    dispose();
    super.finalize();
  }

  private native long initNativeObject();

  private native void setLoggingLevelProxy(long nativeObject, LogLevel level);

  private native void setPathProxy(long nativeObject, String filename);

  private native void errorProxy(long nativeObject, String message);

  private native void warnProxy(long nativeObject, String message);

  private native void infoProxy(long nativeObject, String message);

  private native void traceProxy(long nativeObject,String message);

  private native void deleteNativeObject(long nativeObject);
}
