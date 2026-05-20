package com.wanyor.android.app;

public class ApkMetaInfo {

    private String appName;
    private String minSdkVersion;
    private String targetSdkVersion;
    private String compileSdkVersion;
    private String versionName;
    private Long versionCode;
    private String packageName;
    private long fileSize;
    private String fileMd5;

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