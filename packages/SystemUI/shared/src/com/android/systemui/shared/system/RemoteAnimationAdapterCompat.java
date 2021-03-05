/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.shared.system;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.TransitionOldType;

import android.os.RemoteException;
import android.util.Log;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionInfo;

/**
 * @see RemoteAnimationAdapter
 */
public class RemoteAnimationAdapterCompat {

    private final RemoteAnimationAdapter mWrapped;
    private final RemoteTransitionCompat mRemoteTransition;

    public RemoteAnimationAdapterCompat(RemoteAnimationRunnerCompat runner, long duration,
            long statusBarTransitionDelay) {
        mWrapped = new RemoteAnimationAdapter(wrapRemoteAnimationRunner(runner), duration,
                statusBarTransitionDelay);
        mRemoteTransition = buildRemoteTransition(runner);
    }

    RemoteAnimationAdapter getWrapped() {
        return mWrapped;
    }

    /** Helper to just build a remote transition. Use this if the legacy adapter isn't needed. */
    public static RemoteTransitionCompat buildRemoteTransition(RemoteAnimationRunnerCompat runner) {
        return new RemoteTransitionCompat(wrapRemoteTransition(runner));
    }

    public RemoteTransitionCompat getRemoteTransition() {
        return mRemoteTransition;
    }

    private static IRemoteAnimationRunner.Stub wrapRemoteAnimationRunner(
            final RemoteAnimationRunnerCompat remoteAnimationAdapter) {
        return new IRemoteAnimationRunner.Stub() {
            @Override
            public void onAnimationStart(@TransitionOldType int transit,
                    RemoteAnimationTarget[] apps,
                    RemoteAnimationTarget[] wallpapers,
                    RemoteAnimationTarget[] nonApps,
                    final IRemoteAnimationFinishedCallback finishedCallback) {
                final RemoteAnimationTargetCompat[] appsCompat =
                        RemoteAnimationTargetCompat.wrap(apps);
                final RemoteAnimationTargetCompat[] wallpapersCompat =
                        RemoteAnimationTargetCompat.wrap(wallpapers);
                final RemoteAnimationTargetCompat[] nonAppsCompat =
                        RemoteAnimationTargetCompat.wrap(nonApps);
                final Runnable animationFinishedCallback = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            finishedCallback.onAnimationFinished();
                        } catch (RemoteException e) {
                            Log.e("ActivityOptionsCompat", "Failed to call app controlled animation"
                                    + " finished callback", e);
                        }
                    }
                };
                remoteAnimationAdapter.onAnimationStart(transit, appsCompat, wallpapersCompat,
                        nonAppsCompat, animationFinishedCallback);
            }

            @Override
            public void onAnimationCancelled() {
                remoteAnimationAdapter.onAnimationCancelled();
            }
        };
    }

    private static IRemoteTransition.Stub wrapRemoteTransition(
            final RemoteAnimationRunnerCompat remoteAnimationAdapter) {
        return new IRemoteTransition.Stub() {
            @Override
            public void startAnimation(TransitionInfo info, SurfaceControl.Transaction t,
                    IRemoteTransitionFinishedCallback finishCallback) {
                final RemoteAnimationTargetCompat[] appsCompat =
                        RemoteAnimationTargetCompat.wrap(info, false /* wallpapers */);
                final RemoteAnimationTargetCompat[] wallpapersCompat =
                        RemoteAnimationTargetCompat.wrap(info, true /* wallpapers */);
                // TODO(bc-unlock): Build wrapped object for non-apps target.
                final RemoteAnimationTargetCompat[] nonAppsCompat =
                        new RemoteAnimationTargetCompat[0];

                // TODO(b/177438007): Move this set-up logic into launcher's animation impl.
                boolean isReturnToHome = false;
                for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                    final TransitionInfo.Change change = info.getChanges().get(i);
                    if (change.getTaskInfo() != null
                            && change.getTaskInfo().getActivityType() == ACTIVITY_TYPE_HOME) {
                        isReturnToHome = change.getMode() == TRANSIT_OPEN
                                || change.getMode() == TRANSIT_TO_FRONT;
                        break;
                    }
                }

                if (isReturnToHome) {
                    // Need to "boost" the closing things since that's what launcher expects.
                    for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                        final TransitionInfo.Change change = info.getChanges().get(i);
                        final SurfaceControl leash = change.getLeash();
                        final int mode = info.getChanges().get(i).getMode();
                        // Only deal with independent layers
                        if (!TransitionInfo.isIndependent(change, info)) continue;
                        if (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK) {
                            t.setLayer(leash, info.getChanges().size() * 3 - i);
                        }
                    }
                    // Make wallpaper visible immediately since launcher apparently won't do this.
                    for (int i = wallpapersCompat.length - 1; i >= 0; --i) {
                        t.show(wallpapersCompat[i].leash.getSurfaceControl());
                        t.setAlpha(wallpapersCompat[i].leash.getSurfaceControl(), 1.f);
                    }
                }
                t.apply();

                final Runnable animationFinishedCallback = new Runnable() {
                    @Override
                    public void run() {
                        try {
                            finishCallback.onTransitionFinished(null /* wct */);
                        } catch (RemoteException e) {
                            Log.e("ActivityOptionsCompat", "Failed to call app controlled animation"
                                    + " finished callback", e);
                        }
                    }
                };
                // TODO(bc-unlcok): Pass correct transit type.
                remoteAnimationAdapter.onAnimationStart(
                        TRANSIT_OLD_NONE,
                        appsCompat, wallpapersCompat, nonAppsCompat,
                        animationFinishedCallback);
            }
        };
    }
}
