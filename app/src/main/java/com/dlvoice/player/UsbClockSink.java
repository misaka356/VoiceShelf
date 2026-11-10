package com.dlvoice.player;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.SystemClock;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import com.decent.usbaudio.UsbAudioDevice;
import com.decent.usbaudio.UsbAudioDeviceInfo;
import com.decent.usbaudio.UsbAudioStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Sends PCM only to the userspace USB stream. No Android AudioTrack is initialized. */
final class UsbClockSink extends ForwardingAudioSink {
    private final Context context;
    private final UsbAudioDevice device;
    private UsbAudioStream stream;
    private Format format;
    private long anchor = CURRENT_POSITION_NOT_SET, elapsed, started, submittedEnd;
    private boolean playing, ended;
    private int sampleRate, sampleBytes, inputChannels, outputChannels;
    private float volume = 1f;

    @Override public void setVolume(float value) { volume = Math.max(0f, Math.min(1f, value)); }

    UsbClockSink(AudioSink metadataSink, Context context) {
        super(metadataSink);
        this.context = context;
        device = UsbAudioDevice.Companion.getInstance(context);
    }

    @Override public int getFormatSupport(Format input) {
        return input.pcmEncoding == 2 || input.pcmEncoding == 21 || input.pcmEncoding == 22 || input.pcmEncoding == 4
                ? SINK_FORMAT_SUPPORTED_DIRECTLY : SINK_FORMAT_UNSUPPORTED;
    }

    @Override public boolean supportsFormat(Format input) { return getFormatSupport(input) != SINK_FORMAT_UNSUPPORTED; }

