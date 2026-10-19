package io.github.wayneshao.rayneo.ime;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit short dictation only. Audio stays in memory and recognition runs on-device. */
final class OfflineVoice {
    interface Listener { void status(String message); void result(String text); void stopped(); }
    private static final AtomicBoolean IN_USE = new AtomicBoolean();
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled, stop, active;
    private volatile AudioRecord recorder;
    OfflineVoice(Context context, Listener listener) { this.context = context.getApplicationContext(); this.listener = listener; }
    static boolean permission(Context context) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true;
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) { ((Activity) current).requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 0x5249); break; }
            Context next = ((ContextWrapper) current).getBaseContext(); if (next == current) break; current = next;
        }
        return false;
    }
    boolean active() { return active; }
    void start() {
        if (!IN_USE.compareAndSet(false, true)) { listener.status("上一段语音正在结束，请稍后重试"); listener.stopped(); return; }
        active = true;
        new Thread(this::run, "embedded-dictation").start();
    }
    void stop() { stop = true; }
    void cancel() { cancelled = true; stop = true; AudioRecord r = recorder; if (r != null) { try { r.stop(); } catch (IllegalStateException ignored) { } } }
    private void report(String message) { main.post(() -> { if (!cancelled) listener.status(message); }); }
    private void run() {
        OnlineRecognizer recognizer = null; OnlineStream stream = null;
        ArrayList<float[]> chunks = new ArrayList<>();
        try {
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Audio format");
            AudioRecord recording = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum * 2, 8192));
            recorder = recording;
            if (recording.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Microphone initialization");
            if (cancelled) return;
            recording.startRecording(); report("正在听… 再次选择“结束”识别（最长 20 秒）");
            short[] buffer = new short[1600]; int total = 0;
            while (!stop && !cancelled && total < 320000) {
                int count = recording.read(buffer, 0, buffer.length);
                if (count < 0) { if (cancelled) return; throw new IllegalStateException("Microphone read"); }
                if (count == 0) continue;
                float[] values = new float[count]; for (int i = 0; i < count; i++) values[i] = buffer[i] / 32768f;
                chunks.add(values); total += count;
            }
            recording.stop(); recording.release(); recorder = null;
            if (cancelled || total == 0) return;
            report("正在本机识别…");
            File directory = prepareModels();
            if (cancelled) return;
            System.loadLibrary("sherpa-onnx-jni"); LibraryLoader.setAutoLoadEnabled(false);
            OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                    .setEncoder(new File(directory, "encoder.int8.onnx").getPath())
                    .setDecoder(new File(directory, "decoder.onnx").getPath())
                    .setJoiner(new File(directory, "joiner.int8.onnx").getPath()).build();
            OnlineModelConfig model = OnlineModelConfig.builder().setTransducer(transducer)
                    .setTokens(new File(directory, "tokens.txt").getPath()).setNumThreads(1).setDebug(false)
                    .setProvider("cpu").setModelType("zipformer").build();
            recognizer = new OnlineRecognizer(OnlineRecognizerConfig.builder().setOnlineModelConfig(model).setEnableEndpoint(false).build());
            stream = recognizer.createStream();
            for (float[] values : chunks) {
                if (cancelled) return;
                stream.acceptWaveform(values, 16000);
                while (!cancelled && recognizer.isReady(stream)) recognizer.decode(stream);
                Arrays.fill(values, 0);
            }
            stream.acceptWaveform(new float[8000], 16000); stream.inputFinished();
            while (!cancelled && recognizer.isReady(stream)) recognizer.decode(stream);
            if (cancelled) return;
            final String text = recognizer.getResult(stream).getText().trim();
            main.post(() -> { if (!cancelled) { if (!text.isEmpty()) listener.result(text); listener.status(text.isEmpty() ? "未识别到文字，请重试" : "语音已填入"); } });
        } catch (Exception | LinkageError error) {
            report("语音识别失败，请检查麦克风权限后重试");
            android.util.Log.w("EmbeddedInput", "Dictation failed: " + error.getClass().getSimpleName());
        } finally {
            AudioRecord r = recorder; recorder = null;
            if (r != null) { try { r.stop(); } catch (IllegalStateException ignored) { } r.release(); }
            if (stream != null) stream.release(); if (recognizer != null) recognizer.release();
            for (float[] values : chunks) Arrays.fill(values, 0); chunks.clear();
            active = false; IN_USE.set(false); main.post(() -> { if (!cancelled) listener.stopped(); });
        }
    }
    private File prepareModels() throws Exception {
        File directory = new File(context.getFilesDir(), "embedded-ime/voice-20230220"); directory.mkdirs();
        for (String name : new String[]{"encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"}) {
            if (cancelled) return directory;
            File target = new File(directory, name); if (target.isFile()) continue;
            File temporary = new File(directory, name + ".tmp");
            try (InputStream in = context.getAssets().open("embedded-ime/voice/" + name); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] bytes = new byte[65536]; int count; while ((count = in.read(bytes)) != -1) { if (cancelled) return directory; out.write(bytes, 0, count); }
                out.getFD().sync();
            }
            if (!temporary.renameTo(target)) throw new IllegalStateException("Model install");
        }
        return directory;
    }
}
