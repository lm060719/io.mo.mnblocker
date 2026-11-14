package io.mo.mnblocker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuration UI: home (status + rules), stats, and the detected-channel list,
 * under a bottom tab bar.
 *
 * Built from {@link Ui} (tokens + components), animated with {@link Motion}
 * springs. All logic below the view builders is unchanged by the UI layer.
 */
public final class MainActivity extends Activity
{
    private static final int SORT_BY_APP = 0;
    private static final int SORT_BY_ALPHA = 1;
    private static final String KEY_UI_ONLY_MATCHED = "ui_only_matched";
    private static final String KEY_UI_TAB = "ui_tab";

    private static final int TAB_MAIN = 0;
    private static final int TAB_STATS = 1;
    private static final int TAB_MATCHED = 2;

    /** How far a page travels sideways during a tab switch. */
    private static final int PAGE_SHIFT_DP = Ui.S6;
    /** M3 navigation-bar active indicator. */
    private static final int INDICATOR_W_DP = 64;
    private static final int INDICATOR_H_DP = 32;
    private static final int STATUS_DOT_DP = 8;
    private static final int STAT_TILE_DP = 96;
    private static final int RANK_BADGE_DP = 28;
    /** Ranks drawn with the accent badge. */
    private static final int PODIUM = 3;
    /** Skeleton rows shown until the first channel / stats load lands. */
    private static final int SKELETON_ROWS = 4;
    private static final long JUMP_HIGHLIGHT_MS = 1500;

    private Ui ui;
    private EditText rulesInput;
    private Switch contentEnabledSwitch;
    private EditText contentRulesInput;
    private Switch masterSwitch;
    private Switch matchDescSwitch;
    /**
     * The status readout in the hero card: a state line plus a 2x3 grid of
     * counters. Held as pieces rather than one formatted TextView because the
     * whole point is that the numbers line up in columns — a single string can
     * only fake that with separators.
     */
    private LinearLayout statusBox;
    private SmoothCornerDrawable statusBg;
    private TextView heroModeChip;
    private View statusDot;
    private View statusDivider;
    private TextView statusState;
    private TextView[] statusValues;
    private TextView[] statusLabels;
    private Button clearSafeModeButton;
    // Root-free mode: mode switch + its permission-prompt row + the standing
    // capability-limitation notice (see docs/rootfree-mode-plan.md §8).
    private Button grantAccessButton;
    /** Bounds the listener rebind attempt to one per resume; see refreshStatus(). */
    private boolean rebindNudged;
    /**
     * Mode as of the last render. The picker lives in AboutActivity now, so a
     * change can land while this screen is merely stopped — and the mode decides
     * which stores the channel list and stats are read from, which a plain
     * refresh would not re-route. Compared on resume.
     */
    private String lastKnownMode;
    private TextView listHeader;
    private TextView batchHint;
    private EditText searchQueryInput;
    private Switch onlyMatchedSwitch;
    private LinearLayout listContainer;
    private Ui.Segmented sortSelector;
    private Button multiSelectButton;
    private Button selectAllButton;

    // Bottom-tab pages and controls. Only pageMain is built in onCreate; the other
    // two follow right after the first frame (see pageFor).
    private FrameLayout contentFrame;
    private View pageMain;
    private View pageStats;
    private View pageMatched;
    /** Per tab: whether its page is parked out of sight (see park()). */
    private final boolean[] pageParked = new boolean[TAB_MATCHED + 1];
    private TextView[] tabViews;
    private ImageView[] tabIcons;
    private View[] tabIndicators;
    /** Per-tab selection progress 0..1 driving indicator, icon tint and label. */
    private Motion.Spring[] tabSprings;
    private int currentTab = -1;
    private LinearLayout statsTilesContainer;
    private LinearLayout rankingContainer;

    // Whitelist state is edited on WhitelistActivity; the main screen holds
    // read-only copies (refreshed in onResume) so the matcher / list stay correct
    // and config writes here don't clobber them.
    private String allowRulesText = "";
    private final Set<String> appWhitelist = new LinkedHashSet<>();

    private final List<ChannelRecord> channels = new ArrayList<>();
    /** False until the first channel load lands — the list shows skeletons till then. */
    private boolean channelsLoaded;
    private final Map<String, Boolean> overrides = new HashMap<>();
    // Row / header views survive a re-render: renderList() detaches them and
    // re-attaches the same instances rather than inflating fresh trees. Bounded
    // by DetectedChannelsStore.MAX_ENTRIES.
    private final Map<String, LinearLayout> rowPool = new HashMap<>();
    private final Map<String, View> headerPool = new HashMap<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private int sortMode = SORT_BY_APP;
    private boolean multiSelectMode;
    // Safe-mode state is read via su (the app uid cannot stat /data/system
    // directly), so it is cached here and refreshed on load / manual refresh
    // rather than probed on every refreshStatus() call.
    private boolean safeModeCached;
    /**
     * Whether a hook is actually live in system_server, read alongside safe mode
     * on the same background pass (both live under /data/system and may need su).
     * Null until the first load lands, and in root-free mode, where there is no
     * hook — refreshStatus() treats null as "nothing to claim yet".
     */
    private HookAliveStore.State hookStateCached;

    private final Collator zhCollator = Collator.getInstance(Locale.CHINA);
    private FrameLayout rootFrame;

    // Shared block/allow engine, cached and rebuilt only when the rule text
    // changes so per-row rendering does not recompile regex repeatedly.
    private RuleMatcher cachedMatcher;
    private boolean matcherDirty = true;
    /** Fingerprint of what the stats page last drew, so an unchanged visit is free. */
    private String lastStatsSignature;
    /** channel key -> regex verdict, memoised; cleared whenever the rules change. */
    private final Map<String, Boolean> regexCache = new HashMap<>();
    private String activeJumpPkg;
    private String activeJumpChannelId;
    private int activeJumpUserId = ChannelIdentity.UNKNOWN_USER;
    private long activeJumpTime;