    @Override public void configure(Format input, int bufferSize, int[] channels) throws ConfigurationException {
        releaseStream();
        format = input;
        sampleRate = input.sampleRate;
        inputChannels = input.channelCount;
        sampleBytes = input.pcmEncoding == 2 ? 2 : input.pcmEncoding == 21 ? 3 : 4;
        try {
            if (getFormatSupport(input) == SINK_FORMAT_UNSUPPORTED || sampleRate <= 0 || inputChannels < 1 || inputChannels > 2) {
                throw new IllegalStateException(UiLanguage.text(context, R.string.text_usb_does_not_support_this_pcm_format));
            }
            UsbDevice dac = device.findUsbAudioDevice();
            if (dac == null || !device.hasPermission(dac)) throw new IllegalStateException(UiLanguage.text(context, R.string.text_usb_device_disconnected_or_not_authorized));
            UsbAudioDeviceInfo info = device.openDevice(dac);
            if (info == null) throw new IllegalStateException(UiLanguage.text(context, R.string.text_could_not_claim_the_usb_audio_interface_reconnec));
            byte[] descriptors = info.getConnection().getRawDescriptors();
            int controlInterface = 0, currentInterface = -1, currentAlt = -1;
            int containerBits = 0;
            outputChannels = 0;
            for (int i = 0; i + 2 <= descriptors.length;) {
                int length = descriptors[i] & 255;
                if (length < 2 || i + length > descriptors.length) break;
                int type = descriptors[i + 1] & 255;
                if (type == 4 && length >= 9) {
                    currentInterface = descriptors[i + 2] & 255;
                    currentAlt = descriptors[i + 3] & 255;
                    if (descriptors[i + 5] == 1 && descriptors[i + 6] == 1) controlInterface = currentInterface;
                }
                if (currentInterface == info.getInterfaceId() && currentAlt == info.getBestAltSetting()
                        && type == 0x24 && length >= 6) {
                    int subtype = descriptors[i + 2] & 255;
                    if (subtype == 2) containerBits = (descriptors[i + 4] & 255) * 8;
                    if (subtype == 1 && length >= 16) outputChannels = descriptors[i + 10] & 255;
                }
                i += length;
            }
            if ((containerBits != 16 && containerBits != 24 && containerBits != 32)
                    || outputChannels < 1 || outputChannels > 2
                    || (inputChannels != outputChannels && inputChannels != 1)) {
                throw new IllegalStateException(UiLanguage.text(context, R.string.text_unsupported_sample_container_or_channel_layout_i));
            }
            if (!device.setAltSetting(0)) throw new IllegalStateException(UiLanguage.text(context, R.string.text_could_not_reset_the_usb_audio_interface_reconnec));
            byte[] rate = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate).array();
            int clockIndex = (info.getClockSourceId() << 8) | controlInterface;
            if (info.getClockSourceId() <= 0 || info.getConnection().controlTransfer(
                    0x21, 1, 0x100, clockIndex, rate, 4, 1000) != 4) {
                throw new IllegalStateException(UiLanguage.text(context, R.string.text_dac_rejected_the) + sampleRate + UiLanguage.text(context, R.string.text_hz_sample_rate));
            }
            byte[] actual = new byte[4];
            if (info.getConnection().controlTransfer(0xa1, 1, 0x100, clockIndex, actual, 4, 1000) != 4
                    || ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).getInt() != sampleRate) {
                throw new IllegalStateException(UiLanguage.text(context, R.string.text_usb_sample_rate_differs_from_the_source_audio));
            }
            stream = new UsbAudioStream(info.getFd(), info.getInterfaceId(), info.getEndpointOutAddress(),
                    info.getEndpointFeedbackAddress(), sampleRate, outputChannels, containerBits, info.getMaxPacketSize());
            if (!stream.isReady() || !device.setAltSetting(info.getBestAltSetting())) {
                throw new IllegalStateException(UiLanguage.text(context, R.string.text_could_not_initialize_usb_isochronous_transfer));
            }
            Thread.sleep(50);
            if (!stream.start()) throw new IllegalStateException(UiLanguage.text(context, R.string.text_usb_isochronous_transfer_failed_to_start));
            android.util.Log.i("YinqiUsb", "USB direct: " + sampleRate + " Hz, " + outputChannels
                    + " ch, " + containerBits + "-bit container");
            resetClock();
        } catch (Exception issue) {
            releaseStream();
            throw new ConfigurationException(issue, input);
        }
    }

    @Override public boolean handleBuffer(ByteBuffer buffer, long timestamp, int count) throws WriteException {
        if (stream == null || !stream.isAlive()) throw new WriteException(-1, format, false);
        if (anchor == CURRENT_POSITION_NOT_SET) {
            anchor = submittedEnd = timestamp;
            started = SystemClock.elapsedRealtime();
        }
        long now = SystemClock.elapsedRealtime();
        long estimated = position();
        if (estimated > submittedEnd + 40_000) {
            elapsed = Math.max(0, submittedEnd - anchor);
            started = now;
        }
        if (timestamp > position() + 40_000) return false;
        int frames = buffer.remaining() / (sampleBytes * inputChannels);
        if (format.pcmEncoding == 4) {
            float[] pcm = new float[frames * outputChannels];
            ByteBuffer input = buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
            for (int frame = 0; frame < frames; frame++) {
                float first = input.getFloat();
                pcm[frame * outputChannels] = first * volume;
                if (outputChannels == 2) pcm[frame * 2 + 1] = (inputChannels == 2 ? input.getFloat() : first) * volume;
            }
            stream.write(pcm);
        } else {
            byte[] pcm = new byte[frames * sampleBytes * outputChannels];
            if (inputChannels == outputChannels) buffer.get(pcm);
            else {
                byte[] sample = new byte[sampleBytes];
                for (int frame = 0; frame < frames; frame++) {
                    buffer.get(sample);
                    for (int ch = 0; ch < outputChannels; ch++) {
                        System.arraycopy(sample, 0, pcm, (frame * outputChannels + ch) * sampleBytes, sampleBytes);
                    }
                }
            }
            if (volume != 1f) {
                for (int offset = 0; offset < pcm.length; offset += sampleBytes) {
                    int sample = pcm[offset + sampleBytes - 1];
                    for (int i = sampleBytes - 2; i >= 0; i--) sample = (sample << 8) | (pcm[offset + i] & 255);
                    long scaled = Math.round(sample * (double) volume);
                    for (int i = 0; i < sampleBytes; i++) pcm[offset + i] = (byte) (scaled >> (8 * i));
                }
            }
            stream.writeRaw(pcm, format.pcmEncoding);
        }
        buffer.position(buffer.limit());
        submittedEnd = Math.max(submittedEnd, timestamp + frames * 1_000_000L / sampleRate);
        ended = false;
        return true;
    }

    private long position() {
        return anchor + elapsed + (playing ? (SystemClock.elapsedRealtime() - started) * 1000 : 0);
    }

    @Override public long getCurrentPositionUs(boolean sourceEnded) {
        return anchor == CURRENT_POSITION_NOT_SET ? CURRENT_POSITION_NOT_SET : Math.min(position(), submittedEnd);
    }
    @Override public void play() {
        if (!playing) { started = SystemClock.elapsedRealtime(); playing = true; }
    }
    @Override public void pause() {
        if (playing) elapsed += (SystemClock.elapsedRealtime() - started) * 1000;
        playing = false;
    }
    @Override public void playToEndOfStream() { ended = true; }
    @Override public boolean hasPendingData() { return anchor != CURRENT_POSITION_NOT_SET && position() < submittedEnd; }
    @Override public boolean isEnded() { return ended && !hasPendingData(); }
    @Override public void flush() { if (stream != null) stream.flush(); resetClock(); }
    @Override public void reset() { releaseStream(); resetClock(); super.reset(); }
    @Override public void release() { releaseStream(); super.release(); }
    private void releaseStream() {
        if (stream != null) {
            // Disable the endpoint before freeing queued transfers, so pause does not play their tail.
            device.setAltSetting(0);
            stream.release();
            stream = null;
        }
    }
    private void resetClock() {
        anchor = CURRENT_POSITION_NOT_SET;
        elapsed = submittedEnd = 0;
        ended = false;
        started = SystemClock.elapsedRealtime();
    }
}
