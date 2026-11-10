package com.dlvoice.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.Button;

final class IconButton extends Button {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String label, icon;

    IconButton(Context context, String icon) {
        super(context);
        label = icon;
        this.icon = iconKey(context, icon);
        setContentDescription(icon);
    }

    void setIcon(String value) {
        if (!value.equals(label)) {
            if (label.equals(getContentDescription())) setContentDescription(value);
            label = value;
            icon = iconKey(getContext(), value);
            invalidate();
            if (Motion.enabled(getContext()) && isLaidOut()) {
                animate().cancel();
                setScaleX(.9f);
                setScaleY(.9f);
                animate().scaleX(1f).scaleY(1f).setDuration(310).setInterpolator(Motion.REBOUND).start();
            }
        }
    }

    private static android.os.LocaleList iconLocales;
    private static java.util.Map<String, String> localizedIcons = java.util.Collections.emptyMap();

    private static String iconKey(Context context, String label) {
        android.os.LocaleList locales = context.getResources().getConfiguration().getLocales();
        if (!locales.equals(iconLocales)) {
            java.util.Map<String, String> icons = new java.util.HashMap<>();
            icons.put(context.getString(R.string.text_play), "play");
            icons.put(context.getString(R.string.text_pause), "pause");
            icons.put(context.getString(R.string.text_previous), "previous");
            icons.put(context.getString(R.string.text_next), "next");
            icons.put(context.getString(R.string.text_15_sec), "seek_back");
            icons.put(context.getString(R.string.text_15_sec_2), "seek_forward");
            icons.put(context.getString(R.string.text_back), "back");
            icons.put(context.getString(R.string.text_minimize), "minimize");
            icons.put(context.getString(R.string.text_play_queue), "play_queue");
            icons.put(context.getString(R.string.text_move_up), "move_up");
            icons.put(context.getString(R.string.text_move_down), "move_down");
            icons.put(context.getString(R.string.text_remove), "remove");
            icons.put(context.getString(R.string.text_edit_queue), "edit_queue");
            icons.put(context.getString(R.string.text_done), "done");
            icons.put(context.getString(R.string.text_locate_current_lyric), "locate_current_lyric");
            icons.put(context.getString(R.string.text_locate_current_work), "locate_current_work");
            icons.put(context.getString(R.string.text_sleep_timer), "sleep_timer");
            icons.put(context.getString(R.string.text_sequential), "sequential");
            icons.put(context.getString(R.string.text_repeat_queue), "repeat_queue");
            icons.put(context.getString(R.string.text_shuffle), "shuffle");
            icons.put(context.getString(R.string.text_filter), "filter");
            icons.put(context.getString(R.string.text_expand_mini_player), "expand_mini_player");
            icons.put(context.getString(R.string.text_collapse_mini_player), "collapse_mini_player");
            icons.put(context.getString(R.string.text_clear_search), "clear_search");
            localizedIcons = icons;
            iconLocales = locales;
        }
        return localizedIcons.getOrDefault(label, label);
    }

