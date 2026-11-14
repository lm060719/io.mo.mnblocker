package io.mo.mnblocker;

import android.app.NotificationChannel;
import android.os.FileObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import de.robv.android.xposed.XposedHelpers;

/** Explicit configuration writes, outside query callbacks and on a dedicated system thread. */
@android.annotation.TargetApi(26)
final class ChannelConfigReconciler {
    interface Update {
        void apply(Object service, ChannelIdentity owner, int uid, NotificationChannel copy) throws Throwable;
    }
    private final DetectedChannelsStore detected;
    private final Update update;
    private Handler handler;
    private FileObserver observer;
    private final Runnable task = this::reconcile;

    ChannelConfigReconciler(DetectedChannelsStore detected, Update update) {
        this.detected = detected;
        this.update = update;
    }

    @SuppressWarnings("deprecation")
    void start() {
        HandlerThread thread = new HandlerThread("MNBlocker-channel-config");
        thread.start();
        handler = new Handler(thread.getLooper());
        observer = new FileObserver(HookLogger.DIR, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO
                | FileObserver.CREATE | FileObserver.DELETE) {
            @Override public void onEvent(int event, String path) {
                if ("config.json".equals(path) || "rules.txt".equals(path)
                        || SafetyManager.FLAG_FILE_NAME.equals(path)
                        || SafetyManager.DISABLE_FLAG_FILE_NAME.equals(path)) {
                    handler.removeCallbacks(task);
                    handler.postDelayed(task, 300);
                }
            }
        };
        observer.startWatching();
        handler.postDelayed(task, 5000);
    }

    private void reconcile() {
        try {
            IBinder binder = (IBinder) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.os.ServiceManager", null), "getService", "notification");
            if (binder == null) {
                handler.postDelayed(task, 5000);
                return;
            }
            Object service = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.INotificationManager$Stub", null), "asInterface", binder);
            Object activityThread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread");
            Object context = XposedHelpers.callMethod(activityThread, "getSystemContext");
            Object pm = XposedHelpers.callMethod(context, "getPackageManager");
            // Snapshot before calling NMS; query observation may update detected, never vice versa.
            Set<String> visited = new HashSet<>();
            for (ChannelRecord r : detected.snapshot()) {
                if (r.userId < 0 || !visited.add(r.pkg + "|" + r.userId)) continue;
                try {
                    // Resolve current UID, not an app id cached before reinstall.
                    int uid = (Integer) XposedHelpers.callMethod(pm, "getPackageUidAsUser", r.pkg, r.userId);
                    if (uid < 0 || uid / 100000 != r.userId) continue;
                    Object slice = XposedHelpers.callMethod(service, "getNotificationChannelsForPackage",
                            r.pkg, uid, false);
                    Object list = XposedHelpers.callMethod(slice, "getList");
                    if (!(list instanceof List)) continue;
                    for (Object value : (List<?>) list) {
                        if (value instanceof NotificationChannel) {
                            update.apply(service, new ChannelIdentity(r.pkg, r.userId), uid,
                                    copy((NotificationChannel) value));
                        }
                    }
                } catch (Throwable t) {
                    HookLogger.e("Could not reconcile channels | caller=" + r.pkg + " | user=" + r.userId, t);
                }
            }
        } catch (Throwable t) {
            HookLogger.e("Explicit channel configuration sync unavailable; query observation remains read-only", t);
        }
    }

    private static NotificationChannel copy(NotificationChannel channel) {
        Parcel parcel = Parcel.obtain();
        try {
            channel.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return NotificationChannel.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }
}
