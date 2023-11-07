/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.TaskInfo;
import android.content.pm.ActivityInfo;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.view.InsetsState;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowManager;

/**
 * Information you can retrieve about a starting window of a particular task that is currently
 * start in the system.
 * @hide
 */
public final class StartingWindowInfo implements Parcelable {
    /**
     * Prefer nothing or not care the type of starting window.
     * @hide
     */
    public static final int STARTING_WINDOW_TYPE_NONE = 0;
    /**
     * Prefer splash screen starting window.
     * @hide
     */
    public static final int STARTING_WINDOW_TYPE_SPLASH_SCREEN = 1;
    /**
     * Prefer snapshot starting window.
     * @hide
     */
    public static final int STARTING_WINDOW_TYPE_SNAPSHOT = 2;
    /**
     * Prefer solid color splash screen starting window.
     * @hide
     */
    public static final int STARTING_WINDOW_TYPE_SOLID_COLOR_SPLASH_SCREEN = 3;

    /** @hide **/
    public static final int STARTING_WINDOW_TYPE_LEGACY_SPLASH_SCREEN = 4;

    public static final int STARTING_WINDOW_TYPE_WINDOWLESS = 5;

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = "STARTING_WINDOW_TYPE_", value = {
            STARTING_WINDOW_TYPE_NONE,
            STARTING_WINDOW_TYPE_SPLASH_SCREEN,
            STARTING_WINDOW_TYPE_SNAPSHOT,
            STARTING_WINDOW_TYPE_SOLID_COLOR_SPLASH_SCREEN,
            STARTING_WINDOW_TYPE_LEGACY_SPLASH_SCREEN,
            STARTING_WINDOW_TYPE_WINDOWLESS
    })
    public @interface StartingWindowType {}

    /**
     * The {@link TaskInfo} from this task.
     *  @hide
     */
    @NonNull
    public ActivityManager.RunningTaskInfo taskInfo;

    /**
     * The {@link ActivityInfo} of the target activity which to create the starting window.
     * It can be null if the info is the same as the top in task info.
     * @hide
     */
    @Nullable
    public ActivityInfo targetActivityInfo;

    /**
     * InsetsState of TopFullscreenOpaqueWindow
     * @hide
     */
    @Nullable
    public InsetsState topOpaqueWindowInsetsState;

    /**
     * LayoutParams of TopFullscreenOpaqueWindow
     * @hide
     */
    @Nullable
    public WindowManager.LayoutParams topOpaqueWindowLayoutParams;

    /**
     * LayoutParams of MainWindow
     * @hide
     */
    @Nullable
    public WindowManager.LayoutParams mainWindowLayoutParams;

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = "TYPE_PARAMETER_", value = {
            TYPE_PARAMETER_NEW_TASK,
            TYPE_PARAMETER_TASK_SWITCH,
            TYPE_PARAMETER_PROCESS_RUNNING,
            TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT,
            TYPE_PARAMETER_ACTIVITY_CREATED,
            TYPE_PARAMETER_USE_SOLID_COLOR_SPLASH_SCREEN,
            TYPE_PARAMETER_ALLOW_HANDLE_SOLID_COLOR_SCREEN,
            TYPE_PARAMETER_WINDOWLESS,
            TYPE_PARAMETER_LEGACY_SPLASH_SCREEN
    })
    public @interface StartingTypeParams {}

    /**
     * The parameters of the starting window...
     * @hide
     */
    public static final int TYPE_PARAMETER_NEW_TASK = 0x00000001;
    /** @hide */
    public static final int TYPE_PARAMETER_TASK_SWITCH = 0x00000002;
    /** @hide */
    public static final int TYPE_PARAMETER_PROCESS_RUNNING = 0x00000004;
    /** @hide */
    public static final int TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT = 0x00000008;
    /** @hide */
    public static final int TYPE_PARAMETER_ACTIVITY_CREATED = 0x00000010;
    /** @hide */
    public static final int TYPE_PARAMETER_USE_SOLID_COLOR_SPLASH_SCREEN = 0x00000020;
    /**
     * The parameter which indicates if the activity has finished drawing.
     * @hide
     */
    public static final int TYPE_PARAMETER_ACTIVITY_DRAWN = 0x00000040;
    /**
     * Application will receive the
     * {@link
     * android.window.SplashScreen.OnExitAnimationListener#onSplashScreenExit(SplashScreenView)}
     * callback, even when the splash screen only shows a solid color.
     *
     * @hide
     */
    public static final int TYPE_PARAMETER_ALLOW_HANDLE_SOLID_COLOR_SCREEN = 0x00000080;

    /**
     * Windowless surface
     */
    public static final int TYPE_PARAMETER_WINDOWLESS = 0x00000100;

    /**
     * Application has set Window_windowSplashScreenBehavior to
     * SPLASH_SCREEN_BEHAVIOR_ICON_PREFERRED.
     * @hide
     */
    public static final int TYPE_PARAMETER_APP_PREFERS_ICON = 0x00000200;

    /**
     * Application is allowed to use the legacy splash screen
     * @hide
     */
    public static final int TYPE_PARAMETER_LEGACY_SPLASH_SCREEN = 0x80000000;

    /**
     * The parameters which effect the starting window type.
     * @see android.window.StartingWindowInfo.StartingTypeParams
     * @hide
     */
    public int startingWindowTypeParameter;

    /**
     * Specifies a theme for the splash screen.
     * @hide
     */
    public int splashScreenThemeResId;

    /**
     * Is keyguard occluded on default display.
     * @hide
     */
    public boolean isKeyguardOccluded = false;

    /**
     * TaskSnapshot.
     * @hide
     */
    public TaskSnapshot taskSnapshot;

    @InsetsType public int requestedVisibleTypes = WindowInsets.Type.defaultVisible();

    /**
     * App token where the starting window should add to.
     */
    public IBinder appToken;

    public IWindowlessStartingSurfaceCallback windowlessStartingSurfaceCallback;

    /**
     * The root surface where windowless surface should attach on.
     */
    public SurfaceControl rootSurface;

    /**
     * Notify windowless surface is created.
     * @param addedSurface Created surface.
     */
    public void notifyAddComplete(SurfaceControl addedSurface) {
        if (windowlessStartingSurfaceCallback != null) {
            try {
                windowlessStartingSurfaceCallback.onSurfaceAdded(addedSurface);
            } catch (RemoteException e) {
                //
            }
        }
    }

    public StartingWindowInfo() {

    }

    private StartingWindowInfo(@NonNull Parcel source) {
        readFromParcel(source);
    }

    /**
     * Return whether the application allow to handle the solid color style splash screen.
     */
    public boolean allowHandleSolidColorSplashScreen() {
        return (startingWindowTypeParameter & TYPE_PARAMETER_ALLOW_HANDLE_SOLID_COLOR_SCREEN) != 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(taskInfo, flags);
        dest.writeTypedObject(targetActivityInfo, flags);
        dest.writeInt(startingWindowTypeParameter);
        dest.writeTypedObject(topOpaqueWindowInsetsState, flags);
        dest.writeTypedObject(topOpaqueWindowLayoutParams, flags);
        dest.writeTypedObject(mainWindowLayoutParams, flags);
        dest.writeInt(splashScreenThemeResId);
        dest.writeBoolean(isKeyguardOccluded);
        dest.writeTypedObject(taskSnapshot, flags);
        dest.writeInt(requestedVisibleTypes);
        dest.writeStrongBinder(appToken);
        dest.writeStrongInterface(windowlessStartingSurfaceCallback);
        dest.writeTypedObject(rootSurface, flags);
    }

    void readFromParcel(@NonNull Parcel source) {
        taskInfo = source.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);
        targetActivityInfo = source.readTypedObject(ActivityInfo.CREATOR);
        startingWindowTypeParameter = source.readInt();
        topOpaqueWindowInsetsState = source.readTypedObject(InsetsState.CREATOR);
        topOpaqueWindowLayoutParams = source.readTypedObject(
                WindowManager.LayoutParams.CREATOR);
        mainWindowLayoutParams = source.readTypedObject(WindowManager.LayoutParams.CREATOR);
        splashScreenThemeResId = source.readInt();
        isKeyguardOccluded = source.readBoolean();
        taskSnapshot = source.readTypedObject(TaskSnapshot.CREATOR);
        requestedVisibleTypes = source.readInt();
        appToken = source.readStrongBinder();
        windowlessStartingSurfaceCallback = IWindowlessStartingSurfaceCallback.Stub
                .asInterface(source.readStrongBinder());
        rootSurface = source.readTypedObject(SurfaceControl.CREATOR);
    }

    @Override
    public String toString() {
        return "StartingWindowInfo{taskId=" + taskInfo.taskId
                + " targetActivityInfo=" + targetActivityInfo
                + " displayId=" + taskInfo.displayId
                + " topActivityType=" + taskInfo.topActivityType
                + " preferredStartingWindowType="
                + Integer.toHexString(startingWindowTypeParameter)
                + " insetsState=" + topOpaqueWindowInsetsState
                + " topWindowLayoutParams=" + topOpaqueWindowLayoutParams
                + " mainWindowLayoutParams=" + mainWindowLayoutParams
                + " splashScreenThemeResId " + Integer.toHexString(splashScreenThemeResId);
    }

    public static final @android.annotation.NonNull Creator<StartingWindowInfo> CREATOR =
            new Creator<StartingWindowInfo>() {
                public StartingWindowInfo createFromParcel(@NonNull Parcel source) {
                    return new StartingWindowInfo(source);
                }
                public StartingWindowInfo[] newArray(int size) {
                    return new StartingWindowInfo[size];
                }
            };
}
