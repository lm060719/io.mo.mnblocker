package io.mo.mnblocker;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Only documented helper prefixes are accepted. Unknown ROM signatures pass through. */
final class NotificationOwnerResolver {
    private static final Set<String> QUERIES = new HashSet<>(Arrays.asList(
            "getNotificationChannel", "getNotificationChannels",
            "getConversationNotificationChannel"));

    static ChannelIdentity channel(Method method, Object[] args) {
        return channel(method.getDeclaringClass().getName(), method.getName(),
                method.getParameterTypes(), args);
    }

    static ChannelIdentity channel(String owner, String method, Class<?>[] types, Object[] args) {
        // Do not hook BinderService: its first string can be callingPkg, not targetPkg.
        if (!owner.equals("com.android.server.notification.PreferencesHelper")
                && !owner.equals("com.android.server.notification.RankingHelper")) return null;
        boolean write = method.equals("createNotificationChannel")
                || method.equals("updateNotificationChannel");
        if (!write && !QUERIES.contains(method)) return null;
        if (args == null || types.length != args.length || types.length < 3
                || types[0] != String.class || types[1] != int.class
                || !(args[0] instanceof String) || ((String) args[0]).isEmpty()
                || !(args[1] instanceof Integer) || (Integer) args[1] < 0) return null;
        if (write) {
            if (!types[2].getName().equals("android.app.NotificationChannel")) return null;
            // Modern overloads add booleans/ints after the stable pkg, uid, channel prefix.
            for (int i = 3; i < types.length; i++) {
                if (types[i] != boolean.class && types[i] != int.class) return null;
            }
        } else if (method.equals("getNotificationChannels")) {
            if (!Arrays.equals(types, new Class<?>[]{String.class, int.class, boolean.class})) return null;
        } else if (method.equals("getNotificationChannel")) {
            if (!Arrays.equals(types, new Class<?>[]{String.class, int.class, String.class, boolean.class})) return null;
        } else if (!Arrays.equals(types, new Class<?>[]{String.class, int.class, String.class,
                String.class, boolean.class, boolean.class})) return null;
        return new ChannelIdentity((String) args[0], (Integer) args[1] / 100000);
    }

    static ChannelIdentity enqueue(Method method, Object[] args) {
        Class<?>[] t = method.getParameterTypes();
        if (!method.getName().equals("enqueueNotificationInternal") || args == null
                || args.length != t.length || t.length < 8
                || t[0] != String.class || t[1] != String.class
                || t[2] != int.class || t[3] != int.class || t[4] != String.class
                || t[5] != int.class || !t[6].getName().equals("android.app.Notification")
                || t[7] != int.class || !(args[0] instanceof String)
                || ((String) args[0]).isEmpty() || !(args[7] instanceof Integer)
                || (Integer) args[7] < 0) return null;
        // args[0] is target pkg; args[1] is the delegating opPkg (e.g. com.xiaomi.xmsf).
        return new ChannelIdentity((String) args[0], (Integer) args[7]);
    }
}
