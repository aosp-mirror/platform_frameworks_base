/*---------------------------------------------------------------------------*
 *  Logger.java                                                              *
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

import android.speech.recognition.impl.LoggerImpl;

/**
 * Logs debugging information.
 */
public abstract class Logger
{
  /**
   * Logging level
   */
  public static class LogLevel
  {
    /**
     * Does not log.
     */
    public static LogLevel LEVEL_NONE = new LogLevel("Do not log");
    /**
     * Logs fatal issues. This level only logs ERROR.
     */
    public static LogLevel LEVEL_ERROR = new LogLevel("log  UAPI_ERROR logs");
    /**
     * Logs non-fatal issues. This level also logs ERROR.
     */
    public static LogLevel LEVEL_WARN =
      new LogLevel("log  UAPI_ERROR, UAPI_WARN logs");
    /**
     * Logs debugging information, such as the values of variables. This level also logs ERROR, WARN.
     */
    public static LogLevel LEVEL_INFO =
      new LogLevel("log  UAPI_ERROR, UAPI_WARN, UAPI_INFO logs");
    /**
     * Logs when loggers are created or destroyed. This level also logs INFO, WARN, ERROR.
     */
    public static LogLevel LEVEL_TRACE =
      new LogLevel("log UAPI_ERROR, UAPI_WARN, UAPI_INFO, UAPI_TRACE logs");
    private String message;

    /**
     * Creates a new LogLevel.
     *
     * @param message the message associated with the LogLevel.
     */
    private LogLevel(String message)
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
   * Returns the singleton instance.
   *
   * @return the singleton instance
   */
  public static Logger getInstance()
  {
    return LoggerImpl.getInstance();
  }

  /**
   * Sets the logging level.
   *
   * @param level the logging level
   */
  public abstract void setLoggingLevel(LogLevel level);

  /**
   * Sets the log path.
   *
   * @param path the path of the log file
   */
  public abstract void setPath(String path);

  /**
   * Logs an error message.
   *
   * @param message the message to log
   */
  public abstract void error(String message);

  /**
   * Logs a warning message.
   *
   * @param message the message to log
   */
  public abstract void warn(String message);

  /**
   * Logs an informational message.
   *
   * @param message the message to log
   */
  public abstract void info(String message);

  /**
   * Logs a method tracing message.
   *
   * @param message the message to log
   */
  public abstract void trace(String message);
}
