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

import android.content.Context;
import android.util.Log;
import android.webkit.Plugin;
import android.webkit.Plugin.PreferencesClickHandler;

/**
 * Simple bridge class intercepting the click in the
 * browser plugin list and calling the Gears settings
 * dialog.
 */
public class PluginSettings {

  private static final String TAG = "Gears-J-PluginSettings";
  private Context mContext;

  public PluginSettings(Plugin plugin) {
    plugin.setClickHandler(new ClickHandler());
  }

  /**
   * We do not call the dialog synchronously here as the main
   * message loop would be blocked, so we call it via a secondary thread.
   */
  private class ClickHandler implements PreferencesClickHandler {
    public void handleClickEvent(Context context) {
      mContext = context.getApplicationContext();
      Thread startDialog = new Thread(new StartDialog(context));
      startDialog.start();
    }
  }

  /**
   * Simple wrapper class to call the gears native method in
   * a separate thread (the native code will then instanciate a NativeDialog
   * object which will start the GearsNativeDialog activity defined in
   * the Browser).
   */
  private class StartDialog implements Runnable {
    Context mContext;

    public StartDialog(Context context) {
      mContext = context;
    }

    public void run() {
      runSettingsDialog(mContext);
    }
  }

  private static native void runSettingsDialog(Context c);

}
