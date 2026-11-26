package com.pocketlinux.probe;

import android.system.Os;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** 极简 tar.gz 解压器：只处理 rootfs 需要的 dir/file/symlink 与 PAX/GNU 长名。 */
public class TarExtractor {

    public interface Progress {
        void onEntry(String name, long bytesDone, long bytesTotal);
    }

    public static void extract(InputStream tarGz, Path dest, long totalCompressed, Progress cb)
            throws IOException {
        extractRaw(new GZIPInputStream(new BufferedInputStream(tarGz, 1 << 16)), dest, totalCompressed, cb);
    }

    /** 直接解压未压缩的 tar 流（aapt2 会把 assets 里的 .gz 解压成 .tar）。 */
    public static void extractRaw(InputStream tar, Path dest, long totalCompressed, Progress cb)
            throws IOException {
        Files.createDirectories(dest);
        long done = 0;
        byte[] header = new byte[512];
        String pendingName = null;

        try (InputStream gz = new BufferedInputStream(tar, 1 << 16)) {
            while (true) {
                if (!readFully(gz, header)) break;
                if (isZeroBlock(header)) break;

                String name = str(header, 0, 100);
                long size = octal(header, 124, 12);
                int type = header[156] & 0xff;
                String prefix = str(header, 345, 155);
                String link = str(header, 157, 100);
                int mode = (int) octal(header, 100, 8);
                if (!prefix.isEmpty()) name = prefix + "/" + name;

                if (type == 'L') { // GNU long name
                    byte[] nb = new byte[(int) size];
                    readFully(gz, nb);
                    pendingName = new String(nb, StandardCharsets.UTF_8).trim();
                    skipPadding(gz, size);
                    continue;
                }
                if (type == 'x' || type == 'g' || type == 'K') { // PAX 扩展头：跳过
                    skip(gz, size + padding(size));
                    continue;
                }
                if (pendingName != null) {
                    name = pendingName;
                    pendingName = null;
                }
                if (name.isEmpty()) { // 不应发生，防御
                    skip(gz, size + padding(size));
                    continue;
                }

                Path out = dest.resolve(name).normalize();
                if (!out.startsWith(dest)) { // 防目录穿越
                    skip(gz, size + padding(size));
                    continue;
                }

                if (cb != null) cb.onEntry(name, done, totalCompressed);

                if (type == '5') {                       // 目录
                    Files.createDirectories(out);
                } else if (type == '2') {                // 符号链接
                    Files.createDirectories(out.getParent());
                    Files.deleteIfExists(out);
                    try {
                        Os.symlink(link, out.toString());
                    } catch (Exception e) {
                        // 链接失败不影响主体
                    }
                } else if (type == '0' || type == 0) {   // 普通文件
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16)) {
                        copyN(gz, os, size);
                    }
                    if ((mode & 0111) != 0) out.toFile().setExecutable(true, false);
                } else {                                  // 其他类型：跳过数据
                    skip(gz, size);
                }
                skipPadding(gz, size);
                done += size;
            }
        }
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') { if (v == 0) continue; else break; }
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static long padding(long size) {
        long r = size % 512;
        return r == 0 ? 0 : 512 - r;
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long p = padding(size);
        if (p > 0) skip(in, p);
    }

    private static void skip(InputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) return;
                s = 1;
            }
            n -= s;
        }
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[1 << 16];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) return;
            out.write(buf, 0, r);
            n -= r;
        }
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) return off != 0;
            off += r;
        }
        return true;
    }
}
