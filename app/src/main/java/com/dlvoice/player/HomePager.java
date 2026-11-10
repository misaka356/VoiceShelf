package com.dlvoice.player;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/** Keeps the visible pages edge to edge throughout the drag and its settle. */
final class HomePager extends FrameLayout {
    private final View[] pages;
    private int current, neighbor = -1;
    private float offset;
    private ValueAnimator slide;

    HomePager(Context context, View[] pages, int current) {
        super(context);
        this.pages = pages;
        this.current = current;
        for (View page : pages) addView(page, new FrameLayout.LayoutParams(-1, -1));
        finish();
    }

    void drag(float distance) {
        if (!Motion.enabled(getContext())) return;
        if (slide != null) finish();
        int target = current + (distance < 0 ? 1 : -1);
        neighbor = target >= 0 && target < pages.length ? target : -1;
        float width = getWidth();
        float limit = neighbor < 0 ? width * .08f : width;
        float movement = neighbor < 0 ? distance * .22f : distance;
        offset = Math.max(-limit, Math.min(limit, movement));
        position(current, neighbor, offset);
    }

    void settle(int target, float velocity) {
        if (slide != null) finish();
        int from = current;
        int adjacent = from == target ? neighbor : target;
        float width = getWidth();
        float end = Integer.compare(from, target) * width;
        float remaining = Math.abs(end - offset);
        current = target;
        if (width == 0 || remaining < 1 || !Motion.enabled(getContext())) { finish(); return; }

        float density = getResources().getDisplayMetrics().density;
        float speed = Math.min(85f, Math.abs(velocity) / (density * 30f));
        long duration = Math.round(Math.max(155f, 175f + 150f * remaining / width - speed));
        position(from, adjacent, offset);
        ValueAnimator animation = ValueAnimator.ofFloat(offset, end);
        slide = animation;
        animation.setDuration(duration);
        animation.setInterpolator(Motion.SETTLE);
        animation.addUpdateListener(value -> position(from, adjacent, (float) value.getAnimatedValue()));
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ended) {
                if (slide == ended) {
                    slide = null;
                    finish();
                }
            }
        });
        animation.start();
    }

    void finish() {
        ValueAnimator running = slide;
        slide = null;
        if (running != null) running.cancel();
        neighbor = -1;
        offset = 0;
        position(current, -1, 0);
    }

    boolean isMoving() { return slide != null || offset != 0; }

    void replacePage(int index, View page) {
        removeView(pages[index]);
        pages[index] = page;
        page.setVisibility(index == current ? VISIBLE : INVISIBLE);
        addView(page, index, new FrameLayout.LayoutParams(-1, -1));
    }

    private void position(int from, int to, float x) {
        for (int i = 0; i < pages.length; i++) {
            View page = pages[i];
            if (i == from || i == to) {
                page.setTranslationX(i == from ? x : x + Integer.compare(i, from) * getWidth());
                page.setVisibility(VISIBLE);
            } else page.setVisibility(INVISIBLE);
        }
    }

    @Override protected void onDetachedFromWindow() {
        finish();
        super.onDetachedFromWindow();
    }
}
