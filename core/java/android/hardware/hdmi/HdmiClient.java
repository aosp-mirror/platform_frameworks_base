package android.hardware.hdmi;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.hdmi.HdmiControlManager.VendorCommandListener;
import android.hardware.hdmi.IHdmiVendorCommandListener;
import android.os.RemoteException;
import android.util.Log;

/**
 * Parent for classes of various HDMI-CEC device type used to access
 * {@link HdmiControlService}. Contains methods and data used in common.
 *
 * @hide
 */
@SystemApi
public abstract class HdmiClient {
    private static final String TAG = "HdmiClient";

    protected final IHdmiControlService mService;

    protected abstract int getDeviceType();

    public HdmiClient(IHdmiControlService service) {
        mService = service;
    }

    /**
     * Returns the active source information.
     *
     * @return {@link HdmiDeviceInfo} object that describes the active source
     *         or active routing path
     */
    public HdmiDeviceInfo getActiveSource() {
        try {
            return mService.getActiveSource();
        } catch (RemoteException e) {
            Log.e(TAG, "getActiveSource threw exception ", e);
        }
        return null;
    }

    /**
     * Send a key event to other logical device.
     *
     * @param keyCode key code to send. Defined in {@link android.view.KeyEvent}.
     * @param isPressed true if this is key press event
     */
    public void sendKeyEvent(int keyCode, boolean isPressed) {
        try {
            mService.sendKeyEvent(getDeviceType(), keyCode, isPressed);
        } catch (RemoteException e) {
            Log.e(TAG, "sendKeyEvent threw exception ", e);
        }
    }

    /**
     * Send vendor-specific command.
     *
     * @param targetAddress address of the target device
     * @param params vendor-specific parameter. For &lt;Vendor Command With ID&gt; do not
     *               include the first 3 bytes (vendor ID).
     * @param hasVendorId {@code true} if the command type will be &lt;Vendor Command With ID&gt;.
     *                    {@code false} if the command will be &lt;Vendor Command&gt;
     */
    public void sendVendorCommand(int targetAddress, byte[] params, boolean hasVendorId) {
        try {
            mService.sendVendorCommand(getDeviceType(), targetAddress, params, hasVendorId);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to send vendor command: ", e);
        }
    }

    /**
     * Add a listener used to receive incoming vendor-specific command.
     *
     * @param listener listener object
     */
    public void addVendorCommandListener(@NonNull VendorCommandListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null");
        }
        try {
            mService.addVendorCommandListener(getListenerWrapper(listener), getDeviceType());
        } catch (RemoteException e) {
            Log.e(TAG, "failed to add vendor command listener: ", e);
        }
    }

    private static IHdmiVendorCommandListener getListenerWrapper(
            final VendorCommandListener listener) {
        return new IHdmiVendorCommandListener.Stub() {
            @Override
            public void onReceived(int srcAddress, byte[] params, boolean hasVendorId) {
                listener.onReceived(srcAddress, params, hasVendorId);
            }
        };
    }
}
