package com.wanyor.android.app;

/**
 * 保存从 Android 安装包中提取的元数据。
 * 所有字段均可为 null，表示该字段在安装包中未找到。
 */
public class ApkMetaInfo {

    private String appName;          // 应用名称（来自 application.label）
    private String minSdkVersion;    // 最低支持的 SDK 版本
    private String targetSdkVersion; // 目标 SDK 版本
    private String compileSdkVersion;// 编译时使用的 SDK 版本
    private String versionName;      // 版本名称字符串，如 "1.2.3"
    private Long versionCode;        // 版本号整数，用 Long 以兼容超过 2^31 的值
    private String packageName;      // 应用包名，如 "com.example.app"
    private long fileSize;           // 安装包文件大小（字节）
    private String fileMd5;          // 安装包文件的 MD5 十六进制字符串

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getMinSdkVersion() {
        return minSdkVersion;
    }

    public void setMinSdkVersion(String minSdkVersion) {
        this.minSdkVersion = minSdkVersion;
    }

    public String getTargetSdkVersion() {
        return targetSdkVersion;
    }

    public void setTargetSdkVersion(String targetSdkVersion) {
        this.targetSdkVersion = targetSdkVersion;
    }

    public String getCompileSdkVersion() {
        return compileSdkVersion;
    }

    public void setCompileSdkVersion(String compileSdkVersion) {
        this.compileSdkVersion = compileSdkVersion;
    }

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public Long getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(Long versionCode) {
        this.versionCode = versionCode;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileMd5() {
        return fileMd5;
    }

    public void setFileMd5(String fileMd5) {
        this.fileMd5 = fileMd5;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ApkMetaInfo{").append('\n');
        sb.append("  appName          = ").append(appName).append('\n');
        sb.append("  packageName      = ").append(packageName).append('\n');
        sb.append("  versionCode      = ").append(versionCode).append('\n');
        sb.append("  versionName      = ").append(versionName).append('\n');
        sb.append("  minSdkVersion    = ").append(minSdkVersion).append('\n');
        sb.append("  targetSdkVersion = ").append(targetSdkVersion).append('\n');
        sb.append("  compileSdkVersion= ").append(compileSdkVersion).append('\n');
        sb.append("  fileSize         = ").append(fileSize).append('\n');
        sb.append("  fileMd5          = ").append(fileMd5).append('\n');
        sb.append('}');
        return sb.toString();
    }
}
