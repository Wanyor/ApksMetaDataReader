package com.wanyor.android.app;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 公共入口类：根据文件扩展名将解析请求路由到对应的解析器。
 *
 * 支持格式及解析路径：
 *   .apk  → ApkParser（二进制 XML + 资源表），失败时回退到 ApkParser2
 *   .apks → ZIP 包，提取 base APK 后同 apk 路径
 *   .xapk → 优先读取 manifest.json，缺失字段从 base APK 补全
 *   .apkm → ZIP 包，提取 base APK 后同 apk 路径
 *   其他  → 先尝试作为 ZIP bundle 解析，再回退到 apk 路径
 */
public class MetaDataReader {

    /**
     * 从安装包文件中提取元数据，自动识别格式。
     *
     * @param filePath 安装包路径，支持 .apk / .apks / .xapk / .apkm 及其他 ZIP bundle
     * @return 提取到的元数据，各字段可能为 null（表示未找到）
     * @throws IllegalArgumentException 路径为空或文件不存在时抛出
     */
    public static ApkMetaInfo readMetaInfo(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty");
        }
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("File does not exist: " + filePath);
        }

        String ext = FileUtils.getExtension(filePath);
        ApkMetaInfo info;

        switch (ext) {
            case "apk":
                info = parseApk(filePath);
                // ApkParser 失败（无包名，或同时缺少应用名和版本名）时回退到 ApkParser2
                if (info.getPackageName() == null || (info.getAppName() == null && info.getVersionName() == null)) {
                    ApkParser2 p2 = new ApkParser2();
                    ApkMetaInfo info2 = p2.parse(filePath);
                    if (info2.getPackageName() != null || info2.getAppName() != null || info2.getVersionName() != null) {
                        info = info2;
                    }
                }
                break;
            case "apks":
                info = parseApks(filePath);
                break;
            case "xapk":
                info = parseXapk(filePath);
                break;
            case "apkm":
                info = parseApkm(filePath);
                break;
            default:
                // 未知扩展名：先尝试作为 ZIP bundle 处理，再回退到直接解析
                info = parseAsZipBundle(filePath);
                if (info == null) {
                    info = parseApk(filePath);
                }
                break;
        }

        // 补充文件级元数据（大小和 MD5 针对原始安装包文件，而非解压后的 base APK）
        if (info != null) {
            info.setFileSize(FileUtils.getFileSize(filePath));
            info.setFileMd5(FileUtils.getFileMd5(filePath));
        }
        return info;
    }

    /** 解析标准 APK 文件。 */
    private static ApkMetaInfo parseApk(String filePath) {
        ApkParser parser = new ApkParser();
        return parser.parse(filePath);
    }

    /** 将 APK 字节解析为元数据。 */
    private static ApkMetaInfo parseApkFromBytes(byte[] apkBytes) {
        ApkParser parser = new ApkParser();
        return parser.parseFromBytes(apkBytes);
    }

    /**
     * 解析 .apks 格式（Google Play 分发的 APK 集合，本质是 ZIP）。
     * 从中提取 base APK，解析失败时以 ApkParser2 补全缺失字段。
     */
    private static ApkMetaInfo parseApks(String filePath) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(filePath);
            byte[] baseApk = findBaseApk(zipFile);
            if (baseApk != null) {
                ApkMetaInfo result = parseApkFromBytes(baseApk);
                // 主解析器未能获取关键字段时，用备用解析器补全
                if (result.getPackageName() == null || (result.getAppName() == null && result.getVersionName() == null)) {
                    ApkParser2 p2 = new ApkParser2();
                    ApkMetaInfo result2 = p2.parseFromBytes(baseApk);
                    if (result2.getPackageName() != null || result2.getAppName() != null || result2.getVersionName() != null) {
                        if (result2.getPackageName() != null) result.setPackageName(result2.getPackageName());
                        if (result2.getAppName() != null) result.setAppName(result2.getAppName());
                        if (result2.getVersionCode() != null) result.setVersionCode(result2.getVersionCode());
                        if (result2.getVersionName() != null) result.setVersionName(result2.getVersionName());
                        if (result2.getMinSdkVersion() != null) result.setMinSdkVersion(result2.getMinSdkVersion());
                        if (result2.getTargetSdkVersion() != null) result.setTargetSdkVersion(result2.getTargetSdkVersion());
                        if (result2.getCompileSdkVersion() != null) result.setCompileSdkVersion(result2.getCompileSdkVersion());
                    }
                }
                return result;
            }
            return new ApkMetaInfo();
        } catch (Exception e) {
            e.printStackTrace();
            return new ApkMetaInfo();
        } finally {
            if (zipFile != null) {
                try { zipFile.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 解析 .xapk 格式（APKPure 分发格式）。
     * 优先读取 manifest.json 中的字段，再从 base APK 补全缺失项。
     */
    private static ApkMetaInfo parseXapk(String filePath) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(filePath);
            ApkMetaInfo info = parseXapkManifest(zipFile);
            if (info == null) {
                // 无 manifest.json 时，直接解析 base APK
                byte[] baseApk = findBaseApk(zipFile);
                if (baseApk != null) {
                    return parseApkFromBytes(baseApk);
                }
                return new ApkMetaInfo();
            }
            // manifest.json 可能缺少 appName 或 SDK 版本，从 base APK 补全
            fillMissingFromBaseApk(zipFile, info);
            return info;
        } catch (Exception e) {
            e.printStackTrace();
            return new ApkMetaInfo();
        } finally {
            if (zipFile != null) {
                try { zipFile.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 解析 .apkm 格式（APKMirror 分发格式，ZIP 包含多个 APK）。
     * 提取 base APK 后按标准路径解析。
     */
    private static ApkMetaInfo parseApkm(String filePath) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(filePath);
            byte[] baseApk = findBaseApk(zipFile);
            if (baseApk != null) {
                return parseApkFromBytes(baseApk);
            }
            return new ApkMetaInfo();
        } catch (Exception e) {
            e.printStackTrace();
            return new ApkMetaInfo();
        } finally {
            if (zipFile != null) {
                try { zipFile.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 将未知扩展名的文件作为 ZIP bundle 尝试解析。
     * 依次尝试：base APK、manifest.json、所有 .apk 条目。
     * 均失败时返回 null（调用方会回退到 parseApk）。
     */
    private static ApkMetaInfo parseAsZipBundle(String filePath) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(filePath);
            byte[] baseApk = findBaseApk(zipFile);
            if (baseApk != null) {
                return parseApkFromBytes(baseApk);
            }

            ApkMetaInfo xapkInfo = parseXapkManifest(zipFile);
            if (xapkInfo != null) return xapkInfo;

            // 遍历所有 .apk 条目，取第一个能成功解析包名的
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().toLowerCase().endsWith(".apk")) {
                    byte[] apkData = readZipEntryBytes(zipFile, entry);
                    if (apkData != null) {
                        ApkParser apkParser = new ApkParser();
                        ApkMetaInfo info = apkParser.parseFromBytes(apkData);
                        if (info != null && info.getPackageName() != null) {
                            return info;
                        }
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            if (zipFile != null) {
                try { zipFile.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 解析 XAPK 中的 manifest.json，提取应用包名、版本、SDK 版本等字段。
     * manifest.json 不存在或格式不符时返回 null。
     */
    private static ApkMetaInfo parseXapkManifest(ZipFile zipFile) {
        try {
            ZipEntry manifestEntry = zipFile.getEntry("manifest.json");
            if (manifestEntry == null) return null;

            InputStream is = null;
            try {
                is = zipFile.getInputStream(manifestEntry);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int r;
                while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);

                String json = bos.toString("UTF-8");
                Map<String, Object> map = SimpleJsonParser.parse(json);
                if (map.isEmpty()) return null;

                ApkMetaInfo info = new ApkMetaInfo();

                Object pkgName = map.get("package_name");
                if (pkgName != null) info.setPackageName(pkgName.toString());

                Object appName = map.get("name");
                if (appName != null) info.setAppName(appName.toString());

                Object verCode = map.get("version_code");
                if (verCode != null) {
                    if (verCode instanceof Long) info.setVersionCode((Long) verCode);
                    else if (verCode instanceof Number) info.setVersionCode(((Number) verCode).longValue());
                    else {
                        try { info.setVersionCode(Long.parseLong(verCode.toString())); }
                        catch (NumberFormatException ignored) {}
                    }
                }

                Object verName = map.get("version_name");
                if (verName != null) info.setVersionName(verName.toString());

                Object minSdk = map.get("min_sdk_version");
                if (minSdk != null) {
                    if (minSdk instanceof Number) info.setMinSdkVersion(String.valueOf(((Number) minSdk).intValue()));
                    else info.setMinSdkVersion(minSdk.toString());
                }

                Object targetSdk = map.get("target_sdk_version");
                if (targetSdk != null) {
                    if (targetSdk instanceof Number) info.setTargetSdkVersion(String.valueOf(((Number) targetSdk).intValue()));
                    else info.setTargetSdkVersion(targetSdk.toString());
                }

                return info;
            } finally {
                if (is != null) {
                    try { is.close(); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 ZIP 中的 base APK 补全 info 中尚未填充的字段。
     * 用于 XAPK：manifest.json 提供部分信息，base APK 提供其余字段。
     */
    private static void fillMissingFromBaseApk(ZipFile zipFile, ApkMetaInfo info) {
        byte[] baseApk = findBaseApk(zipFile);
        if (baseApk == null) return;

        ApkMetaInfo apkInfo = parseApkFromBytes(baseApk);
        if (apkInfo == null) return;

        if (info.getPackageName() == null) info.setPackageName(apkInfo.getPackageName());
        if (info.getAppName() == null) info.setAppName(apkInfo.getAppName());
        if (info.getVersionCode() == null) info.setVersionCode(apkInfo.getVersionCode());
        if (info.getVersionName() == null) info.setVersionName(apkInfo.getVersionName());
        if (info.getMinSdkVersion() == null) info.setMinSdkVersion(apkInfo.getMinSdkVersion());
        if (info.getTargetSdkVersion() == null) info.setTargetSdkVersion(apkInfo.getTargetSdkVersion());
        if (info.getCompileSdkVersion() == null) info.setCompileSdkVersion(apkInfo.getCompileSdkVersion());
    }

    /**
     * 在 ZIP bundle 中查找 base APK 字节。
     *
     * 查找策略（按优先级）：
     *   1. 按固定名称列表 O(1) 直接查找（base.apk、base-master.apk 等）
     *   2. 单次遍历：优先返回名称含 "base"/"master" 的 .apk；
     *      否则返回首个通过 AndroidManifest.xml 内存校验的 .apk；
     *      最终兜底返回第一个 .apk 条目
     */
    private static byte[] findBaseApk(ZipFile zipFile) {
        // 优先级 1：常见固定名称（O(1) 直接查找，无需遍历）
        String[] baseNames = {
            "base-master.apk",
            "splits/base-master.apk",
            "base.apk",
            "splits/base.apk",
            "master.apk"
        };
        for (String name : baseNames) {
            byte[] data = readZipEntryBytes(zipFile, name);
            if (data != null) return data;
        }

        // 单次遍历合并原有优先级 2/3/4
        byte[] firstBaseMatch = null; // 名称含 "base"/"master" 的第一个 .apk
        byte[] firstValidApk  = null; // 第一个通过 AndroidManifest.xml 校验的 .apk
        byte[] firstApk       = null; // 兜底：第一个 .apk 条目

        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String lowerName = entry.getName().toLowerCase();
            if (!lowerName.endsWith(".apk")) continue;

            byte[] data = readZipEntryBytes(zipFile, entry);
            if (data == null) continue;

            if (firstApk == null) firstApk = data;

            if (firstBaseMatch == null && (lowerName.contains("base") || lowerName.contains("master"))) {
                firstBaseMatch = data;
            }

            if (firstValidApk == null && isValidApk(data)) {
                firstValidApk = data;
            }

            // 已有命名匹配且已有合法校验结果，可提前退出
            if (firstBaseMatch != null && firstValidApk != null) break;
        }

        if (firstBaseMatch != null) return firstBaseMatch;
        if (firstValidApk  != null) return firstValidApk;
        return firstApk;
    }

    /**
     * 用 ZipInputStream 在内存中校验字节数组是否为合法 APK
     * （包含 AndroidManifest.xml 即认为合法）。
     * 不写临时文件，不依赖文件系统。
     */
    private static boolean isValidApk(byte[] data) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("AndroidManifest.xml".equals(entry.getName())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 按名称读取 ZIP 条目字节，委托给 FileUtils。 */
    private static byte[] readZipEntryBytes(ZipFile zipFile, String entryName) {
        return FileUtils.readZipEntry(zipFile, entryName);
    }

    /** 按 ZipEntry 读取 ZIP 条目字节，委托给 FileUtils。 */
    private static byte[] readZipEntryBytes(ZipFile zipFile, ZipEntry entry) {
        return FileUtils.readZipEntry(zipFile, entry);
    }

    /** 命令行入口：java -jar ApksMetaDataReader.jar &lt;文件路径&gt; */
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: MetaDataReader <file_path>");
            System.out.println("Supported formats: .apk, .apks, .xapk, .apkm");
            return;
        }
        String filePath = args[0];
        try {
            ApkMetaInfo info = readMetaInfo(filePath);
            System.out.println(info);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
