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
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebView;

/**
 * Utility class to create a shortcut on Android
 */
public class DesktopAndroid {

  private static final String TAG = "Gears-J-Desktop";
  private static final String EXTRA_SHORTCUT_DUPLICATE = "duplicate";
  private static final String ACTION_INSTALL_SHORTCUT =
      "com.android.launcher.action.INSTALL_SHORTCUT";

  // Android now enforces a 64x64 limit for the icon
  private static int MAX_WIDTH = 64;
  private static int MAX_HEIGHT = 64;

  /**
   * Small utility function returning a Bitmap object.
   *
   * @param path the icon path
   */
  private static Bitmap getBitmap(String path) {
    return BitmapFactory.decodeFile(path);
  }

  /**
   * Create a shortcut for a webpage.
   *
   * <p>To set a shortcut on Android, we use the ACTION_INSTALL_SHORTCUT
   * from the default Home application. We only have to create an Intent
   * containing extra parameters specifying the shortcut.
   * <p>Note: the shortcut mechanism is not system wide and depends on the
   * Home application; if phone carriers decide to rewrite a Home application
   * that does not accept this Intent, no shortcut will be added.
   *
   * @param webview the webview we are called from
   * @param title the shortcut's title
   * @param url the shortcut's url
   * @param imagePath the local path of the shortcut's icon
   */
  public static void setShortcut(WebView webview, String title,
        String url, String imagePath) {
    Context context = webview.getContext();

    Intent viewWebPage = new Intent(Intent.ACTION_VIEW);
    viewWebPage.setData(Uri.parse(url));
    viewWebPage.addCategory(Intent.CATEGORY_BROWSABLE);

    Intent intent = new Intent(ACTION_INSTALL_SHORTCUT);
    intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, viewWebPage);
    intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, title);

    // We disallow the creation of duplicate shortcuts (i.e. same
    // url, same title, but different screen position).
    intent.putExtra(EXTRA_SHORTCUT_DUPLICATE, false);

    Bitmap bmp = getBitmap(imagePath);
    if (bmp != null) {
      if ((bmp.getWidth() > MAX_WIDTH) ||
          (bmp.getHeight() > MAX_HEIGHT)) {
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bmp,
            MAX_WIDTH, MAX_HEIGHT, true);
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, scaledBitmap);
      } else {
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON, bmp);
      }
    } else {
      // This should not happen as we just downloaded the icon
      Log.e(TAG, "icon file <" + imagePath + "> not found");
    }

    context.sendBroadcast(intent);
  }

}
