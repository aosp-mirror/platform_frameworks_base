/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.policy.impl;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Stores a mapping of global keys.
 * <p>
 * A global key will NOT go to the foreground application and instead only ever be sent via targeted
 * broadcast to the specified component. The action of the intent will be
 * {@link Intent#ACTION_GLOBAL_BUTTON} and the KeyEvent will be included in the intent with
 * {@link Intent#EXTRA_KEY_EVENT}.
 */
final class GlobalKeyManager {

    private static final String TAG = "GlobalKeyManager";

    private static final String TAG_GLOBAL_KEYS = "global_keys";
    private static final String ATTR_VERSION = "version";
    private static final String TAG_KEY = "key";
    private static final String ATTR_KEY_CODE = "keyCode";
    private static final String ATTR_COMPONENT = "component";

    private static final int GLOBAL_KEY_FILE_VERSION = 1;

    private SparseArray<ComponentName> mKeyMapping;

    public GlobalKeyManager(Context context) {
        mKeyMapping = new SparseArray<ComponentName>();
        loadGlobalKeys(context);
    }

    /**
     * Broadcasts an intent if the keycode is part of the global key mapping.
     *
     * @param context context used to broadcast the event
     * @param keyCode keyCode which triggered this function
     * @param event keyEvent which trigged this function
     * @return {@code true} if this was handled
     */
    boolean handleGlobalKey(Context context, int keyCode, KeyEvent event) {
        if (mKeyMapping.size() > 0) {
            ComponentName component = mKeyMapping.get(keyCode);
            if (component != null) {
                Intent intent = new Intent(Intent.ACTION_GLOBAL_BUTTON)
                        .setComponent(component)
                        .putExtra(Intent.EXTRA_KEY_EVENT, event);
                context.sendBroadcastAsUser(intent, UserHandle.CURRENT, null);
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if the key will be handled globally.
     */
    boolean shouldHandleGlobalKey(int keyCode, KeyEvent event) {
        return mKeyMapping.get(keyCode) != null;
    }

    private void loadGlobalKeys(Context context) {
        XmlResourceParser parser = null;
        try {
            parser = context.getResources().getXml(com.android.internal.R.xml.global_keys);
            XmlUtils.beginDocument(parser, TAG_GLOBAL_KEYS);
            int version = parser.getAttributeIntValue(null, ATTR_VERSION, 0);
            if (GLOBAL_KEY_FILE_VERSION == version) {
                while (true) {
                    XmlUtils.nextElement(parser);
                    String element = parser.getName();
                    if (element == null) {
                        break;
                    }
                    if (TAG_KEY.equals(element)) {
                        String keyCodeName = parser.getAttributeValue(null, ATTR_KEY_CODE);
                        String componentName = parser.getAttributeValue(null, ATTR_COMPONENT);
                        int keyCode = KeyEvent.keyCodeFromString(keyCodeName);
                        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            mKeyMapping.put(keyCode, ComponentName.unflattenFromString(
                                    componentName));
                        }
                    }
                }
            }
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "global keys file not found", e);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "XML parser exception reading global keys file", e);
        } catch (IOException e) {
            Log.w(TAG, "I/O exception reading global keys file", e);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        final int numKeys = mKeyMapping.size();
        if (numKeys == 0) {
            pw.print(prefix); pw.println("mKeyMapping.size=0");
            return;
        }
        pw.print(prefix); pw.println("mKeyMapping={");
        for (int i = 0; i < numKeys; ++i) {
            pw.print("  ");
            pw.print(prefix);
            pw.print(KeyEvent.keyCodeToString(mKeyMapping.keyAt(i)));
            pw.print("=");
            pw.println(mKeyMapping.valueAt(i).flattenToString());
        }
        pw.print(prefix); pw.println("}");
    }
}
