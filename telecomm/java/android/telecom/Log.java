/*
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.telecom;

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.telecom.Logging.EventManager;
import android.telecom.Logging.Session;
import android.telecom.Logging.SessionManager;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;

import java.util.Arrays;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Manages logging for the entire module.
 *
 * @hide
 */
public class Log {

    private static final long EXTENDED_LOGGING_DURATION_MILLIS = 60000 * 30; // 30 minutes

    private static final int EVENTS_TO_CACHE = 10;
    private static final int EVENTS_TO_CACHE_DEBUG = 20;

    /**
     * When generating a bug report, include the last X dialable digits when logging phone numbers.
     */
    private static final int NUM_DIALABLE_DIGITS_TO_LOG = Build.IS_USER ? 0 : 2;

    // Generic tag for all Telecom logging
    @VisibleForTesting
    public static String TAG = "TelecomFramework";
    public static boolean DEBUG = isLoggable(android.util.Log.DEBUG);
    public static boolean INFO = isLoggable(android.util.Log.INFO);
    public static boolean VERBOSE = isLoggable(android.util.Log.VERBOSE);
    public static boolean WARN = isLoggable(android.util.Log.WARN);
    public static boolean ERROR = isLoggable(android.util.Log.ERROR);

    private static final boolean FORCE_LOGGING = false; /* STOP SHIP if true */
    private static final boolean USER_BUILD = Build.IS_USER;

    // Used to synchronize singleton logging lazy initialization
    private static final Object sSingletonSync = new Object();
    private static EventManager sEventManager;
    private static SessionManager sSessionManager;

    /**
     * Tracks whether user-activated extended logging is enabled.
     */
    private static boolean sIsUserExtendedLoggingEnabled = false;

    /**
     * The time when user-activated extended logging should be ended.  Used to determine when
     * extended logging should automatically be disabled.
     */
    private static long sUserExtendedLoggingStopTime = 0;

    private Log() {
    }

    public static void d(String prefix, String format, Object... args) {
        if (sIsUserExtendedLoggingEnabled) {
            maybeDisableLogging();
            android.util.Slog.i(TAG, buildMessage(prefix, format, args));
        } else if (DEBUG) {
            android.util.Slog.d(TAG, buildMessage(prefix, format, args));
        }
    }

