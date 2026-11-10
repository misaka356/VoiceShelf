package com.dlvoice.player;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import java.util.Locale;

enum UiLanguage {
    CHINESE("zh-CN", "简体中文", "Simplified Chinese"),
    ENGLISH("en", "English", "English"),
    JAPANESE("ja", "日本語", "Japanese");

    final String tag, label, target;
    private final Locale locale;

    UiLanguage(String tag, String label, String target) {
        this.tag = tag;
        this.label = label;
        this.target = target;
        locale = Locale.forLanguageTag(tag);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    }

    static UiLanguage current(Context context) {
        LocaleList locales;
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            locales = manager.getApplicationLocales();
            if (locales.isEmpty()) locales = manager.getSystemLocales();
        } else {
            String tag = preferences(context).getString("language", "");
            for (UiLanguage language : values()) if (language.tag.equals(tag)) return language;
            locales = Resources.getSystem().getConfiguration().getLocales();
        }
        for (int i = 0; i < locales.size(); i++) {
            for (UiLanguage language : values()) {
                if (language.locale.getLanguage().equals(locales.get(i).getLanguage())) return language;
            }
        }
        return ENGLISH;
    }

    static Context attach(Context context) {
        return Build.VERSION.SDK_INT >= 33 ? context : current(context).localized(context);
    }

    void select(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager.class).setApplicationLocales(new LocaleList(locale));
        } else {
            preferences(activity).edit().putString("language", tag).apply();
            activity.recreate();
        }
    }

    private Context localized(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocales(new LocaleList(locale));
        return context.createConfigurationContext(config);
    }

    static String text(Context context, int id, Object... args) {
        UiLanguage language = current(context);
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        Context localized = locale != null && locale.getLanguage().equals(language.locale.getLanguage())
                ? context : language.localized(context);
        return args.length == 0 ? localized.getString(id) : localized.getString(id, args);
    }
}