    @Override
    protected void attachBaseContext(Context newBase)
    {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        ui = new Ui(this);

        if (!prefs().contains(RegexConfig.KEY_OPERATING_MODE))
        {
            startActivity(new Intent(this, SetupModeActivity.class));
            finish();
            return;
        }

        if (!rootFree())
        {
            // Repair dir ownership + check root in one background thread.
            // Root-free mode never touches /data/system, so this su spawn (and
            // its "Root not granted" hint, which would be flatly wrong for a
            // root-free user) is skipped entirely.
            new Thread(() -> {
                boolean hasRoot = ShellUtils.fixDirPermissions();
                if (!hasRoot) {
                    runOnUiThread(() -> say(getString(R.string.main_no_root_hint), true));
                }
            }).start();
        }

        rootFrame = new FrameLayout(this);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(ui.p.bg);

        contentFrame = new FrameLayout(this);

        // Only the landing page is built here — the stats and matched pages cost
        // a full view tree each and most launches never open them.
        pageMain = buildMainPage();
        contentFrame.addView(pageMain, matchFrame());

        View tabBar = buildTabBar();
        outer.addView(contentFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.addView(tabBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        rootFrame.addView(outer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);

        // Status-bar inset goes on the page stack, nav-bar inset on the tab bar —
        // so the tab bar's card background is what fills the gesture-pill area.
        SystemBars.edgeToEdge(this, rootFrame, outer, tabBar);

        loadSwitchesAndRules();
        reloadChannelsAndOverrides();

        // Restore the tab last shown (rotation) without playing the transition.
        // Done after the data loads, so a lazily-built page renders fully populated.
        setTabImmediate(savedInstanceState == null
                ? TAB_MAIN
                : savedInstanceState.getInt(KEY_UI_TAB, TAB_MAIN));

        // Build the other two pages once the first frame is on screen. Deferring
        // their construction keeps the cold start cheap, but building one lazily
        // on the tap that opens it put a whole view-tree inflation inside the
        // transition's first frame — which is what made a quick tab tap stutter.
        rootFrame.post(() ->
        {
            pageFor(TAB_STATS);
            // onResume ran before this page existed. Load the numbers now so the
            // first visit opens on them, not on skeletons that swap out mid-glance.
            refreshStats();
            pageFor(TAB_MATCHED);
            handleJumpIntent(getIntent());
        });
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);
        setIntent(intent);
        handleJumpIntent(intent);
    }

    private void handleJumpIntent(Intent intent)
    {
        if (intent == null)
        {
            return;
        }
        String pkg = intent.getStringExtra(SystemUiHook.EXTRA_JUMP_PKG);
        String channelId = intent.getStringExtra(SystemUiHook.EXTRA_JUMP_CHANNEL);
        if (TextUtils.isEmpty(pkg))
        {
            return;
        }

        activeJumpUserId = intent.getIntExtra(SystemUiHook.EXTRA_JUMP_USER, ChannelIdentity.UNKNOWN_USER);
        activeJumpPkg = pkg;
        activeJumpChannelId = channelId;
        activeJumpTime = System.currentTimeMillis();

        setTabImmediate(TAB_MATCHED);

        if (!TextUtils.isEmpty(channelId))
        {
            boolean found = false;
            for (ChannelRecord r : channels)
            {
                if (pkg.equals(r.pkg) && channelId.equals(r.id) && r.userId == activeJumpUserId)
                {
                    found = true;
                    break;
                }
            }
            if (!found && activeJumpUserId >= 0)
            {
                channels.add(new ChannelRecord(pkg, activeJumpUserId, channelId, channelId, "", 3, false, System.currentTimeMillis()));
            }
        }

        if (onlyMatchedSwitch != null && onlyMatchedSwitch.isChecked())
        {
            onlyMatchedSwitch.setChecked(false);
        }

        renderList();
    }

    private void jumpToChannelRow(String pkg, String channelId)
    {
        if (pageMatched == null || listContainer == null)
        {
            return;
        }

        View targetView = null;

        // Priority 1: Exact row match for user + package + channel.
        if (!TextUtils.isEmpty(channelId))
        {
            targetView = rowPool.get(ChannelIdentity.key(pkg, activeJumpUserId, channelId));
            if (targetView == null)
            {
                for (Map.Entry<String, LinearLayout> entry : rowPool.entrySet())
                {
                    if (entry.getKey().equals(ChannelIdentity.key(pkg, activeJumpUserId, channelId)))
                    {
                        targetView = entry.getValue();
                        break;
                    }
                }
            }
        }

        // Priority 2: Any channel row under this package (has toggle switch)
        if (targetView == null && !rowPool.isEmpty())
        {
            for (Map.Entry<String, LinearLayout> entry : rowPool.entrySet())
            {
                if (entry.getKey().startsWith(ChannelIdentity.key(pkg, activeJumpUserId, "")))
                {
                    targetView = entry.getValue();
                    break;
                }
            }
        }

        // Priority 3: Fall back to app group header only if no channel row exists
        if (targetView == null)
        {
            targetView = headerPool.get(pkg);
        }

        if (targetView != null && pageMatched instanceof ScrollView)
        {
            final View viewToScroll = targetView;
            final ScrollView scroll = (ScrollView) pageMatched;
            scroll.post(() ->
            {
                int[] loc = new int[2];
                viewToScroll.getLocationOnScreen(loc);
                int[] scrollLoc = new int[2];
                scroll.getLocationOnScreen(scrollLoc);
                int relativeTop = loc[1] - scrollLoc[1] + scroll.getScrollY();
                scroll.scrollTo(0, Math.max(0, relativeTop - ui.dp(Ui.S5)));

                pop(viewToScroll);
                final android.graphics.drawable.Drawable origBg = viewToScroll.getBackground();
                viewToScroll.setBackground(ui.shape(ui.p.primaryContainer, Ui.R_MEDIUM));
                viewToScroll.postDelayed(() -> viewToScroll.setBackground(origBg),
                        JUMP_HIGHLIGHT_MS);
            });
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out)
    {
        super.onSaveInstanceState(out);
        out.putInt(KEY_UI_TAB, currentTab);
    }

    private FrameLayout.LayoutParams matchFrame()
    {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private View buildMainPage()
    {
        Ui.Page page = ui.page();
        LinearLayout root = page.content;

        ImageView about = ui.iconButton(R.drawable.ic_about, ui.p.onSurfaceVariant,
                getString(R.string.nav_about),
                v -> startActivity(new Intent(this, AboutActivity.class)));
        root.addView(ui.largeTitle(getString(R.string.app_name), null, about));

        root.addView(heroCard());
        root.addView(globalCard());
        root.addView(whitelistEntryCard());
        root.addView(rulesCard());
        root.addView(mainActionRow());
        return page.scroll;
    }

    /** Tappable entry that opens the dedicated whitelist settings screen. */
    private View whitelistEntryCard()
    {
        LinearLayout card = ui.card();
        card.setPadding(card.getPaddingLeft(), ui.dp(Ui.S2),
                card.getPaddingRight(), ui.dp(Ui.S2));
        card.addView(ui.navRow(getString(R.string.whitelist_title),
                getString(R.string.whitelist_entry_desc),
                v -> startActivity(new Intent(this, WhitelistActivity.class))));
        return card;
    }

    private View buildMatchedPage()
    {
        Ui.Page page = ui.page();
        LinearLayout root = page.content;

        ImageView refresh = ui.iconButton(R.drawable.ic_refresh, ui.p.onSurfaceVariant,
                getString(R.string.action_refresh), v ->
                {
                    reloadChannelsAndOverrides();
                    renderList();
                    say(getString(R.string.toast_refreshed), false);
                });
        root.addView(ui.largeTitle(getString(R.string.tab_matched),
                getString(R.string.channel_card_title), refresh));

        root.addView(filterCard());
        root.addView(batchCard());

        listHeader = ui.sectionLabel("");
        root.addView(listHeader);

        listContainer = ui.column();
        root.addView(listContainer, Ui.matchWrap());

        // Keep only the rows near the viewport drawn (see updateListWindow). The
        // scroll view's own resize (IME, multi-window) does not always re-lay out
        // the list, hence the listener on both.
        page.scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> updateListWindow());
        View.OnLayoutChangeListener relayout =
                (v, l, t, r, b, ol, ot, or, ob) -> updateListWindow();
        page.scroll.addOnLayoutChangeListener(relayout);
        listContainer.addOnLayoutChangeListener(relayout);
        return page.scroll;
    }

    /**
     * Draw only the list rows within a screen of the viewport.
     *
     * The channel list holds up to {@link DetectedChannelsStore#MAX_ENTRIES} real
     * row views, and Android records a display list for every VISIBLE child,
     * on screen or not — so opening the tab, or any renderList(), re-recorded all
     * of them on the UI thread (~960 rows took 200–500 ms on a real device). Rows
     * further away are INVISIBLE instead: they keep their size, so the scroll
     * range and every offset (jumpToChannelRow included) are unchanged, but they
     * are not drawn. Runs during layout, i.e. before the frame that draws it.
     */
    private void updateListWindow()
    {
        if (listContainer == null || !(pageMatched instanceof ScrollView))
        {
            return;
        }
        ScrollView scroll = (ScrollView) pageMatched;
        int h = scroll.getHeight();
        if (h == 0)
        {
            return;
        }
        // The list's top in scroll-content coordinates: it sits in the page column.
        int origin = listContainer.getTop() + ((View) listContainer.getParent()).getTop();
        int from = scroll.getScrollY() - origin - h;
        int to = scroll.getScrollY() - origin + 2 * h;
        for (int i = 0, n = listContainer.getChildCount(); i < n; i++)
        {
            View c = listContainer.getChildAt(i);
            if (c.getVisibility() == View.GONE)
            {
                continue;
            }
            int want = c.getBottom() > from && c.getTop() < to ? View.VISIBLE : View.INVISIBLE;
            if (c.getVisibility() != want)
            {
                c.setVisibility(want);
            }
        }
    }

    private View buildStatsPage()
    {
        Ui.Page page = ui.page();
        LinearLayout root = page.content;
        root.addView(ui.largeTitle(getString(R.string.stats_title), null, null));

        // Skeletons until the counters (su-backed in root mode) arrive.
        statsTilesContainer = ui.column();
        statsTilesContainer.addView(tileRow(ui.skeletonBlock(STAT_TILE_DP),
                ui.skeletonBlock(STAT_TILE_DP)));
        statsTilesContainer.addView(tileRow(ui.skeletonBlock(STAT_TILE_DP),
                ui.skeletonBlock(STAT_TILE_DP)));
        root.addView(statsTilesContainer);

        root.addView(ui.sectionLabel(getString(R.string.stats_ranking_title)));
        LinearLayout rankCard = ui.card();
        rankCard.setPadding(ui.dp(Ui.S2), ui.dp(Ui.S2), ui.dp(Ui.S2), ui.dp(Ui.S2));
        rankingContainer = ui.column();
        for (int i = 0; i < SKELETON_ROWS; i++)
        {
            rankingContainer.addView(ui.skeletonRow());
        }
        rankCard.addView(rankingContainer);
        root.addView(rankCard);

        Button clear = ui.button(Ui.BTN_DANGER, getString(R.string.action_clear_count));
        clear.setOnClickListener(v -> onResetContentStats());
        root.addView(clear, Ui.matchWrap());
        return page.scroll;
    }

    private View mainActionRow()
    {
        Button save = ui.button(Ui.BTN_PRIMARY, getString(R.string.action_save_rules));
        save.setOnClickListener(v -> onSaveRules());
        save.setLayoutParams(ui.margins(Ui.matchWrap(), 0, Ui.S1, 0, 0));
        return save;
    }

    /**
     * M3-style navigation bar: each tab is an icon inside a pill indicator over a
     * label. Selection is a 0..1 spring per tab driving the indicator's width and
     * opacity and the icon / label colours together, so a quick re-tap redirects
     * the motion instead of waiting for it.
     */
    private View buildTabBar()
    {
        LinearLayout bar = ui.column();
        bar.setBackgroundColor(ui.p.surface);

        View hairline = new View(this);
        hairline.setBackgroundColor(ui.p.outlineVariant);
        bar.addView(hairline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1) / 2)));

        LinearLayout tabs = ui.row();
        tabs.setPadding(ui.dp(Ui.S2), ui.dp(Ui.S3), ui.dp(Ui.S2), ui.dp(Ui.S3));
        bar.addView(tabs, Ui.matchWrap());

        String[] labels = {getString(R.string.tab_home), getString(R.string.stats_title),
                getString(R.string.tab_matched)};
        int[] icons = {R.drawable.ic_tab_home, R.drawable.ic_tab_stats, R.drawable.ic_tab_matched};
        int n = labels.length;
        tabViews = new TextView[n];
        tabIcons = new ImageView[n];
        tabIndicators = new View[n];
        tabSprings = new Motion.Spring[n];
        for (int i = 0; i < n; i++)
        {
            final int idx = i;

            LinearLayout tab = ui.column();
            tab.setGravity(Gravity.CENTER_HORIZONTAL);
            tab.setMinimumHeight(ui.dp(Ui.TOUCH));
            tab.setClickable(true);
            tab.setContentDescription(labels[i]);
            tab.setOnClickListener(v -> showTab(idx));

            FrameLayout iconWrap = new FrameLayout(this);
            View indicator = new View(this);
            indicator.setBackground(ui.pill(ui.p.primaryContainer));
            iconWrap.addView(indicator, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ImageView icon = ui.icon(icons[i], ui.p.onSurfaceVariant);
            iconWrap.addView(icon, new FrameLayout.LayoutParams(
                    ui.dp(Ui.ICON), ui.dp(Ui.ICON), Gravity.CENTER));
            tab.addView(iconWrap, ui.size(INDICATOR_W_DP, INDICATOR_H_DP));

            TextView t = ui.text(labels[i], Ui.Type.CAPTION, ui.p.onSurfaceVariant);
            t.setTypeface(Ui.typeface(Ui.Weight.MEDIUM));
            t.setGravity(Gravity.CENTER);
            tab.addView(t, ui.margins(Ui.wrapWrap(), 0, Ui.S1, 0, 0));

            // The whole tab takes the touch; only the icon pill dips.
            Motion.pressable(tab, iconWrap, Motion.PRESS_SCALE);

            tabIcons[i] = icon;
            tabViews[i] = t;
            tabIndicators[i] = indicator;
            tabSprings[i] = new Motion.Spring(0f, 0.002f, v -> paintTab(idx, v));
            tabs.addView(tab, Ui.fillRow());
        }
        return bar;
    }

    /** Draw tab {@code i} at selection progress {@code v} (may overshoot 1 briefly). */
    private void paintTab(int i, float v)
    {
        float c = Math.max(0f, Math.min(1f, v));
        tabIndicators[i].setAlpha(c);
        tabIndicators[i].setScaleX(0.5f + 0.5f * Math.max(0f, v));
        tabIcons[i].setImageTintList(ColorStateList.valueOf(
                Palette.blend(ui.p.onSurfaceVariant, ui.p.onPrimaryContainer, c)));
        tabViews[i].setTextColor(Palette.blend(ui.p.onSurfaceVariant, ui.p.onSurface, c));
    }

    /** The page for a tab, or {@code null} if it has not been built yet. */
    private View builtPage(int i)
    {
        if (i == TAB_STATS)
        {
            return pageStats;
        }
        if (i == TAB_MATCHED)
        {
            return pageMatched;
        }
        return pageMain;
    }

