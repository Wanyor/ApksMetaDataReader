package com.wanyor.android.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 文件操作与字节序工具方法，供各解析器共用。 */
public class FileUtils {

    /** 单个 ZIP 条目允许读取的最大解压字节数（512 MB），防止 Zip Bomb。 */
    private static final long MAX_ENTRY_BYTES = 512L * 1024 * 1024;

    /** 返回文件大小（字节）。 */
    public static long getFileSize(String filePath) {
        return new File(filePath).length();
    }

    /** 计算文件的 MD5 摘要，返回小写十六进制字符串；出错返回 null。 */
    public static String getFileMd5(String filePath) {
        FileInputStream fis = null;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            fis = new FileInputStream(filePath);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            return null;
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** 按名称从 ZIP 中读取条目字节；条目不存在或超出大小限制时返回 null。 */
    static byte[] readZipEntry(ZipFile zipFile, String entryName) {
        try {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) return null;
            return readZipEntry(zipFile, entry);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取 ZIP 条目的全部字节。
     * 安全措施：声明大小或实际读取量超过 512 MB 时拒绝，防止 Zip Bomb。
     */
    static byte[] readZipEntry(ZipFile zipFile, ZipEntry entry) {
        long declared = entry.getSize();
        if (declared > MAX_ENTRY_BYTES) return null;
        InputStream is = null;
        try {
            is = zipFile.getInputStream(entry);
            int capacity = declared > 0 ? (int) declared : 8192;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(capacity);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = is.read(buf)) != -1) {
                total += n;
                if (total > MAX_ENTRY_BYTES) return null; // 实际解压量超限则中止
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) try { is.close(); } catch (Exception ignored) {}
        }
    }

    /** 以小端序读取 16 位有符号整数。 */
    static short readShort(byte[] data, int offset) {
        return (short) ((data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8));
    }

    /** 以小端序读取 32 位有符号整数。 */
    static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    /** 以小端序读取 16 位无符号整数，返回值范围 0–65535。 */
    static int readUShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    /** 返回文件路径的扩展名（小写，不含点）；无扩展名时返回空字符串。 */
    static String getExtension(String filePath) {
        if (filePath == null) return "";
        int lastDot = filePath.lastIndexOf('.');
        if (lastDot < 0 || lastDot >= filePath.length() - 1) return "";
        return filePath.substring(lastDot + 1).toLowerCase();
    }
}
