/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.textservice;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.textservice.SpellCheckerInfo;
import android.view.textservice.SpellCheckerSubtype;


/**
 * Delegate used to provide new implementation of a select few methods of
 * {@link ITextServicesManager$Stub}
 *
 * Through the layoutlib_create tool, the original  methods of Stub have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class ITextServicesManager_Stub_Delegate {

    @LayoutlibDelegate
    public static ITextServicesManager asInterface(IBinder obj) {
        // ignore the obj and return a fake interface implementation
        return new FakeTextServicesManager();
    }

    private static class FakeTextServicesManager implements ITextServicesManager {

        @Override
        public void finishSpellCheckerService(ISpellCheckerSessionListener arg0)
                throws RemoteException {
            // TODO Auto-generated method stub

        }

        @Override
        public SpellCheckerInfo getCurrentSpellChecker(String arg0) throws RemoteException {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public SpellCheckerSubtype getCurrentSpellCheckerSubtype(String arg0, boolean arg1)
                throws RemoteException {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public SpellCheckerInfo[] getEnabledSpellCheckers() throws RemoteException {
            // TODO Auto-generated method stub
            return null;
        }

        @Override
        public void getSpellCheckerService(String arg0, String arg1,
                ITextServicesSessionListener arg2, ISpellCheckerSessionListener arg3, Bundle arg4)
                throws RemoteException {
            // TODO Auto-generated method stub

        }

        @Override
        public boolean isSpellCheckerEnabled() throws RemoteException {
            // TODO Auto-generated method stub
            return false;
        }

        @Override
        public void setCurrentSpellChecker(String arg0, String arg1) throws RemoteException {
            // TODO Auto-generated method stub

        }

        @Override
        public void setCurrentSpellCheckerSubtype(String arg0, int arg1) throws RemoteException {
            // TODO Auto-generated method stub

        }

        @Override
        public void setSpellCheckerEnabled(boolean arg0) throws RemoteException {
            // TODO Auto-generated method stub

        }

        @Override
        public IBinder asBinder() {
            // TODO Auto-generated method stub
            return null;
        }

    }
 }
