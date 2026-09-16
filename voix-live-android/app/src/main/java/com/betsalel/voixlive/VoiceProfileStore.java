package com.betsalel.voixlive;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class VoiceProfileStore {
    static final int ENROLLMENT_SAMPLES = 160_000;
    private static final String FILE_NAME = "voice_profile_16khz.pcm";

    private VoiceProfileStore() {}

    static boolean exists(Context context) {
        File file = file(context);
        return file.isFile() && file.length() == ENROLLMENT_SAMPLES * 2L;
    }

    static void saveRecording(Context context, short[] tenSeconds) throws IOException {
        if (tenSeconds.length < ENROLLMENT_SAMPLES) throw new IOException("Enregistrement trop court");
        double energy = 0;
        int clipped = 0;
        for (int i = 0; i < ENROLLMENT_SAMPLES; i += 4) {
            double value = tenSeconds[i] / 32768.0;
            energy += value * value;
            if (Math.abs(tenSeconds[i]) > 32_000) clipped++;
        }
        double rms = Math.sqrt(energy / (ENROLLMENT_SAMPLES / 4.0));
        if (rms < 0.008) throw new IOException("La voix est trop faible. Rapproche le téléphone et recommence.");
        if (clipped > 2_000) throw new IOException("La voix sature. Éloigne légèrement le téléphone et recommence.");
        ByteBuffer bytes = ByteBuffer.allocate(ENROLLMENT_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < ENROLLMENT_SAMPLES; i++) bytes.putShort(tenSeconds[i]);
        try (FileOutputStream stream = new FileOutputStream(file(context))) {
            stream.write(bytes.array());
        }
    }

    static float[][] loadFloat(Context context) throws IOException {
        byte[] bytes = new byte[ENROLLMENT_SAMPLES * 2];
        try (FileInputStream stream = new FileInputStream(file(context))) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = stream.read(bytes, offset, bytes.length - offset);
                if (read < 0) throw new IOException("Profil vocal incomplet");
                offset += read;
            }
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[][] result = new float[1][ENROLLMENT_SAMPLES];
        for (int i = 0; i < ENROLLMENT_SAMPLES; i++) result[0][i] = buffer.getShort() / 32768.0f;
        return result;
    }

    private static File file(Context context) { return new File(context.getFilesDir(), FILE_NAME); }
}
