/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.app;

import com.android.ide.common.rendering.api.LayoutlibCallback;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.content.Context;
import android.os.Bundle;

/**
 * Delegate used to provide new implementation of a select few methods of {@link Fragment}
 *
 * Through the layoutlib_create tool, the original  methods of Fragment have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * The methods being re-implemented are the ones responsible for instantiating Fragment objects.
 * Because the classes of these objects are found in the project, these methods need access to
 * {@link LayoutlibCallback} object. They are however static methods, so the callback is set
 * before the inflation through {@link #setLayoutlibCallback(LayoutlibCallback)}.
 */
public class Fragment_Delegate {

    private static LayoutlibCallback sLayoutlibCallback;

    /**
     * Sets the current {@link LayoutlibCallback} to be used to instantiate classes coming
     * from the project being rendered.
     */
    public static void setLayoutlibCallback(LayoutlibCallback layoutlibCallback) {
        sLayoutlibCallback = layoutlibCallback;
    }

    /**
     * Like {@link #instantiate(Context, String, Bundle)} but with a null
     * argument Bundle.
     */
    @LayoutlibDelegate
    /*package*/ static Fragment instantiate(Context context, String fname) {
        return instantiate(context, fname, null);
    }

    /**
     * Create a new instance of a Fragment with the given class name.  This is
     * the same as calling its empty constructor.
     *
     * @param context The calling context being used to instantiate the fragment.
     * This is currently just used to get its ClassLoader.
     * @param fname The class name of the fragment to instantiate.
     * @param args Bundle of arguments to supply to the fragment, which it
     * can retrieve with {@link Fragment#getArguments()}.  May be null.
     * @return Returns a new fragment instance.
     * @throws Fragment.InstantiationException If there is a failure in instantiating
     * the given fragment class.  This is a runtime exception; it is not
     * normally expected to happen.
     */
    @LayoutlibDelegate
    /*package*/ static Fragment instantiate(Context context, String fname, Bundle args) {
        try {
            if (sLayoutlibCallback != null) {
                Fragment f = (Fragment) sLayoutlibCallback.loadView(fname,
                        new Class[0], new Object[0]);

                if (args != null) {
                    args.setClassLoader(f.getClass().getClassLoader());
                    f.mArguments = args;
                }
                return f;
            }

            return null;
        } catch (ClassNotFoundException e) {
            throw new Fragment.InstantiationException("Unable to instantiate fragment " + fname
                    + ": make sure class name exists, is public, and has an"
                    + " empty constructor that is public", e);
        } catch (java.lang.InstantiationException e) {
            throw new Fragment.InstantiationException("Unable to instantiate fragment " + fname
                    + ": make sure class name exists, is public, and has an"
                    + " empty constructor that is public", e);
        } catch (IllegalAccessException e) {
            throw new Fragment.InstantiationException("Unable to instantiate fragment " + fname
                    + ": make sure class name exists, is public, and has an"
                    + " empty constructor that is public", e);
        } catch (Exception e) {
            throw new Fragment.InstantiationException("Unable to instantiate fragment " + fname
                    + ": make sure class name exists, is public, and has an"
                    + " empty constructor that is public", e);
        }
    }
}
