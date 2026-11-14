package io.mo.mnblocker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.util.Property;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import java.util.HashMap;
import java.util.Map;

/**
 * Motion tokens and a physics spring, built only on framework APIs (the app has
 * no AndroidX, so no {@code SpringAnimation}).
 *
 * Every interactive animation in the UI is a spring, never a fixed-duration
 * tween: a tween locks the view into its curve until it ends, while a spring
 * can be handed a new target mid-flight and carries its current velocity into
 * the new motion. That is what makes a rapid second tap feel like it redirects
 * the first instead of queueing behind it.
 *
 * Tokens (mass 1, stiffness = (2π / period)²):
 *   STANDARD  ζ 1.0  k 380   ~0.32 s, no overshoot — tabs, pages, expand/collapse
 *   FLING     ζ 0.8  k 320   ~0.35 s, slight overshoot — things released or landing
 *   MICRO     ζ 0.6  k 1500  ~0.16 s — press scale, indicators, toggles
 */
final class Motion {

    private Motion() {}

    static final class Spec {
        final float damping;
        final float stiffness;

        Spec(float damping, float stiffness) {
            this.damping = damping;
            this.stiffness = stiffness;
        }
    }

    static final Spec STANDARD = new Spec(1.0f, 380f);
    static final Spec FLING = new Spec(0.8f, 320f);
    static final Spec MICRO = new Spec(0.6f, 1500f);

    /** Scale a pressed control dips to. */
    static final float PRESS_SCALE = 0.96f;
    /** Gentler dip for large surfaces (cards, rows): 4% of a full-width card is a lot of pixels. */
    static final float PRESS_SCALE_LARGE = 0.985f;

