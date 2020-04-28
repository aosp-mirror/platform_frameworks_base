/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.preload.classdataretrieval.hprof;

import com.android.ddmlib.Client;
import com.android.ddmlib.ClientData.IHprofDumpHandler;

import java.util.ArrayList;
import java.util.List;

public class GeneralHprofDumpHandler implements IHprofDumpHandler {

    private List<IHprofDumpHandler> handlers = new ArrayList<>();

    public void addHandler(IHprofDumpHandler h) {
      synchronized (handlers) {
        handlers.add(h);
      }
    }

    public void removeHandler(IHprofDumpHandler h) {
      synchronized (handlers) {
        handlers.remove(h);
      }
    }

    private List<IHprofDumpHandler> getIterationList() {
      synchronized (handlers) {
        return new ArrayList<>(handlers);
      }
    }

    @Override
    public void onEndFailure(Client arg0, String arg1) {
      List<IHprofDumpHandler> iterList = getIterationList();
      for (IHprofDumpHandler h : iterList) {
        h.onEndFailure(arg0, arg1);
      }
    }

    @Override
    public void onSuccess(String arg0, Client arg1) {
      List<IHprofDumpHandler> iterList = getIterationList();
      for (IHprofDumpHandler h : iterList) {
        h.onSuccess(arg0, arg1);
      }
    }

    @Override
    public void onSuccess(byte[] arg0, Client arg1) {
      List<IHprofDumpHandler> iterList = getIterationList();
      for (IHprofDumpHandler h : iterList) {
        h.onSuccess(arg0, arg1);
      }
    }
  }