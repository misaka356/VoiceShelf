package com.dlvoice.player;

import android.animation.ValueAnimator;
import android.content.Context;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.Window;
import android.view.animation.PathInterpolator;

final class Motion {
    // Window animations use the matching curves in res/interpolator.
    static final PathInterpolator ENTER = new PathInterpolator(.30f, .06f, .22f, 1f);
    static final PathInterpolator EXIT = new PathInterpolator(.36f, 0f, .62f, .46f);
    static final PathInterpolator REBOUND = new PathInterpolator(.20f, .70f, .32f, 1.13f);
    static final PathInterpolator SETTLE = new PathInterpolator(.12f, .72f, .24f, 1f);

    static boolean enabled(Context context) {
        return ValueAnimator.areAnimatorsEnabled()
                && !context.getSharedPreferences("library", Context.MODE_PRIVATE).getBoolean("reduceMotion", false);
    }

    static void press(View view) {
        view.setOnTouchListener((button, event) -> {
            int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return false;
            if (!enabled(button.getContext())) {
                button.setScaleX(1);
                button.setScaleY(1);
            } else if (action == MotionEvent.ACTION_DOWN) {
                button.animate().cancel();
                button.setScaleX(.983f);
                button.setScaleY(.983f);
                button.animate().scaleX(.966f).scaleY(.966f).setDuration(90).setInterpolator(SETTLE).start();
            } else {
                button.animate().scaleX(1f).scaleY(1f).setDuration(210).setInterpolator(REBOUND).start();
            }
            return false;
        });
    }

    static void layout(ViewGroup parent) {
        if (!enabled(parent.getContext()) || !parent.isLaidOut()) return;
        TransitionSet transition = new TransitionSet().setOrdering(TransitionSet.ORDERING_TOGETHER)
                .addTransition(new ChangeBounds().setDuration(390).setInterpolator(ENTER))
                .addTransition(new Fade(Fade.IN).setDuration(310).setInterpolator(ENTER))
                .addTransition(new Fade(Fade.OUT).setDuration(230).setInterpolator(EXIT));
        TransitionManager.beginDelayedTransition(parent, transition);
    }

    static void appear(View view) {
        if (!enabled(view.getContext())) return;
        enter(view, 0, 0, .60f, 320).start();
    }

    static ViewPropertyAnimator enter(View view, float x, float y, float alpha, long duration) {
        view.animate().cancel();
        view.setAlpha(alpha);
        view.setTranslationX(x);
        view.setTranslationY(y);
        return view.animate().alpha(1f).translationX(0).translationY(0).setDuration(duration).setInterpolator(ENTER);
    }

    static void reset(View view) {
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationX(0);
        view.setTranslationY(0);
    }

    static void window(Window window, int style) {
        window.setWindowAnimations(enabled(window.getContext()) ? style : 0);
    }

    private Motion() {}
}
