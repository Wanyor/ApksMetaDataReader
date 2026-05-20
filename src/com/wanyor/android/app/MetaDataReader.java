package com.wanyor.android.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MetaDataReader {

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
                info = parseAsZipBundle(filePath);
                if (info == null) {
                    info = parseApk(filePath);
                }
                break;
        }

        if (info != null) {
            info.setFileSize(FileUtils.getFileSize(filePath));
            info.setFileMd5(FileUtils.getFileMd5(filePath));
        }
        return info;
    }

    private static ApkMetaInfo parseApk(String filePath) {
        ApkParser parser = new ApkParser();
        return parser.parse(filePath);
    }

    private static ApkMetaInfo parseApkFromBytes(byte[] apkBytes) {
        ApkParser parser = new ApkParser();
        return parser.parseFromBytes(apkBytes);
    }

    private static ApkMetaInfo parseApks(String filePath) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(filePath);
            byte[] baseApk = findBaseApk(zipFile);
            if (baseApk != null) {
                ApkMetaInfo result = parseApkFromBytes(baseApk);
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
                try { 
                    zipFile.close(); 
                } catch (Exception ignored) {}
            }
        }
    }

    private static ApkMetaInfo parseXapk(String filePath) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(filePath);
            ApkMetaInfo info = parseXapkManifest(zipFile);
            if (info == null) {
                byte[] baseApk = findBaseApk(zipFile);
                if (baseApk != null) {
                    return parseApkFromBytes(baseApk);
                }
                return new ApkMetaInfo();
            }

            fillMissingFromBaseApk(zipFile, info);
            return info;
        } catch (Exception e) {
            e.printStackTrace();
            return new ApkMetaInfo();
        } finally {
            if (zipFile != null) {
                try { 
                    zipFile.close(); 
                } catch (Exception ignored) {}
            }
        }
    }

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
                try { 
                    zipFile.close(); 
                } catch (Exception ignored) {}
            }
        }
    }

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
                try { 
                    zipFile.close(); 
                } catch (Exception ignored) {}
            }
        }
    }

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

    private static byte[] findBaseApk(ZipFile zipFile) {
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

        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String entryName = entry.getName().toLowerCase();
            if (entryName.endsWith(".apk") &&
                (entryName.contains("base") || entryName.contains("master"))) {
                byte[] data = readZipEntryBytes(zipFile, entry);
                if (data != null) return data;
            }
        }

        entries = zipFile.entries();
        ZipEntry firstApk = null;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.getName().toLowerCase().endsWith(".apk")) {
                if (firstApk == null) {
                    firstApk = entry;
                }
                byte[] data = readZipEntryBytes(zipFile, entry);
                if (data != null) {
                    try {
                        java.io.File temp = java.io.File.createTempFile("apk_find_", ".apk");
                        temp.deleteOnExit();
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(temp);
                        fos.write(data);
                        fos.close();
                        ZipFile innerZip = new ZipFile(temp);
                        byte[] manifest = ApkParser.readZipEntry(innerZip, "AndroidManifest.xml");
                        innerZip.close();
                        if (manifest != null) {
                            temp.delete();
                            return data;
                        }
                        temp.delete();
                    } catch (Exception ignored) {}
                }
            }
        }

        if (firstApk != null) {
            return readZipEntryBytes(zipFile, firstApk);
        }
        return null;
    }

    private static byte[] readZipEntryBytes(ZipFile zipFile, String entryName) {
        try {
            ZipEntry entry = zipFile.getEntry(entryName);
            if (entry == null) return null;
            return readZipEntryBytes(zipFile, entry);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readZipEntryBytes(ZipFile zipFile, ZipEntry entry) {
        InputStream is = null;
        try {
            is = zipFile.getInputStream(entry);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (is != null) {
                try { is.close(); } catch (Exception ignored) {}
            }
        }
    }

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