    static boolean supports(Context context, String value) {
        switch (iconKey(context, value)) {
            case "play": case "pause": case "previous": case "next":
            case "seek_back": case "seek_forward": case "⚙": case "⋯":
            case "back": case "minimize": case "play_queue":
            case "move_up": case "move_down": case "remove": case "edit_queue": case "done":
            case "locate_current_lyric": case "locate_current_work": case "sleep_timer":
            case "sequential": case "repeat_queue": case "shuffle":
            case "filter": case "expand_mini_player": case "collapse_mini_player":
            case "clear_search":
                return true;
            default:
                return false;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = 22 * getResources().getDisplayMetrics().density;
        canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / 24, size / 24);
        paint.setColor(getCurrentTextColor());
        if (!isEnabled()) paint.setAlpha(90);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.9f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        switch (icon) {
            case "play":
                triangle(canvas, 8, 4, 8, 20, 20, 12);
                break;
            case "pause":
                paint.setStrokeWidth(3.4f);
                canvas.drawLine(8, 5, 8, 19, paint);
                canvas.drawLine(16, 5, 16, 19, paint);
                break;
            case "previous": case "next":
                if ("previous".equals(icon)) { canvas.translate(24, 0); canvas.scale(-1, 1); }
                triangle(canvas, 5, 5, 5, 19, 16, 12);
                canvas.drawLine(19, 5, 19, 19, paint);
                break;
            case "⚙":
                gear(canvas);
                break;
            case "⋯":
                paint.setStyle(Paint.Style.FILL);
                for (int x = 5; x <= 19; x += 7) canvas.drawCircle(x, 12, 1.55f, paint);
                break;
            case "back":
                line(canvas, 14, 5, 7, 12, 14, 19);
                canvas.drawLine(8, 12, 21, 12, paint);
                break;
            case "minimize":
                line(canvas, 5, 9, 12, 16, 19, 9);
                break;
            case "expand_mini_player": case "collapse_mini_player":
                if ("expand_mini_player".equals(icon)) canvas.rotate(180, 12, 12);
                line(canvas, 7, 10, 12, 15, 17, 10);
                break;
            case "filter":
                canvas.drawLine(4, 6, 20, 6, paint);
                canvas.drawLine(7, 12, 17, 12, paint);
                canvas.drawLine(10, 18, 14, 18, paint);
                break;
            case "clear_search":
                canvas.drawLine(7, 7, 17, 17, paint);
                canvas.drawLine(7, 17, 17, 7, paint);
                break;
            case "play_queue":
                canvas.drawLine(3, 6, 20, 6, paint);
                canvas.drawLine(3, 12, 20, 12, paint);
                canvas.drawLine(3, 18, 12, 18, paint);
                triangle(canvas, 16, 15, 16, 21, 22, 18);
                break;
            case "move_up": case "move_down":
                if ("move_down".equals(icon)) canvas.rotate(180, 12, 12);
                line(canvas, 6, 11, 12, 5, 18, 11);
                canvas.drawLine(12, 5, 12, 20, paint);
                break;
            case "remove":
                line(canvas, 6, 7, 7, 20, 17, 20, 18, 7);
                canvas.drawLine(4, 7, 20, 7, paint);
                line(canvas, 9, 7, 9, 4, 15, 4, 15, 7);
                break;
            case "edit_queue":
                line(canvas, 4, 15, 4, 20, 9, 20, 20, 9, 15, 4, 4, 15);
                canvas.drawLine(13, 6, 18, 11, paint);
                break;
            case "done":
                line(canvas, 4, 12, 9, 17, 20, 6);
                break;
            case "locate_current_lyric": case "locate_current_work":
                canvas.drawCircle(12, 12, 7, paint);
                canvas.drawCircle(12, 12, 2.4f, paint);
                canvas.drawLine(12, 2, 12, 5, paint);
                canvas.drawLine(12, 19, 12, 22, paint);
                canvas.drawLine(2, 12, 5, 12, paint);
                canvas.drawLine(19, 12, 22, 12, paint);
                break;
            case "sleep_timer":
                canvas.drawCircle(12, 13, 8, paint);
                canvas.drawLine(9, 2, 15, 2, paint);
                line(canvas, 12, 8, 12, 13, 16, 15);
                break;
            case "sequential":
                canvas.drawLine(3, 6, 12, 6, paint);
                canvas.drawLine(3, 12, 12, 12, paint);
                canvas.drawLine(3, 18, 12, 18, paint);
                canvas.drawLine(18, 5, 18, 19, paint);
                line(canvas, 14, 15, 18, 19, 22, 15);
                break;
            case "repeat_queue":
                line(canvas, 4, 10, 4, 6, 20, 6);
                line(canvas, 16, 2, 20, 6, 16, 10);
                line(canvas, 20, 14, 20, 18, 4, 18);
                line(canvas, 8, 14, 4, 18, 8, 22);
                break;
            case "shuffle":
                line(canvas, 3, 6, 7, 6, 17, 18, 21, 18);
                line(canvas, 17, 14, 21, 18, 17, 22);
                line(canvas, 3, 18, 7, 18, 10, 14.4f);
                line(canvas, 14, 9.6f, 17, 6, 21, 6);
                line(canvas, 17, 2, 21, 6, 17, 10);
                break;
            case "seek_back": case "seek_forward":
                seek(canvas, "seek_forward".equals(icon));
                break;
            default:
                break;
        }
        canvas.restore();
    }

    private void seek(Canvas canvas, boolean forward) {
        canvas.save();
        if (!forward) { canvas.translate(24, 0); canvas.scale(-1, 1); }
        canvas.drawArc(3, 5, 21, 23, -138, 304, false, paint);
        line(canvas, 7, 2, 5, 8, 11, 9);
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(9);
        canvas.drawText("15", 12, 17.5f, paint);
    }

    private void gear(Canvas canvas) {
        canvas.drawCircle(12, 12, 3.1f, paint);
        Path outline = new Path();
        for (int i = 0; i < 8; i++) {
            for (int tooth = 0; tooth < 4; tooth++) {
                double angle = (i * 45 + new int[]{-22, -12, 12, 22}[tooth]) * Math.PI / 180;
                int radius = tooth == 0 || tooth == 3 ? 8 : 10;
                float x = 12 + radius * (float) Math.cos(angle);
                float y = 12 + radius * (float) Math.sin(angle);
                if (i == 0 && tooth == 0) outline.moveTo(x, y); else outline.lineTo(x, y);
            }
        }
        outline.close();
        canvas.drawPath(outline, paint);
    }

    private void triangle(Canvas canvas, float... points) {
        paint.setStyle(Paint.Style.FILL);
        Path path = path(points);
        path.close();
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
    }

    private void line(Canvas canvas, float... points) { canvas.drawPath(path(points), paint); }

    private static Path path(float[] points) {
        Path path = new Path();
        path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        return path;
    }
}
