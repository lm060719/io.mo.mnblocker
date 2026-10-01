package io.mo.mnblocker;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Hidden debug screen, entered by tapping the app icon / name 5 times on the
 * About page.
 *
 * Controls:
 *  1. "输出日志" switch — creates / deletes a flag file that HookLogger checks.
 *     Default OFF: hook.log is not written unless explicitly enabled.
 *  2. "导出 Hook 日志" button — copies hook.log to /sdcard/ via su.
 *     If hook.log does not exist, a bottom-anchored hint fades in and out.
 *  3. "禁用安全模式" switch — bypasses automatic crash-loop protection.
 */
public final class DebugActivity extends Activity
{
    private static final int COLOR_BG      = 0xFFF6F7FB;
    private static final int COLOR_CARD    = 0xFFFFFFFF;
    private static final int COLOR_TEXT    = 0xFF172033;
    private static final int COLOR_SUB     = 0xFF6D7484;
    private static final int COLOR_PRIMARY = 0xFF3F6DF6;
    private static final int COLOR_LINE    = 0xFFE7EAF0;
    private static final int COLOR_WARN_BG   = 0xFFFFF5DD;
    private static final int COLOR_WARN_TEXT = 0xFF9A5B00;

    private Switch logSwitch;
    private Switch disableXposedLogSwitch;
    private Switch disableSafeModeSwitch;
    private boolean updatingSafeModeSwitch;
    private Button[] levelButtons;
    private int currentLevel = HookLogger.LEVEL_ERROR;
    private FrameLayout rootFrame;

    @Override
    protected void attachBaseContext(Context newBase)
    {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        rootFrame = new FrameLayout(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(26), dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(titleSection());
        root.addView(warningCard());
        root.addView(safetyCard());
        root.addView(loggingCard());
        root.addView(exportCard());

        rootFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);
        SystemBars.edgeToEdge(this, rootFrame, root, root);

        // Read current flag states in background.
        new Thread(() -> {
            boolean logEnabled = ShellUtils.isDebugLogging();
            boolean xposedDisabled = ShellUtils.isDisableXposedLog();
            boolean safeModeDisabled = ShellUtils.isDisableSafeMode();
            int level = ShellUtils.getXposedLogLevel();
            runOnUiThread(() -> {
                setCheckedSilently(logEnabled);
                setXposedDisabledSilently(xposedDisabled);
                setSafeModeDisabledSilently(safeModeDisabled);
                disableSafeModeSwitch.setEnabled(true);
                currentLevel = level;
                updateLevelButtons(level);
                setLevelButtonsEnabled(!xposedDisabled);
            });
        }).start();
    }

    // ---- UI sections -------------------------------------------------------

    private View titleSection()
    {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, dp(16));