    /**
     * Whether the user turned animations off (developer options "Animator
     * duration scale" = off, or the accessibility "Remove animations" switch,
     * which sets the same value). Springs then jump straight to their target.
     */
    static boolean reduced(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return !ValueAnimator.areAnimatorsEnabled();
        }
        try {
            return Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Spring
    // ------------------------------------------------------------------

    /** Receives every frame's value. */
    interface Listener {
        void onUpdate(float value);
    }

    /**
     * A one-dimensional damped spring, stepped on the display's vsync.
     *
     * {@link #animateTo} may be called at any time; the motion continues from
     * the current value and velocity toward the new target. The end action
     * belongs to the latest {@code animateTo} call only — a retarget replaces it,
     * so a superseded motion never runs its "arrived" side effects.
     */
    static final class Spring implements Choreographer.FrameCallback {

        /** Integration step; small enough to stay stable at MICRO's stiffness. */
        private static final float STEP_S = 0.004f;
        /** A dropped-frame gap larger than this is integrated as if it were this. */
        private static final float MAX_FRAME_S = 0.064f;
        /**
         * The display's frame interval, as last measured by any running spring.
         * A spring's first frame has no previous one to measure from; stepping it
         * a fixed 1/60 s made every motion open with a double-length jump on a
         * 120 Hz panel.
         */
        private static float sFrameS = 1f / 60f;

        private final Listener listener;
        private final float restDelta;
        private float value;
        private float velocity;
        private float target;
        private Spec spec = STANDARD;
        private boolean running;
        private long lastFrameNanos;
        private Runnable endAction;

        Spring(float initial, float restDelta, Listener listener) {
            this.value = initial;
            this.target = initial;
            this.restDelta = restDelta;
            this.listener = listener;
        }

        float value() {
            return value;
        }

        float target() {
            return target;
        }

        boolean isRunning() {
            return running;
        }

        void animateTo(float newTarget, Spec newSpec, Runnable onEnd) {
            target = newTarget;
            spec = newSpec;
            endAction = onEnd;
            if (!running) {
                if (Math.abs(value - target) < restDelta && Math.abs(velocity) < restDelta) {
                    settle();
                    return;
                }
                running = true;
                lastFrameNanos = 0;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        /** Jump to {@code v} with no motion, dropping any pending end action. */
        void snapTo(float v) {
            stop();
            value = v;
            target = v;
            velocity = 0f;
            endAction = null;
            listener.onUpdate(v);
        }

        /** Freeze where it is — the next {@link #animateTo} picks up from here. */
        void stop() {
            if (running) {
                running = false;
                Choreographer.getInstance().removeFrameCallback(this);
            }
        }

        /** Set the current value without touching the target or motion. */
        void jumpValue(float v) {
            value = v;
            listener.onUpdate(v);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running) {
                return;
            }
            float dt;
            if (lastFrameNanos == 0) {
                dt = sFrameS;
            } else {
                dt = Math.min((frameTimeNanos - lastFrameNanos) / 1e9f, MAX_FRAME_S);
                if (dt > 0f && dt < MAX_FRAME_S) {
                    sFrameS = dt;
                }
            }
            lastFrameNanos = frameTimeNanos;

            float omega = (float) Math.sqrt(spec.stiffness);
            float c = 2f * spec.damping * omega;
            while (dt > 0f) {
                float h = Math.min(dt, STEP_S);
                // Semi-implicit Euler: velocity first, then position from the new velocity.
                float accel = -spec.stiffness * (value - target) - c * velocity;
                velocity += accel * h;
                value += velocity * h;
                dt -= h;
            }

            if (Math.abs(value - target) < restDelta && Math.abs(velocity) < restDelta * 10f) {
                running = false;
                settle();
                return;
            }
            listener.onUpdate(value);
            Choreographer.getInstance().postFrameCallback(this);
        }

        private void settle() {
            value = target;
            velocity = 0f;
            listener.onUpdate(value);
            Runnable r = endAction;
            endAction = null;
            if (r != null) {
                r.run();
            }
        }
    }

    // ------------------------------------------------------------------
    // View properties
    // ------------------------------------------------------------------

    /**
     * Spring {@code view}'s {@code property} to {@code target}.
     *
     * One spring is kept per (view, property) — so calling this again while the
     * previous motion is still running retargets it with its velocity intact,
     * which is the whole point. With animations disabled it just sets the value.
     */
    static void animate(View view, Property<View, Float> property, float target,
                        Spec spec, Runnable onEnd) {
        if (reduced(view.getContext())) {
            Spring s = existing(view, property);
            if (s != null) {
                s.snapTo(target);
            } else {
                property.set(view, target);
            }
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        springFor(view, property).animateTo(target, spec, onEnd);
    }

    static void animate(View view, Property<View, Float> property, float target, Spec spec) {
        animate(view, property, target, spec, null);
    }

    /** Set a property immediately, stopping any spring that was driving it. */
    static void set(View view, Property<View, Float> property, float value) {
        Spring s = existing(view, property);
        if (s != null) {
            s.snapTo(value);
        } else {
            property.set(view, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Spring existing(View view, Property<View, Float> property) {
        Object tag = view.getTag(R.id.motion_springs);
        if (!(tag instanceof Map)) {
            return null;
        }
        return ((Map<Property<View, Float>, Spring>) tag).get(property);
    }

    @SuppressWarnings("unchecked")
    private static Spring springFor(final View view, final Property<View, Float> property) {
        Object tag = view.getTag(R.id.motion_springs);
        Map<Property<View, Float>, Spring> map;
        if (tag instanceof Map) {
            map = (Map<Property<View, Float>, Spring>) tag;
        } else {
            map = new HashMap<>();
            view.setTag(R.id.motion_springs, map);
        }
        Spring s = map.get(property);
        if (s == null) {
            // Translations are in px; everything else here is a unit-ish fraction.
            boolean pixels = property == View.TRANSLATION_X || property == View.TRANSLATION_Y;
            s = new Spring(property.get(view), pixels ? 0.5f : 0.002f,
                    v -> property.set(view, v));
            map.put(property, s);
        } else if (!s.isRunning()) {
            // Someone may have set the property directly since the spring last ran.
            s.jumpValue(property.get(view));
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Press feedback
    // ------------------------------------------------------------------

    /**
     * Press feedback: the view dips while held and springs back on release.
     *
     * Returns {@code false} from the touch listener so the view's own click /
     * ripple handling still runs — this only draws.
     */
    static void pressable(final View view, final float pressedScale) {
        pressable(view, view, pressedScale);
    }

    /** As above, but the touch lands on {@code target} while {@code scaled} dips. */
    static void pressable(final View target, final View scaled, final float pressedScale) {
        target.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (v.isEnabled()) {
                        animate(scaled, View.SCALE_X, pressedScale, MICRO);
                        animate(scaled, View.SCALE_Y, pressedScale, MICRO);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animate(scaled, View.SCALE_X, 1f, MICRO);
                    animate(scaled, View.SCALE_Y, 1f, MICRO);
                    break;
                default:
                    break;
            }
            return false;
        });
    }
}
