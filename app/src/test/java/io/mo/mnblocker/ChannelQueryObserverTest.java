package io.mo.mnblocker;

import android.app.NotificationChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ChannelQueryObserverTest {
    private NotificationChannel channel(int importance) {
        NotificationChannel c = mock(NotificationChannel.class);
        when(c.getId()).thenReturn("promotions");
        when(c.getName()).thenReturn("营销消息");
        when(c.getDescription()).thenReturn("活动推荐");
        when(c.getImportance()).thenReturn(importance);
        return c;
    }

    @Test public void matchingQueryRecordsVerdictWithoutMutatingInternalChannel() {
        NotificationChannel c = channel(3);
        List<ChannelRecord> seen = new ArrayList<>();
        ChannelQueryObserver.collect(new ChannelIdentity("pkg", 999), c,
                RuleMatcher.compile(null, null), true, seen::add);
        assertEquals(1, seen.size());
        assertEquals(999, seen.get(0).userId);
        assertEquals(3, seen.get(0).importance);
        assertTrue(seen.get(0).regexMatched);
        verify(c, never()).setImportance(anyInt());
        verify(c, never()).setShowBadge(anyBoolean());
        verify(c, never()).setBypassDnd(anyBoolean());
    }

    @Test public void allowedQueriesDoNotRestoreSilencedChannel() {
        NotificationChannel c = channel(0);
        List<ChannelRecord> seen = new ArrayList<>();
        ChannelQueryObserver.collect(new ChannelIdentity("pkg", 0), c,
                RuleMatcher.compile(null, Collections.singletonList("营销")), false, seen::add);
        assertFalse(seen.get(0).regexMatched);
        assertEquals(0, seen.get(0).importance);
        verify(c, never()).setImportance(anyInt());
    }

    public static class Slice {
        final Object list;
        Slice(Object list) { this.list = list; }
        public Object getList() { return list; }
    }

    @Test public void nestedQueryWrappersAndCyclesAreReadOnly() {
        NotificationChannel c = channel(3);
        List<Object> cycle = new ArrayList<>();
        cycle.add(cycle);
        cycle.add(new Slice(Arrays.asList(c, new Object[]{c})));
        List<ChannelRecord> seen = new ArrayList<>();
        ChannelQueryObserver.collect(new ChannelIdentity("pkg", 0), cycle,
                RuleMatcher.compile(null, null), true, seen::add);
        assertEquals(1, seen.size());
        verify(c, never()).setImportance(anyInt());
    }
}
