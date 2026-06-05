package com.atobkchkn.tools.config;

/**
 * WebExplorer 配置类
 * 封装所有 CSS 选择器、URL、超时等配置项，支持自定义覆盖
 */
public class WebExplorerConfig {

    // ==================== URL 配置 ====================
    private String creditChinaUrl = "https://www.creditchina.gov.cn";

    // ==================== 搜索页面选择器 ====================
    /** 搜索输入框 */
    private String searchInputSelector = "#search_input";
    /** 搜索按钮 */
    private String searchButtonSelector = "#search_btn";

    // ==================== 验证码选择器 ====================
    /** 验证码图片 */
    private String captchaImageSelector = "#captcha_img";
    /** 验证码输入框 */
    private String captchaInputSelector = "#captcha_input";
    /** 验证码确认按钮 */
    private String captchaConfirmSelector = "#captcha_confirm";

    // ==================== 搜索结果选择器 ====================
    /** 公司链接（支持多选择器 fallback） */
    private String[] companyLinkSelectors = {
        ".search-result-item .company-name a",
        ".result-list .company-link",
        ".search-results a[href*='detail']",
        "table tr td a"  // 备用
    };

    // ==================== 详情页模块选择器 ====================
    /** 严重失信计数 */
    private String seriousDishonestySelector = ".module-serious-dishonesty .count";
    /** 经营异常计数 */
    private String abnormalOperationSelector = ".module-abnormal-operation .count";
    /** 司法判决计数 */
    private String judicialJudgmentSelector = ".module-judicial-judgment .count";

    // ==================== 超时与重试配置 ====================
    /** 显式等待超时（秒） */
    private int waitTimeoutSeconds = 30;
    /** 验证码最大重试次数 */
    private int maxCaptchaRetry = 10;
    /** 页面操作间隔（毫秒） */
    private int actionDelayMillis = 1000;

    // ==================== 截图配置 ====================
    /** 截图根目录 */
    private String screenshotBaseDir = ".";
    /** 一级文件夹格式（日期） */
    private String dateFolderFormat = "yyyyMMdd";
    /** 二级文件夹名称 */
    private String categoryFolderName = "信用中国";
    /** 截图文件名格式 */
    private String screenshotFileFormat = "yyyyMMdd_HHmmss";

    // ==================== 浏览器配置 ====================
    /** 是否 headless 模式 */
    private boolean headless = true;
    /** 浏览器窗口宽度 */
    private int windowWidth = 1920;
    /** 浏览器窗口高度 */
    private int windowHeight = 1080;
    /** 用户代理 */
    private String userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    // ==================== 构造方法 ====================
    public WebExplorerConfig() {}

    // ==================== Getter / Setter ====================

    public String getCreditChinaUrl() { return creditChinaUrl; }
    public void setCreditChinaUrl(String creditChinaUrl) { this.creditChinaUrl = creditChinaUrl; }

    public String getSearchInputSelector() { return searchInputSelector; }
    public void setSearchInputSelector(String searchInputSelector) { this.searchInputSelector = searchInputSelector; }

    public String getSearchButtonSelector() { return searchButtonSelector; }
    public void setSearchButtonSelector(String searchButtonSelector) { this.searchButtonSelector = searchButtonSelector; }

    public String getCaptchaImageSelector() { return captchaImageSelector; }
    public void setCaptchaImageSelector(String captchaImageSelector) { this.captchaImageSelector = captchaImageSelector; }

    public String getCaptchaInputSelector() { return captchaInputSelector; }
    public void setCaptchaInputSelector(String captchaInputSelector) { this.captchaInputSelector = captchaInputSelector; }

    public String getCaptchaConfirmSelector() { return captchaConfirmSelector; }
    public void setCaptchaConfirmSelector(String captchaConfirmSelector) { this.captchaConfirmSelector = captchaConfirmSelector; }

    public String[] getCompanyLinkSelectors() { return companyLinkSelectors; }
    public void setCompanyLinkSelectors(String[] companyLinkSelectors) { this.companyLinkSelectors = companyLinkSelectors; }

    public String getSeriousDishonestySelector() { return seriousDishonestySelector; }
    public void setSeriousDishonestySelector(String seriousDishonestySelector) { this.seriousDishonestySelector = seriousDishonestySelector; }

    public String getAbnormalOperationSelector() { return abnormalOperationSelector; }
    public void setAbnormalOperationSelector(String abnormalOperationSelector) { this.abnormalOperationSelector = abnormalOperationSelector; }

    public String getJudicialJudgmentSelector() { return judicialJudgmentSelector; }
    public void setJudicialJudgmentSelector(String judicialJudgmentSelector) { this.judicialJudgmentSelector = judicialJudgmentSelector; }

    public int getWaitTimeoutSeconds() { return waitTimeoutSeconds; }
    public void setWaitTimeoutSeconds(int waitTimeoutSeconds) { this.waitTimeoutSeconds = waitTimeoutSeconds; }

    public int getMaxCaptchaRetry() { return maxCaptchaRetry; }
    public void setMaxCaptchaRetry(int maxCaptchaRetry) { this.maxCaptchaRetry = maxCaptchaRetry; }

    public int getActionDelayMillis() { return actionDelayMillis; }
    public void setActionDelayMillis(int actionDelayMillis) { this.actionDelayMillis = actionDelayMillis; }

    public String getScreenshotBaseDir() { return screenshotBaseDir; }
    public void setScreenshotBaseDir(String screenshotBaseDir) { this.screenshotBaseDir = screenshotBaseDir; }

    public String getDateFolderFormat() { return dateFolderFormat; }
    public void setDateFolderFormat(String dateFolderFormat) { this.dateFolderFormat = dateFolderFormat; }

    public String getCategoryFolderName() { return categoryFolderName; }
    public void setCategoryFolderName(String categoryFolderName) { this.categoryFolderName = categoryFolderName; }

    public String getScreenshotFileFormat() { return screenshotFileFormat; }
    public void setScreenshotFileFormat(String screenshotFileFormat) { this.screenshotFileFormat = screenshotFileFormat; }

    public boolean isHeadless() { return headless; }
    public void setHeadless(boolean headless) { this.headless = headless; }

    public int getWindowWidth() { return windowWidth; }
    public void setWindowWidth(int windowWidth) { this.windowWidth = windowWidth; }

    public int getWindowHeight() { return windowHeight; }
    public void setWindowHeight(int windowHeight) { this.windowHeight = windowHeight; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
