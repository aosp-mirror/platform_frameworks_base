package android.app.pinner;

import android.app.pinner.PinnedFileStat;

/**
 * Interface for processes to communicate with system's PinnerService.
 * @hide
 */
interface IPinnerService {
    @EnforcePermission("DUMP")
    List<PinnedFileStat> getPinnerStats();
}