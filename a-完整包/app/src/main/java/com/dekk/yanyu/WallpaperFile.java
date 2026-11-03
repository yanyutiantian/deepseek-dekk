package com.dekk.yanyu;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 宿主私有壁纸副本，无需 root 或存储权限。 */
final class WallpaperFile {

    static final String NAME = "dekk-wallpaper.jpg";
    /** 旧版本目录名，读到旧路径时自动迁移。 */
    static final String LEGACY_DIR = "dekk-wallpapers";

    private WallpaperFile() { }

    static File dir(Context context) {
        File base = context.getFilesDir();
        return base;
    }

    static File target(Context context) {
        return new File(dir(context), NAME);
    }

    /**
     * 保证壁纸文件在新位置；旧目录里的那份会搬过来。
     *
     * @return 当前有效的路径；没有壁纸时返回空串。
     */
    static String ensure(Context context, String storedPath) {
        if (storedPath == null || storedPath.length() == 0) return "";
        File target = target(context);
        File stored = new File(storedPath);
        if (target.getAbsolutePath().equals(stored.getAbsolutePath())) {
            if (!stored.isFile()) return "";
            deleteLegacy(context);
            return target.getAbsolutePath();
        }
        if (!stored.isFile()) return "";
        try {
            File dir = dir(context);
            if (!dir.isDirectory() && !dir.mkdirs()) return stored.getAbsolutePath();
            target.delete();
            if (!stored.renameTo(target) && !copy(stored, target)) return stored.getAbsolutePath();
            stored.delete();
            HotBridge.log("[DEKK][背景] 壁纸已迁移到 " + target.getAbsolutePath() + "（宿主私有目录）");
            return target.getAbsolutePath();
        } catch (Throwable t) {
            HotBridge.log("[DEKK][背景] 壁纸迁移失败，继续用原路径：" + t);
            return stored.getAbsolutePath();
        }
    }

    /** 把压缩好的图片写到新位置（覆盖旧的），。 */
    static File write(Context context, Bitmap bitmap) throws IOException {
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("directory");
        File target = target(context);
        android.util.AtomicFile atomic = new android.util.AtomicFile(target);
        FileOutputStream stream = atomic.startWrite();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) throw new IOException("compress");
            atomic.finishWrite(stream);
        } catch (Throwable e) {
            atomic.failWrite(stream);
            if(e instanceof IOException)throw (IOException)e;
            if(e instanceof Error)throw (Error)e;
            throw new IOException("wallpaper write",e);
        }
        return target;
    }

    /** 清空壁纸时把文件也删掉（只读文件同样能删，目录可写）。 */
    static void delete(Context context) {
        try {
            target(context).delete();
        } catch (Throwable ignored) { }
        deleteLegacy(context);
    }

    /** 兼容旧调用；访问权限由宿主私有目录保证。 */
    static void lock(File file) {
        // 私有目录已经由 Android 沙箱隔离，不依赖外部存储 chmod。
    }

    private static void deleteLegacy(Context context) {
        try {
            File legacy = new File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), LEGACY_DIR);
            File[] files = legacy.listFiles();
            if (files != null) for (File file : files) file.delete();
            legacy.delete();
        } catch (Throwable ignored) { }
    }

    private static boolean copy(File from, File to) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(from);
            out = new FileOutputStream(to);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) { }
            if (out != null) try { out.close(); } catch (Throwable ignored) { }
        }
    }
}