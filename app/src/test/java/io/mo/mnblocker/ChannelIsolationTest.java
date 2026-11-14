package io.mo.mnblocker;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ChannelIsolationTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private final ChannelIdentity main = new ChannelIdentity("com.coolapk.market", 0);
    private final ChannelIdentity clone = new ChannelIdentity("com.coolapk.market", 999);
    private final String channel = "mipush|com.coolapk.market|105770";

    @Test public void separateOriginalStatesSurviveReloadAndIndependentRemoval() {
        File f = new File(tmp.getRoot(), "states.json");
        OriginalChannelStateStore store = new OriginalChannelStateStore(f);
        assertTrue(store.saveIfAbsent(main, channel, new OriginalChannelStateStore.OriginalState(3, true, false)));
        assertTrue(store.saveIfAbsent(clone, channel, new OriginalChannelStateStore.OriginalState(2, false, true)));
        store = new OriginalChannelStateStore(f);
        assertEquals(3, store.get(main, channel).importance);
        assertEquals(2, store.get(clone, channel).importance);
        assertTrue(store.remove(main, channel));
        store = new OriginalChannelStateStore(f);
        assertNull(store.get(main, channel));
        assertEquals(2, store.get(clone, channel).importance);
        assertTrue(store.get(clone, channel).bypassDnd);
    }

    @Test public void legacyStateIsNotAppliedToEitherProfileOrOverwritten() throws Exception {
        File f = new File(tmp.getRoot(), "states.json");
        String oldKey = main.pkg + "|" + channel;
        Files.write(f.toPath(), new JSONObject().put(oldKey,
                new JSONObject().put("importance", 4)).toString().getBytes(StandardCharsets.UTF_8));
        OriginalChannelStateStore store = new OriginalChannelStateStore(f);
        assertNull(store.get(main, channel));
        assertNull(store.get(clone, channel));
        assertTrue(store.saveIfAbsent(main, channel, new OriginalChannelStateStore.OriginalState(3, true, false)));
        assertTrue(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)).has(oldKey));
    }

    @Test public void failedPersistenceCannotAuthorizeBlocking() throws Exception {
        File parentFile = tmp.newFile();
        OriginalChannelStateStore store = new OriginalChannelStateStore(new File(parentFile, "states.json"));
        assertFalse(store.saveIfAbsent(main, channel, new OriginalChannelStateStore.OriginalState(3, true, false)));
        assertNull(store.get(main, channel));
    }

    @Test public void malformedStateIsPreserved() throws Exception {
        File f = tmp.newFile();
        Files.write(f.toPath(), "broken json".getBytes(StandardCharsets.UTF_8));
        OriginalChannelStateStore store = new OriginalChannelStateStore(f);
        assertFalse(store.saveIfAbsent(main, channel, new OriginalChannelStateStore.OriginalState(3, true, false)));
        assertEquals("broken json", new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void channelRowsStayIndependentInPersistedStore() {
        File f = new File(tmp.getRoot(), "rows.json");
        RootFreeChannelStore store = new RootFreeChannelStore(f);
        ChannelRecord a = new ChannelRecord(main.pkg, 0, channel, "私信", "", 3, false, 1);
        ChannelRecord b = new ChannelRecord(clone.pkg, 999, channel, "私信", "", 0, true, 2);
        store.record(a);
        store.record(b);
        assertEquals(2, RootFreeChannelStore.readAll(f).size());
        assertNotEquals(a.key(), b.key());
        assertEquals(999, ChannelRecord.fromJson(b.toJson()).userId);
    }

    @Test public void legacyRowsKeepUnknownUserInsteadOfAssumingPrimary() throws Exception {
        ChannelRecord old = ChannelRecord.fromJson(new JSONObject().put("pkg", main.pkg).put("id", channel));
        assertEquals(ChannelIdentity.UNKNOWN_USER, old.userId);
        assertNotEquals(main.key(channel), old.key());
        assertNotEquals(clone.key(channel), old.key());
    }

    @Test public void manualOverridesAreIsolatedAndLegacyKeysNeverBecomePrimary() throws Exception {
        android.content.Context context = mock(android.content.Context.class);
        android.content.SharedPreferences prefs = mock(android.content.SharedPreferences.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getSharedPreferences(RegexConfig.PREFS_NAME, 0)).thenReturn(prefs);
        JSONObject overrides = new JSONObject()
                .put(main.key(channel), true).put(clone.key(channel), false)
                .put(main.pkg + "|legacy-channel", true);
        when(prefs.getString(RegexConfig.KEY_OVERRIDES, "")).thenReturn(overrides.toString());
        RootFreeConfig config = new RootFreeConfig(context);
        assertEquals(Boolean.TRUE, config.overrideFor(main.pkg, 0, channel));
        assertEquals(Boolean.FALSE, config.overrideFor(main.pkg, 999, channel));
        assertNull(config.overrideFor(main.pkg, -1, channel));
        assertNull(config.overrideFor(main.pkg, 0, "legacy-channel"));
        assertNull(config.overrideFor(main.pkg, 999, "legacy-channel"));
        config.close();
    }
}
