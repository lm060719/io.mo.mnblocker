package io.mo.mnblocker;

import android.app.NotificationChannel;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * SystemUI Xposed hook for notification long-press inline control menus.
 *
 * Supports AOSP, MIUI / HyperOS (ModalWindowView / MiuiNotificationMenuRow),
 * ColorOS, OriginOS, and Samsung One UI.
 *
 * Injects a prominent button ("🛡️ MNBlocker 拦截开关") that opens MainActivity
 * and jumps directly to the matching category switch.
 */
public final class SystemUiHook {

    public static final String EXTRA_JUMP_PKG = "io.mo.mnblocker.extra.JUMP_PKG";
    public static final String EXTRA_JUMP_USER = "io.mo.mnblocker.extra.JUMP_USER";
    public static final String EXTRA_JUMP_CHANNEL = "io.mo.mnblocker.extra.JUMP_CHANNEL";

    private static final String BUTTON_TAG = "mnblocker_manage_btn";

    private static final String[] TARGET_CLASSES = {
            // MIUI / HyperOS Specific Modal Long-Press Views
            "com.android.systemui.statusbar.notification.modal.ModalWindowView",
            "com.android.systemui.statusbar.notification.modal.ModalControllerImpl",
            "com.android.systemui.statusbar.notification.row.MiuiNotificationMenuRow",
            // AOSP & Multi-ROM Standard Classes
            "com.android.systemui.statusbar.notification.row.NotificationInfo",
            "com.android.systemui.statusbar.notification.row.NotificationConversationInfo",
            "com.android.systemui.statusbar.notification.row.PartialConversationInfo",
            "com.android.systemui.statusbar.notification.row.PromotedNotificationInfo",
            "com.android.systemui.statusbar.notification.row.MiuiNotificationInfo",
            "com.android.systemui.statusbar.notification.row.MiuiNotificationGuts",
            "com.android.systemui.statusbar.notification.row.NotificationGuts",
            "com.android.systemui.statusbar.notification.row.NotificationGutsManager",
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    };

    private static final String[] TARGET_METHODS = {
            "enterModal",
            "addMenu",
            "tryEnterModal",
            "bindNotification",
            "bindHeader",
            "bindGuts",
            "initializeNotificationInfo",
            "initializeConversationNotificationInfo",
            "setGutsContent",
            "openGuts",
            "makeGuts"
    };

    private final SafetyManager safety;

    public SystemUiHook(SafetyManager safety) {
        this.safety = safety;
    }

    public void install(LoadPackageParam lpparam) {
        if (!safety.hookingAllowed()) {
            HookLogger.w("Safe mode active — SystemUiHook deferred.");
            return;
        }

        int hookedCount = 0;
        for (String className : TARGET_CLASSES) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, lpparam.classLoader);
            if (clazz == null) {
                continue;
            }

