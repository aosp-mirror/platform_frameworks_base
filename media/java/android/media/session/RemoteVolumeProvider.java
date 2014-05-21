package android.media.session;

/**
 * Handles requests to adjust or set the volume on a session. This is also used
 * to push volume updates back to the session after a request has been handled.
 * You can set a volume provider on a session by calling
 * {@link MediaSession#useRemotePlayback}.
 */
public abstract class RemoteVolumeProvider {

    /**
     * Handles relative volume changes via {@link #onAdjustVolume(int)}.
     */
    public static final int FLAG_VOLUME_RELATIVE = 1 << 0;

    /**
     * Handles setting the volume via {@link #onSetVolume(int)}.
     */
    public static final int FLAG_VOLUME_ABSOLUTE = 1 << 1;

    private final int mFlags;
    private final int mMaxVolume;

    /**
     * Create a new volume provider for handling volume events. You must specify
     * the type of events and the maximum volume that can be used.
     *
     * @param flags The flags to use with this provider.
     * @param maxVolume The maximum allowed volume.
     */
    public RemoteVolumeProvider(int flags, int maxVolume) {
        mFlags = flags;
        mMaxVolume = maxVolume;
    }

    /**
     * Get the current volume of the remote playback.
     *
     * @return The current volume.
     */
    public abstract int getCurrentVolume();

    /**
     * Get the flags that were set for this volume provider.
     *
     * @return The flags for this volume provider
     */
    public final int getFlags() {
        return mFlags;
    }

    /**
     * Get the maximum volume this provider allows.
     *
     * @return The max allowed volume.
     */
    public final int getMaxVolume() {
        return mMaxVolume;
    }

    /**
     * Notify the system that the remove playback's volume has been changed.
     */
    public final void notifyVolumeChanged() {
        // TODO
    }

    /**
     * Override to handle requests to set the volume of the current output.
     *
     * @param volume The volume to set the output to.
     */
    public void onSetVolume(int volume) {
    }

    /**
     * Override to handle requests to adjust the volume of the current
     * output.
     *
     * @param delta The amount to change the volume
     */
    public void onAdjustVolume(int delta) {
    }
}