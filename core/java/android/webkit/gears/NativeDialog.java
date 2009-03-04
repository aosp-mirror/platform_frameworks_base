// Copyright 2008 The Android Open Source Project
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
//  1. Redistributions of source code must retain the above copyright notice,
//     this list of conditions and the following disclaimer.
//  2. Redistributions in binary form must reproduce the above copyright notice,
//     this list of conditions and the following disclaimer in the documentation
//     and/or other materials provided with the distribution.
//  3. Neither the name of Google Inc. nor the names of its contributors may be
//     used to endorse or promote products derived from this software without
//     specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
// WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
// EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
// PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
// OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
// WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
// OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
// ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package android.webkit.gears;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.lang.InterruptedException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Utility class to call a modal native dialog on Android
 * The dialog itself is an Activity defined in the Browser.
 * @hide
 */
public class NativeDialog {

  private static final String TAG = "Gears-J-NativeDialog";

  private final String DIALOG_PACKAGE = "com.android.browser";
  private final String DIALOG_CLASS = DIALOG_PACKAGE + ".GearsNativeDialog";

  private static Lock mLock = new ReentrantLock();
  private static Condition mDialogFinished = mLock.newCondition();
  private static String mResults = null;

  private static boolean mAsynchronousDialog;

  /**
   * Utility function to build the intent calling the
   * dialog activity
   */
  private Intent createIntent(String type, String arguments) {
    Intent intent = new Intent();
    intent.setClassName(DIALOG_PACKAGE, DIALOG_CLASS);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.putExtra("dialogArguments", arguments);
    intent.putExtra("dialogType", type);
    return intent;
  }

  /**
   * Opens a native dialog synchronously and waits for its completion.
   *
   * The dialog is an activity (GearsNativeDialog) provided by the Browser
   * that we call via startActivity(). Contrary to a normal activity though,
   * we need to block until it returns. To do so, we define a static lock
   * object in this class, which GearsNativeDialog can unlock once done
   */
  public String showDialog(Context context, String file,
      String arguments) {

    try {
      mAsynchronousDialog = false;
      mLock.lock();
      File path = new File(file);
      String fileName = path.getName();
      String type = fileName.substring(0, fileName.indexOf(".html"));
      Intent intent = createIntent(type, arguments);

      mResults = null;
      context.startActivity(intent);
      mDialogFinished.await();
    } catch (InterruptedException e) {
      Log.e(TAG, "exception e: " + e);
    } catch (ActivityNotFoundException e) {
      Log.e(TAG, "exception e: " + e);
    } finally {
      mLock.unlock();
    }

    return mResults;
  }

  /**
   * Opens a native dialog asynchronously
   *
   * The dialog is an activity (GearsNativeDialog) provided by the
   * Browser.
   */
  public void showAsyncDialog(Context context, String type,
                           String arguments) {
    mAsynchronousDialog = true;
    Intent intent = createIntent(type, arguments);
    context.startActivity(intent);
  }

  /**
   * Static method that GearsNativeDialog calls to unlock us
   */
  public static void signalFinishedDialog() {
    if (!mAsynchronousDialog) {
      mLock.lock();
      mDialogFinished.signal();
      mLock.unlock();
    } else {
      // we call the native callback
      closeAsynchronousDialog(mResults);
    }
  }

  /**
   * Static method that GearsNativeDialog calls to set the
   * dialog's result
   */
  public static void closeDialog(String res) {
    mResults = res;
  }

  /**
   * Native callback method
   */
  private native static void closeAsynchronousDialog(String res);
}
