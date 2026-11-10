package com.dlvoice.player;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import com.decent.usbaudio.UsbAudioDevice;



final class UsbOutput {
    static final String MODE = "usbExclusive";
    static final String PERMISSION = "com.dlvoice.player.USB_PERMISSION";
    private static final java.util.Set<Integer> pending = new java.util.HashSet<>();

    static boolean available(Context context) {
        return Build.VERSION.SDK_INT >= 29 && context.getPackageManager()
                .hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST);
    }

    static UsbDevice device(Context context) {
        return available(context) ? driver(context).findUsbAudioDevice() : null;
    }

    static boolean authorized(Context context, UsbDevice device) {
        return device != null && context.getSystemService(UsbManager.class).hasPermission(device);
    }

    static synchronized void request(Context context, UsbDevice device) {
        if (authorized(context, device) || !pending.add(device.getDeviceId())) return;
        PendingIntent result = PendingIntent.getBroadcast(context, device.getDeviceId(),
                new Intent(PERMISSION).setPackage(context.getPackageName()),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        context.getSystemService(UsbManager.class).requestPermission(device, result);
    }

    static synchronized void handleEvent(Intent intent) {
        if (!PERMISSION.equals(intent.getAction())
                && !UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) return;
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device != null) pending.remove(device.getDeviceId());
    }

    static boolean supports(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(wav|mp3|flac)$");
    }

    static ExoPlayer create(Context context) {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context) {
            @Override protected AudioSink buildAudioSink(Context ctx, boolean floatOutput, boolean playbackParams) {
                DefaultAudioSink delegate = new DefaultAudioSink.Builder(ctx)
                        .setEnableFloatOutput(true)
                        .setAudioTrackProvider((config, attributes, id, app) -> {
                            throw new IllegalStateException("USB exclusive output unavailable");
                        }).build();
                return new UsbClockSink(delegate, ctx);
            }
        };
        renderers.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        ExoPlayer player = new ExoPlayer.Builder(context, renderers).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        player.setPlaybackSpeed(1f);
        return player;
    }

    static void load(ExoPlayer player, Uri uri, long position) {
        player.setMediaItem(MediaItem.fromUri(uri), position);
        player.prepare();
    }

    static void closeDevice(Context context) { driver(context).closeDevice(); }

    private static UsbAudioDevice driver(Context context) {
        return UsbAudioDevice.Companion.getInstance(context.getApplicationContext());
    }

    private UsbOutput() {}
}

