/*---------------------------------------------------------------------------*
 *  Codec.java                                                               *
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
 * Audio formats.
 */
public abstract class Codec
{
  /**
   * PCM, 16 bits, 8KHz.
   */
  public static final Codec PCM_16BIT_8K = new Codec("PCM/16bit/8KHz")
  {
    @Override
    public byte getBitsPerSample()
    {
      return 16;
    }

    @Override
    public int getSampleRate()
    {
      return 8000;
    }
  };
  /**
   * PCM, 16 bits, 11KHz.
   */
  public static final Codec PCM_16BIT_11K = new Codec("PCM/16bit/11KHz")
  {
    @Override
    public byte getBitsPerSample()
    {
      return 16;
    }

    @Override
    public int getSampleRate()
    {
      return 11025;
    }
  };
  /**
   * PCM, 16 bits, 22KHz.
   */
  public static final Codec PCM_16BIT_22K = new Codec("PCM/16bit/22KHz")
  {
    @Override
    public byte getBitsPerSample()
    {
      return 16;
    }

    @Override
    public int getSampleRate()
    {
      return 22050;
    }
  };
  /**
   * ULAW, 8 bits, 8KHz.
   */
  public static final Codec ULAW_8BIT_8K = new Codec("ULAW/8bit/8KHz")
  {
    @Override
    public byte getBitsPerSample()
    {
      return 8;
    }

    @Override
    public int getSampleRate()
    {
      return 8000;
    }
  };
  private final String message;

  /**
   * Creates a new Codec.
   *
   * @param message the message to associate with the codec
   */
  private Codec(String message)
  {
    this.message = message;
  }

  @Override
  public String toString()
  {
    return message;
  }

  /**
   * Returns the codec sample-rate.
   * 
   * @return the codec sample-rate
   */
  public abstract int getSampleRate();

  /**
   * Returns the codec bitrate.
   * 
   * @return the codec bitrate
   */
  public abstract byte getBitsPerSample();
}
