/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media;

import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.ContentResolver;
import android.net.Uri;
import android.provider.MediaStore;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Immutable representation a desired ringtone, usually originating from a user preference.
 * Unlike sound-only Uris, a "silent" setting is an explicit selection value, rather than null.
 *
 * <p>This representation can be converted into (or from) a URI form for storing within a string
 * preference or when using the ringtone picker via {@link RingtoneManager#ACTION_RINGTONE_PICKER}.
 * It does not carry any actual media data - it only references the components that make
 * up the preference. Initial selections can be built using {@link RingtoneSelection.Builder}.
 *
 * <p>A RingtoneSelection is typically played by passing into a {@link Ringtone.Builder}, and
 * supplementing with contextual defaults from the application. Bad Uris are handled by the
 * {@link Ringtone} class - the RingtoneSelection doesn't validate the target of the Uri.
 *
 * <p>When a RingtoneSelection is created/loaded, the values of its properties are modified
 * to be internally consistent and reflect effective values - with the exception of not verifying
 * the actual URI content. For example, loading a selection Uri that sets a sound source to
 * {@link #SOUND_SOURCE_URI}, but doesn't also have a sound Uri set, will result in this class
 * instead returning {@link #SOUND_SOURCE_DEFAULT} from {@link #getSoundSource}.
 *
 * <h2>Storing preferences</h2>
 *
 * <p>A ringtone preference can have several states: either unset, set to a ringtone selection Uri,
 * or, from prior to the introduction of {@code RingtoneSelection}, set to a sound-only Uri or
 * explicitly set to null to indicate silent.
 *
 * @hide
 */
@TestApi
public final class RingtoneSelection {

    /**
     * The sound source was specified but its value was not recognized. This value is used
     * internally for not stripping unrecognised (possibly future) values during processing.
     * @hide
     */
    public static final int SOUND_SOURCE_UNKNOWN = -1;

    /**
     * The sound source is not explicitly specified, so it can follow default behavior for its
     * context.
     */
    public static final int SOUND_SOURCE_DEFAULT = 0;

    /**
     * Sound is explicitly disabled, such as the user having selected "Silent" in the sound picker.
     */
    public static final int SOUND_SOURCE_OFF = 1;

    /**
     * The sound Uri should be used as the source of sound.
     */
    public static final int SOUND_SOURCE_URI = 2;

    /**
     * Directive for how to make sound.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "SOUND_SOURCE_", value = {
            SOUND_SOURCE_UNKNOWN,
            SOUND_SOURCE_DEFAULT,
            SOUND_SOURCE_OFF,
            SOUND_SOURCE_URI,
    })
    public @interface SoundSource {}

    /**
     * The vibration source was specified but its value was not recognized.
     * This value is used internally for not stripping unrecognised (possibly
     * future) values during processing.
     * @hide
     */
    public static final int VIBRATION_SOURCE_UNKNOWN = -1;

    /**
     * Vibration source is not explicitly specified. If vibration is enabled, this will use the
     * first available of {@link #VIBRATION_SOURCE_AUDIO_CHANNEL},
     * {@link #VIBRATION_SOURCE_APPLICATION_PROVIDED}, or system default vibration.
     */
    public static final int VIBRATION_SOURCE_DEFAULT = 0;

    /** Specifies that vibration is explicitly disabled for this ringtone. */
    public static final int VIBRATION_SOURCE_OFF = 1;

    /** The vibration Uri should be used as the source of vibration. */
    public static final int VIBRATION_SOURCE_URI = 2;

    /**
     * Specifies that vibration should use the vibration provided by the application. This is
     * typically the application's own default for the use-case, provided via
     * {@link Ringtone.Builder#setVibrationEffect}. For notification channels, this is the vibration
     * effect saved on the notification channel.
     *
     * <p>If no vibration is specified by the application, this value behaves if the source was
     * {@link #VIBRATION_SOURCE_DEFAULT}.
     */
    public static final int VIBRATION_SOURCE_APPLICATION_PROVIDED = 3;

    /**
     * Specifies that vibration should use haptic audio channels from the
     * sound Uri. If the sound URI doesn't have haptic channels, then reverts to the order specified
     * by {@link #VIBRATION_SOURCE_DEFAULT}.
     */
    // Numeric gap from VIBRATION_SOURCE_APPLICATION_PROVIDED in case we want other common elements.
    public static final int VIBRATION_SOURCE_AUDIO_CHANNEL = 10;

    /**
     * Specifies that vibration should generate haptic audio channels from the
     * audio tracks of the sound Uri.
     *
     * If the sound Uri already has haptic channels, then behaves as though
     * {@link #VIBRATION_SOURCE_AUDIO_CHANNEL} was specified instead.
     */
    public static final int VIBRATION_SOURCE_HAPTIC_GENERATOR = 11;

    /**
     * Directive for how to vibrate.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "VIBRATION_SOURCE_", value = {
            VIBRATION_SOURCE_UNKNOWN,
            VIBRATION_SOURCE_DEFAULT,
            VIBRATION_SOURCE_OFF,
            VIBRATION_SOURCE_URI,
            VIBRATION_SOURCE_APPLICATION_PROVIDED,
            VIBRATION_SOURCE_AUDIO_CHANNEL,
            VIBRATION_SOURCE_HAPTIC_GENERATOR,
    })
    public @interface VibrationSource {}

    /**
     * Configures {@link #RingtoneSelection#fromUri} to treat an unrecognized Uri as the sound Uri
     * for the returned {@link RingtoneSelection}, with null meaning {@link #SOUND_SOURCE_OFF}.
     * This behavior is particularly suited to loading values from older settings that may contain
     * a raw sound Uri or null for silent.
     *
     * <p>An unrecognized Uri is one for which {@link #isRingtoneSelectionUri(Uri)} returns false.
     */
    public static final int FROM_URI_RINGTONE_SELECTION_OR_SOUND = 1;

    /**
     * Configures {@link #RingtoneSelection#fromUri} to treat an unrecognized Uri as the vibration
     * Uri for the returned {@link RingtoneSelection}, with null meaning
     * {@link #VIBRATION_SOURCE_OFF}.
     *
     * <p>An unrecognized Uri is one for which {@link #isRingtoneSelectionUri(Uri)} returns false.
     */
    public static final int FROM_URI_RINGTONE_SELECTION_OR_VIBRATION = 2;

    /**
     * Configures {@link #RingtoneSelection#fromUri} to treat an unrecognized Uri as an invalid
     * value. Null or an invalid values will revert to default behavior correspnoding to
     * {@link #DEFAULT_SELECTION_URI_STRING}.
     *
     * <p>An unrecognized Uri is one for which {@link #isRingtoneSelectionUri(Uri)} returns false,
     * which include {@code null}.
     */
    public static final int FROM_URI_RINGTONE_SELECTION_ONLY = 3;

    /**
     * How to treat values in {@link #fromUri}.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = "FROM_URI_", value = {
            FROM_URI_RINGTONE_SELECTION_OR_SOUND,
            FROM_URI_RINGTONE_SELECTION_OR_VIBRATION,
            FROM_URI_RINGTONE_SELECTION_ONLY
    })
    public @interface FromUriBehavior {}

    private static final String BASE_RINGTONE_URI = "content://media/ringtone";
    /**
     * String representation of a RingtoneSelection Uri that says to use defaults (equivalent
     * to {@code new RingtoneSelection.Builder().build()}).
     */
    public static final String DEFAULT_SELECTION_URI_STRING = BASE_RINGTONE_URI;

    private static final String MEDIA_URI_RINGTONE_PATH = "/ringtone";

    /* Query param keys. */
    private static final String URI_PARAM_SOUND_URI = "su";
    private static final String URI_PARAM_SOUND_SOURCE = "ss";
    private static final String URI_PARAM_VIBRATION_URI = "vu";
    private static final String URI_PARAM_VIBRATION_SOURCE = "vs";

    /* Common param values */
    private static final String SOURCE_OFF_STRING = "off";

    /* Vibration source param values. */
    private static final String VIBRATION_SOURCE_AUDIO_CHANNEL_STRING = "ac";
    private static final String VIBRATION_SOURCE_APPLICATION_PROVIDED_STRING = "app";
    private static final String VIBRATION_SOURCE_HAPTIC_GENERATOR_STRING = "hg";

    @Nullable
    private final Uri mSoundUri;
    @SoundSource
    private final int mSoundSource;

    @Nullable
    private final Uri mVibrationUri;
    @VibrationSource
    private final int mVibrationSource;

    private RingtoneSelection(@Nullable Uri soundUri, @SoundSource int soundSource,
            @Nullable Uri vibrationUri, @VibrationSource int vibrationSource) {
        // Enforce guarantees on the source values: revert to unset if they depend on something
        // that's not set.
        switch (soundSource) {
            case SOUND_SOURCE_URI:
            case SOUND_SOURCE_UNKNOWN:  // Allow unknown to revert to URI before default.
                mSoundSource = soundUri != null ? SOUND_SOURCE_URI : SOUND_SOURCE_DEFAULT;
                break;
            default:
                mSoundSource = soundSource;
                break;
        }
        switch (vibrationSource) {
            case VIBRATION_SOURCE_AUDIO_CHANNEL:
            case VIBRATION_SOURCE_HAPTIC_GENERATOR:
                mVibrationSource = soundUri != null ? vibrationSource : VIBRATION_SOURCE_DEFAULT;
                break;
            case VIBRATION_SOURCE_URI:
            case VIBRATION_SOURCE_UNKNOWN:  // Allow unknown to revert to URI.
                mVibrationSource =
                        vibrationUri != null ? VIBRATION_SOURCE_URI : VIBRATION_SOURCE_DEFAULT;
                break;
            default:
                mVibrationSource = vibrationSource;
                break;
        }
        // Clear Uri values if they're un-used by the source.
        switch (mSoundSource) {
            case SOUND_SOURCE_OFF:
                mSoundUri = null;
                break;
            default:
                // Unset case isn't handled here: the defaulting behavior is left to the player.
                mSoundUri = soundUri;
                break;
        }
        switch (mVibrationSource) {
            case VIBRATION_SOURCE_OFF:
            case VIBRATION_SOURCE_APPLICATION_PROVIDED:
            case VIBRATION_SOURCE_AUDIO_CHANNEL:
            case VIBRATION_SOURCE_HAPTIC_GENERATOR:
                mVibrationUri = null;
                break;
            default:
                // Unset case isn't handled here: the defaulting behavior is left to the player.
                mVibrationUri = vibrationUri;
                break;
        }
    }

    /**
     * Returns the stored sound behavior.
     */
    @SoundSource
    public int getSoundSource() {
        return mSoundSource;
    }

    /**
     * Returns the sound Uri for this selection. This is guaranteed to be non-null if
     * {@link #getSoundSource} returns {@link #SOUND_SOURCE_URI}.
     */
    @Nullable
    public Uri getSoundUri() {
        return mSoundUri;
    }

    /**
     * Returns the selected vibration behavior.
     */
    @VibrationSource
    public int getVibrationSource() {
        return mVibrationSource;
    }

    /**
     * Returns the vibration Uri for this selection. This is guaranteed to be non-null if
     * {@link #getVibrationSource} returns {@link #SOUND_SOURCE_URI}.
     */
    @Nullable
    public Uri getVibrationUri() {
        return mVibrationUri;
    }

    /**
     * Converts the ringtone selection into a Uri-form, suitable for storing as a user preference
     * or returning as a result.
     */
    @NonNull
    public Uri toUri() {
        Uri.Builder builder = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(MediaStore.AUTHORITY)
                .path(MEDIA_URI_RINGTONE_PATH);
        if (mSoundUri != null) {
            builder.appendQueryParameter(URI_PARAM_SOUND_URI, mSoundUri.toString());
        }
        // Only off is explicit for sound sources
        String soundSourceStr = soundSourceToString(mSoundSource);
        if (soundSourceStr != null) {
            builder.appendQueryParameter(URI_PARAM_SOUND_SOURCE, soundSourceStr);
        }
        if (mVibrationUri != null) {
            builder.appendQueryParameter(URI_PARAM_VIBRATION_URI, mVibrationUri.toString());
        }
        String vibrationSourceStr = vibrationSourceToString(mVibrationSource);
        if (vibrationSourceStr != null) {
            builder.appendQueryParameter(URI_PARAM_VIBRATION_SOURCE, vibrationSourceStr);
        }
        return builder.build();
    }

    /**
     * Returns true if the Uri is an encoded {@link RingtoneSelection}. This method doesn't
     * validate the parameters of the selection.
     *
     * @see #fromUri
     * @see #toUri
     */
    public static boolean isRingtoneSelectionUri(@Nullable Uri uri) {
        if (uri == null) {
            return false;
        }
        // Any URI content://media/ringtone
        return ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                && MediaStore.AUTHORITY.equals(uri.getAuthority())
                && MEDIA_URI_RINGTONE_PATH.equals(uri.getPath());
    }



    /**
     * Converts a Uri into a RingtoneSelection.
     *
     * <p>Null values and Uris that {@link #isRingtoneSelectionUri(Uri)} returns false will be
     * treated according to the behaviour specified by the {@code unrecognizedValueBehavior}
     * parameter.
     *
     * @param uri The Uri to convert, potentially null.
     * @param unrecognizedValueBehavior indicates how to treat values for which
     *   {@link #isRingtoneSelectionUri(Uri)} returns false (including null).
     * @return the RingtoneSelection represented by the given uri.
     */
    @NonNull
    public static RingtoneSelection fromUri(@Nullable Uri uri,
            @FromUriBehavior int unrecognizedValueBehavior) {
        if (isRingtoneSelectionUri(uri)) {
            return parseRingtoneSelectionUri(uri);
        }
        RingtoneSelection.Builder builder = new RingtoneSelection.Builder();
        switch (unrecognizedValueBehavior) {
            case FROM_URI_RINGTONE_SELECTION_ONLY:
                // Always return use-defaults for unrecognized ringtone selection Uris.
                return builder.build();
            case FROM_URI_RINGTONE_SELECTION_OR_SOUND:
                if (uri == null) {
                    return builder.setSoundSource(SOUND_SOURCE_OFF).build();
                } else {
                    return builder.setSoundSource(uri).build();
                }
            case FROM_URI_RINGTONE_SELECTION_OR_VIBRATION:
                if (uri == null) {
                    return builder.setVibrationSource(VIBRATION_SOURCE_OFF).build();
                } else {
                    return builder.setVibrationSource(uri).build();
                }
            default:
                throw new IllegalArgumentException("Unknown behavior parameter: "
                        + unrecognizedValueBehavior);
        }
    }

    /** Parses the Uri, which has already been checked for {@link #isRingtoneSelectionUri(Uri)}. */
    @NonNull
    private static RingtoneSelection parseRingtoneSelectionUri(@NonNull Uri uri) {
        RingtoneSelection.Builder builder = new RingtoneSelection.Builder();
        int soundSource = stringToSoundSource(uri.getQueryParameter(URI_PARAM_SOUND_SOURCE));
        int vibrationSource = stringToVibrationSource(
                uri.getQueryParameter(URI_PARAM_VIBRATION_SOURCE));
        Uri soundUri = getParamAsUri(uri, URI_PARAM_SOUND_URI);
        Uri vibrationUri = getParamAsUri(uri, URI_PARAM_VIBRATION_URI);
        if (soundUri != null) {
            builder.setSoundSource(soundUri);
        }
        if (vibrationUri != null) {
            builder.setVibrationSource(vibrationUri);
        }
        // Don't set the source if there's a URI and the source is default, because that will
        // override the Uri source set above. In effect, we prioritise "explicit" sources over
        // an implicit Uri source - except for "default", which isn't really explicit.
        if (soundSource != SOUND_SOURCE_DEFAULT || soundUri == null) {
            builder.setSoundSource(soundSource);
        }
        if (vibrationSource != VIBRATION_SOURCE_DEFAULT || vibrationUri == null) {
            builder.setVibrationSource(vibrationSource);
        }
        return builder.build();
    }

    @Nullable
    private static Uri getParamAsUri(@NonNull Uri uri, String param) {
        // This returns the uri-decoded value, no need to further decode.
        String value = uri.getQueryParameter(param);
        if (value == null) {
            return null;
        }
        return Uri.parse(value);
    }

    /**
     * Converts the {@link SoundSource} to the uri query param value for it, or null
     * if the sound source is default, unknown, or implicit (uri).
     */
    @Nullable
    private static String soundSourceToString(@SoundSource int soundSource) {
        switch (soundSource) {
            case SOUND_SOURCE_OFF: return SOURCE_OFF_STRING;
            default: return null;
        }
    }

    /**
     * Returns the sound source int corresponding to the query string value. Returns
     * {@link #SOUND_SOURCE_UNKNOWN} if the value isn't recognised, and
     * {@link #SOUND_SOURCE_DEFAULT} if the value is {@code null} (not in the Uri).
     */
    @SoundSource
    private static int stringToSoundSource(@Nullable String soundSource) {
        if (soundSource == null) {
            return SOUND_SOURCE_DEFAULT;
        }
        switch (soundSource) {
            case SOURCE_OFF_STRING: return SOUND_SOURCE_OFF;
            default: return SOUND_SOURCE_UNKNOWN;
        }
    }

    /**
     * Converts the {@code vibrationSource} to the uri query param value for it, or null
     * if the vibration source is default, unknown, or implicit (uri).
     */
    @Nullable
    private static String vibrationSourceToString(@VibrationSource int vibrationSource) {
        switch (vibrationSource) {
            case VIBRATION_SOURCE_OFF: return SOURCE_OFF_STRING;
            case VIBRATION_SOURCE_AUDIO_CHANNEL: return VIBRATION_SOURCE_AUDIO_CHANNEL_STRING;
            case VIBRATION_SOURCE_HAPTIC_GENERATOR:
                return VIBRATION_SOURCE_HAPTIC_GENERATOR_STRING;
            case VIBRATION_SOURCE_APPLICATION_PROVIDED:
                return VIBRATION_SOURCE_APPLICATION_PROVIDED_STRING;
            default: return null;
        }
    }

    @VibrationSource
    private static int stringToVibrationSource(@Nullable String vibrationSource) {
        if (vibrationSource == null) {
            return VIBRATION_SOURCE_DEFAULT;
        }
        switch (vibrationSource) {
            case SOURCE_OFF_STRING: return VIBRATION_SOURCE_OFF;
            case VIBRATION_SOURCE_AUDIO_CHANNEL_STRING: return VIBRATION_SOURCE_AUDIO_CHANNEL;
            case VIBRATION_SOURCE_HAPTIC_GENERATOR_STRING: return VIBRATION_SOURCE_HAPTIC_GENERATOR;
            case VIBRATION_SOURCE_APPLICATION_PROVIDED_STRING:
                return VIBRATION_SOURCE_APPLICATION_PROVIDED;
            default: return VIBRATION_SOURCE_UNKNOWN;
        }
    }

    /**
     * Builder for {@link RingtoneSelection}. In general, this builder will be used by interfaces
     * allowing the user to configure their selection. Once a selection is stored as a Uri, then
     * the RingtoneSelection can be loaded directly using {@link RingtoneSelection#fromUri}.
     */
    public static final class Builder {
        private Uri mSoundUri;
        private Uri mVibrationUri;
        @SoundSource private int mSoundSource = SOUND_SOURCE_DEFAULT;
        @VibrationSource private int mVibrationSource = VIBRATION_SOURCE_DEFAULT;

        /**
         * Creates a new {@link RingtoneSelection} builder. A default ringtone selection has its
         * sound and vibration source unset, which means they would fall back to system defaults.
         */
        public Builder() {}

        /**
         * Creates a builder initialized with the given ringtone selection.
         */
        public Builder(@NonNull RingtoneSelection selection) {
            requireNonNull(selection);
            mSoundSource = selection.getSoundSource();
            mSoundUri = selection.getSoundUri();
            mVibrationSource = selection.getVibrationSource();
            mVibrationUri = selection.getVibrationUri();
        }

        /**
         * Sets the desired sound source.
         *
         * <p>Values other than {@link #SOUND_SOURCE_URI} will clear any previous sound Uri.
         * For {@link #SOUND_SOURCE_URI}, the {@link #setSoundSource(Uri)} method should be
         * used instead, as setting it here will have no effect unless the Uri is also set.
         */
        @NonNull
        public Builder setSoundSource(@SoundSource int soundSource) {
            mSoundSource = soundSource;
            if (soundSource != SOUND_SOURCE_URI && soundSource != SOUND_SOURCE_UNKNOWN) {
                // Note that this means the configuration of "silent sound, but use haptic
                // generator" is currently not supported. Future support could be added by either
                // using the vibration uri in that case, or by having a special
                // "setSoundUriForVibrationOnly(Uri)" method that sets sound source to off but
                // also retains the Uri.
                mSoundUri = null;
            }
            return this;
        }

        /**
         * Sets the sound source to {@link #SOUND_SOURCE_URI}, and the sound Uri to the
         * specified {@link Uri}.
         */
        @NonNull
        public Builder setSoundSource(@NonNull Uri soundUri) {
            mSoundUri = requireNonNull(soundUri);
            mSoundSource = SOUND_SOURCE_URI;
            return this;
        }

        /**
         * Sets the vibration source to the specified value.
         *
         * <p>Values other than {@link #VIBRATION_SOURCE_URI} will clear any previous vibration Uri.
         * For {@link #VIBRATION_SOURCE_URI}, the {@link #setVibrationSource(Uri)} method should be
         * used instead, as setting it here will have no effect unless the Uri is also set.
         */
        @NonNull
        public Builder setVibrationSource(@VibrationSource int vibrationSource) {
            mVibrationSource = vibrationSource;
            if (vibrationSource != VIBRATION_SOURCE_URI
                    && vibrationSource != VIBRATION_SOURCE_UNKNOWN) {
                mVibrationUri = null;
            }
            return this;
        }

        /**
         * Sets the vibration source to {@link #VIBRATION_SOURCE_URI}, and the vibration Uri to the
         * specified {@link Uri}.
         */
        @NonNull
        public Builder setVibrationSource(@NonNull Uri vibrationUri) {
            mVibrationUri = requireNonNull(vibrationUri);
            mVibrationSource = VIBRATION_SOURCE_URI;
            return this;
        }

        /**
         * Returns the ringtone Uri that was configured.
         */
        @NonNull
        public RingtoneSelection build() {
            return new RingtoneSelection(mSoundUri, mSoundSource, mVibrationUri, mVibrationSource);
        }
    }
}
