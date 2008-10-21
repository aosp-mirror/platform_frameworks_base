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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.webkit.CacheManager;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Utility class to call a modal HTML dialog on Android
 */
public class HtmlDialogAndroid {

  private static final String TAG = "Gears-J-HtmlDialog";
  private static final String DIALOG_PACKAGE = "com.android.browser";
  private static final String DIALOG_SERVICE = DIALOG_PACKAGE
      + ".GearsDialogService";
  private static final String DIALOG_INTERFACE = DIALOG_PACKAGE
      + ".IGearsDialogService";

  private static IGearsDialogService gearsDialogService;

  public static void setGearsDialogService(IGearsDialogService service) {
    gearsDialogService = service;
  }

  /**
   * Bind to the GearsDialogService.
   */
  public static boolean bindToService(Context context,
      ServiceConnection serviceConnection) {
    Intent dialogIntent = new Intent();
    dialogIntent.setClassName(DIALOG_PACKAGE, DIALOG_SERVICE);
    dialogIntent.setAction(DIALOG_INTERFACE);
    return context.bindService(dialogIntent, serviceConnection,
        Context.BIND_AUTO_CREATE);
  }

  /**
   * Bind to the GearsDialogService synchronously.
   * The service is started using our own defaultServiceConnection
   * handler, and we wait until the handler notifies us.
   */
  public void synchronousBindToService(Context context) {
    try {
      if (bindToService(context, defaultServiceConnection)) {
        if (gearsDialogService == null) {
          synchronized(defaultServiceConnection) {
            defaultServiceConnection.wait(3000); // timeout after 3s
          }
        }
      }
    } catch (InterruptedException e) {
      Log.e(TAG, "exception: " + e);
    }
  }

  /**
   * Read the HTML content from the disk
   */
  public String readHTML(String filePath) {
    FileInputStream inputStream = null;
    String content = "";
    try {
      inputStream = new FileInputStream(filePath);
      StringBuffer out = new StringBuffer();
      byte[] buffer = new byte[4096];
      for (int n; (n = inputStream.read(buffer)) != -1;) {
        out.append(new String(buffer, 0, n));
      }
      content = out.toString();
    } catch (IOException e) {
      Log.e(TAG, "exception: " + e);
    } finally {
      if (inputStream != null) {
        try {
         inputStream.close();
        } catch (IOException e) {
          Log.e(TAG, "exception: " + e);
        }
      }
    }
    return content;
  }

  /**
   * Open an HTML dialog synchronously and waits for its completion.
   * The dialog is accessed through the GearsDialogService provided by
   * the Android Browser.
   * We can be called either directly, and then gearsDialogService will
   * not be set and we will bind to the service synchronously, and unbind
   * after calling the service, or called indirectly via GearsPluginSettings.
   * In the latter case, GearsPluginSettings does the binding/unbinding.
   */
  public String showDialog(Context context, String htmlFilePath,
      String arguments) {

    CacheManager.endCacheTransaction();

    String ret = null;
    boolean synchronousCall = false;
    if (gearsDialogService == null) {
      synchronousCall = true;
      synchronousBindToService(context);
    }

    try {
      if (gearsDialogService != null) {
        String htmlContent = readHTML(htmlFilePath);
        if (htmlContent.length() > 0) {
          ret = gearsDialogService.showDialog(htmlContent, arguments,
              !synchronousCall);
        }
      } else {
        Log.e(TAG, "Could not connect to the GearsDialogService!");
      }
      if (synchronousCall) {
        context.unbindService(defaultServiceConnection);
        gearsDialogService = null;
      }
    } catch (RemoteException e) {
      Log.e(TAG, "remote exception: " + e);
      gearsDialogService = null;
    }

    CacheManager.startCacheTransaction();

    return ret;
  }

  private ServiceConnection defaultServiceConnection =
      new ServiceConnection() {
    public void onServiceConnected(ComponentName className, IBinder service) {
      synchronized (defaultServiceConnection) {
        gearsDialogService = IGearsDialogService.Stub.asInterface(service);
        defaultServiceConnection.notify();
      }
    }
    public void onServiceDisconnected(ComponentName className) {
      gearsDialogService = null;
    }
  };
}
