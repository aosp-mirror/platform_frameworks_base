/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.os.strictmode;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;

import java.util.Objects;

/**
 * Violation raised when your app launches an {@link Intent} which originated
 * from outside your app.
 * <p>
 * Violations may indicate security vulnerabilities in the design of your app,
 * where a malicious app could trick you into granting {@link Uri} permissions
 * or launching unexported components. Here are some typical design patterns
 * that can be used to safely resolve these violations:
 * <ul>
 * <li>The ideal approach is to migrate to using a {@link PendingIntent}, which
 * ensures that your launch is performed using the identity of the original
 * creator, completely avoiding the security issues described above.
 * <li>If using a {@link PendingIntent} isn't feasible, an alternative approach
 * is to create a brand new {@link Intent} and carefully copy only specific
 * values from the original {@link Intent} after careful validation.
 * </ul>
 * <p>
 * Note that this <em>may</em> detect false-positives if your app sends itself
 * an {@link Intent} which is first routed through the OS, such as using
 * {@link Intent#createChooser}. In these cases, careful inspection is required
 * to determine if the return point into your app is appropriately protected
 * with a signature permission or marked as unexported. If the return point is
 * not protected, your app is likely vulnerable to malicious apps.
 */
public final class UnsafeIntentLaunchViolation extends Violation {
    private transient Intent mIntent;

    public UnsafeIntentLaunchViolation(@NonNull Intent intent) {
        super("Launch of unsafe intent: " + intent);
        mIntent = Objects.requireNonNull(intent);
    }

    /**
     * Return the {@link Intent} which caused this violation to be raised. Note
     * that this value is not available if this violation has been serialized
     * since intents cannot be serialized.
     */
    @SuppressWarnings("IntentBuilderName")
    public @Nullable Intent getIntent() {
        return mIntent;
    }
}
