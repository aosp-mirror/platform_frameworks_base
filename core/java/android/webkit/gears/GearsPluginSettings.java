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
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;
import android.webkit.Plugin;
import android.webkit.Plugin.PreferencesClickHandler;

/**
 * Simple bridge class intercepting the click in the
 * browser plugin list and calling the Gears settings
 * dialog.
 */
public class GearsPluginSettings {

  private static final String TAG = "Gears-J-GearsPluginSettings";
  private Context context;

  public GearsPluginSettings(Plugin plugin) {
    plugin.setClickHandler(new ClickHandler());
  }

  /**
   * We do not need to call the dialog synchronously here (doing so
   * actually cause a lot of problems as the main message loop is also
   * blocked), which is why we simply call it via a thread.
   */
  private class ClickHandler implements PreferencesClickHandler {
    public void handleClickEvent(Context aContext) {
      context = aContext;
      Thread startService = new Thread(new StartService());
      startService.run();
    }
  }

  private static native void runSettingsDialog(Context c);

  /**
   * StartService is the runnable we use to open the dialog.
   * We bind the service to serviceConnection; upon
   * onServiceConnected the dialog will be called from the
   * native side using the runSettingsDialog method.
   */
  private class StartService implements Runnable {
    public void run() {
      HtmlDialogAndroid.bindToService(context, serviceConnection);
    }
  }

  /**
   * ServiceConnection instance.
   * onServiceConnected is called upon connection with the service;
   * we can then safely open the dialog.
   */
  private ServiceConnection serviceConnection = new ServiceConnection() {
    public void onServiceConnected(ComponentName className, IBinder service) {
      IGearsDialogService gearsDialogService =
          IGearsDialogService.Stub.asInterface(service);
      HtmlDialogAndroid.setGearsDialogService(gearsDialogService);
      runSettingsDialog(context);
      context.unbindService(serviceConnection);
      HtmlDialogAndroid.setGearsDialogService(null);
    }
    public void onServiceDisconnected(ComponentName className) {
      HtmlDialogAndroid.setGearsDialogService(null);
    }
  };
}