    public static void d(Object objectPrefix, String format, Object... args) {
        if (sIsUserExtendedLoggingEnabled) {
            maybeDisableLogging();
            android.util.Slog.i(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        } else if (DEBUG) {
            android.util.Slog.d(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        }
    }

    @UnsupportedAppUsage
    public static void i(String prefix, String format, Object... args) {
        if (INFO) {
            android.util.Slog.i(TAG, buildMessage(prefix, format, args));
        }
    }

    public static void i(Object objectPrefix, String format, Object... args) {
        if (INFO) {
            android.util.Slog.i(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        }
    }

    public static void v(String prefix, String format, Object... args) {
        if (sIsUserExtendedLoggingEnabled) {
            maybeDisableLogging();
            android.util.Slog.i(TAG, buildMessage(prefix, format, args));
        } else if (VERBOSE) {
            android.util.Slog.v(TAG, buildMessage(prefix, format, args));
        }
    }

    public static void v(Object objectPrefix, String format, Object... args) {
        if (sIsUserExtendedLoggingEnabled) {
            maybeDisableLogging();
            android.util.Slog.i(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        } else if (VERBOSE) {
            android.util.Slog.v(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        }
    }

    @UnsupportedAppUsage
    public static void w(String prefix, String format, Object... args) {
        if (WARN) {
            android.util.Slog.w(TAG, buildMessage(prefix, format, args));
        }
    }

    public static void w(Object objectPrefix, String format, Object... args) {
        if (WARN) {
            android.util.Slog.w(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args));
        }
    }

    public static void e(String prefix, Throwable tr, String format, Object... args) {
        if (ERROR) {
            android.util.Slog.e(TAG, buildMessage(prefix, format, args), tr);
        }
    }

    public static void e(Object objectPrefix, Throwable tr, String format, Object... args) {
        if (ERROR) {
            android.util.Slog.e(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args),
                    tr);
        }
    }

    public static void wtf(String prefix, Throwable tr, String format, Object... args) {
        android.util.Slog.wtf(TAG, buildMessage(prefix, format, args), tr);
    }

    public static void wtf(Object objectPrefix, Throwable tr, String format, Object... args) {
        android.util.Slog.wtf(TAG, buildMessage(getPrefixFromObject(objectPrefix), format, args),
                tr);
    }

    public static void wtf(String prefix, String format, Object... args) {
        String msg = buildMessage(prefix, format, args);
        android.util.Slog.wtf(TAG, msg, new IllegalStateException(msg));
    }

    public static void wtf(Object objectPrefix, String format, Object... args) {
        String msg = buildMessage(getPrefixFromObject(objectPrefix), format, args);
        android.util.Slog.wtf(TAG, msg, new IllegalStateException(msg));
    }

    /**
     * The ease of use methods below only act mostly as proxies to the Session and Event Loggers.
     * They also control the lazy loaders of the singleton instances, which will never be loaded if
     * the proxy methods aren't used.
     *
     * Please see each method's documentation inside of their respective implementations in the
     * loggers.
     */

    public static void setSessionContext(Context context) {
        getSessionManager().setContext(context);
    }

    public static void startSession(String shortMethodName) {
        getSessionManager().startSession(shortMethodName, null);
    }

    public static void startSession(Session.Info info, String shortMethodName) {
        getSessionManager().startSession(info, shortMethodName, null);
    }

    public static void startSession(String shortMethodName, String callerIdentification) {
        getSessionManager().startSession(shortMethodName, callerIdentification);
    }

    public static void startSession(Session.Info info, String shortMethodName,
            String callerIdentification) {
        getSessionManager().startSession(info, shortMethodName, callerIdentification);
    }

    public static Session createSubsession() {
        return getSessionManager().createSubsession();
    }

    public static Session.Info getExternalSession() {
        return getSessionManager().getExternalSession();
    }

    /**
     * Retrieves external session information, providing a context for the recipient of the session
     * info where the external session came from.
     * @param ownerInfo The external owner info.
     * @return New {@link Session.Info} instance with owner info set.
     */
    public static Session.Info getExternalSession(@NonNull String ownerInfo) {
        return getSessionManager().getExternalSession(ownerInfo);
    }

    public static void cancelSubsession(Session subsession) {
        getSessionManager().cancelSubsession(subsession);
    }

    public static void continueSession(Session subsession, String shortMethodName) {
        getSessionManager().continueSession(subsession, shortMethodName);
    }

    public static void endSession() {
        getSessionManager().endSession();
    }

    public static void registerSessionListener(SessionManager.ISessionListener l) {
        getSessionManager().registerSessionListener(l);
    }

    public static String getSessionId() {
        // If the Session logger has not been initialized, then there have been no sessions logged.
        // Don't load it now!
        synchronized (sSingletonSync) {
            if (sSessionManager != null) {
                return getSessionManager().getSessionId();
            } else {
                return "";
            }
        }
    }

    public static void addEvent(EventManager.Loggable recordEntry, String event) {
        getEventManager().event(recordEntry, event, null);
    }

    public static void addEvent(EventManager.Loggable recordEntry, String event, Object data) {
        getEventManager().event(recordEntry, event, data);
    }

    public static void addEvent(EventManager.Loggable recordEntry, String event, String format,
            Object... args) {
        getEventManager().event(recordEntry, event, format, args);
    }

    public static void registerEventListener(EventManager.EventListener e) {
        getEventManager().registerEventListener(e);
    }

    public static void addRequestResponsePair(EventManager.TimedEventPair p) {
        getEventManager().addRequestResponsePair(p);
    }

    public static void dumpEvents(IndentingPrintWriter pw) {
        // If the Events logger has not been initialized, then there have been no events logged.
        // Don't load it now!
        synchronized (sSingletonSync) {
            if (sEventManager != null) {
                getEventManager().dumpEvents(pw);
            } else {
                pw.println("No Historical Events Logged.");
            }
        }
    }

    /**
     * Dumps the events in a timeline format.
     * @param pw The {@link IndentingPrintWriter} to write to.
     * @hide
     */
    public static void dumpEventsTimeline(IndentingPrintWriter pw) {
        // If the Events logger has not been initialized, then there have been no events logged.
        // Don't load it now!
        synchronized (sSingletonSync) {
            if (sEventManager != null) {
                getEventManager().dumpEventsTimeline(pw);
            } else {
                pw.println("No Historical Events Logged.");
            }
        }
    }

    /**
     * Enable or disable extended telecom logging.
     *
     * @param isExtendedLoggingEnabled {@code true} if extended logging should be enabled,
     *          {@code false} if it should be disabled.
     */
    public static void setIsExtendedLoggingEnabled(boolean isExtendedLoggingEnabled) {
        // If the state hasn't changed, bail early.
        if (sIsUserExtendedLoggingEnabled == isExtendedLoggingEnabled) {
            return;
        }

        if (sEventManager != null) {
            sEventManager.changeEventCacheSize(isExtendedLoggingEnabled ?
                    EVENTS_TO_CACHE_DEBUG : EVENTS_TO_CACHE);
        }

        sIsUserExtendedLoggingEnabled = isExtendedLoggingEnabled;
        if (sIsUserExtendedLoggingEnabled) {
            sUserExtendedLoggingStopTime = System.currentTimeMillis()
                    + EXTENDED_LOGGING_DURATION_MILLIS;
        } else {
            sUserExtendedLoggingStopTime = 0;
        }
    }

    private static EventManager getEventManager() {
        // Checking for null again outside of synchronization because we only need to synchronize
        // during the lazy loading of the events logger. We don't need to synchronize elsewhere.
        if (sEventManager == null) {
            synchronized (sSingletonSync) {
                if (sEventManager == null) {
                    sEventManager = new EventManager(Log::getSessionId);
                    return sEventManager;
                }
            }
        }
        return sEventManager;
    }

    @VisibleForTesting
    public static SessionManager getSessionManager() {
        // Checking for null again outside of synchronization because we only need to synchronize
        // during the lazy loading of the session logger. We don't need to synchronize elsewhere.
        if (sSessionManager == null) {
            synchronized (sSingletonSync) {
                if (sSessionManager == null) {
                    sSessionManager = new SessionManager();
                    return sSessionManager;
                }
            }
        }
        return sSessionManager;
    }

    public static void setTag(String tag) {
        TAG = tag;
        DEBUG = isLoggable(android.util.Log.DEBUG);
        INFO = isLoggable(android.util.Log.INFO);
        VERBOSE = isLoggable(android.util.Log.VERBOSE);
        WARN = isLoggable(android.util.Log.WARN);
        ERROR = isLoggable(android.util.Log.ERROR);
    }

    /**
     * If user enabled extended logging is enabled and the time limit has passed, disables the
     * extended logging.
     */
    private static void maybeDisableLogging() {
        if (!sIsUserExtendedLoggingEnabled) {
            return;
        }

        if (sUserExtendedLoggingStopTime < System.currentTimeMillis()) {
            sUserExtendedLoggingStopTime = 0;
            sIsUserExtendedLoggingEnabled = false;
        }
    }

    public static boolean isLoggable(int level) {
        return FORCE_LOGGING || android.util.Log.isLoggable(TAG, level);
    }

    /**
     * Generates an obfuscated string for a calling handle in {@link Uri} format, or a raw phone
     * phone number in {@link String} format.
     * @param pii The information to obfuscate.
     * @return The obfuscated string.
     */
    public static String piiHandle(Object pii) {
        if (pii == null || VERBOSE) {
            return String.valueOf(pii);
        }

        StringBuilder sb = new StringBuilder();
        if (pii instanceof Uri) {
            Uri uri = (Uri) pii;
            String scheme = uri.getScheme();

            if (!TextUtils.isEmpty(scheme)) {
                sb.append(scheme).append(":");
            }

            String textToObfuscate = uri.getSchemeSpecificPart();
            if (PhoneAccount.SCHEME_TEL.equals(scheme)) {
                obfuscatePhoneNumber(sb, textToObfuscate);
            } else if (PhoneAccount.SCHEME_SIP.equals(scheme)) {
                for (int i = 0; i < textToObfuscate.length(); i++) {
                    char c = textToObfuscate.charAt(i);
                    if (c != '@' && c != '.') {
                        c = '*';
                    }
                    sb.append(c);
                }
            } else {
                sb.append(pii(pii));
            }
        } else if (pii instanceof String) {
            String number = (String) pii;
            obfuscatePhoneNumber(sb, number);
        }

        return sb.toString();
    }

    /**
     * Obfuscates a phone number, allowing NUM_DIALABLE_DIGITS_TO_LOG digits to be exposed for the
     * phone number.
     * @param sb String buffer to write obfuscated number to.
     * @param phoneNumber The number to obfuscate.
     */
    private static void obfuscatePhoneNumber(StringBuilder sb, String phoneNumber) {
        int numDigitsToObfuscate = getDialableCount(phoneNumber)
                - NUM_DIALABLE_DIGITS_TO_LOG;
        for (int i = 0; i < phoneNumber.length(); i++) {
            char c = phoneNumber.charAt(i);
            boolean isDialable = PhoneNumberUtils.isDialable(c);
            if (isDialable) {
                numDigitsToObfuscate--;
            }
            sb.append(isDialable && numDigitsToObfuscate >= 0 ? "*" : c);
        }
    }

    /**
     * Determines the number of dialable characters in a string.
     * @param toCount The string to count dialable characters in.
     * @return The count of dialable characters.
     */
    private static int getDialableCount(String toCount) {
        int numDialable = 0;
        for (char c : toCount.toCharArray()) {
            if (PhoneNumberUtils.isDialable(c)) {
                numDialable++;
            }
        }
        return numDialable;
    }

    /**
     * Redact personally identifiable information for production users.
     * If we are running in verbose mode, return the original string,
     * and return "***" otherwise.
     */
    public static String pii(Object pii) {
        if (pii == null || VERBOSE) {
            return String.valueOf(pii);
        }
        return "***";
    }

    private static String getPrefixFromObject(Object obj) {
        return obj == null ? "<null>" : obj.getClass().getSimpleName();
    }

    private static String buildMessage(String prefix, String format, Object... args) {
        // Incorporate thread ID and calling method into prefix
        String sessionName = getSessionId();
        String sessionPostfix = TextUtils.isEmpty(sessionName) ? "" : ": " + sessionName;

        String msg;
        try {
            msg = (args == null || args.length == 0) ? format
                    : String.format(Locale.US, format, args);
        } catch (IllegalFormatException ife) {
            e(TAG, ife, "Log: IllegalFormatException: formatString='%s' numArgs=%d", format,
                    args.length);
            msg = format + " (An error occurred while formatting the message.)";
        }
        return String.format(Locale.US, "%s: %s%s", prefix, msg, sessionPostfix);
    }

    /**
     * Generates an abbreviated version of the package name from a component.
     * E.g. com.android.phone becomes cap
     * @param componentName The component name to abbreviate.
     * @return Abbreviation of empty string if component is null.
     * @hide
     */
    public static String getPackageAbbreviation(ComponentName componentName) {
        if (componentName == null) {
            return "";
        }
        return getPackageAbbreviation(componentName.getPackageName());
    }

    /**
     * Generates an abbreviated version of the package name.
     * E.g. com.android.phone becomes cap
     * @param packageName The packageName name to abbreviate.
     * @return Abbreviation of empty string if package is null.
     * @hide
     */
    public static String getPackageAbbreviation(String packageName) {
        if (packageName == null) {
            return "";
        }
        return Arrays.stream(packageName.split("\\."))
                .map(s -> s.substring(0,1))
                .collect(Collectors.joining(""));
    }
}
