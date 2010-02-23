package android.app;

import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * This class provides access to the system uimode services.  These services
 * allow applications to control UI modes of the device.
 * It provides functionality to disable the car mode and it gives access to the
 * night mode settings.
 *
 * <p>You do not instantiate this class directly; instead, retrieve it through
 * {@link android.content.Context#getSystemService
 * Context.getSystemService(Context.UI_MODE_SERVICE)}.
 */
public class UiModeManager {
    private static final String TAG = "UiModeManager";

    public static final int MODE_NOTNIGHT = 1;
    public static final int MODE_NIGHT = 2;
    public static final int MODE_AUTO = 3;

    private IUiModeManager mService;

    /*package*/ UiModeManager() {
        mService = IUiModeManager.Stub.asInterface(
                ServiceManager.getService("uimode"));
    }

    /**
     * Disables the car mode.
     */
    public void disableCarMode() {
        if (mService != null) {
            try {
                mService.disableCarMode();
            } catch (RemoteException e) {
                Log.e(TAG, "disableCarMode: RemoteException", e);
            }
        }
    }

    /**
     * Sets the night mode.  Changes to the night mode are only effective when
     * the car mode is enabled on a device.
     *
     * <p>The mode can be one of:
     * <ul>
     *   <li><em>{@link #MODE_NOTNIGHT}<em> - sets the device into notnight
     *       mode.</li>
     *   <li><em>{@link #MODE_NIGHT}</em> - sets the device into night mode.
     *   </li>
     *   <li><em>{@link #MODE_AUTO}</em> - automatic night/notnight switching
     *       depending on the location and certain other sensors.</li>
     */
    public void setNightMode(int mode) {
        if (mService != null) {
            try {
                mService.setNightMode(mode);
            } catch (RemoteException e) {
                Log.e(TAG, "setNightMode: RemoteException", e);
            }
        }
    }

    /**
     * Returns the currently configured night mode.
     *
     * @return {@link #MODE_NOTNIGHT}, {@link #MODE_NIGHT} or {@link #MODE_AUTO}
     *         When an error occurred -1 is returned.
     */
    public int getNightMode() {
        if (mService != null) {
            try {
                return mService.getNightMode();
            } catch (RemoteException e) {
                Log.e(TAG, "getNightMode: RemoteException", e);
            }
        }
        return -1;
    }
}
