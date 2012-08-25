package android.service.dreams;

import static android.provider.Settings.Secure.SCREENSAVER_COMPONENTS;
import static android.provider.Settings.Secure.SCREENSAVER_DEFAULT_COMPONENT;
import java.io.FileDescriptor;
import java.io.PrintWriter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

/**
 *
 * @hide
 *
 */

public class DreamManagerService
        extends IDreamManager.Stub
        implements ServiceConnection
{
    private static final boolean DEBUG = true;
    private static final String TAG = "DreamManagerService";

    final Object mLock = new Object[0];

    private Context mContext;
    private IWindowManager mIWindowManager;

    private ComponentName mCurrentDreamComponent;
    private IDreamService mCurrentDream;
    private Binder mCurrentDreamToken;

    public DreamManagerService(Context context) {
        if (DEBUG) Slog.v(TAG, "DreamManagerService startup");
        mContext = context;
        mIWindowManager = WindowManagerGlobal.getWindowManagerService();
    }

    private void checkPermission(String permission) {
        if (PackageManager.PERMISSION_GRANTED != mContext.checkCallingOrSelfPermission(permission)) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    // IDreamManager method
    @Override
    public void dream() {
        ComponentName[] dreams = getDreamComponents();
        ComponentName name = dreams != null && dreams.length > 0 ? dreams[0] : null;
        if (name != null) {
            synchronized (mLock) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    bindDreamComponentL(name, false);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }

    // IDreamManager method
    @Override
    public void setDreamComponents(ComponentName[] componentNames) {
        Settings.Secure.putString(mContext.getContentResolver(),
                SCREENSAVER_COMPONENTS,
                componentsToString(componentNames));
    }

    private static String componentsToString(ComponentName[] componentNames) {
        StringBuilder names = new StringBuilder();
        if (componentNames != null) {
            for (ComponentName componentName : componentNames) {
                if (names.length() > 0)
                    names.append(',');
                names.append(componentName.flattenToString());
            }
        }
        return names.toString();
    }

    private static ComponentName[] componentsFromString(String names) {
        String[] namesArray = names.split(",");
        ComponentName[] componentNames = new ComponentName[namesArray.length];
        for (int i = 0; i < namesArray.length; i++)
            componentNames[i] = ComponentName.unflattenFromString(namesArray[i]);
        return componentNames;
    }

    // IDreamManager method
    @Override
    public ComponentName[] getDreamComponents() {
        // TODO(dsandler) don't load this every time, watch the value
        String names = Settings.Secure.getString(mContext.getContentResolver(), SCREENSAVER_COMPONENTS);
        return names == null ? null : componentsFromString(names);
    }

    // IDreamManager method
    @Override
    public ComponentName getDefaultDreamComponent() {
        String name = Settings.Secure.getString(mContext.getContentResolver(), SCREENSAVER_DEFAULT_COMPONENT);
        return name == null ? null : ComponentName.unflattenFromString(name);
    }

    // IDreamManager method
    @Override
    public void testDream(ComponentName name) {
        if (DEBUG) Slog.v(TAG, "startDream name=" + name
                + " pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
//        checkPermission(android.Manifest.permission.SET_WALLPAPER_COMPONENT);
        synchronized (mLock) {
            final long ident = Binder.clearCallingIdentity();
            try {
                bindDreamComponentL(name, true);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    // IDreamManager method
    @Override
    public void awaken() {
        if (DEBUG) Slog.v(TAG, "awaken()");
        synchronized (mLock) {
            if (mCurrentDream != null) {
                if (DEBUG) Slog.v(TAG, "disconnecting: " +  mCurrentDreamComponent + " service: " + mCurrentDream);
                mContext.unbindService(this);
                mCurrentDream = null;
                mCurrentDreamToken = null;
            }
        }
    }

    // IDreamManager method
    @Override
    public boolean isDreaming() {
        synchronized (mLock) {
            return mCurrentDreamToken != null;
        }
    }

    public void bindDreamComponentL(ComponentName componentName, boolean test) {
        if (DEBUG) Slog.v(TAG, "bindDreamComponent: componentName=" + componentName
                + " pid=" + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());

        Intent intent = new Intent(Intent.ACTION_MAIN)
            .setComponent(componentName)
            .addFlags(
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            .putExtra("android.dreams.TEST", test);

        mCurrentDreamComponent = componentName;
        mCurrentDreamToken = new Binder();
        try {
            if (DEBUG) Slog.v(TAG, "Adding window token: " + mCurrentDreamToken
                    + " for window type: " + WindowManager.LayoutParams.TYPE_DREAM);
            mIWindowManager.addWindowToken(mCurrentDreamToken,
                    WindowManager.LayoutParams.TYPE_DREAM);
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to add window token. Proceed at your own risk.");
        }

        if (!mContext.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
            Slog.w(TAG, "unable to bind service: " + componentName);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (DEBUG) Slog.v(TAG, "connected to dream: " + name + " binder=" + service + " thread=" + Thread.currentThread().getId());

        mCurrentDream = IDreamService.Stub.asInterface(service);
        try {
            if (DEBUG) Slog.v(TAG, "attaching with token:" + mCurrentDreamToken);
            mCurrentDream.attach(mCurrentDreamToken);
        } catch (RemoteException ex) {
            Slog.w(TAG, "Unable to send window token to dream:" + ex);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (DEBUG) Slog.v(TAG, "disconnected: " + name + " service: " + mCurrentDream);
        // Only happens in exceptional circumstances
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);

        pw.println("Dreamland:");
        pw.print("  component="); pw.println(mCurrentDreamComponent);
        pw.print("  token="); pw.println(mCurrentDreamToken);
        pw.print("  dream="); pw.println(mCurrentDream);
    }

    public void systemReady() {
        if (DEBUG) Slog.v(TAG, "ready to dream!");
    }

}
