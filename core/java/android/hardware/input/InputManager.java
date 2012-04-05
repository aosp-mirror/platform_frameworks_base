/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.input;

import com.android.internal.util.XmlUtils;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.view.KeyCharacterMap;
import android.view.KeyCharacterMap.UnavailableException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Provides information about input devices and available key layouts.
 * <p>
 * Get an instance of this class by calling
 * {@link android.content.Context#getSystemService(java.lang.String)
 * Context.getSystemService()} with the argument
 * {@link android.content.Context#INPUT_SERVICE}.
 * </p>
 */
public final class InputManager {
    private static final String TAG = "InputManager";

    private final Context mContext;

    // Used to simulate a persistent data store.
    // TODO: Replace with the real thing.
    private static final HashMap<String, String> mFakeRegistry = new HashMap<String, String>();

    /**
     * Broadcast Action: Query available keyboard layouts.
     * <p>
     * The input manager service locates available keyboard layouts
     * by querying broadcast receivers that are registered for this action.
     * An application can offer additional keyboard layouts to the user
     * by declaring a suitable broadcast receiver in its manifest.
     * </p><p>
     * Here is an example broadcast receiver declaration that an application
     * might include in its AndroidManifest.xml to advertise keyboard layouts.
     * The meta-data specifies a resource that contains a description of each keyboard
     * layout that is provided by the application.
     * <pre><code>
     * &lt;receiver android:name=".InputDeviceReceiver">
     *     &lt;intent-filter>
     *         &lt;action android:name="android.hardware.input.action.QUERY_KEYBOARD_LAYOUTS" />
     *     &lt;/intent-filter>
     *     &lt;meta-data android:name="android.hardware.input.metadata.KEYBOARD_LAYOUTS"
     *             android:resource="@xml/keyboard_layouts" />
     * &lt;/receiver>
     * </code></pre>
     * </p><p>
     * In the above example, the <code>@xml/keyboard_layouts</code> resource refers to
     * an XML resource whose root element is <code>&lt;keyboard-layouts></code> that
     * contains zero or more <code>&lt;keyboard-layout></code> elements.
     * Each <code>&lt;keyboard-layout></code> element specifies the name, label, and location
     * of a key character map for a particular keyboard layout.
     * <pre></code>
     * &lt;?xml version="1.0" encoding="utf-8"?>
     * &lt;keyboard-layouts xmlns:android="http://schemas.android.com/apk/res/android">
     *     &lt;keyboard-layout android:name="keyboard_layout_english_us"
     *             android:label="@string/keyboard_layout_english_us_label"
     *             android:kcm="@raw/keyboard_layout_english_us" />
     * &lt;/keyboard-layouts>
     * </p><p>
     * The <code>android:name</code> attribute specifies an identifier by which
     * the keyboard layout will be known in the package.
     * The <code>android:label</code> attributes specifies a human-readable descriptive
     * label to describe the keyboard layout in the user interface, such as "English (US)".
     * The <code>android:kcm</code> attribute refers to a
     * <a href="http://source.android.com/tech/input/key-character-map-files.html">
     * key character map</a> resource that defines the keyboard layout.
     * </p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_QUERY_KEYBOARD_LAYOUTS =
            "android.hardware.input.action.QUERY_KEYBOARD_LAYOUTS";

    /**
     * Metadata Key: Keyboard layout metadata associated with
     * {@link #ACTION_QUERY_KEYBOARD_LAYOUTS}.
     * <p>
     * Specifies the resource id of a XML resource that describes the keyboard
     * layouts that are provided by the application.
     * </p>
     */
    public static final String META_DATA_KEYBOARD_LAYOUTS =
            "android.hardware.input.metadata.KEYBOARD_LAYOUTS";

    /** @hide */
    public InputManager(Context context) {
        mContext = context;
    }

    /**
     * Gets information about all supported keyboard layouts.
     * <p>
     * The input manager consults the built-in keyboard layouts as well
     * as all keyboard layouts advertised by applications using a
     * {@link #ACTION_QUERY_KEYBOARD_LAYOUTS} broadcast receiver.
     * </p>
     *
     * @return A list of all supported keyboard layouts.
     * @hide
     */
    public List<KeyboardLayout> getKeyboardLayouts() {
        ArrayList<KeyboardLayout> list = new ArrayList<KeyboardLayout>();

        final PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(ACTION_QUERY_KEYBOARD_LAYOUTS);
        for (ResolveInfo resolveInfo : pm.queryBroadcastReceivers(intent,
                PackageManager.GET_META_DATA)) {
            loadKeyboardLayouts(pm, resolveInfo.activityInfo, list, null);
        }
        return list;
    }

    /**
     * Gets the keyboard layout with the specified descriptor.
     *
     * @param keyboardLayoutDescriptor The keyboard layout descriptor, as returned by
     * {@link KeyboardLayout#getDescriptor()}.
     * @return The keyboard layout, or null if it could not be loaded.
     *
     * @hide
     */
    public KeyboardLayout getKeyboardLayout(String keyboardLayoutDescriptor) {
        if (keyboardLayoutDescriptor == null) {
            throw new IllegalArgumentException("keyboardLayoutDescriptor must not be null");
        }

        KeyboardLayoutDescriptor d = parseKeyboardLayoutDescriptor(keyboardLayoutDescriptor);
        if (d == null) {
            return null;
        }

        final PackageManager pm = mContext.getPackageManager();
        try {
            ActivityInfo receiver = pm.getReceiverInfo(
                    new ComponentName(d.packageName, d.receiverName),
                    PackageManager.GET_META_DATA);
            return loadKeyboardLayouts(pm, receiver, null, d.keyboardLayoutName);
        } catch (NameNotFoundException ex) {
            Log.w(TAG, "Could not load keyboard layout '" + d.keyboardLayoutName
                    + "' from receiver " + d.packageName + "/" + d.receiverName, ex);
            return null;
        }
    }

    /**
     * Gets the keyboard layout descriptor for the specified input device.
     *
     * @param inputDeviceDescriptor The input device descriptor.
     * @return The keyboard layout descriptor, or null if unknown or if the default
     * keyboard layout will be used.
     *
     * @hide
     */
    public String getInputDeviceKeyboardLayoutDescriptor(String inputDeviceDescriptor) {
        if (inputDeviceDescriptor == null) {
            throw new IllegalArgumentException("inputDeviceDescriptor must not be null");
        }

        return mFakeRegistry.get(inputDeviceDescriptor);
    }

    /**
     * Sets the keyboard layout descriptor for the specified input device.
     * <p>
     * This method may have the side-effect of causing the input device in question
     * to be reconfigured.
     * </p>
     *
     * @param inputDeviceDescriptor The input device descriptor.
     * @param keyboardLayoutDescriptor The keyboard layout descriptor, or null to remove
     * the mapping so that the default keyboard layout will be used for the input device.
     *
     * @hide
     */
    public void setInputDeviceKeyboardLayoutDescriptor(String inputDeviceDescriptor,
            String keyboardLayoutDescriptor) {
        if (inputDeviceDescriptor == null) {
            throw new IllegalArgumentException("inputDeviceDescriptor must not be null");
        }

        mFakeRegistry.put(inputDeviceDescriptor, keyboardLayoutDescriptor);
    }

    private KeyboardLayout loadKeyboardLayouts(
            PackageManager pm, ActivityInfo receiver,
            List<KeyboardLayout> list, String keyboardName) {
        Bundle metaData = receiver.metaData;
        if (metaData == null) {
            return null;
        }

        int configResId = metaData.getInt(META_DATA_KEYBOARD_LAYOUTS);
        if (configResId == 0) {
            Log.w(TAG, "Missing meta-data '" + META_DATA_KEYBOARD_LAYOUTS + "' on receiver "
                    + receiver.packageName + "/" + receiver.name);
            return null;
        }

        try {
            Resources resources = pm.getResourcesForApplication(receiver.applicationInfo);
            XmlResourceParser parser = resources.getXml(configResId);
            try {
                XmlUtils.beginDocument(parser, "keyboard-layouts");

                for (;;) {
                    XmlUtils.nextElement(parser);
                    String element = parser.getName();
                    if (element == null) {
                        break;
                    }
                    if (element.equals("keyboard-layout")) {
                        TypedArray a = resources.obtainAttributes(
                                parser, com.android.internal.R.styleable.KeyboardLayout);
                        try {
                            String name = a.getString(
                                    com.android.internal.R.styleable.KeyboardLayout_name);
                            String label = a.getString(
                                    com.android.internal.R.styleable.KeyboardLayout_label);
                            int kcmResId = a.getResourceId(
                                     com.android.internal.R.styleable.KeyboardLayout_kcm, 0);
                            if (name == null || label == null || kcmResId == 0) {
                                Log.w(TAG, "Missing required 'name', 'label' or 'kcm' "
                                        + "attributes in keyboard layout "
                                        + "resource from receiver "
                                        + receiver.packageName + "/" + receiver.name);
                            } else {
                                String descriptor = makeKeyboardLayoutDescriptor(
                                        receiver.packageName, receiver.name, name);
                                KeyboardLayout c = new KeyboardLayout(
                                        descriptor, label, kcmResId);
                                if (keyboardName != null && name.equals(keyboardName)) {
                                    return c;
                                }
                                if (list != null) {
                                    list.add(c);
                                }
                            }
                        } finally {
                            a.recycle();
                        }
                    } else {
                        Log.w(TAG, "Skipping unrecognized element '" + element
                                + "' in keyboard layout resource from receiver "
                                + receiver.packageName + "/" + receiver.name);
                    }
                }
            } finally {
                parser.close();
            }
        } catch (Exception ex) {
            Log.w(TAG, "Could not load keyboard layout resource from receiver "
                    + receiver.packageName + "/" + receiver.name, ex);
            return null;
        }
        if (keyboardName != null) {
            Log.w(TAG, "Could not load keyboard layout '" + keyboardName
                    + "' from receiver " + receiver.packageName + "/" + receiver.name
                    + " because it was not declared in the keyboard layout resource.");
        }
        return null;
    }

    private static String makeKeyboardLayoutDescriptor(String packageName,
            String receiverName, String keyboardName) {
        return packageName + "/" + receiverName + "/" + keyboardName;
    }

    private static KeyboardLayoutDescriptor parseKeyboardLayoutDescriptor(String descriptor) {
        int pos = descriptor.indexOf('/');
        if (pos < 0 || pos + 1 == descriptor.length()) {
            return null;
        }
        int pos2 = descriptor.indexOf('/', pos + 1);
        if (pos2 < pos + 2 || pos2 + 1 == descriptor.length()) {
            return null;
        }

        KeyboardLayoutDescriptor result = new KeyboardLayoutDescriptor();
        result.packageName = descriptor.substring(0, pos);
        result.receiverName = descriptor.substring(pos + 1, pos2);
        result.keyboardLayoutName = descriptor.substring(pos2 + 1);
        return result;
    }

    /**
     * Describes a keyboard layout.
     *
     * @hide
     */
    public static final class KeyboardLayout implements Parcelable,
            Comparable<KeyboardLayout> {
        private final String mDescriptor;
        private final String mLabel;
        private final int mKeyCharacterMapResId;

        private KeyCharacterMap mKeyCharacterMap;

        public static final Parcelable.Creator<KeyboardLayout> CREATOR =
                new Parcelable.Creator<KeyboardLayout>() {
            public KeyboardLayout createFromParcel(Parcel source) {
                return new KeyboardLayout(source);
            }
            public KeyboardLayout[] newArray(int size) {
                return new KeyboardLayout[size];
            }
        };

        private KeyboardLayout(String descriptor,
                String label, int keyCharacterMapResId) {
            mDescriptor = descriptor;
            mLabel = label;
            mKeyCharacterMapResId = keyCharacterMapResId;
        }

        private KeyboardLayout(Parcel source) {
            mDescriptor = source.readString();
            mLabel = source.readString();
            mKeyCharacterMapResId = source.readInt();
        }

        /**
         * Gets the keyboard layout descriptor, which can be used to retrieve
         * the keyboard layout again later using
         * {@link InputManager#getKeyboardLayout(String)}.
         *
         * @return The keyboard layout descriptor.
         */
        public String getDescriptor() {
            return mDescriptor;
        }

        /**
         * Gets the keyboard layout descriptive label to show in the user interface.
         * @return The keyboard layout descriptive label.
         */
        public String getLabel() {
            return mLabel;
        }

        /**
         * Loads the key character map associated with the keyboard layout.
         *
         * @param pm The package manager.
         * @return The key character map, or null if it could not be loaded for any reason.
         */
        public KeyCharacterMap loadKeyCharacterMap(PackageManager pm) {
            if (pm == null) {
                throw new IllegalArgumentException("pm must not be null");
            }

            if (mKeyCharacterMap == null) {
                KeyboardLayoutDescriptor d = parseKeyboardLayoutDescriptor(mDescriptor);
                if (d == null) {
                    Log.e(TAG, "Could not load key character map '" + mDescriptor
                            + "' because the descriptor could not be parsed.");
                    return null;
                }

                CharSequence cs = pm.getText(d.packageName, mKeyCharacterMapResId, null);
                if (cs == null) {
                    Log.e(TAG, "Could not load key character map '" + mDescriptor
                            + "' because its associated resource could not be loaded.");
                    return null;
                }

                try {
                    mKeyCharacterMap = KeyCharacterMap.load(cs);
                } catch (UnavailableException ex) {
                    Log.e(TAG, "Could not load key character map '" + mDescriptor
                            + "' due to an error while parsing.", ex);
                    return null;
                }
            }
            return mKeyCharacterMap;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(mDescriptor);
            dest.writeString(mLabel);
            dest.writeInt(mKeyCharacterMapResId);
        }

        @Override
        public int compareTo(KeyboardLayout another) {
            return mLabel.compareToIgnoreCase(another.mLabel);
        }

        @Override
        public String toString() {
            return mLabel;
        }
    }

    private static final class KeyboardLayoutDescriptor {
        public String packageName;
        public String receiverName;
        public String keyboardLayoutName;
    }
}