        TextView title = new TextView(this);
        title.setText(getString(R.string.debug_title));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.debug_subtitle));
        sub.setTextColor(COLOR_SUB);
        sub.setTextSize(13);
        sub.setPadding(0, dp(6), 0, 0);
        box.addView(sub);

        return box;
    }

    private View warningCard()
    {
        LinearLayout card = cardLayout();
        card.setBackground(roundBg(COLOR_WARN_BG, dp(22)));

        TextView warn = new TextView(this);
        warn.setText(getString(R.string.debug_warning));
        warn.setTextColor(COLOR_WARN_TEXT);
        warn.setTextSize(12);
        warn.setLineSpacing(dp(2), 1.0f);
        card.addView(warn);

        return card;
    }

    private View safetyCard()
    {
        LinearLayout card = cardLayout();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(R.string.debug_disable_safe_mode_title);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(15);
        labels.addView(title);

        TextView desc = new TextView(this);
        desc.setText(R.string.debug_disable_safe_mode_desc);
        desc.setTextColor(COLOR_SUB);
        desc.setTextSize(11);
        desc.setPadding(0, dp(4), dp(8), 0);
        labels.addView(desc);
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        disableSafeModeSwitch = new Switch(this);
        disableSafeModeSwitch.setContentDescription(
                getString(R.string.debug_disable_safe_mode_title));
        // Avoid overwriting a persisted setting before the background read finishes.
        disableSafeModeSwitch.setEnabled(false);
        disableSafeModeSwitch.setOnCheckedChangeListener((btn, checked) -> {
            if (updatingSafeModeSwitch) {
                return;
            }
            disableSafeModeSwitch.setEnabled(false);
            new Thread(() -> {
                boolean ok = ShellUtils.setDisableSafeMode(checked);
                boolean actual = ok ? checked : ShellUtils.isDisableSafeMode();
                runOnUiThread(() -> {
                    setSafeModeDisabledSilently(actual);
                    disableSafeModeSwitch.setEnabled(true);
                    if (!ok) {
                        Toast.makeText(this, R.string.toast_op_failed_root,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });
        row.addView(disableSafeModeSwitch);
        card.addView(row);
        return card;
    }

    private View loggingCard()
    {
        LinearLayout card = cardLayout();

        TextView title = new TextView(this);
        title.setText(getString(R.string.logging_card_title));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        // ---- switch row ----
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(4));

        LinearLayout labelCol = new LinearLayout(this);
        labelCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelCol.setLayoutParams(labelLp);

        TextView label = new TextView(this);
        label.setText(getString(R.string.debug_output_log_title));
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(15);
        labelCol.addView(label);

        TextView desc = new TextView(this);
        desc.setText(getString(R.string.debug_output_log_desc));
        desc.setTextColor(COLOR_SUB);
        desc.setTextSize(11);
        desc.setPadding(0, dp(2), 0, 0);
        labelCol.addView(desc);

        row.addView(labelCol);

        logSwitch = new Switch(this);
        logSwitch.setChecked(false);
        logSwitch.setOnCheckedChangeListener((btn, checked) -> {
            new Thread(() -> {
                boolean ok = ShellUtils.setDebugLogging(checked);
                if (!ok) {
                    runOnUiThread(() -> {
                        setCheckedSilently(!checked);
                        Toast.makeText(this,
                                getString(R.string.toast_op_failed_root),
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });
        row.addView(logSwitch);

        card.addView(row);

        // ---- divider ----
        View divider1 = new View(this);
        divider1.setBackgroundColor(COLOR_LINE);
        LinearLayout.LayoutParams divLp1 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp1.topMargin = dp(10);
        divLp1.bottomMargin = dp(10);
        card.addView(divider1, divLp1);

        // ---- disable xposed log switch row ----
        LinearLayout xpRow = new LinearLayout(this);
        xpRow.setOrientation(LinearLayout.HORIZONTAL);
        xpRow.setGravity(Gravity.CENTER_VERTICAL);
        xpRow.setPadding(0, dp(4), 0, dp(4));

        LinearLayout xpLabelCol = new LinearLayout(this);
        xpLabelCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams xpLabelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        xpLabelCol.setLayoutParams(xpLabelLp);

        TextView xpLabel = new TextView(this);
        xpLabel.setText(getString(R.string.debug_disable_xposed_log_title));
        xpLabel.setTextColor(COLOR_TEXT);
        xpLabel.setTextSize(15);
        xpLabelCol.addView(xpLabel);

        TextView xpDesc = new TextView(this);
        xpDesc.setText(getString(R.string.debug_disable_xposed_log_desc));
        xpDesc.setTextColor(COLOR_SUB);
        xpDesc.setTextSize(11);
        xpDesc.setPadding(0, dp(2), 0, 0);
        xpLabelCol.addView(xpDesc);

        xpRow.addView(xpLabelCol);

        disableXposedLogSwitch = new Switch(this);
        disableXposedLogSwitch.setChecked(false);
        disableXposedLogSwitch.setOnCheckedChangeListener((btn, checked) -> {
            setLevelButtonsEnabled(!checked);
            new Thread(() -> {
                boolean ok = ShellUtils.setDisableXposedLog(checked);
                if (!ok) {
                    runOnUiThread(() -> {
                        setXposedDisabledSilently(!checked);
                        setLevelButtonsEnabled(checked);
                        Toast.makeText(this,
                                getString(R.string.toast_op_failed_root),
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });
        xpRow.addView(disableXposedLogSwitch);

        card.addView(xpRow);

        // ---- divider ----
        View divider2 = new View(this);
        divider2.setBackgroundColor(COLOR_LINE);
        LinearLayout.LayoutParams divLp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        divLp2.topMargin = dp(10);
        divLp2.bottomMargin = dp(10);
        card.addView(divider2, divLp2);

        // ---- log level selector ----
        LinearLayout lvlSection = new LinearLayout(this);
        lvlSection.setOrientation(LinearLayout.VERTICAL);
        lvlSection.setPadding(0, dp(4), 0, dp(4));

        TextView lvlLabel = new TextView(this);
        lvlLabel.setText(getString(R.string.debug_log_level_title));
        lvlLabel.setTextColor(COLOR_TEXT);
        lvlLabel.setTextSize(15);
        lvlSection.addView(lvlLabel);

        TextView lvlDesc = new TextView(this);
        lvlDesc.setText(getString(R.string.debug_log_level_desc));
        lvlDesc.setTextColor(COLOR_SUB);
        lvlDesc.setTextSize(11);
        lvlDesc.setPadding(0, dp(2), 0, dp(10));
        lvlSection.addView(lvlDesc);

        // Horizontal button group
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER_VERTICAL);

        String[] labels = {"ALL", "DEBUG", "INFO", "WARN", "ERROR"};
        int[] levels = {
                HookLogger.LEVEL_ALL,
                HookLogger.LEVEL_DEBUG,
                HookLogger.LEVEL_INFO,
                HookLogger.LEVEL_WARN,
                HookLogger.LEVEL_ERROR
        };
        levelButtons = new Button[labels.length];

        for (int i = 0; i < labels.length; i++) {
            final int lvl = levels[i];
            Button b = new Button(this);
            b.setText(labels[i]);
            b.setTextSize(11);
            b.setAllCaps(false);
            b.setPadding(dp(6), dp(8), dp(6), dp(8));
            b.setMinimumWidth(0);
            b.setMinWidth(0);
            b.setMinimumHeight(0);
            b.setMinHeight(0);

            // Default unselected style
            b.setTextColor(COLOR_SUB);
            b.setBackground(roundBg(0xFFEEF0F4, dp(10)));

            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < labels.length - 1) {
                bLp.rightMargin = dp(6);
            }

            b.setOnClickListener(v -> {
                currentLevel = lvl;
                updateLevelButtons(lvl);
                new Thread(() -> {
                    boolean ok = ShellUtils.setXposedLogLevel(lvl);
                    if (!ok) {
                        runOnUiThread(() -> Toast.makeText(this,
                                getString(R.string.toast_op_failed_root),
                                Toast.LENGTH_SHORT).show());
                    }
                }).start();
            });

            levelButtons[i] = b;
            btnRow.addView(b, bLp);
        }

        lvlSection.addView(btnRow);
        card.addView(lvlSection);

        // Default highlight: ERROR
        updateLevelButtons(HookLogger.LEVEL_ERROR);

        return card;
    }

    private View exportCard()
    {
        LinearLayout card = cardLayout();

        TextView title = new TextView(this);
        title.setText(getString(R.string.export_card_title));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText(getString(R.string.export_card_desc));
        desc.setTextColor(COLOR_SUB);
        desc.setTextSize(12);
        desc.setPadding(0, dp(6), 0, dp(14));
        card.addView(desc);

        Button exportBtn = new Button(this);
        exportBtn.setText(getString(R.string.action_export_log));
        exportBtn.setTextColor(Color.WHITE);
        exportBtn.setTextSize(14);
        exportBtn.setTypeface(Typeface.DEFAULT_BOLD);
        exportBtn.setBackground(roundBg(COLOR_PRIMARY, dp(14)));
        exportBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        exportBtn.setAllCaps(false);
        exportBtn.setOnClickListener(v -> onExport());

        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        card.addView(exportBtn, btnLp);

        return card;
    }

    // ---- actions -----------------------------------------------------------

    private void onExport()
    {
        new Thread(() -> {
            boolean exists = ShellUtils.hookLogExists();
            if (!exists) {
                runOnUiThread(() -> showFadeHint(getString(R.string.hint_no_log)));
                return;
            }
            boolean ok = ShellUtils.exportHookLog();
            runOnUiThread(() -> {
                if (ok) {
                    Toast.makeText(this,
                            getString(R.string.toast_exported),
                            Toast.LENGTH_SHORT).show();
                } else {
                    showFadeHint(getString(R.string.hint_export_failed));
                }
            });
        }).start();
    }

    /**
     * Show a centered hint near the bottom of the screen that fades out
     * after 2 seconds.
     */
    private void showFadeHint(String text)
    {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(24), dp(12), dp(24), dp(12));
        hint.setBackground(roundBg(0xCC333333, dp(24)));
        hint.setAlpha(1f);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp(80);

        rootFrame.addView(hint, lp);

        new Handler(Looper.getMainLooper()).postDelayed(() ->
                hint.animate()
                        .alpha(0f)
                        .setDuration(800)
                        .withEndAction(() -> rootFrame.removeView(hint))
                        .start(),
                2000);
    }

    // ---- helpers (suppress listener during programmatic change) -------------

    private void setSafeModeDisabledSilently(boolean checked)
    {
        updatingSafeModeSwitch = true;
        disableSafeModeSwitch.setChecked(checked);
        updatingSafeModeSwitch = false;
    }

    private void setCheckedSilently(boolean checked)
    {
        logSwitch.setOnCheckedChangeListener(null);
        logSwitch.setChecked(checked);
        logSwitch.setOnCheckedChangeListener((btn, c) -> {
            new Thread(() -> {
                boolean ok = ShellUtils.setDebugLogging(c);
                if (!ok) {
                    runOnUiThread(() -> {
                        setCheckedSilently(!c);
                        Toast.makeText(this,
                                getString(R.string.toast_op_failed_root),
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });
    }

    private void setXposedDisabledSilently(boolean checked)
    {
        disableXposedLogSwitch.setOnCheckedChangeListener(null);
        disableXposedLogSwitch.setChecked(checked);
        disableXposedLogSwitch.setOnCheckedChangeListener((btn, c) -> {
            setLevelButtonsEnabled(!c);
            new Thread(() -> {
                boolean ok = ShellUtils.setDisableXposedLog(c);
                if (!ok) {
                    runOnUiThread(() -> {
                        setXposedDisabledSilently(!c);
                        setLevelButtonsEnabled(c);
                        Toast.makeText(this,
                                getString(R.string.toast_op_failed_root),
                                Toast.LENGTH_SHORT).show();
                    });
                }
            }).start();
        });
    }

    private void updateLevelButtons(int selectedLevel)
    {
        if (levelButtons == null) {
            return;
        }
        int[] levels = {
                HookLogger.LEVEL_ALL,
                HookLogger.LEVEL_DEBUG,
                HookLogger.LEVEL_INFO,
                HookLogger.LEVEL_WARN,
                HookLogger.LEVEL_ERROR
        };
        for (int i = 0; i < levelButtons.length; i++) {
            Button b = levelButtons[i];
            if (levels[i] == selectedLevel) {
                b.setTextColor(Color.WHITE);
                b.setBackground(roundBg(COLOR_PRIMARY, dp(10)));
            } else {
                b.setTextColor(COLOR_SUB);
                b.setBackground(roundBg(0xFFEEF0F4, dp(10)));
            }
        }
    }

    private void setLevelButtonsEnabled(boolean enabled)
    {
        if (levelButtons == null) {
            return;
        }
        for (Button b : levelButtons) {
            b.setEnabled(enabled);
            b.setAlpha(enabled ? 1.0f : 0.4f);
        }
    }

    // ---- card / drawing helpers (same style as AboutActivity) ---------------

    private LinearLayout cardLayout()
    {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundBg(COLOR_CARD, dp(22)));
        card.setElevation(dp(2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private GradientDrawable roundBg(int color, int radius)
    {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radius);
        return gd;
    }

    private int dp(int v)
    {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