    /**
     * The page for a tab, building it on first use. A page built here is
     * populated straight away: by the time any tab can be tapped, onCreate has
     * already loaded the channel / override data it renders from.
     */
    private View pageFor(int i)
    {
        if (i == TAB_STATS && pageStats == null)
        {
            pageStats = buildStatsPage();
            contentFrame.addView(pageStats, matchFrame());
            park(TAB_STATS);
        }
        else if (i == TAB_MATCHED && pageMatched == null)
        {
            pageMatched = buildMatchedPage();
            contentFrame.addView(pageMatched, matchFrame());
            park(TAB_MATCHED);
            renderList();
        }
        return builtPage(i);
    }

    /**
     * Take an inactive page out of sight without taking it out of the render tree.
     *
     * A GONE (or INVISIBLE) page drops out of its parent's display list, and HWUI
     * then frees the display lists of its whole subtree — so every return to a
     * tab re-recorded the entire page on the UI thread, inside the transition's
     * first frame. A parked page stays VISIBLE and keeps its recordings: it is
     * drawn at alpha 0, which HWUI skips, and shifted off-screen, so it takes no
     * touches. Hidden from accessibility and from focus, as GONE was.
     */
    private void park(int k)
    {
        View p = builtPage(k);
        pageParked[k] = true;
        p.setLayerType(View.LAYER_TYPE_NONE, null);
        // Block first: clearing focus re-focuses the first focusable in the window,
        // which must not be back inside this page.
        ((ViewGroup) p).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (p.hasFocus())
        {
            p.clearFocus();
        }
        p.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        Motion.set(p, View.ALPHA, 0f);
        // Past the frame in either orientation.
        Motion.set(p, View.TRANSLATION_X, getResources().getDisplayMetrics().widthPixels
                + getResources().getDisplayMetrics().heightPixels);
    }

    /** Undo park()'s focus / accessibility gating; the caller places the page. */
    private void unpark(int k)
    {
        View p = builtPage(k);
        pageParked[k] = false;
        // ScrollView's own default — every page is a Ui.page() scroll view.
        ((ViewGroup) p).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        p.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    /** Select a tab with no transition — first layout, rotation restore, jumps. */
    private void setTabImmediate(int i)
    {
        pageFor(i);
        for (int k = TAB_MAIN; k <= TAB_MATCHED; k++)
        {
            View p = builtPage(k);
            if (p == null)
            {
                continue;
            }
            // set() also stops any spring mid-flight and drops its end action, so
            // an interrupted fade-out can no longer park the page afterwards.
            if (k == i)
            {
                unpark(k);
                Motion.set(p, View.ALPHA, 1f);
                Motion.set(p, View.TRANSLATION_X, 0f);
                p.setLayerType(View.LAYER_TYPE_NONE, null);
            }
            else
            {
                park(k);
            }
        }

        applyTabColors(i, false);
        currentTab = i;
        onTabSettled(i); // no transition to stay out of the way of
    }

    /**
     * Switch tabs with a spring crossfade + slide. Every page property is driven
     * by a per-view spring, so tapping another tab mid-transition retargets the
     * motion from where it is (velocity included) rather than snapping or
     * queueing — the old tween version had to cancel and reset pages instead.
     */
    private void showTab(int i)
    {
        if (i == currentTab)
        {
            return; // the press dip already acknowledged the touch
        }
        Haptics.tick(tabViews[i]);

        View from = builtPage(currentTab);
        pageFor(i);
        if (from == null || Motion.reduced(this))
        {
            setTabImmediate(i);
            return;
        }

        // Travel direction follows the tab order, so the pages feel laid out
        // side by side rather than stacked.
        final float dx = ui.dp(PAGE_SHIFT_DP) * (i > currentTab ? 1 : -1);
        final int target = i;

        for (int k = TAB_MAIN; k <= TAB_MATCHED; k++)
        {
            final View p = builtPage(k);
            if (p == null)
            {
                continue;
            }
            final int page = k;
            if (k == i)
            {
                if (pageParked[k])
                {
                    unpark(k);
                    Motion.set(p, View.ALPHA, 0f);
                    Motion.set(p, View.TRANSLATION_X, dx);
                }
                // Hardware layer while moving: fading a whole ScrollView subtree
                // without one re-renders every child each frame (7 ms -> 27 ms p90).
                p.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                Motion.animate(p, View.TRANSLATION_X, 0f, Motion.STANDARD);
                Motion.animate(p, View.ALPHA, 1f, Motion.STANDARD, () ->
                {
                    p.setLayerType(View.LAYER_TYPE_NONE, null);
                    onTabSettled(target);
                });
            }
            else if (!pageParked[k])
            {
                p.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                Motion.animate(p, View.TRANSLATION_X, -dx, Motion.STANDARD);
                Motion.animate(p, View.ALPHA, 0f, Motion.STANDARD, () -> park(page));
            }
        }

        applyTabColors(i, true);
        currentTab = i;
    }

    /**
     * Work deferred until a tab transition has finished.
     *
     * Refreshing the stats page rebuilds its tiles and the whole app ranking. Doing
     * that while the pages are still animating meant every tap paid for a view
     * rebuild during the transition's own frames. If a transition is redirected by
     * another tap this never runs — but the tap that finally settles will run it.
     */
    private void onTabSettled(int i)
    {
        if (i == TAB_STATS)
        {
            refreshStats();
        }
    }

    /** Move the selection to tab {@code i}, springing when animating. */
    private void applyTabColors(int i, boolean animate)
    {
        if (tabSprings == null)
        {
            return;
        }
        boolean motion = animate && !Motion.reduced(this);
        for (int k = 0; k < tabSprings.length; k++)
        {
            float target = k == i ? 1f : 0f;
            ((View) tabViews[k].getParent()).setSelected(k == i);
            if (motion)
            {
                tabSprings[k].animateTo(target, Motion.MICRO, null);
            }
            else
            {
                tabSprings[k].snapTo(target);
            }
        }
    }

    /**
     * The hero: mode badge, one-line pitch, and the live status readout. Tonal
     * (primary container), so it leads the page without shouting in dark mode
     * the way a filled primary block would.
     */
    private View heroCard()
    {
        LinearLayout card = ui.card(ui.p.primaryContainer);
        card.setPadding(ui.dp(Ui.S5), ui.dp(Ui.S5), ui.dp(Ui.S5), ui.dp(Ui.S5));

        heroModeChip = ui.chip(modeLabel(), ui.p.onPrimary, ui.p.primary);
        card.addView(heroModeChip, Ui.wrapWrap());

        card.addView(ui.text(getString(R.string.hero_desc), Ui.Type.BODY_SMALL,
                ui.p.onPrimaryContainer), ui.margins(Ui.matchWrap(), 0, Ui.S3, 0, 0));

        card.addView(statusBox(), ui.margins(Ui.matchWrap(), 0, Ui.S4, 0, 0));

        clearSafeModeButton = ui.button(Ui.BTN_PRIMARY, getString(R.string.safe_mode_close_button));
        clearSafeModeButton.setVisibility(View.GONE);
        clearSafeModeButton.setOnClickListener(v -> onClearSafeMode());
        card.addView(clearSafeModeButton, ui.margins(Ui.matchWrap(), 0, Ui.S3, 0, 0));

        return card;
    }

    private String modeLabel()
    {
        return getString(rootFree() ? R.string.setup_rootfree_title : R.string.setup_root_title);
    }

    private void onClearSafeMode()
    {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.safe_mode_close_button))
                .setMessage(getString(R.string.safe_mode_dialog_message))
                .setPositiveButton(getString(R.string.safe_mode_confirm), (d, w) -> new Thread(() ->
                {
                    boolean ok = ShellUtils.clearSafeMode();
                    runOnUiThread(() ->
                    {
                        if (!ok)
                        {
                            say(getString(R.string.safe_mode_clear_failed), true);
                            return;
                        }
                        safeModeCached = false;
                        refreshStatus();
                        Haptics.confirm(contentFrame);
                        say(getString(R.string.safe_mode_cleared_toast), false);
                    });
                }).start())
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private View globalCard()
    {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(getString(R.string.global_switch_title), null));

        Ui.SwitchRow master = ui.switchRow(getString(R.string.switch_master_enable), null);
        masterSwitch = master.sw;
        masterSwitch.setOnCheckedChangeListener((b, v) -> persistSwitches());
        card.addView(master.view);

        card.addView(ui.divider());

        Ui.SwitchRow desc = ui.switchRow(getString(R.string.switch_match_desc_title),
                getString(R.string.switch_match_desc_sub));
        matchDescSwitch = desc.sw;
        matchDescSwitch.setOnCheckedChangeListener((b, v) -> persistSwitches());
        card.addView(desc.view);

        // The mode picker itself lives in AboutActivity — it is a one-off setup
        // choice, not a daily toggle. Only the grant button stays here, because
        // it is operational: it pairs with the status banner above and is what
        // the user needs when root-free mode is on but access is missing.
        grantAccessButton = ui.button(Ui.BTN_TONAL, getString(R.string.rootfree_grant_access_button));
        grantAccessButton.setOnClickListener(v -> NotificationAccessUtils.openListenerSettings(this));
        card.addView(grantAccessButton, ui.margins(Ui.matchWrap(), 0, Ui.S2, 0, 0));

