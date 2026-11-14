package io.mo.mnblocker;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Consumer;

/** Snapshots query results. There is deliberately no mutation or NMS update dependency. */
@android.annotation.TargetApi(26)
final class ChannelQueryObserver {
    static void collect(ChannelIdentity owner, Object result, RuleMatcher rules,
                        boolean matchDescription, Consumer<ChannelRecord> sink) {
        collect(owner, result, rules, matchDescription, sink,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void collect(ChannelIdentity owner, Object value, RuleMatcher rules,
                                boolean matchDescription, Consumer<ChannelRecord> sink,
                                Set<Object> seen) {
        if (value == null || !seen.add(value)) return;
        if (value instanceof NotificationChannel) {
            NotificationChannel channel = (NotificationChannel) value;
            String id = channel.getId();
            CharSequence cs = channel.getName();
            String name = cs == null ? null : cs.toString();
            String desc = channel.getDescription();
            sink.accept(new ChannelRecord(owner.pkg, owner.userId, id, name, desc,
                    channel.getImportance(), rules.shouldBlock(id, name, matchDescription ? desc : null),
                    System.currentTimeMillis()));
        } else if (value instanceof NotificationChannelGroup) {
            collect(owner, ((NotificationChannelGroup) value).getChannels(), rules, matchDescription, sink, seen);
        } else if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) collect(owner, item, rules, matchDescription, sink, seen);
        } else if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) {
                collect(owner, Array.get(value, i), rules, matchDescription, sink, seen);
            }
        } else {
            // ParceledListSlice (and compatible wrappers). Never probe arbitrary fields/setters.
            try {
                Method getList = value.getClass().getMethod("getList");
                getList.setAccessible(true);
                collect(owner, getList.invoke(value), rules, matchDescription, sink, seen);
            } catch (ReflectiveOperationException | SecurityException ignored) {
                // Unknown wrapper: skip observation, preserving the untouched query result.
            }
        }
    }
}