            for (String methodName : TARGET_METHODS) {
                try {
                    XposedBridge.hookAllMethods(clazz, methodName, genericGutsCallback);
                    hookedCount++;
                    HookLogger.i("Hooked " + className + "#" + methodName + " for SystemUI inline tile");
                } catch (Throwable ignored) {
                }
            }
        }

        if (hookedCount > 0) {
            HookLogger.i("SystemUiHook installed successfully in " + lpparam.packageName
                    + " (" + hookedCount + " method hooks placed)");
        } else {
            HookLogger.w("No SystemUI notification inline control class/method could be hooked.");
        }
    }

    private final XC_MethodHook genericGutsCallback = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            if (!safety.hookingAllowed()) {
                return;
            }
            try {
                String pkg = extractPkg(param.thisObject, param.args);
                if (pkg == null || pkg.isEmpty() || pkg.equals("android") || pkg.equals("com.android.systemui")) {
                    return;
                }
                String channelId = extractChannelId(param.thisObject, param.args);
                StatusBarNotification sbn = extractSbn(param.thisObject, param.args);
                int userId = sbn == null ? ChannelIdentity.UNKNOWN_USER : sbn.getUserId();
                if (sbn != null) pkg = sbn.getPackageName();

                ViewGroup root = findContainerViewGroup(param.thisObject, param.args);
                if (root == null) {
                    return;
                }

                View existing = root.findViewWithTag(BUTTON_TAG);
                if (existing != null) {
                    setupClickListener(existing, pkg, channelId, userId);
                    return;
                }

                View tile = createMiuiMenuItem(root.getContext(), pkg, channelId, userId);
                tile.setTag(BUTTON_TAG);

                // Determine target margin from existing native children or system dimen resource
                int targetMargin = dp(root.getContext(), 12);
                try {
                    int dimenRes = root.getContext().getResources().getIdentifier(
                            "miui_notification_modal_menu_margin", "dimen", "com.android.systemui");
                    if (dimenRes != 0) {
                        targetMargin = root.getContext().getResources().getDimensionPixelSize(dimenRes);
                    }
                } catch (Throwable ignored) {
                }

                if (root.getChildCount() > 0) {
                    View firstChild = root.getChildAt(0);
                    if (firstChild != null && firstChild.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                        ViewGroup.MarginLayoutParams childLp = (ViewGroup.MarginLayoutParams) firstChild.getLayoutParams();
                        if (childLp.leftMargin > 0) {
                            targetMargin = childLp.leftMargin;
                        }
                    }
                }

                if (root instanceof LinearLayout) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.leftMargin = targetMargin;
                    lp.rightMargin = targetMargin;
                    lp.gravity = Gravity.CENTER_VERTICAL;
                    root.addView(tile, 0, lp); // Insert tile at the beginning of the row

                    // Uniformly adjust margins for ALL child items to ensure 100% symmetrical spacing
                    for (int i = 0; i < root.getChildCount(); i++) {
                        View child = root.getChildAt(i);
                        ViewGroup.LayoutParams clp = child.getLayoutParams();
                        if (clp instanceof LinearLayout.LayoutParams) {
                            LinearLayout.LayoutParams mlp = (LinearLayout.LayoutParams) clp;
                            mlp.leftMargin = targetMargin;
                            mlp.rightMargin = targetMargin;
                            child.setLayoutParams(mlp);
                        }
                    }
                } else {
                    ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.leftMargin = targetMargin;
                    lp.rightMargin = targetMargin;
                    root.addView(tile, lp);
                }

                HookLogger.i("Injected MNBlocker tile into SystemUI view (" + param.thisObject.getClass().getSimpleName()
                        + ") for pkg=" + pkg + " channel=" + channelId);
            } catch (Throwable t) {
                HookLogger.e("Error injecting SystemUI tile", t);
            }
        }
    };

    private View createMiuiMenuItem(Context context, String pkg, String channelId, int userId) {
        // Strategy A: Try inflating native SystemUI layout 'miui_notification_modal_menu'
        try {
            int layoutRes = context.getResources().getIdentifier("miui_notification_modal_menu", "layout", "com.android.systemui");
            if (layoutRes != 0) {
                View menuItem = LayoutInflater.from(context).inflate(layoutRes, null);
                int titleId = context.getResources().getIdentifier("modal_menu_title", "id", "com.android.systemui");
                int iconId = context.getResources().getIdentifier("modal_menu_icon", "id", "com.android.systemui");
                int bgRes = context.getResources().getIdentifier("miui_notification_menu_ic_bg_inactive", "drawable", "com.android.systemui");

                if (titleId != 0) {
                    TextView tv = menuItem.findViewById(titleId);
                    if (tv != null) {
                        tv.setText("拦截设置");
                    }
                }
                if (iconId != 0) {
                    ImageView iv = menuItem.findViewById(iconId);
                    if (iv != null) {
                        if (bgRes != 0) {
                            iv.setBackgroundResource(bgRes);
                        } else {
                            GradientDrawable circleBg = new GradientDrawable();
                            circleBg.setShape(GradientDrawable.OVAL);
                            circleBg.setColor(0x33FFFFFF);
                            iv.setBackground(circleBg);
                        }
                        iv.setImageDrawable(createShieldIconDrawable(context));
                    }
                }
                setupClickListener(menuItem, pkg, channelId, userId);
                return menuItem;
            }
        } catch (Throwable ignored) {
        }

        // Strategy B: Construct programmatic view matching native MIUI '更多设置' (52dp circle + label below)
        LinearLayout itemLayout = new LinearLayout(context);
        itemLayout.setOrientation(LinearLayout.VERTICAL);
        itemLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(context, 64),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        itemLayout.setLayoutParams(lp);

        // 1. Circle Icon Frame (52dp x 52dp)
        ImageView iconView = new ImageView(context);
        int iconSize = dp(context, 52);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconLp.gravity = Gravity.CENTER_HORIZONTAL;
        iconView.setLayoutParams(iconLp);

        // Increased padding to 17dp to scale icon down to match adjacent native icons
        int p = dp(context, 17f);
        iconView.setPadding(p, p, p, p);

        GradientDrawable circleBg = new GradientDrawable();
        circleBg.setShape(GradientDrawable.OVAL);
        circleBg.setColor(0x33FFFFFF);
        iconView.setBackground(circleBg);
        iconView.setImageDrawable(createShieldIconDrawable(context));

        // 2. Title Label
        TextView titleView = new TextView(context);
        titleView.setText("拦截设置");
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(12);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(context, 6);
        titleLp.gravity = Gravity.CENTER_HORIZONTAL;
        titleView.setLayoutParams(titleLp);

        itemLayout.addView(iconView);
        itemLayout.addView(titleView);

        setupClickListener(itemLayout, pkg, channelId, userId);
        return itemLayout;
    }

    private Drawable createShieldIconDrawable(Context context) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();

            @Override
            public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                float w = bounds.width();
                float h = bounds.height();
                paint.setColor(Color.WHITE);
                paint.setStyle(Paint.Style.STROKE);
                // Refined thin stroke width (w * 0.065f) matching native system icons
                paint.setStrokeWidth(Math.max(2.0f, w * 0.065f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);

                float cx = bounds.left + w * 0.5f;
                float topY = bounds.top + h * 0.18f;
                float rx = w * 0.32f;
                float shoulderY = bounds.top + h * 0.30f;
                float midY = bounds.top + h * 0.58f;
                float bottomY = bounds.top + h * 0.86f;

                path.reset();
                path.moveTo(cx, topY);
                path.lineTo(cx + rx, shoulderY);
                path.lineTo(cx + rx, midY);
                path.cubicTo(cx + rx, bounds.top + h * 0.78f,
                        cx, bottomY,
                        cx, bottomY);
                path.cubicTo(cx, bottomY,
                        cx - rx, bounds.top + h * 0.78f,
                        cx - rx, midY);
                path.lineTo(cx - rx, shoulderY);
                path.close();

                canvas.drawPath(path, paint);
            }

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter colorFilter) { paint.setColorFilter(colorFilter); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    private void setupClickListener(View tile, final String pkg, final String channelId, final int userId) {
        tile.setOnClickListener(v -> {
            try {
                Context context = v.getContext();

                // 1. Launch MainActivity immediately with zero blocking
                Intent intent = new Intent();
                intent.setComponent(new ComponentName("io.mo.mnblocker", "io.mo.mnblocker.MainActivity"));
                intent.putExtra(EXTRA_JUMP_PKG, pkg);
                intent.putExtra(EXTRA_JUMP_USER, userId);
                if (channelId != null) {
                    intent.putExtra(EXTRA_JUMP_CHANNEL, channelId);
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(intent);

                // 2. Collapse notification shade asynchronously in background
                new Thread(() -> collapseNotificationShade(context)).start();

                HookLogger.i("Clicked MNBlocker tile: launching MainActivity for pkg=" + pkg + " channel=" + channelId);
            } catch (Throwable t) {
                HookLogger.e("Failed to launch MainActivity from SystemUI tile", t);
            }
        });
    }

    private void collapseNotificationShade(Context context) {
        try {
            Intent closeIntent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            context.sendBroadcast(closeIntent);
        } catch (Throwable ignored) {
        }
        try {
            Object sbm = context.getSystemService("statusbar");
            if (sbm != null) {
                Method collapse = sbm.getClass().getMethod("collapsePanels");
                collapse.setAccessible(true);
                collapse.invoke(sbm);
            }
        } catch (Throwable ignored) {
        }
    }

    private StatusBarNotification extractSbn(Object target, Object[] args) {
        StatusBarNotification direct = findArg(args, StatusBarNotification.class);
        if (direct != null) return direct;
        Object entry = findArgByClassName(args, "NotificationEntry");
        if (entry == null && target != null) entry = readField(target, "mEntry");
        if (entry == null && target != null) entry = readField(target, "entry");
        Object sbn = entry == null ? null : invokeMethod(entry, "getSbn");
        if (sbn == null && entry != null) sbn = readField(entry, "mSbn");
        if (sbn == null && target != null) sbn = readField(target, "mSbn");
        return sbn instanceof StatusBarNotification ? (StatusBarNotification) sbn : null;
    }

    private String extractPkg(Object target, Object[] args) {
        StatusBarNotification sbn = findArg(args, StatusBarNotification.class);
        if (sbn != null && sbn.getPackageName() != null) {
            return sbn.getPackageName();
        }

        Object entry = findArgByClassName(args, "NotificationEntry");
        if (entry == null && target != null) {
            entry = readField(target, "mEntry");
        }
        if (entry == null && target != null) {
            entry = readField(target, "entry");
        }
        if (entry != null) {
            Object entrySbn = invokeMethod(entry, "getSbn");
            if (entrySbn == null) {
                entrySbn = readField(entry, "mSbn");
            }
            if (entrySbn instanceof StatusBarNotification) {
                return ((StatusBarNotification) entrySbn).getPackageName();
            }
        }

        NotificationChannel channel = findArg(args, NotificationChannel.class);

        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof String) {
                    String str = (String) arg;
                    if (str.contains(".") && !str.startsWith("com.android.systemui")) {
                        return str;
                    }
                }
            }
        }

        if (target != null) {
            String pkg = readStringField(target, "mPkg");
            if (pkg == null) pkg = readStringField(target, "mPackageName");
            if (pkg == null) pkg = readStringField(target, "mAppName");
            if (pkg != null && pkg.contains(".")) return pkg;

            Object targetSbn = readField(target, "mSbn");
            if (targetSbn instanceof StatusBarNotification) {
                return ((StatusBarNotification) targetSbn).getPackageName();
            }
        }

        return null;
    }

    private String extractChannelId(Object target, Object[] args) {
        // Direct NotificationChannel arg
        NotificationChannel channel = findArg(args, NotificationChannel.class);
        if (channel != null && channel.getId() != null) {
            return channel.getId();
        }

        // Direct StatusBarNotification arg
        StatusBarNotification sbn = findArg(args, StatusBarNotification.class);
        if (sbn != null && sbn.getNotification() != null && sbn.getNotification().getChannelId() != null) {
            return sbn.getNotification().getChannelId();
        }

        // NotificationEntry arg or target field
        Object entry = findArgByClassName(args, "NotificationEntry");
        if (entry == null && target != null) {
            entry = readField(target, "mEntry");
        }
        if (entry == null && target != null) {
            entry = readField(target, "entry");
        }
        if (entry != null) {
            Object ch = invokeMethod(entry, "getChannel");
            if (ch instanceof NotificationChannel) {
                return ((NotificationChannel) ch).getId();
            }

            Object ranking = readField(entry, "mRanking");
            if (ranking != null) {
                Object rCh = invokeMethod(ranking, "getChannel");
                if (rCh instanceof NotificationChannel) {
                    return ((NotificationChannel) rCh).getId();
                }
            }

            Object entrySbn = readField(entry, "mSbn");
            if (entrySbn == null) {
                entrySbn = invokeMethod(entry, "getSbn");
            }
            if (entrySbn instanceof StatusBarNotification) {
                StatusBarNotification sbnObj = (StatusBarNotification) entrySbn;
                if (sbnObj.getNotification() != null && sbnObj.getNotification().getChannelId() != null) {
                    return sbnObj.getNotification().getChannelId();
                }
            }
        }

        if (target != null) {
            Object targetSbn = readField(target, "mSbn");
            if (targetSbn instanceof StatusBarNotification) {
                StatusBarNotification sbnObj = (StatusBarNotification) targetSbn;
                if (sbnObj.getNotification() != null && sbnObj.getNotification().getChannelId() != null) {
                    return sbnObj.getNotification().getChannelId();
                }
            }

            Object ch = readField(target, "mSingleNotificationChannel");
            if (ch == null) ch = readField(target, "mNotificationChannel");
            if (ch == null) ch = readField(target, "mChannel");
            if (ch instanceof NotificationChannel) {
                return ((NotificationChannel) ch).getId();
            }
        }

        return null;
    }

    private ViewGroup findContainerViewGroup(Object target, Object[] args) {
        if (target != null && target.getClass().getName().contains("ModalWindowView")) {
            Object menuView = readField(target, "mMenuView");
            if (menuView instanceof ViewGroup) {
                return (ViewGroup) menuView;
            }
            return null;
        }

        if (target instanceof ViewGroup && !target.getClass().getName().contains("ModalWindowView")) {
            return (ViewGroup) target;
        }

        for (Object arg : args) {
            if (arg instanceof ViewGroup && !arg.getClass().getName().contains("ModalWindowView")) {
                return (ViewGroup) arg;
            }
        }

        if (target != null) {
            Object menu = readField(target, "mMenuView");
            if (menu instanceof ViewGroup) {
                return (ViewGroup) menu;
            }
            Object menuContainer = readField(target, "mMenuContainer");
            if (menuContainer instanceof ViewGroup) {
                return (ViewGroup) menuContainer;
            }
            Object guts = readField(target, "mGuts");
            if (guts instanceof ViewGroup && !guts.getClass().getName().contains("ModalWindowView")) {
                return (ViewGroup) guts;
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T findArg(Object[] args, Class<T> clazz) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg != null && (clazz == null || clazz.isAssignableFrom(arg.getClass()))) {
                return (T) arg;
            }
        }
        return null;
    }

    private static Object findArgByClassName(Object[] args, String classNameContains) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg != null && arg.getClass().getName().contains(classNameContains)) {
                return arg;
            }
        }
        return null;
    }

    private static Object readField(Object obj, String fieldName) {
        try {
            Field f = findField(obj.getClass(), fieldName);
            if (f != null) {
                f.setAccessible(true);
                return f.get(obj);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String readStringField(Object obj, String fieldName) {
        Object v = readField(obj, fieldName);
        return v instanceof String ? (String) v : null;
    }

    private static Field findField(Class<?> cls, String name) {
        while (cls != null) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private static Object invokeMethod(Object obj, String methodName) {
        try {
            Method m = obj.getClass().getMethod(methodName);
            m.setAccessible(true);
            return m.invoke(obj);
        } catch (Throwable ignored) {}
        return null;
    }

    private static int dp(Context context, float value) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }
}
