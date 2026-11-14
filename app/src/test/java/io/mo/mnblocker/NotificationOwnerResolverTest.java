package io.mo.mnblocker;

import android.app.Notification;
import android.app.NotificationChannel;
import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationOwnerResolverTest {
    private static final String HELPER = "com.android.server.notification.PreferencesHelper";
    private static final Class<?>[] QUERY = {String.class, int.class, String.class, boolean.class};

    @Test public void resolvesTargetUidForBothProfiles() {
        ChannelIdentity main = NotificationOwnerResolver.channel(HELPER, "getNotificationChannel", QUERY,
                new Object[]{"com.coolapk.market", 10631, "mipush|com.coolapk.market|105770", false});
        ChannelIdentity clone = NotificationOwnerResolver.channel(HELPER, "getNotificationChannel", QUERY,
                new Object[]{"com.coolapk.market", 99910631, "mipush|com.coolapk.market|105770", false});
        assertEquals(0, main.userId);
        assertEquals(999, clone.userId);
        assertEquals("com.coolapk.market", clone.pkg);
    }

    @Test public void binderCallingPackageMustNotBecomeOwner() {
        assertNull(NotificationOwnerResolver.channel(
                "com.android.server.notification.NotificationManagerService$BinderService",
                "getNotificationChannel", new Class<?>[]{String.class, int.class, String.class, String.class},
                new Object[]{"com.xiaomi.xmsf", 0, "com.coolapk.market", "105770"}));
    }

    @Test public void groupIdAndUnknownOverloadsAreNotGuessed() {
        assertNull(NotificationOwnerResolver.channel(HELPER, "getNotificationChannelGroup",
                new Class<?>[]{String.class, String.class, int.class},
                new Object[]{"marketing", "com.coolapk.market", 10631}));
        assertNull(NotificationOwnerResolver.channel(HELPER, "getNotificationChannel",
                new Class<?>[]{String.class, int.class, String.class, String.class, boolean.class},
                new Object[]{"proxy", 10631, "target", "id", false}));
        assertNull(NotificationOwnerResolver.channel(HELPER, "getNotificationChannel", QUERY,
                new Object[]{"pkg", -1, "id", false}));
    }

    @Test public void writesUseExactHelperOwnerPrefix() {
        ChannelIdentity owner = NotificationOwnerResolver.channel(HELPER, "createNotificationChannel",
                new Class<?>[]{String.class, int.class, NotificationChannel.class, boolean.class, boolean.class, int.class, boolean.class},
                new Object[]{"com.coolapk.market", 99910631, null, true, true, 10207, false});
        assertEquals(999, owner.userId);
    }

    public static void enqueueNotificationInternal(String pkg, String opPkg, int uid, int pid,
            String tag, int id, Notification n, int user, boolean extra) {}

    @Test public void delegatedEnqueueBelongsToTargetNotXiaomi() throws Exception {
        java.lang.reflect.Method method = getClass().getMethod("enqueueNotificationInternal",
                String.class, String.class, int.class, int.class, String.class, int.class,
                Notification.class, int.class, boolean.class);
        Object[] args = {"com.coolapk.market", "com.xiaomi.xmsf", 10207, 100, null, 12, null, 999, false};
        ChannelIdentity owner = NotificationOwnerResolver.enqueue(method, args);
        assertEquals("com.coolapk.market", owner.pkg);
        assertEquals(999, owner.userId);
        args[7] = -1;
        assertNull(NotificationOwnerResolver.enqueue(method, args));
    }
}