        return card;
    }

    private View rulesCard()
    {
        LinearLayout card = ui.card();
        card.addView(ui.sectionTitle(getString(R.string.rules_card_title), null));

        rulesInput = ui.field(getString(R.string.rules_hint), 5, true);
        // matcher() no longer re-reads this field on every call, so the edits have
        // to announce themselves.
        rulesInput.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s)
            {
                invalidateMatcher();
            }
        });
        card.addView(rulesInput, Ui.matchWrap());

        View divider = ui.divider();
        ((LinearLayout.LayoutParams) divider.getLayoutParams())
                .setMargins(0, ui.dp(Ui.S4), 0, ui.dp(Ui.S4));
        card.addView(divider);

        card.addView(ui.text(getString(R.string.content_section_title),
                Ui.Type.TITLE_SMALL, ui.p.onSurface));
        // Root-free needs no reboot to load a hook — it has none.
        card.addView(ui.text(getString(rootFree()
                        ? R.string.content_section_desc_rootfree
                        : R.string.content_section_desc),
                Ui.Type.BODY_SMALL, ui.p.onSurfaceVariant),
                ui.margins(Ui.matchWrap(), 0, Ui.S1, 0, 0));

        Ui.SwitchRow content = ui.switchRow(getString(R.string.switch_content_enable_title),
                getString(R.string.switch_content_enable_sub));
        contentEnabledSwitch = content.sw;
        contentEnabledSwitch.setOnCheckedChangeListener((b, v) -> persistSwitches());
        card.addView(content.view, ui.margins(Ui.matchWrap(), 0, Ui.S1, 0, 0));

        contentRulesInput = ui.field(getString(R.string.content_rules_hint), 3, true);
        card.addView(contentRulesInput, ui.margins(Ui.matchWrap(), 0, Ui.S2, 0, 0));

        return card;
    }

    /** Search, the only-matched filter and the sort order for the channel list. */
    private View filterCard()
    {
        LinearLayout card = ui.card();

        searchQueryInput = ui.searchField(getString(R.string.search_channel_hint));
        searchQueryInput.addTextChangedListener(new TextWatcher()
        {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s)
            {
                selected.clear();
                renderList();
            }
        });
        card.addView(searchQueryInput, Ui.matchWrap());

        Ui.SwitchRow only = ui.switchRow(getString(R.string.switch_only_matched_title),
                getString(rootFree()
                        ? R.string.switch_only_matched_sub_rootfree
                        : R.string.switch_only_matched_sub));
        onlyMatchedSwitch = only.sw;
        // This page is built lazily, i.e. after loadSwitchesAndRules() has run, so
        // it restores its own persisted state rather than being fed it from there.
        onlyMatchedSwitch.setChecked(prefs().getBoolean(KEY_UI_ONLY_MATCHED, true));
        onlyMatchedSwitch.setOnCheckedChangeListener((b, v) ->
        {
            prefs().edit().putBoolean(KEY_UI_ONLY_MATCHED, v).apply();
            selected.clear();
            renderList();
        });
        card.addView(only.view, ui.margins(Ui.matchWrap(), 0, Ui.S1, 0, Ui.S1));

        sortSelector = ui.segmented(
                new String[]{getString(R.string.sort_by_app), getString(R.string.sort_by_alpha)},
                sortMode == SORT_BY_APP ? 0 : 1,
                idx ->
                {
                    sortMode = idx == 0 ? SORT_BY_APP : SORT_BY_ALPHA;
                    renderList();
                });
        card.addView(sortSelector.view, Ui.matchWrap());

        return card;
    }

    /** Multi-select and the batch actions, which apply to the ticked or the listed rows. */
    private View batchCard()
    {
        LinearLayout card = ui.card();

        LinearLayout row1 = ui.row();
        multiSelectButton = ui.button(Ui.BTN_TONAL, getString(R.string.action_multi_select));
        multiSelectButton.setOnClickListener(v ->
        {
            multiSelectMode = !multiSelectMode;
            ui.styleButton(multiSelectButton, multiSelectMode ? Ui.BTN_PRIMARY : Ui.BTN_TONAL);
            Haptics.toggle(v, multiSelectMode);
            selected.clear();
            renderList();
        });
        row1.addView(multiSelectButton, gapRight());

        selectAllButton = ui.button(Ui.BTN_OUTLINE, getString(R.string.action_select_all_clear));
        selectAllButton.setOnClickListener(v ->
        {
            List<ChannelRecord> visible = visibleChannels();
            if (selected.size() < visible.size())
            {
                for (ChannelRecord r : visible)
                {
                    selected.add(r.key());
                }
            }
            else
            {
                selected.clear();
            }
            renderList();
        });
        row1.addView(selectAllButton, Ui.fillRow());
        card.addView(row1, Ui.matchWrap());

        LinearLayout row2 = ui.row();
        Button blockAll = ui.button(Ui.BTN_DANGER, getString(R.string.action_block_all));
        blockAll.setOnClickListener(v -> batchSet(true));
        row2.addView(blockAll, gapRight());

        Button allowAll = ui.button(Ui.BTN_SUCCESS, getString(R.string.action_allow_all));
        allowAll.setOnClickListener(v -> batchSet(false));
        row2.addView(allowAll, Ui.fillRow());
        card.addView(row2, ui.margins(Ui.matchWrap(), 0, Ui.S2, 0, 0));

        Button resetDefault = ui.button(Ui.BTN_OUTLINE, getString(R.string.action_reset_default));
        resetDefault.setOnClickListener(v -> batchClearOverrides());
        card.addView(resetDefault, ui.margins(Ui.matchWrap(), 0, Ui.S2, 0, 0));

        batchHint = ui.text("", Ui.Type.CAPTION, ui.p.onSurfaceVariant);
        card.addView(batchHint, ui.margins(Ui.matchWrap(), Ui.S1, Ui.S3, Ui.S1, 0));

        return card;
    }

    /** Equal-width slot in a button row, with the gap on its right. */
    private LinearLayout.LayoutParams gapRight()
    {
        return ui.margins(Ui.fillRow(), 0, 0, Ui.S2, 0);
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences prefs()
    {
        try
        {
            return getSharedPreferences(RegexConfig.PREFS_NAME, Context.MODE_WORLD_READABLE);
        }
        catch (SecurityException e)
        {
            return getSharedPreferences(RegexConfig.PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    /**
     * Whether the root-free (NotificationListenerService) path is active.
     * A pure pref read — never spawns su — so it is safe to call from onCreate
     * and any UI thread code path without background dispatch.
     */
    private boolean rootFree()
    {
        return RegexConfig.MODE_ROOTFREE.equals(
                prefs().getString(RegexConfig.KEY_OPERATING_MODE, RegexConfig.MODE_ROOT));
    }

    /**
     * The config bridge may need su, so the disk read is backgrounded — except
     * in root-free mode, where mnblocker_prefs IS the source of truth and a
     * disk read would only spend 100ms+ on an su spawn to confirm what the
     * pref already says (see docs/rootfree-mode-plan.md §2.4, §8).
     */
    private void loadSwitchesAndRules()
    {
        SharedPreferences sp = prefs();
        final boolean prefMaster = sp.getBoolean(RegexConfig.KEY_MASTER_ENABLED, true);
        final boolean prefMatchDesc = sp.getBoolean(RegexConfig.KEY_MATCH_DESC, true);
        final String prefRules = sp.getString(RegexConfig.KEY_RULES, "");
        final boolean prefContentOn = sp.getBoolean(RegexConfig.KEY_CONTENT_ENABLED, false);
        final String prefContentRules = sp.getString(RegexConfig.KEY_CONTENT_RULES, "");

        if (rootFree())
        {
            setCheckedSilently(masterSwitch, prefMaster);
            setCheckedSilently(matchDescSwitch, prefMatchDesc);
            setCheckedSilently(contentEnabledSwitch, prefContentOn);
            rulesInput.setText(prefRules);
            contentRulesInput.setText(prefContentRules);

            invalidateMatcher(); // rule text just changed
            loadWhitelistState();
            refreshStatus();
            return;
        }

        Bg.load(this, ConfigFileStore::readForApp, disk ->
        {
            setCheckedSilently(masterSwitch,
                    disk.hasValue ? disk.masterEnabled : prefMaster);
            setCheckedSilently(matchDescSwitch,
                    disk.hasValue ? disk.matchDescription : prefMatchDesc);
            setCheckedSilently(contentEnabledSwitch,
                    disk.hasValue ? disk.contentEnabled : prefContentOn);
            rulesInput.setText(disk.hasValue ? disk.rules : prefRules);
            contentRulesInput.setText(disk.hasValue ? disk.contentRules : prefContentRules);

            invalidateMatcher(); // rule text just changed
            loadWhitelistState();
            refreshStatus();
        });
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        rebindNudged = false;
        // The whitelist screen may have changed allow rules / app whitelist.
        loadWhitelistState();

        // The About screen may have switched modes, which re-points the channel
        // list and stats at the other mode's stores entirely.
        String mode = prefs().getString(RegexConfig.KEY_OPERATING_MODE, RegexConfig.MODE_ROOT);
        if (lastKnownMode != null && !lastKnownMode.equals(mode))
        {
            loadSwitchesAndRules();
            reloadChannelsAndOverrides();
        }
        lastKnownMode = mode;

        refreshStatus();
        renderList();
        refreshStats();
    }

    /**
     * Refresh the read-only whitelist copies the main screen relies on.
     *
     * Root mode: the config bridge may only be readable via su, so the read is
     * backgrounded and the list re-rendered once the values arrive.
     * Root-free mode: mnblocker_prefs is already the source of truth (same
     * uid as the listener), so this applies synchronously — no su, no wait.
     */
    private void loadWhitelistState()
    {
        SharedPreferences sp = prefs();
        final String prefAllow = sp.getString(RegexConfig.KEY_ALLOW_RULES, "");
        final String prefApps = sp.getString(RegexConfig.KEY_APP_WHITELIST, "");

        if (rootFree())
        {
            applyWhitelistState(prefAllow, prefApps);
            return;
        }

        Bg.load(this, ConfigFileStore::readForApp, disk ->
                applyWhitelistState(
                        disk.hasValue ? disk.allowRules : prefAllow,
                        disk.hasValue ? disk.appWhitelist : prefApps));
    }

    private void applyWhitelistState(String allow, String appList)
    {
        allowRulesText = allow == null ? "" : allow;
        appWhitelist.clear();
        if (!TextUtils.isEmpty(appList))
        {
            for (String line : appList.split("\\r?\\n"))
            {
                String t = line.trim();
                if (!t.isEmpty())
                {
                    appWhitelist.add(t);
                }
            }
        }
        invalidateMatcher(); // allow rules may have changed
        renderList();
        refreshStats();
    }

    private void persistSwitches()
    {
        // matchDescSwitch decides which fields candidates() tests, so any switch
        // change invalidates the cached verdicts. (This is also the listener that
        // setCheckedSilently reinstalls, so it must live here, not at the call site.)
        invalidateMatcher();
        prefs().edit()
                .putBoolean(RegexConfig.KEY_MASTER_ENABLED, masterSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_MATCH_DESC, matchDescSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_CONTENT_ENABLED,
                        contentEnabledSwitch != null && contentEnabledSwitch.isChecked())
                .apply();
        if (!rootFree())
        {
            persistConfigFile(false, null);
        }
        refreshStatus();
    }

    private void onSaveRules()
    {
        String rules = rulesInput.getText().toString();
        String contentRules = contentRulesInput.getText().toString();
        String bad = firstInvalidRegex(rules);
        EditText badField = rulesInput;
        if (bad == null)
        {
            bad = firstInvalidRegex(contentRules);
            badField = contentRulesInput;
        }
        if (bad != null)
        {
            // Point at the field that is wrong, not just at a toast about it.
            ui.markError(badField);
            say(getString(R.string.toast_regex_invalid_fmt, bad), true);
            return;
        }

        // apply(), not commit(): commit() writes the XML synchronously on the
        // main thread. The bridge write below is the one that can actually fail
        // (it needs root), so it alone decides the toast.
        prefs().edit()
                .putBoolean(RegexConfig.KEY_MASTER_ENABLED, masterSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_MATCH_DESC, matchDescSwitch.isChecked())
                .putBoolean(RegexConfig.KEY_CONTENT_ENABLED, contentEnabledSwitch.isChecked())
                .putString(RegexConfig.KEY_RULES, rules)
                .putString(RegexConfig.KEY_CONTENT_RULES, contentRules)
                .apply();

        if (rootFree())
        {
            // The listener reads mnblocker_prefs directly (same uid, no su
            // bridge), so there is no hook to sync to and nothing that can fail
            // — hence plain success, and NOT the root path's "synced to Hook".
            Haptics.confirm(contentFrame);
            say(getString(R.string.toast_saved_rootfree), false);
        }
        else
        {
            persistConfigFile(false, ok ->
            {
                if (ok)
                {
                    Haptics.confirm(contentFrame);
                }
                say(ok ? getString(R.string.toast_saved_synced)
                        : getString(R.string.toast_saved_sync_failed), !ok);
            });
        }

        refreshStatus();
        renderList();
        refreshStats();
    }

    /**
     * Reload the detected channels, the per-channel overrides and the safe-mode
     * flag.
     *
     * Root mode: all three live under /data/system and may need su, so they
     * are read together on one background pass and applied when they land.
     * Root-free mode: channels come from the app-private RootFreeChannelStore,
     * overrides from mnblocker_prefs directly, and safe mode (a root-hook-only
     * concept — see SafetyManager) is always false. Still dispatched through
     * Bg for a uniform code path, even though none of this needs su.
     */
    private void reloadChannelsAndOverrides()
    {
        final String prefOverrides = prefs().getString(RegexConfig.KEY_OVERRIDES, "");
        final boolean rf = rootFree();

        Bg.load(this, () ->
        {
            ChannelSnapshot s = new ChannelSnapshot();
            if (rf)
            {
                s.channels = RootFreeChannelStore.readAll(MainActivity.this);
                s.overridesJson = prefOverrides;
                s.safeMode = false;
                // No hook to be alive in this mode; the listener reports itself.
                s.hookState = null;
            }
            else
            {
                s.channels = DetectedChannelsStore.readAllFromDiskForApp();
                ConfigFileStore.ConfigSnapshot disk = ConfigFileStore.readForApp();
                s.overridesJson = disk.hasValue ? disk.overrides : prefOverrides;
                s.safeMode = ShellUtils.isSafeModeTripped();
                s.hookState = HookAliveStore.readForApp();
            }
            return s;
        }, s ->
        {
            channels.clear();
            // Legacy rows have no trustworthy owner; re-observe instead of assigning user 0.
            for (ChannelRecord record : s.channels) {
                if (record.userId >= 0) channels.add(record);
            }
            channelsLoaded = true;
            regexCache.clear(); // the records themselves may have changed

            overrides.clear();
            if (!TextUtils.isEmpty(s.overridesJson))
            {
                try
                {
                    JSONObject o = new JSONObject(s.overridesJson);
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); )
                    {
                        String k = it.next();
                        overrides.put(k, o.optBoolean(k, false));
                    }
                }
                catch (Throwable ignored)
                {
                }
            }

            safeModeCached = s.safeMode;
            hookStateCached = s.hookState;
            selected.retainAll(keySet());
            refreshStatus();

            if (!TextUtils.isEmpty(activeJumpPkg) && (System.currentTimeMillis() - activeJumpTime < 8000))
            {
                if (!TextUtils.isEmpty(activeJumpChannelId))
                {
                    boolean found = false;
                    for (ChannelRecord r : channels)
                    {
                        if (activeJumpPkg.equals(r.pkg) && activeJumpChannelId.equals(r.id) && r.userId == activeJumpUserId)
                        {
                            found = true;
                            break;
                        }
                    }
                    if (!found && activeJumpUserId >= 0)
                    {
                        channels.add(new ChannelRecord(activeJumpPkg, activeJumpUserId, activeJumpChannelId, activeJumpChannelId, "", 3, false, System.currentTimeMillis()));
                    }
                }

                if (onlyMatchedSwitch != null && onlyMatchedSwitch.isChecked())
                {
                    onlyMatchedSwitch.setChecked(false);
                }
            }

            renderList();
            refreshStats();
        });
    }

    /** What {@link #reloadChannelsAndOverrides} gathers off the main thread. */
    private static final class ChannelSnapshot
    {
        List<ChannelRecord> channels;
        String overridesJson;
        boolean safeMode;
        HookAliveStore.State hookState;
    }

    /**
     * Rebuild the stats page: tiles + the combined per-app block ranking.
     *
     * The counters live under /data/system and may only be reachable via su, so
     * they are fetched off the main thread — this runs on every visit to the
     * stats tab, and a blocking read here would stall the tab transition.
     */
    private void refreshStats()
    {
        if (statsTilesContainer == null || rankingContainer == null)
        {
            return;
        }
        if (rootFree())
        {
            Bg.load(this, () -> RootFreeStatsStore.readFromDisk(this), this::renderStats);
        }
        else
        {
            Bg.load(this, ContentStatsStore::readForApp, this::renderStats);
        }
    }

    private void renderStats(ContentStatsStore.Snapshot cs)
    {
        if (statsTilesContainer == null || rankingContainer == null)
        {
            return;
        }

        Map<String, Integer> blockedByApp = blockedChannelsByApp();
        int totalBlockedChannels = 0;
        for (int v : blockedByApp.values())
        {
            totalBlockedChannels += v;
        }

        // Tearing down and rebuilding the tiles and the whole app ranking costs a
        // dropped frame, and this runs on every visit to the tab — but the numbers
        // usually have not moved since the last one. Rebuild only when they have.
        String signature = cs.count + "|" + cs.lastBlocked + "|" + cs.perApp
                + "|" + blockedByApp + "|" + rulesText();
        if (signature.equals(lastStatsSignature))
        {
            return;
        }
        lastStatsSignature = signature;
        Set<String> apps = new LinkedHashSet<>(blockedByApp.keySet());
        apps.addAll(cs.perApp.keySet());
        int ruleCount = countRules(rulesInput == null ? "" : rulesInput.getText().toString()) + 1;

        String when = cs.lastBlocked > 0
                ? DateUtils.getRelativeTimeSpanString(cs.lastBlocked,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                : "—";

        statsTilesContainer.removeAllViews();
        // Bento: the headline counter leads in the accent tile, the rest stay neutral.
        statsTilesContainer.addView(tileRow(
                statTile(getString(R.string.stat_tile_content_blocks), String.valueOf(cs.count), true),
                statTile(getString(R.string.stat_tile_channel_blocks),
                        String.valueOf(totalBlockedChannels), false)));
        statsTilesContainer.addView(tileRow(
                statTile(getString(R.string.stat_tile_apps), String.valueOf(apps.size()), false),
                statTile(getString(R.string.stat_tile_rules), String.valueOf(ruleCount), false)));

        statsTilesContainer.addView(ui.text(getString(R.string.stats_last_blocked_fmt, when),
                Ui.Type.CAPTION, ui.p.onSurfaceVariant),
                ui.margins(Ui.matchWrap(), Ui.S1, 0, Ui.S1, Ui.S1));

        // ---- combined ranking (channel blocks + content blocks), desc ----
        final Map<String, Integer> chan = blockedByApp;
        final Map<String, Long> cont = cs.perApp;
        List<String> rank = new ArrayList<>();
        for (String pkg : apps)
        {
            if (score(chan, cont, pkg) > 0)
            {
                rank.add(pkg);
            }
        }
        Collections.sort(rank, (a, b) ->
        {
            long sa = score(chan, cont, a);
            long sb = score(chan, cont, b);
            if (sa != sb)
            {
                return Long.compare(sb, sa);
            }
            return appLabel(a).compareToIgnoreCase(appLabel(b));
        });

        rankingContainer.removeAllViews();
        if (rank.isEmpty())
        {
            rankingContainer.addView(ui.emptyState(R.drawable.ic_inbox,
                    getString(R.string.stats_empty), 0));
            return;
        }
        int i = 1;
        for (String pkg : rank)
        {
            int ch = chan.containsKey(pkg) ? chan.get(pkg) : 0;
            long ct = cont.containsKey(pkg) ? cont.get(pkg) : 0L;
            rankingContainer.addView(rankingRow(i++, pkg, ch, ct));
        }
    }

    private static long score(Map<String, Integer> chan, Map<String, Long> cont, String pkg)
    {
        long c = chan.containsKey(pkg) ? chan.get(pkg) : 0;
        long t = cont.containsKey(pkg) ? cont.get(pkg) : 0L;
        return c + t;
    }

    private Map<String, Integer> blockedChannelsByApp()
    {
        Map<String, Integer> m = new HashMap<>();
        for (ChannelRecord r : channels)
        {
            if (effectiveBlocked(r))
            {
                Integer prev = m.get(r.pkg);
                m.put(r.pkg, (prev == null ? 0 : prev) + 1);
            }
        }
        return m;
    }

    private View tileRow(View a, View b)
    {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.TOP);
        row.setLayoutParams(ui.margins(Ui.matchWrap(), 0, 0, 0, Ui.S3));
        row.addView(a, ui.margins(Ui.fillRow(), 0, 0, Ui.S3, 0));
        row.addView(b, Ui.fillRow());
        return row;
    }

    private View statTile(String label, String value, boolean accent)
    {
        LinearLayout t = ui.column();
        t.setMinimumHeight(ui.dp(STAT_TILE_DP));
        t.setPadding(ui.dp(Ui.S4), ui.dp(Ui.S4), ui.dp(Ui.S4), ui.dp(Ui.S4));
        t.setBackground(ui.shape(accent ? ui.p.primaryContainer : ui.p.surface, Ui.R_LARGE));

        t.addView(ui.text(label, Ui.Type.CAPTION,
                accent ? ui.p.onPrimaryContainer : ui.p.onSurfaceVariant));
        t.addView(ui.text(value, Ui.Type.NUMBER,
                accent ? ui.p.onPrimaryContainer : ui.p.primary),
                ui.margins(Ui.matchWrap(), 0, Ui.S2, 0, 0));
        return t;
    }

    private View rankingRow(int rank, String pkg, int channelBlocks, long contentBlocks)
    {
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(Ui.ROW_MIN));
        row.setPadding(ui.dp(Ui.S2), ui.dp(Ui.S2), ui.dp(Ui.S1), ui.dp(Ui.S2));
        row.setLayoutParams(Ui.matchWrap());

        boolean podium = rank <= PODIUM;
        TextView badge = ui.text(String.valueOf(rank), Ui.Type.LABEL,
                podium ? ui.p.onPrimaryContainer : ui.p.onSurfaceVariant);
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(ui.dp(RANK_BADGE_DP));
        badge.setMinHeight(ui.dp(RANK_BADGE_DP));
        badge.setBackground(ui.circle(podium ? ui.p.primaryContainer : ui.p.surfaceVariant));
        row.addView(badge, ui.margins(Ui.wrapWrap(), 0, 0, Ui.S3, 0));

        ImageView iconView = new ImageView(this);
        iconView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        AppIconCache.bindIcon(this, iconView, pkg);
        row.addView(iconView, ui.margins(ui.size(Ui.APP_ICON, Ui.APP_ICON), 0, 0, Ui.S3, 0));

        LinearLayout text = ui.column();
        TextView name = ui.text(appLabel(pkg), Ui.Type.TITLE_SMALL, ui.p.onSurface);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(name);

        TextView meta = ui.text(getString(R.string.ranking_meta_fmt, channelBlocks, contentBlocks, pkg),
                Ui.Type.CAPTION, ui.p.onSurfaceVariant);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(meta, ui.margins(Ui.matchWrap(), 0, Ui.S_HALF, 0, 0));
        row.addView(text, Ui.fillRow());

        row.addView(ui.text(String.valueOf(channelBlocks + contentBlocks),
                Ui.Type.NUMBER_SMALL, ui.p.danger), ui.margins(Ui.wrapWrap(), Ui.S2, 0, 0, 0));

        // Tap the row to see this app's dropped content notifications in detail.
        row.addView(ui.icon(R.drawable.ic_chevron_right, ui.p.onSurfaceVariant),
                ui.margins(ui.size(Ui.ICON, Ui.ICON), Ui.S1, 0, 0, 0));

        final String tapPkg = pkg;
        ui.tappable(row, ui.p.surface, Ui.R_MEDIUM, v ->
        {
            Intent it = new Intent(this, BlockedNotificationsActivity.class);
            it.putExtra(BlockedNotificationsActivity.EXTRA_PKG, tapPkg);
            startActivity(it);
        });
        return row;
    }

    private String appLabel(String pkg)
    {
        return AppIconCache.label(this, pkg);
    }

    private void onResetContentStats()
    {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.confirm_reset_stats_title))
                .setMessage(getString(R.string.confirm_reset_stats_msg))
                .setPositiveButton(getString(R.string.action_clear), (d, w) -> new Thread(() ->
                {
                    boolean ok;
                    if (rootFree())
                    {
                        ok = RootFreeStatsStore.reset(this);
                        // Keep the per-app detail log consistent with the zeroed count.
                        RootFreeBlockLogStore.reset(this);
                    }
                    else
                    {
                        ok = ContentStatsStore.resetFromApp();
                        // Keep the per-app detail log consistent with the zeroed count.
                        ContentBlockLogStore.resetFromApp();
                    }
                    final boolean okF = ok;
                    runOnUiThread(() ->
                    {
                        if (okF)
                        {
                            Haptics.confirm(contentFrame);
                        }
                        say(okF ? getString(R.string.toast_cleared)
                                : getString(R.string.toast_clear_failed), !okF);
                        refreshStats();
                    });
                }).start())
                .setNegativeButton(getString(R.string.action_cancel), null)
                .show();
    }

    private void persistOverrides()
    {
        JSONObject o = new JSONObject();
        try
        {
            for (Map.Entry<String, Boolean> e : overrides.entrySet())
            {
                o.put(e.getKey(), e.getValue());
            }
        }
        catch (Throwable ignored)
        {
        }
        prefs().edit().putString(RegexConfig.KEY_OVERRIDES, o.toString()).apply();
        if (!rootFree())
        {
            persistConfigFile(false, null);
        }
    }

    /**
     * Push the whole config to the root-writable JSON bridge.
     *
     * The write goes through su, so the UI state is snapshotted here on the main
     * thread and the actual write happens on {@link Bg}. {@code then} (may be
     * null) receives the outcome back on the main thread.
     */
    private void persistConfigFile(final boolean showToast, final Bg.Consumer<Boolean> then)
    {
        final boolean master = masterSwitch != null && masterSwitch.isChecked();
        final boolean matchDesc = matchDescSwitch == null || matchDescSwitch.isChecked();
        final String rules = rulesInput == null ? "" : rulesInput.getText().toString();
        final String allow = allowRulesText;
        final String ovr = overridesJson();
        final boolean contentOn = contentEnabledSwitch != null && contentEnabledSwitch.isChecked();
        final String contentRules = contentRulesInput == null
                ? "" : contentRulesInput.getText().toString();
        final String apps = TextUtils.join("\n", appWhitelist);
        final String mode = prefs().getString(RegexConfig.KEY_OPERATING_MODE, RegexConfig.MODE_ROOT);

        Bg.load(this,
                () -> ConfigFileStore.writeFromApp(master, matchDesc, rules, allow, ovr,
                        contentOn, contentRules, apps, mode),
                ok ->
                {
                    if (!ok && showToast)
                    {
                        say(getString(R.string.toast_config_sync_failed_fmt,
                                ConfigFileStore.CONFIG_FILE), true);
                    }
                    if (then != null)
                    {
                        then.accept(ok);
                    }
                });
    }

    private String overridesJson()
    {
        JSONObject o = new JSONObject();
        try
        {
            for (Map.Entry<String, Boolean> e : overrides.entrySet())
            {
                o.put(e.getKey(), e.getValue());
            }
        }
        catch (Throwable ignored)
        {
        }
        return o.toString();
    }

    private void renderList()
    {
        // The matched page is built lazily; nothing to render until it exists.
        // pageFor() calls this itself once the page is created.
        if (listContainer == null)
        {
            return;
        }

        multiSelectButton.setText(multiSelectMode
                ? getString(R.string.multi_select_active) : getString(R.string.action_multi_select));
        Ui.setEnabledLook(selectAllButton, multiSelectMode);

        List<ChannelRecord> visible = visibleChannels();

        if (multiSelectMode)
        {
            batchHint.setText(getString(R.string.batch_hint_multi_fmt, selected.size()));
        }
        else
        {
            batchHint.setText(getString(R.string.batch_hint_normal_fmt, visible.size()));
        }

        // Detaches the children only — they stay alive in the pools below and are
        // re-attached and rebound. renderList() runs on every toggle / sort /
        // multi-select tap, and rebuilding up to 1000 row trees each time is what
        // used to make those taps stutter.
        listContainer.removeAllViews();

        if (!channelsLoaded)
        {
            // First load still in flight (su-backed in root mode): show the shape
            // of the list, not an "empty" verdict that is not true yet.
            listHeader.setText("");
            for (int i = 0; i < SKELETON_ROWS; i++)
            {
                listContainer.addView(ui.skeletonRow());
            }
            return;
        }

        if (visible.isEmpty())
        {
            // The root-mode text tells the user to check root read access — advice
            // that makes no sense in root-free mode, where the store is this app's
            // own private file and a sparse list is expected rather than a fault.
            int emptyText;
            if (!channels.isEmpty())
            {
                emptyText = R.string.empty_no_regex_match;
            }
            else
            {
                emptyText = rootFree()
                        ? R.string.empty_no_data_rootfree
                        : R.string.empty_no_data;
            }
            listContainer.addView(ui.emptyState(R.drawable.ic_inbox,
                    getString(emptyText), ui.p.surface));
            listHeader.setText(getString(R.string.list_header_zero_fmt, channels.size()));
            return;
        }

        List<ChannelRecord> sorted = new ArrayList<>(visible);
        Collections.sort(sorted, comparator());

        listHeader.setText(getString(R.string.list_header_fmt,
                sorted.size(), channels.size(), countBlocked(sorted)));

        String lastApp = null;
        for (ChannelRecord r : sorted)
        {
            if (sortMode == SORT_BY_APP && !r.pkg.equals(lastApp))
            {
                lastApp = r.pkg;
                listContainer.addView(headerFor(r.pkg));
            }
            listContainer.addView(rowFor(r));
        }

        if (!TextUtils.isEmpty(activeJumpPkg) && (System.currentTimeMillis() - activeJumpTime < 8000))
        {
            final String jumpPkg = activeJumpPkg;
            final String jumpChannel = activeJumpChannelId;
            if (rootFrame != null)
            {
                rootFrame.post(() -> jumpToChannelRow(jumpPkg, jumpChannel));
            }
        }
    }

    /** A group header for {@code pkg}, created once and reused across renders. */
    private View headerFor(String pkg)
    {
        View v = headerPool.get(pkg);
        if (v == null)
        {
            v = appGroupHeader(pkg);
            headerPool.put(pkg, v);
        }
        return v;
    }

    /** The row for a channel, created once and rebound on every render. */
    private View rowFor(ChannelRecord r)
    {
        String key = r.key();
        LinearLayout row = rowPool.get(key);
        if (row == null)
        {
            row = createChannelRow();
            rowPool.put(key, row);
        }
        bindChannelRow(row, r);
        return row;
    }

    private Comparator<ChannelRecord> comparator()
    {
        if (SORT_BY_APP == sortMode)
        {
            return (a, b) ->
            {
                // Group by app, ordered by the label the header shows. Falling back
                // to the package keeps each app's channels contiguous even when two
                // apps happen to share a label.
                int c = zhCollator.compare(appLabel(a.pkg), appLabel(b.pkg));
                if (c == 0)
                {
                    c = a.pkg.compareToIgnoreCase(b.pkg);
                }
                if (c != 0)
                {
                    return c;
                }
                return zhCollator.compare(displayName(a), displayName(b));
            };
        }

        return (a, b) ->
        {
            int c = zhCollator.compare(displayName(a), displayName(b));
            if (c != 0)
            {
                return c;
            }
            return a.pkg.compareToIgnoreCase(b.pkg);
        };
    }

    private View appGroupHeader(String pkg)
    {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(Ui.S1), ui.dp(Ui.S4), ui.dp(Ui.S1), ui.dp(Ui.S2));

        ImageView icon = new ImageView(this);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        AppIconCache.bindIcon(this, icon, pkg);
        row.addView(icon, ui.margins(ui.size(Ui.APP_ICON_SMALL, Ui.APP_ICON_SMALL), 0, 0, Ui.S2, 0));

        row.addView(ui.text(appLabel(pkg), Ui.Type.LABEL, ui.p.onSurface));

        // Keep the package visible — two apps can share a label, and it is what
        // the per-channel override is actually keyed on.
        TextView pkgView = ui.text(pkg, Ui.Type.CAPTION, ui.p.onSurfaceVariant);
        pkgView.setSingleLine(true);
        pkgView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(pkgView, ui.margins(Ui.fillRow(), Ui.S2, 0, 0, 0));

        return row;
    }

    /**
     * The channel-row skeleton, created once per channel and then rebound.
     *
     * The checkbox is always present and merely hidden outside multi-select mode:
     * keeping the child indices stable is what lets a row survive a mode flip as
     * a rebind instead of a rebuild.
     */
    private LinearLayout createChannelRow()
    {
        LinearLayout row = ui.row();
        row.setMinimumHeight(ui.dp(Ui.ROW_MIN));
        row.setPadding(ui.dp(Ui.S3), ui.dp(Ui.S3), ui.dp(Ui.S3), ui.dp(Ui.S3));
        row.setBackground(ui.ripple(ui.shape(ui.p.surface, Ui.R_MEDIUM), Ui.R_MEDIUM));
        row.setLayoutParams(ui.margins(Ui.matchWrap(), 0, 0, 0, Ui.S2));

        row.addView(ui.checkBox()); // child 0

        LinearLayout text = ui.column();

        TextView nameView = ui.text("", Ui.Type.TITLE_SMALL, ui.p.onSurface);
        text.addView(nameView); // text 0

        TextView metaView = ui.text("", Ui.Type.CAPTION, ui.p.onSurfaceVariant);
        metaView.setSingleLine(true);
        metaView.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(metaView, ui.margins(Ui.matchWrap(), 0, Ui.S_HALF, 0, 0)); // text 1

        TextView statusLine = ui.text("", Ui.Type.CAPTION, ui.p.onSurfaceVariant);
        statusLine.setTypeface(Ui.typeface(Ui.Weight.MEDIUM));
        text.addView(statusLine, ui.margins(Ui.matchWrap(), 0, Ui.S1, 0, 0)); // text 2

        row.addView(text, Ui.fillRow()); // child 1

        Switch sw = ui.switchView();
        // Click, not checked-change: only a user flip should buzz, never a rebind.
        sw.setOnClickListener(v -> Haptics.toggle(v, ((Switch) v).isChecked()));
        row.addView(sw, ui.margins(Ui.wrapWrap(), Ui.S2, 0, 0, 0)); // child 2
        return row;
    }

    private String channelUserLabel(int userId)
    {
        if (userId == 0) return getString(R.string.channel_user_primary);
        if (userId == 999) return getString(R.string.channel_user_clone);
        return getString(R.string.channel_user_other_fmt, userId);
    }

    private void bindChannelRow(LinearLayout row, ChannelRecord r)
    {
        final String key = r.key();
        final boolean blocked = effectiveBlocked(r);

        CheckBox cb = (CheckBox) row.getChildAt(0);
        LinearLayout text = (LinearLayout) row.getChildAt(1);
        TextView nameView = (TextView) text.getChildAt(0);
        TextView metaView = (TextView) text.getChildAt(1);
        final TextView statusLine = (TextView) text.getChildAt(2);
        Switch sw = (Switch) row.getChildAt(2);

        // Detach the listener before setChecked — on a rebind the view still
        // carries the previous channel's listener, and setting the state would
        // otherwise fire it and write an override for the wrong channel.
        cb.setOnCheckedChangeListener(null);
        cb.setVisibility(multiSelectMode ? View.VISIBLE : View.GONE);
        cb.setChecked(selected.contains(key));
        cb.setOnCheckedChangeListener((b, checked) ->
        {
            if (checked)
            {
                selected.add(key);
            }
            else
            {
                selected.remove(key);
            }
            batchHint.setText(getString(R.string.batch_hint_multi_fmt, selected.size()));
        });

        text.setPadding(multiSelectMode ? 0 : ui.dp(Ui.S_HALF), 0, ui.dp(Ui.S2), 0);
        nameView.setText(displayName(r));
        sw.setContentDescription(displayName(r));
        metaView.setText(r.pkg + "  ·  " + channelUserLabel(r.userId) + "  ·  id=" + (TextUtils.isEmpty(r.id) ? "—" : r.id));

        statusLine.setText(getString(R.string.status_line_fmt, verdictSource(r),
                getString(blocked ? R.string.status_blocked : R.string.status_allowed)));
        statusLine.setTextColor(blocked ? ui.p.danger : ui.p.success);

        sw.setOnCheckedChangeListener(null);
        sw.setChecked(blocked);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) ->
        {
            overrides.put(key, checked);
            persistOverrides();
            statusLine.setText(getString(R.string.status_line_fmt, getString(R.string.source_override),
                    getString(checked ? R.string.status_blocked : R.string.status_allowed)));
            statusLine.setTextColor(checked ? ui.p.danger : ui.p.success);
            List<ChannelRecord> visible = visibleChannels();
            listHeader.setText(getString(R.string.list_header_fmt,
                    visible.size(), channels.size(), countBlocked(visible)));
        });

        if (multiSelectMode)
        {
            row.setOnClickListener(v ->
            {
                if (!selected.remove(key))
                {
                    selected.add(key);
                }
                renderList();
            });
        }
        else
        {
            row.setOnClickListener(null);
            row.setClickable(false);
        }
    }

    /** Which rule decided this channel's verdict — shown on the row's status line. */
    private String verdictSource(ChannelRecord r)
    {
        if (overrides.get(r.key()) != null)
        {
            return getString(R.string.source_override);
        }
        if (appWhitelist.contains(r.pkg))
        {
            return getString(R.string.source_app_whitelist);
        }
        RuleMatcher m = matcher();
        String[] cand = candidates(r);
        if (m.firstAllowMatch(cand) != null)
        {
            return getString(R.string.source_allow_whitelist);
        }
        if (m.firstBlockMatch(cand) != null)
        {
            return getString(R.string.source_regex_matched);
        }
        return getString(R.string.source_regex_not_matched);
    }

    /**
     * Which channels a batch action applies to: the ticked ones in multi-select
     * mode, otherwise everything currently listed.
     *
     * @return null when there is nothing to act on — a toast has been shown.
     */
    private Set<String> batchTargets()
    {
        if (multiSelectMode)
        {
            if (selected.isEmpty())
            {
                say(getString(R.string.toast_select_first), true);
                return null;
            }
            return new LinkedHashSet<>(selected);
        }
        Set<String> visible = visibleKeySet();
        if (visible.isEmpty())
        {
            say(getString(R.string.toast_list_empty), true);
        }
        return visible.isEmpty() ? null : visible;
    }

    private void batchSet(boolean block)
    {
        Set<String> targets = batchTargets();
        if (targets == null)
        {
            return;
        }

        for (String k : targets)
        {
            overrides.put(k, block);
        }

        persistOverrides();
        renderList();
        Haptics.confirm(contentFrame);
        say(getString(block ? R.string.toast_blocked_n_fmt : R.string.toast_allowed_n_fmt,
                targets.size()), false);
    }

    /**
     * Drop the per-channel override so the channel goes back to following the
     * regex rules. Setting a row's switch used to be one-way: the UI could only
     * put true/false and never remove, so "no override, just follow the rules"
     * was unreachable once a channel had been touched.
     *
     * Mode-agnostic on purpose — overrides live in one pref that both engines
     * read, and persistOverrides() already handles pushing to the root bridge.
     * In root mode a channel that was forced silent gets its original importance
     * back the next time the hook sees it (OriginalChannelStateStore), so no
     * reboot is needed here either.
     */
    private void batchClearOverrides()
    {
        Set<String> targets = batchTargets();
        if (targets == null)
        {
            return;
        }

        int cleared = 0;
        for (String k : targets)
        {
            if (overrides.remove(k) != null)
            {
                cleared++;
            }
        }

        if (cleared == 0)
        {
            say(getString(R.string.toast_no_override), true);
            return;
        }

        persistOverrides();
        renderList();
        Haptics.confirm(contentFrame);
        say(getString(R.string.toast_reset_n_fmt, cleared), false);
    }

    private boolean effectiveBlocked(ChannelRecord r)
    {
        Boolean ov = overrides.get(r.key());
        if (ov != null)
        {
            return ov;
        }
        if (appWhitelist.contains(r.pkg))
        {
            return false;
        }
        return uiRegexMatched(r);
    }

    private int countBlocked(List<ChannelRecord> list)
    {
        int n = 0;
        for (ChannelRecord r : list)
        {
            if (effectiveBlocked(r))
            {
                n++;
            }
        }
        return n;
    }

    private List<ChannelRecord> visibleChannels()
    {
        boolean filterOnlyMatched = onlyMatchedSwitch != null && onlyMatchedSwitch.isChecked();
        String query = (searchQueryInput != null && searchQueryInput.getText() != null)
                ? searchQueryInput.getText().toString().trim().toLowerCase(Locale.ROOT)
                : "";

        List<ChannelRecord> out = new ArrayList<>();
        for (ChannelRecord r : channels)
        {
            if (filterOnlyMatched && !uiRegexMatched(r))
            {
                continue;
            }

            if (!query.isEmpty())
            {
                String label = appLabel(r.pkg).toLowerCase(Locale.ROOT);
                String pkg = r.pkg.toLowerCase(Locale.ROOT);
                String name = r.name != null ? r.name.toLowerCase(Locale.ROOT) : "";
                String id = r.id != null ? r.id.toLowerCase(Locale.ROOT) : "";

                if (!label.contains(query) && !pkg.contains(query) && !name.contains(query) && !id.contains(query))
                {
                    continue;
                }
            }

            out.add(r);
        }
        return out;
    }

    private Set<String> visibleKeySet()
    {
        Set<String> s = new LinkedHashSet<>();
        for (ChannelRecord r : visibleChannels())
        {
            s.add(r.key());
        }
        return s;
    }

    private Set<String> keySet()
    {
        Set<String> s = new LinkedHashSet<>();
        for (ChannelRecord r : channels)
        {
            s.add(r.key());
        }
        return s;
    }

    /**
     * Net regex verdict for a channel using the SAME engine as the hook, so the
     * UI never disagrees with what actually gets blocked. Reflects the allow
     * whitelist (allow beats block) and the "匹配描述文本" toggle.
     */
    /**
     * Whether the regex rules match this channel, memoised per channel.
     *
     * Callers run this over the whole channel list — the stats page does it for
     * every record on every visit — so without the cache a single tab switch
     * could execute the rule set a thousand times mid-animation.
     */
    private boolean uiRegexMatched(ChannelRecord r)
    {
        String key = r.key();
        Boolean cached = regexCache.get(key);
        if (cached != null)
        {
            return cached;
        }
        boolean matched = matcher().shouldBlock(candidates(r));
        regexCache.put(key, matched);
        return matched;
    }

    /**
     * Shared engine built from the current rule text.
     *
     * Rebuilt only when something actually invalidates it. It used to re-read the
     * EditText and rebuild a cache key string on EVERY call — i.e. once per
     * channel per render — which is exactly the work this is supposed to avoid.
     */
    private RuleMatcher matcher()
    {
        if (cachedMatcher == null || matcherDirty)
        {
            String block = rulesInput == null ? "" : rulesInput.getText().toString();
            String allow = allowRulesText == null ? "" : allowRulesText;
            cachedMatcher = RuleMatcher.compile(splitLines(block), splitLines(allow));
            matcherDirty = false;
            regexCache.clear();
        }
        return cachedMatcher;
    }

    /** Rule text / match-description / allow list changed: verdicts must be recomputed. */
    private void invalidateMatcher()
    {
        matcherDirty = true;
        regexCache.clear();
        lastStatsSignature = null; // the rule count and the verdicts both feed the stats
    }

    private String rulesText()
    {
        return rulesInput == null ? "" : rulesInput.getText().toString();
    }

    /** Candidate strings to test: id + name, plus description when the toggle is on. */
    private String[] candidates(ChannelRecord r)
    {
        boolean useDesc = matchDescSwitch != null && matchDescSwitch.isChecked()
                && !TextUtils.isEmpty(r.desc);
        return useDesc
                ? new String[]{r.id, r.name, r.desc}
                : new String[]{r.id, r.name};
    }

    private static List<String> splitLines(String blob)
    {
        List<String> out = new ArrayList<>();
        if (blob == null)
        {
            return out;
        }
        for (String line : blob.split("\\r?\\n"))
        {
            out.add(line);
        }
        return out;
    }

    private static int countRules(String blob)
    {
        int n = 0;
        if (!TextUtils.isEmpty(blob))
        {
            for (String l : blob.split("\\r?\\n"))
            {
                String t = l.trim();
                if (!t.isEmpty() && !t.startsWith("#"))
                {
                    n++;
                }
            }
        }
        return n;
    }

    private String firstInvalidRegex(String blob)
    {
        if (blob == null)
        {
            return null;
        }
        for (String line : blob.split("\\r?\\n"))
        {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#"))
            {
                continue;
            }
            try
            {
                Pattern.compile(t);
            }
            catch (Exception e)
            {
                return t;
            }
        }
        return null;
    }

    private String displayName(ChannelRecord r)
    {
        if (!TextUtils.isEmpty(r.name))
        {
            return r.name;
        }
        if (!TextUtils.isEmpty(r.id))
        {
            return r.id;
        }
        return getString(R.string.unknown_name);
    }

    /**
     * Builds the status readout. Labels are fixed at build time; only the values,
     * the state line and the palette change on refresh.
     */
    private View statusBox()
    {
        statusBox = ui.column();
        statusBox.setPadding(ui.dp(Ui.S4), ui.dp(Ui.S3), ui.dp(Ui.S4), ui.dp(Ui.S4));
        statusBg = new SmoothCornerDrawable(ui.p.surface, ui.dp(Ui.R_MEDIUM));
        statusBox.setBackground(statusBg);

        LinearLayout stateRow = ui.row();
        statusDot = new View(this);
        stateRow.addView(statusDot, ui.margins(ui.size(STATUS_DOT_DP, STATUS_DOT_DP),
                0, 0, Ui.S2, 0));
        statusState = ui.text("", Ui.Type.TITLE_SMALL, ui.p.onSurface);
        stateRow.addView(statusState, Ui.fillRow());
        statusBox.addView(stateRow);

        statusDivider = new View(this);
        statusBox.addView(statusDivider, ui.margins(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, ui.dp(1) / 2)),
                0, Ui.S3, 0, Ui.S3));

        statusValues = new TextView[6];
        statusLabels = new TextView[6];
        statusBox.addView(statusGridRow(0,
                R.string.status_label_rules,
                R.string.status_label_allow,
                R.string.status_label_apps));
        statusBox.addView(statusGridRow(3,
                R.string.status_label_overrides,
                R.string.status_label_content_rules,
                R.string.status_label_content), ui.margins(Ui.matchWrap(), 0, Ui.S3, 0, 0));

        return statusBox;
    }

    private View statusGridRow(int start, int labelA, int labelB, int labelC)
    {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.TOP);
        row.addView(statusCell(start, labelA), Ui.fillRow());
        row.addView(statusCell(start + 1, labelB), Ui.fillRow());
        row.addView(statusCell(start + 2, labelC), Ui.fillRow());
        return row;
    }

    /** One counter: value over label, an equal-width column of the grid. */
    private View statusCell(int index, int labelRes)
    {
        LinearLayout cell = ui.column();

        TextView value = ui.text("", Ui.Type.NUMBER_SMALL, ui.p.onSurface);
        cell.addView(value);
        statusValues[index] = value;

        TextView label = ui.text(getString(labelRes), Ui.Type.CAPTION, ui.p.onSurfaceVariant);
        cell.addView(label, ui.margins(Ui.matchWrap(), 0, Ui.S_HALF, Ui.S1, 0));
        statusLabels[index] = label;
        return cell;
    }

    private void refreshStatus()
    {
        if (statusBox == null)
        {
            return;
        }

        boolean rf = rootFree();
        // Safe mode is a root-hook-only concept (SystemUI crash-loop
        // protection via SafetyManager) — never meaningful in root-free mode.
        boolean safeMode = !rf && safeModeCached;
        boolean listenerGranted = rf && NotificationAccessUtils.isListenerAccessGranted(this);
        // Granted != bound: replacing the APK drops the binding but keeps the
        // grant, so reporting on the grant alone claimed "running" while nothing
        // was being intercepted.
        boolean listenerConnected = listenerGranted && RootFreeNotificationListener.isConnected();
        if (listenerGranted && !listenerConnected && !rebindNudged)
        {
            // One nudge per resume: rebinding is async, so re-check once rather
            // than spin here every time the status is refreshed.
            rebindNudged = true;
            NotificationAccessUtils.requestRebind(this);
            statusBox.postDelayed(this::refreshStatus, 1500);
        }

        String state;
        if (safeMode)
        {
            state = getString(R.string.state_safe_mode);
        }
        else if (masterSwitch != null && !masterSwitch.isChecked())
        {
            state = getString(R.string.state_master_off);
        }
        else if (rf)
        {
            if (!listenerGranted)
            {
                state = getString(R.string.state_rootfree_not_granted);
            }
            else
            {
                state = listenerConnected
                        ? getString(R.string.state_rootfree_connected)
                        : getString(R.string.state_rootfree_reconnecting);
            }
        }
        else if (hookStateCached == HookAliveStore.State.NOT_LOADED)
        {
            state = getString(R.string.state_hook_not_loaded);
        }
        else if (hookStateCached == HookAliveStore.State.VERSION_MISMATCH)
        {
            state = getString(R.string.state_hook_outdated);
        }
        else
        {
            // Null only until the first background load lands. Claiming "running"
            // there would flash a green light we have not earned yet.
            state = hookStateCached == null
                    ? getString(R.string.state_checking)
                    : getString(R.string.state_running);
        }

        int count = countRules(rulesInput == null
                ? prefs().getString(RegexConfig.KEY_RULES, "")
                : rulesInput.getText().toString());
        int allowCount = countRules(allowRulesText);
        int contentCount = countRules(contentRulesInput == null
                ? prefs().getString(RegexConfig.KEY_CONTENT_RULES, "")
                : contentRulesInput.getText().toString());
        boolean contentOn = contentEnabledSwitch != null && contentEnabledSwitch.isChecked();
        boolean masterOff = masterSwitch != null && !masterSwitch.isChecked();

        statusState.setText(state);
        statusValues[0].setText(String.valueOf(count));
        statusValues[1].setText(String.valueOf(allowCount));
        statusValues[2].setText(String.valueOf(appWhitelist.size()));
        statusValues[3].setText(String.valueOf(overrides.size()));
        statusValues[4].setText(String.valueOf(contentCount));
        statusValues[5].setText(contentOn
                ? getString(R.string.content_on)
                : getString(R.string.content_off));

        // Root mode earns its green only on a confirmed live hook of this
        // version; anything else is a real problem the user has to act on
        // (enable the module, tick the scope, reboot).
        boolean hookTrouble = !rf && hookStateCached != null
                && hookStateCached != HookAliveStore.State.ALIVE;
        boolean warnStyle = safeMode || (rf && !listenerConnected) || hookTrouble;
        int text = warnStyle ? ui.p.onWarnContainer : ui.p.onSurface;
        int label = warnStyle ? ui.p.onWarnContainer : ui.p.onSurfaceVariant;
        statusBg.setFillColor(warnStyle ? ui.p.warnContainer : ui.p.surface);
        statusDivider.setBackgroundColor(warnStyle
                ? Palette.withAlpha(ui.p.onWarnContainer, 0.2f) : ui.p.outlineVariant);
        statusState.setTextColor(text);
        for (int i = 0; i < statusValues.length; i++)
        {
            statusValues[i].setTextColor(text);
            statusLabels[i].setTextColor(label);
        }
        // A green dot next to "master switch is off" — or next to a state we
        // have not established yet — would be the same kind of lie the old
        // grant-only status banner told.
        boolean unproven = masterOff || (!rf && hookStateCached == null);
        statusDot.setBackground(ui.circle(
                warnStyle ? ui.p.onWarnContainer : (unproven ? ui.p.outline : ui.p.success)));
        if (heroModeChip != null)
        {
            heroModeChip.setText(modeLabel());
        }

        if (clearSafeModeButton != null)
        {
            clearSafeModeButton.setVisibility(safeMode ? View.VISIBLE : View.GONE);
        }

        refreshRootFreeUi(rf, listenerGranted);
    }

    /** The access prompt is only meaningful in root-free mode, and only while it is missing. */
    private void refreshRootFreeUi(boolean rf, boolean listenerGranted)
    {
        if (grantAccessButton != null)
        {
            grantAccessButton.setVisibility(rf && !listenerGranted ? View.VISIBLE : View.GONE);
        }
    }

    private void setCheckedSilently(Switch sw, boolean checked)
    {
        // Switches on the stats / matched pages do not exist until their tab is
        // first opened; those pages read their own state when they are built.
        if (sw == null)
        {
            return;
        }
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        if (sw == onlyMatchedSwitch)
        {
            sw.setOnCheckedChangeListener((b, v) ->
            {
                prefs().edit().putBoolean(KEY_UI_ONLY_MATCHED, v).apply();
                selected.clear();
                renderList();
            });
        }
        else
        {
            sw.setOnCheckedChangeListener((b, v) -> persistSwitches());
        }
    }

    /** Message over the bottom of the current page, clear of the tab bar. */
    private void say(String message, boolean error)
    {
        ui.snackbar(contentFrame, message, error);
    }

    /** Spring "look here" bounce for a row the user was jumped to. */
    private void pop(View v)
    {
        Motion.set(v, View.SCALE_X, Motion.PRESS_SCALE);
        Motion.set(v, View.SCALE_Y, Motion.PRESS_SCALE);
        Motion.animate(v, View.SCALE_X, 1f, Motion.FLING);
        Motion.animate(v, View.SCALE_Y, 1f, Motion.FLING);
    }
}
