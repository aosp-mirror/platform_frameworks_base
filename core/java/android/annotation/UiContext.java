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

package android.annotation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Context;
import android.os.Bundle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.WindowManager;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Denotes a {@link Context} that can be used to create UI, meaning that it can provide a
 * {@link Display} via {@link Context#getDisplay} and can be used to obtain a {@link WindowManager},
 * {@link LayoutInflater} or {@link WallpaperManager} via {@link Context#getSystemService(String)}.
 * A {@link Context} which is marked as {@link UiContext} implies that the {@link Context} is also
 * a {@link DisplayContext}.
 * <p>
 * This kind of {@link Context} is usually an {@link Activity} or
 * created via {@link Context#createWindowContext(int, Bundle)}.
 * </p>
 * This is a marker annotation and has no specific attributes.
 *
 * @see Context#getDisplay()
 * @see Context#getSystemService(String)
 * @see Context#getSystemService(Class)
 * @see Context#createWindowContext(int, Bundle)
 * @see DisplayContext
 * @hide
 */
@Retention(SOURCE)
@Target({TYPE, METHOD, PARAMETER, FIELD})
public @interface UiContext {
}
