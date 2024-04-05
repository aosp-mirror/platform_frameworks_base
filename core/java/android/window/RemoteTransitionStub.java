/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.window;

import android.os.IBinder;
import android.os.RemoteException;
import android.view.SurfaceControl;

/**
 * Utility base implementation of {@link IRemoteTransition} that users can extend to avoid stubbing.
 *
 * @hide
 */
public abstract class RemoteTransitionStub extends IRemoteTransition.Stub {
    @Override
    public void mergeAnimation(IBinder transition, TransitionInfo info,
            SurfaceControl.Transaction t, IBinder mergeTarget,
            IRemoteTransitionFinishedCallback finishCallback) throws RemoteException {}

    @Override
    public void onTransitionConsumed(IBinder transition, boolean aborted)
            throws RemoteException {}
}
