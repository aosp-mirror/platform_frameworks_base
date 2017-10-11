/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package lockedregioncodeinjection;

public class TestTarget {
  public static int boostCount = 0;
  public static int unboostCount = 0;
  public static int invokeCount = 0;
  public static boolean nextUnboostThrows = false;

  public static void boost() {
    boostCount++;
  }

  public static void unboost() {
    if (nextUnboostThrows) {
      nextUnboostThrows = false;
      throw new RuntimeException();
    }
    unboostCount++;
  }

  public static void invoke() {
    invokeCount++;
  }

  public static void resetCount() {
    boostCount = 0;
    unboostCount = 0;
    invokeCount = 0;
  }

  public synchronized void synchronizedCall() {
    invoke();
  }

  public synchronized int synchronizedCallReturnInt() {
    invoke();
    return 0;
  }

  public synchronized Object synchronizedCallReturnObject() {
    invoke();
    return this;
  }

  public void synchronizedThrowsOnUnboost() {
    nextUnboostThrows = true;
    synchronized(this) {
      invoke();
    }
  }
}
