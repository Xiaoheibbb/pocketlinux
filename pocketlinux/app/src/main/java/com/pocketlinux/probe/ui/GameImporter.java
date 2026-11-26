package com.pocketlinux.probe.ui;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 从系统文件选择器导入 exe / 游戏文件夹的公共逻辑（后台线程调用）。
 * 目标目录约定：files/games/&lt;id&gt;/，MainActivity 会把它映射成容器里的 /games。
 */
public final class GameImporter {
    private static final String TAG = "PLPROBE";

    private GameImporter() {}

    public static final class Result {
        public String name;
        /** App 私有目录里的 exe 绝对路径（MainActivity 合同里的 EXTRA_EXE）。 */
        public String exePath;
        /** App 私有目录里的工作目录绝对路径（EXTRA_WORKDIR）。 */
        public String workDir;
        public long size;
        public File dir;
        /** 导入文件夹时复制失败的文件数（0 表示全部成功）。 */
        public int failedFiles;
    }

    /** 导入单个文件（exe）。 */
    public static Result importFile(Context ctx, Uri uri, File gamesRoot) throws IOException {
        String display = displayName(ctx, uri);
        // 单文件只接受 .exe：从「下载/文档」里选出来的可能是 docId 或别的格式
        if (display == null || !display.toLowerCase().endsWith(".exe")) {
            throw new IOException("请选择 .exe 文件（当前选择：" + display + "）");
        }
        File dir = new File(gamesRoot, String.valueOf(System.currentTimeMillis()));
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法创建目录");
        File dest = new File(dir, safeName(display));
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("无法读取所选文件");
            copy(in, out);
        } catch (Throwable t) {
            deleteTree(dir);
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException("导入失败：" + t.getMessage(), t);
        }
        Result r = new Result();
        r.dir = dir;
        r.exePath = dest.getAbsolutePath();
        r.workDir = dir.getAbsolutePath();
        r.name = stripExt(display);
        r.size = dest.length();
        return r;
    }

    /** 导入整个文件夹，自动寻找第一个 .exe 作为启动目标。 */
    public static Result importTree(Context ctx, Uri treeUri, File gamesRoot) throws IOException {
        String display = displayName(ctx, treeUri);
        File dir = new File(gamesRoot, "tree-" + System.currentTimeMillis());
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法创建目录");
        int failed;
        try {
            failed = copyDocTree(ctx, treeUri, dir);
        } catch (Throwable t) {
            deleteTree(dir);
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException("导入失败：" + t.getMessage(), t);
        }
        File exe = findFirstExe(dir);
        if (exe == null) {
            deleteTree(dir);
            throw new IOException("这个文件夹里没有找到 .exe 文件");
        }
        Result r = new Result();
        r.dir = dir;
        r.exePath = exe.getAbsolutePath();
        r.workDir = exe.getParentFile().getAbsolutePath();
        r.name = display == null || display.isEmpty() ? stripExt(exe.getName()) : display;
        r.size = dirSize(dir);
        r.failedFiles = failed;
        return r;
    }

    public static File findFirstExe(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".exe")) return f;
        }
        for (File f : fs) {
            if (f.isDirectory()) {
                File r = findFirstExe(f);
                if (r != null) return r;
            }
        }
        return null;
    }

    public static long dirSize(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) s += dirSize(c);
        return s;
    }

    public static void deleteTree(File f) {
        if (f == null) return;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024L * 1024L) return String.format("%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024L * 1024L) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static String stripExt(String n) {
        if (n == null) return "game";
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    /** 选中项显示名：普通文件走 DISPLAY_NAME，文件夹走 tree document id 的末段。 */
    public static String displayName(Context ctx, Uri uri) {
        try {
            if (DocumentsContract.isTreeUri(uri)) {
                String docId = DocumentsContract.getTreeDocumentId(uri);
                if (docId != null) {
                    int c = docId.indexOf(':');
                    String tail = c >= 0 ? docId.substring(c + 1) : docId;
                    int slash = tail.lastIndexOf('/');
                    if (slash >= 0) tail = tail.substring(slash + 1);
                    if (!tail.isEmpty()) return tail;
                }
            }
            try (Cursor c = ctx.getContentResolver().query(
                    uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String n = c.getString(0);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Throwable ignored) {
        }
        String seg = uri.getLastPathSegment();
        if (seg == null) return "程序";
        int colon = seg.indexOf(':');
        if (colon >= 0) seg = seg.substring(colon + 1);
        int i = seg.lastIndexOf('/');
        seg = i >= 0 ? seg.substring(i + 1) : seg;
        return seg.isEmpty() ? "程序" : seg;
    }

    /** 文件名去掉路径分隔符等非法字符。 */
    private static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) return "program.exe";
        String s = name.replace('/', '_').replace('\\', '_').replace('\u0000', '_');
        return s.replaceAll("[\\p{Cntrl}]", "_");
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static int copyDocTree(Context ctx, Uri dirUri, File dest) throws IOException {
        android.database.Cursor c = ctx.getContentResolver().query(dirUri, null, null, null, null);
        if (c == null) throw new IOException("无法读取文件夹内容");
        int failed = 0;
        try {
            while (c.moveToNext()) {
                String name = c.getString(c.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME));
                String mime = c.getString(c.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE));
                String docId = c.getString(c.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID));
                Uri child = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId);
                File out = new File(dest, safeName(name));
                if (mime != null && mime.equals(DocumentsContract.Document.MIME_TYPE_DIR)) {
                    if (!out.mkdirs() && !out.isDirectory()) {
                        failed++;
                        continue;
                    }
                    failed += copyDocTree(ctx, child, out);
                } else {
                    try (InputStream in = ctx.getContentResolver().openInputStream(child)) {
                        if (in == null) continue;
                        try (OutputStream os = new FileOutputStream(out)) {
                            copy(in, os);
                        }
                    } catch (Throwable t) {
                        failed++;
                        Log.e(TAG, "copy file fail: " + name, t);
                    }
                }
            }
        } finally {
            c.close();
        }
        return failed;
    }

    /** 找 files/games 下某 exe 所属的顶层导入目录（删除时整目录一起删）；找不到返回 null。 */
    public static File topImportDir(File gamesRoot, File exe) {
        if (gamesRoot == null || exe == null || !isInside(gamesRoot, exe)) return null;
        File dir = exe.getParentFile();
        File best = null;
        while (dir != null && !dir.getAbsoluteFile().equals(gamesRoot.getAbsoluteFile())) {
            best = dir;
            dir = dir.getParentFile();
        }
        return best;
    }

    public static boolean isInside(File parent, File child) {
        try {
            String p = parent.getCanonicalPath() + File.separator;
            String c = child.getCanonicalPath();
            return c.startsWith(p) || c.equals(parent.getCanonicalPath());
        } catch (Throwable t) {
            return false;
        }
    }
}
