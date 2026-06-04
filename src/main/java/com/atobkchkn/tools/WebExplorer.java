package com.atobkchkn.tools;

import com.atobkchkn.tools.captcha.CaptchaResolver;
import com.atobkchkn.tools.captcha.OcrCaptchaResolver;
import com.atobkchkn.tools.config.WebExplorerConfig;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

/**
 * 信用中国网站查询工具（Playwright 版）
 *
 * <p>使用 Playwright 替代 Selenium，反检测能力更强，可绕过知道创宇等 WAF 防护。</p>
 *
 * <p><b>使用示例：</b></p>
 * <pre>{@code
 * // 默认配置 + OCR 自动识别
 * WebExplorer explorer = new WebExplorer();
 * boolean hasAbnormal = explorer.queryCompanyAbnormal("公司名称");
 *
 * // 自定义配置 + 手动输入验证码
 * WebExplorerConfig config = new WebExplorerConfig();
 * config.setHeadless(false);
 * WebExplorer explorer = new WebExplorer(config, new ManualCaptchaResolver());
 * }</pre>
 */
public class WebExplorer {

    private static final Logger logger = LoggerFactory.getLogger(WebExplorer.class);

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private Page page;
    private final WebExplorerConfig config;
    private final CaptchaResolver captchaResolver;

    /**
     * 默认构造：使用默认配置 + OCR 自动识别
     */
    public WebExplorer() {
        this(new WebExplorerConfig(), new OcrCaptchaResolver());
    }

    /**
     * 指定验证码策略
     */
    public WebExplorer(CaptchaResolver captchaResolver) {
        this(new WebExplorerConfig(), captchaResolver);
    }

    /**
     * 指定配置
     */
    public WebExplorer(WebExplorerConfig config) {
        this(config, new OcrCaptchaResolver());
    }

    /**
     * 完整构造：指定配置 + 验证码策略
     */
    public WebExplorer(WebExplorerConfig config, CaptchaResolver captchaResolver) {
        this.config = config;
        this.captchaResolver = captchaResolver;

        this.playwright = Playwright.create();

        // 检测系统是否安装了 Google Chrome，优先使用系统 Chrome（更难被检测）
        String chromePath = findSystemChrome();

        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
            .setHeadless(config.isHeadless())
            .setArgs(Arrays.asList(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--disable-web-security",
                "--disable-blink-features=AutomationControlled",
                "--disable-features=IsolateOrigins,site-per-process",
                "--disable-site-isolation-trials",
                "--window-size=" + config.getWindowWidth() + "," + config.getWindowHeight(),
                "--lang=zh-CN",
                "--start-maximized",
                "--disable-infobars",
                "--disable-extensions",
                "--disable-notifications",
                "--disable-popup-blocking",
                "--disable-save-password-bubble",
                "--disable-single-click-autofill",
                "--disable-translate",
                "--disable-logging",
                "--no-default-browser-check",
                "--no-first-run"
            ));

        if (chromePath != null) {
            launchOptions.setExecutablePath(java.nio.file.Paths.get(chromePath));
            logger.info("使用系统 Chrome: {}", chromePath);
        }

        this.browser = playwright.chromium().launch(launchOptions);

        // 创建浏览器上下文，设置完整的反检测参数
        // viewportSize设为null以支持--start-maximized，浏览器会使用实际窗口大小
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
            .setViewportSize(null)
            .setUserAgent(config.getUserAgent())
            .setLocale("zh-CN")
            .setTimezoneId("Asia/Shanghai")
            .setPermissions(Arrays.asList())
            .setHasTouch(false)
            .setColorScheme(com.microsoft.playwright.options.ColorScheme.LIGHT);

        this.context = browser.newContext(contextOptions);

        // 注入完整的反检测脚本
        context.addInitScript("() => {\n"
            + "  // 移除 webdriver 标志\n"
            + "  Object.defineProperty(navigator, 'webdriver', { get: () => undefined });\n"
            + "  \n"
            + "  // 伪装插件\n"
            + "  Object.defineProperty(navigator, 'plugins', {\n"
            + "    get: () => [\n"
            + "      { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format' },\n"
            + "      { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: 'Portable Document Format viewer' },\n"
            + "      { name: 'Native Client', filename: 'internal-nacl-plugin', description: 'Native Client module' }\n"
            + "    ]\n"
            + "  });\n"
            + "  \n"
            + "  // 伪装 chrome 对象\n"
            + "  window.chrome = { runtime: { OnInstalledReason: { CHROME_UPDATE: 'chrome_update' } } };\n"
            + "  \n"
            + "  // 伪装 Notification\n"
            + "  if (window.Notification) {\n"
            + "    Object.defineProperty(window.Notification, 'permission', { get: () => 'default' });\n"
            + "  }\n"
            + "  \n"
            + "  // 伪装权限查询\n"
            + "  const originalQuery = window.navigator.permissions.query;\n"
            + "  window.navigator.permissions.query = (parameters) => (\n"
            + "    parameters.name === 'notifications'\n"
            + "      ? Promise.resolve({ state: Notification.permission })\n"
            + "      : originalQuery(parameters)\n"
            + "  );\n"
            + "  \n"
            + "  // 移除自动化特征\n"
            + "  delete navigator.__proto__.webdriver;\n"
            + "}");

        this.page = context.newPage();
    }

    /**
     * 查询指定公司是否有异常记录
     */
    public boolean queryCompanyAbnormal(String companyName) {
        logger.info("[开始查询] 公司: {} | 验证码策略: {}", companyName, captchaResolver.getName());
        try {
            step1AccessHomepage();
            step2SearchCompany(companyName);
            step3HandleCaptcha();
            boolean found = step4ClickCompanyLink(companyName);
            if (!found) {
                logger.warn("未找到公司 '{}' 的搜索结果", companyName);
                return false;
            }
            boolean hasAbnormal = step5CheckAbnormalRecords();
            step6SaveScreenshot();
            return hasAbnormal;

        } catch (Exception e) {
            logger.error("查询过程中发生异常", e);
            String debugPath = saveDebugInfo("error_" + e.getClass().getSimpleName());
            logger.error("===== 异常调试信息已保存到: {} =====", debugPath);
            return false;
        }
    }

    // ==================== 步骤实现 ====================

    /** 步骤1：访问信用中国首页 */
    private void step1AccessHomepage() {
        logger.info("[步骤1] 正在加载页面: {}", config.getCreditChinaUrl());

        // 最大化浏览器窗口
        page.evaluate("() => { window.moveTo(0, 0); window.resizeTo(screen.width, screen.height); }");
        logger.info("[步骤1] 浏览器窗口已最大化");

        // Playwright 导航并等待网络空闲
        page.navigate(config.getCreditChinaUrl());
        page.waitForLoadState(LoadState.NETWORKIDLE);

        // 额外等待 JS 渲染
        page.waitForTimeout(config.getActionDelayMillis() * 3);

        // 检查页面是否为空
        String html = page.content();
        if (html == null || html.trim().length() < 200 || html.contains("<body></body>")) {
            logger.warn("[步骤1] 页面内容为空，可能被反爬虫拦截，保存调试信息...");
            saveDebugInfo("empty_page");

            // 尝试刷新页面（WAF 有时需要第二次访问）
            logger.info("[步骤1] 尝试刷新页面...");
            page.reload();
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(3000);

            html = page.content();
            if (html == null || html.trim().length() < 200 || html.contains("<body></body>")) {
                throw new RuntimeException(
                    "页面加载为空，可能原因：\n"
                    + "1. 网站反爬虫机制拦截 → 建议关闭 headless 模式运行\n"
                    + "2. 网络问题 → 检查是否能正常访问 " + config.getCreditChinaUrl());
            }
        }

        logger.info("[步骤1] 页面加载完成，内容大小: {} 字符", html.length());
    }

    /** 步骤2：输入公司名称并搜索 */
    private void step2SearchCompany(String companyName) {
        // 多选择器尝试查找搜索输入框
        String[] inputSelectors = {
            config.getSearchInputSelector(),
            "input[placeholder*='搜索']",
            "input[placeholder*='企业']",
            "input[placeholder*='公司']",
            "input[type='text']",
            "#keyword", "#q", "#query",
            "input[name='keyword']",
            "input[name='q']",
            "input[name='query']",
            ".search-input",
            ".search-box input",
            ".search-form input",
            "form input[type='text']",
            "input"
        };
        Locator searchInput = findLocatorWithFallback(inputSelectors, "搜索输入框");
        searchInput.fill(companyName);
        logger.info("[步骤2] 已输入公司名称: {}", companyName);

        // 多选择器尝试查找搜索按钮（CSS + 文本内容）
        Locator searchBtn = findSearchButton();

        // 点击搜索按钮，并监听新页面打开（有些网站会在新页面显示结果）
        Page newPage = context.waitForPage(() -> {
            searchBtn.click();
            logger.info("[步骤2] 已点击搜索按钮");
        });

        if (newPage != null && !newPage.equals(page)) {
            logger.info("[步骤2] 检测到新页面打开，切换到新页面");
            page = newPage;
        }

        // 等待搜索结果页面加载
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.waitForTimeout(config.getActionDelayMillis());
    }

    /**
     * 智能查找搜索按钮：先尝试 CSS 选择器，再尝试文本匹配
     */
    private Locator findSearchButton() {
        // 策略1：CSS 选择器（包含带空格的"搜 索"）
        String[] btnSelectors = {
            config.getSearchButtonSelector(),
            "button[type='submit']",
            ".search-btn",
            ".search-button",
            "input[type='submit']",
            "button:has-text('搜 索')",   // ← 带空格版本
            "button:has-text('搜索')",
            "a:has-text('搜 索')",
            "a:has-text('搜索')",
            "span:has-text('搜 索')",
            "span:has-text('搜索')",
            ".btn-search",
            "#searchBtn",
            "#search-btn"
        };
        for (String selector : btnSelectors) {
            if (selector == null || selector.isBlank()) continue;
            try {
                Locator locator = page.locator(selector);
                if (locator.count() > 0) {
                    locator.first().waitFor();
                    logger.info("找到 '搜索按钮' | 选择器: {}", selector);
                    return locator.first();
                }
            } catch (Exception e) {
                logger.debug("选择器 '{}' 未匹配搜索按钮", selector);
            }
        }

        // 策略2：遍历所有 button/a/span 元素，模糊匹配文本（处理空格/换行等）
        String[] tagNames = {"button", "a", "span", "div", "input"};
        String[] searchKeywords = {"搜 索", "搜索", "查询", "搜", "索"};
        for (String tag : tagNames) {
            try {
                List<Locator> elements = page.locator(tag).all();
                for (Locator el : elements) {
                    String text = el.textContent();
                    if (text == null) continue;
                    String trimmed = text.trim().replaceAll("\\s+", "");
                    for (String keyword : searchKeywords) {
                        String keywordNormalized = keyword.replaceAll("\\s+", "");
                        if (trimmed.contains(keywordNormalized) || text.trim().equals(keyword)) {
                            logger.info("找到 '搜索按钮' | 策略: 模糊文本匹配 | tag={} text='{}'", tag, text.trim());
                            return el;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 策略3：通过 title/alt 属性查找
        String[] attrSelectors = {
            "[title*='搜索']", "[title*='搜']",
            "[alt*='搜索']", "[alt*='搜']",
            "[value*='搜索']", "[value*='搜']"
        };
        for (String selector : attrSelectors) {
            try {
                Locator locator = page.locator(selector);
                if (locator.count() > 0) {
                    logger.info("找到 '搜索按钮' | 属性匹配选择器: {}", selector);
                    return locator.first();
                }
            } catch (Exception ignored) {}
        }

        // 策略4：查找表单内任意可点击元素作为搜索按钮
        try {
            Locator formBtn = page.locator("form button, form input[type='submit'], form a");
            if (formBtn.count() > 0) {
                logger.info("找到 '搜索按钮' | 策略: 表单内第一个可点击元素");
                return formBtn.first();
            }
        } catch (Exception ignored) {}

        // 全部失败
        String debugPath = saveDebugInfo("find_failed_搜索按钮");
        throw new RuntimeException(
            "未找到 '搜索按钮'，已尝试多种策略 | 调试信息已保存到: " + debugPath
            + "\n请查看 page_source.html 中搜索按钮的 HTML 结构，然后更新选择器");
    }

    /** 步骤3：处理验证码（支持模态框中的验证码） */
    private void step3HandleCaptcha() {
        // 先等待验证码可能出现（异步加载）
        logger.info("[步骤3] 等待验证码加载...");
        page.waitForTimeout(3000);

        for (int attempt = 1; attempt <= config.getMaxCaptchaRetry(); attempt++) {
            try {
                // 检测验证码：先在模态框中查找，再在页面中查找
                CaptchaElements captcha = findCaptchaElements();

                if (captcha == null || captcha.image == null) {
                    // 检查是否已经有搜索结果了（不需要验证码）
                    if (hasSearchResults()) {
                        logger.info("[步骤3] 未检测到验证码，且已有搜索结果，跳过");
                        return;
                    }

                    // 未检测到验证码：尝试点击"看不清，换一张"刷新，最多3次
                    if (attempt <= 3) {
                        logger.info("[步骤3] 未检测到验证码，尝试点击'换一张'刷新 ({}/3)", attempt);
                        clickRefreshCaptchaLink();
                        page.waitForTimeout(1000);
                        continue; // 回到循环开始，重新检测
                    }

                    logger.info("[步骤3] 未检测到验证码，已尝试3次刷新，跳过");
                    return;
                }

                logger.info("[步骤3] 检测到验证码，第 {}/{} 次尝试 | 策略: {} | 在模态框中: {}",
                    attempt, config.getMaxCaptchaRetry(), captchaResolver.getName(), captcha.inModal);

                // 先等待验证码图片可见（最多5秒）
                try {
                    captcha.image.waitFor(new Locator.WaitForOptions().setTimeout(5000));
                } catch (Exception e) {
                    logger.warn("[步骤3] 验证码图片未变为可见，等待后重试");
                    page.waitForTimeout(2000);
                    continue;
                }

                // 截取验证码图片并转为 Base64（元素截图失败则尝试页面截图）
                byte[] captchaBytes;
                try {
                    captchaBytes = captcha.image.screenshot();
                } catch (Exception screenshotEx) {
                    logger.warn("[步骤3] 元素截图失败，尝试页面截图: {}", screenshotEx.getMessage());
                    captchaBytes = page.screenshot();
                }
                String captchaBase64 = java.util.Base64.getEncoder().encodeToString(captchaBytes);
                String code = captchaResolver.resolve(captchaBase64);

                // 如果识别为空或明显错误（如"00"），刷新验证码重新识别
                if (code == null || code.isEmpty() || "00".equals(code)) {
                    logger.warn("[步骤3] 验证码识别结果异常: '{}', 刷新验证码重新识别", code);
                    if (captcha.inModal) {
                        clickRefreshCaptchaLink();
                    } else {
                        refreshCaptcha();
                    }
                    page.waitForTimeout(1000);
                    continue;
                }

                // 使用找到的输入框和确认按钮
                captcha.input.fill(code);
                captcha.confirm.click();
                page.waitForTimeout(config.getActionDelayMillis() + 500);

                CaptchaStatus status = checkCaptchaStatus();

                if (status == CaptchaStatus.PASSED) {
                    logger.info("[步骤3] 验证码验证成功，等待搜索结果加载...");
                    page.waitForTimeout(5000); // 等待5秒让搜索结果加载
                    return;
                }

                // 验证码失效或错误，点击"换一张"刷新后重试
                if (status == CaptchaStatus.INVALID && attempt <= 3) {
                    logger.warn("[步骤3] 验证码已失效/错误，点击'换一张'刷新后重试 ({}/3)", attempt);
                    clickRefreshCaptchaLink();
                    page.waitForTimeout(1000); // 等待1秒让新验证码加载
                    continue;
                }

                // 验证失败（未知原因），刷新验证码后重试
                if (attempt <= 3) {
                    logger.warn("[步骤3] 验证码验证失败，点击'换一张'刷新后重试 ({}/3)", attempt);
                    clickRefreshCaptchaLink();
                    page.waitForTimeout(1000);
                    continue;
                }

                logger.error("[步骤3] 验证码验证失败，已尝试3次，放弃");

            } catch (PlaywrightException e) {
                if (e.getMessage().contains("timeout") || e.getMessage().contains("closed")) {
                    logger.info("[步骤3] 未检测到验证码，跳过");
                    return;
                }
                logger.error("[步骤3] 验证码处理异常", e);
            }
        }
        throw new RuntimeException("验证码处理失败，已超过最大重试次数: " + config.getMaxCaptchaRetry());
    }

    /** 验证码元素封装 */
    private static class CaptchaElements {
        final Locator image;
        final Locator input;
        final Locator confirm;
        final boolean inModal;

        CaptchaElements(Locator image, Locator input, Locator confirm, boolean inModal) {
            this.image = image;
            this.input = input;
            this.confirm = confirm;
            this.inModal = inModal;
        }
    }

    /**
     * 查找验证码元素：使用 JavaScript 直接操作 DOM
     */
    private CaptchaElements findCaptchaElements() {
        // 策略1：用 JavaScript 直接通过 id 获取（最可靠）
        try {
            Object result = page.evaluate("() => document.getElementById('vcodeimg') !== null");
            if (Boolean.TRUE.equals(result)) {
                logger.info("[步骤3] JS 检测到 id=vcodeimg 的验证码图片");

                // 用 JS 获取相关元素
                String inputSelector = (String) page.evaluate(
                    "() => {\n"
                    + "  var img = document.getElementById('vcodeimg');\n"
                    + "  if (!img) return null;\n"
                    + "  var parent = img.parentElement;\n"
                    + "  while (parent && parent.tagName !== 'BODY') {\n"
                    + "    var inputs = parent.querySelectorAll('input[type=text], input:not([type])');\n"
                    + "    if (inputs.length > 0) return 'found';\n"
                    + "    var buttons = parent.querySelectorAll('button, a');\n"
                    + "    if (buttons.length > 0) return 'found';\n"
                    + "    parent = parent.parentElement;\n"
                    + "  }\n"
                    + "  return 'found';\n"
                    + "}");

                Locator captchaImg = page.locator("#vcodeimg");

                // 查找输入框：先尝试常见 id，再尝试验证码图片附近的 input
                Locator captchaInput = page.locator("#vcode");
                if (captchaInput.count() == 0) captchaInput = page.locator("input[name='vcode']");
                if (captchaInput.count() == 0) captchaInput = page.locator("input[placeholder*='验证码']");
                if (captchaInput.count() == 0) captchaInput = page.locator("input[type='text']").last();

                // 查找确认按钮
                Locator confirmBtn = page.locator("button:has-text('验证')");
                if (confirmBtn.count() == 0) confirmBtn = page.locator("button:has-text('确定')");
                if (confirmBtn.count() == 0) confirmBtn = page.locator("button:has-text('确认')");
                if (confirmBtn.count() == 0) confirmBtn = page.locator("button:has-text('提交')");
                if (confirmBtn.count() == 0) confirmBtn = page.locator("a:has-text('验证')");
                if (confirmBtn.count() == 0) confirmBtn = page.locator("a:has-text('确定')");
                if (confirmBtn.count() == 0) confirmBtn = page.locator("button").last();

                logger.info("[步骤3] 通过 JS 找到完整的验证码元素");
                return new CaptchaElements(captchaImg, captchaInput, confirmBtn, true);
            }
        } catch (Exception e) {
            logger.debug("JS 查找 vcodeimg 失败: {}", e.getMessage());
        }

        // 策略2：遍历所有 img 元素查找（调试用）
        logger.info("[步骤3] 遍历页面上所有 img 元素...");
        try {
            @SuppressWarnings("unchecked")
            List<String> imgInfo = (List<String>) page.evaluate(
                "() => Array.from(document.querySelectorAll('img')).map(img =>\n"
                + "  'id=' + (img.id || 'null') + '|class=' + (img.className || 'null') + '|src=' + (img.src || 'null').substring(0, 50)\n"
                + ")");
            for (String info : imgInfo) {
                logger.info("[步骤3] img 元素: {}", info);
            }
        } catch (Exception e) {
            logger.debug("遍历 img 元素失败: {}", e.getMessage());
        }

        // 策略3：通用选择器查找
        String[] imgSelectors = {
            config.getCaptchaImageSelector(),
            "#vcodeimg", "#captcha", ".captcha-img",
            "img[src*='captcha']", "img[src*='verify']"
        };
        Locator captchaImg = findFirstVisible(imgSelectors);
        if (captchaImg == null) return null;

        Locator captchaInput = findCaptchaInput();
        Locator confirmBtn = findCaptchaConfirm();
        return new CaptchaElements(captchaImg, captchaInput, confirmBtn, false);
    }

    /**
     * 在指定容器内查找元素
     */
    private Locator findInContainer(Locator container, String[] selectors) {
        for (String selector : selectors) {
            try {
                Locator loc = container.locator(selector);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    return loc.first();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 在页面中查找第一个可见的元素
     */
    private Locator findFirstVisible(String[] selectors) {
        for (String selector : selectors) {
            if (selector == null || selector.isBlank()) continue;
            try {
                Locator loc = page.locator(selector);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    return loc.first();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** 检查是否已有搜索结果 */
    private boolean hasSearchResults() {
        for (String selector : config.getCompanyLinkSelectors()) {
            if (page.locator(selector).count() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 查找验证码输入框 */
    private Locator findCaptchaInput() {
        String[] selectors = {
            config.getCaptchaInputSelector(),
            "input[name='captcha']",
            "input[name='verifyCode']",
            "input[placeholder*='验证码']",
            "#captcha_input",
            ".captcha-input",
            "input[type='text']"
        };
        for (String selector : selectors) {
            Locator loc = page.locator(selector);
            if (loc.count() > 0) return loc.first();
        }
        throw new RuntimeException("未找到验证码输入框");
    }

    /** 查找验证码确认按钮 */
    private Locator findCaptchaConfirm() {
        String[] selectors = {
            config.getCaptchaConfirmSelector(),
            "button:has-text('验证')",
            "button:has-text('确认')",
            "button:has-text('提交')",
            "a:has-text('确定')",
            "input[type='submit']",
            ".btn-confirm",
            "#confirm"
        };
        for (String selector : selectors) {
            Locator loc = page.locator(selector);
            if (loc.count() > 0) return loc.first();
        }
        throw new RuntimeException("未找到验证码确认按钮");
    }

    /** 步骤4：点击搜索结果中的公司链接 */
    private boolean step4ClickCompanyLink(String companyName) {
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.waitForTimeout(2000); // 额外等待结果渲染

        // 打印页面上所有链接文本（调试用）
        try {
            List<Locator> allLinks = page.locator("a").all();
            logger.info("[步骤4] 页面上共有 {} 个链接", allLinks.size());
            for (int i = 0; i < Math.min(allLinks.size(), 20); i++) {
                String text = allLinks.get(i).textContent().trim();
                if (!text.isEmpty()) {
                    logger.info("[步骤4] 链接 #{}: '{}'", i, text);
                }
            }
        } catch (Exception e) {
            logger.debug("打印链接失败: {}", e.getMessage());
        }

        // 扩展选择器列表
        String[] extendedSelectors = {
            ".search-result-item .company-name a",
            ".result-list .company-link",
            ".search-results a[href*='detail']",
            "table tr td a",
            ".list-item a",
            ".result-item a",
            "a[href*='company']",
            "a[href*='enterprise']",
            ".content a",
            "a"
        };

        for (String selector : extendedSelectors) {
            try {
                List<Locator> links = page.locator(selector).all();
                if (links.isEmpty()) continue;
                logger.info("[步骤4] 选择器 '{}' 找到 {} 个元素", selector, links.size());

                // 优先精确匹配
                for (Locator link : links) {
                    String text = link.textContent().trim();
                    if (text.contains(companyName)) {
                        logger.info("[步骤4] ✅ 精确匹配: '{}' | 选择器: {}", text, selector);
                        clickAndSwitchToDetailPage(link);
                        return true;
                    }
                }

                // 模糊匹配
                for (Locator link : links) {
                    String text = link.textContent().trim();
                    String fuzzyName = companyName.length() > 5 ? companyName.substring(0, 5) : companyName;
                    if (text.contains(fuzzyName) || fuzzyName.contains(text)) {
                        logger.info("[步骤4] ✅ 模糊匹配: '{}' (搜索: {}) | 选择器: {}", text, fuzzyName, selector);
                        clickAndSwitchToDetailPage(link);
                        return true;
                    }
                }

                // 兜底：点击第一个有效链接
                for (Locator link : links) {
                    String text = link.textContent().trim();
                    if (!text.isEmpty() && text.length() > 2) {
                        logger.info("[步骤4] ⚠️ 未匹配，点击首个有效链接: '{}' | 选择器: {}", text, selector);
                        clickAndSwitchToDetailPage(link);
                        return true;
                    }
                }
            } catch (Exception e) {
                logger.debug("选择器 {} 查找失败: {}", selector, e.getMessage());
            }
        }

        logger.error("[步骤4] 未找到公司 '{}' 的搜索结果", companyName);
        return false;
    }

    /**
     * 点击链接并切换到详情页面（支持新窗口/标签页）
     */
    private void clickAndSwitchToDetailPage(Locator link) {
        // 监听新页面打开
        Page newPage = context.waitForPage(() -> link.click());
        if (newPage != null && !newPage.equals(page)) {
            logger.info("[步骤4] 详情页在新窗口打开，切换到新页面");
            page = newPage;
        }
        // 等待详情页加载
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.waitForTimeout(2000); // 用户要求等待2秒
        logger.info("[步骤4] 已打开详情页面: {}", page.url());
    }

    /** 步骤5：检查异常记录 */
    private boolean step5CheckAbnormalRecords() {
        int serious = getModuleCount(config.getSeriousDishonestySelector(), "严重失信");
        int abnormal = getModuleCount(config.getAbnormalOperationSelector(), "经营异常");
        int judicial = getModuleCount(config.getJudicialJudgmentSelector(), "司法判决");

        logger.info("[步骤5] 查询结果 - 严重失信: {}, 经营异常: {}, 司法判决: {}",
            serious, abnormal, judicial);

        boolean hasAbnormal = serious > 0 || abnormal > 0 || judicial > 0;
        logger.info("[步骤5] 是否存在异常记录: {}", hasAbnormal);
        return hasAbnormal;
    }

    /** 步骤6：保存截图 */
    private void step6SaveScreenshot() {
        String path = saveScreenshot();
        logger.info("[步骤6] 截图已保存: {}", path);
    }

    // ==================== Playwright 智能元素查找 ====================

    private Locator findLocatorWithFallback(String[] selectors, String elementName) {
        for (String selector : selectors) {
            if (selector == null || selector.isBlank()) continue;
            Locator locator = page.locator(selector);
            try {
                if (locator.count() > 0) {
                    locator.first().waitFor();
                    logger.info("找到 '{}' | 选择器: {}", elementName, selector);
                    return locator.first();
                }
            } catch (Exception e) {
                logger.debug("选择器 '{}' 未匹配 '{}': {}", selector, elementName, e.getMessage());
            }
        }
        String debugPath = saveDebugInfo("find_failed_" + elementName);
        throw new RuntimeException(
            "未找到 '" + elementName + "'，已尝试选择器: " + Arrays.toString(selectors)
            + " | 调试信息已保存到: " + debugPath);
    }

    // ==================== 工具方法 ====================

    private int getModuleCount(String selector, String moduleName) {
        try {
            Locator locator = page.locator(selector);
            if (locator.count() == 0) {
                logger.warn("未找到 '{}' 模块 | 选择器: {}", moduleName, selector);
                return 0;
            }
            String text = locator.first().textContent().trim();
            String num = text.replaceAll("[^0-9]", "");
            return num.isEmpty() ? 0 : Integer.parseInt(num);
        } catch (Exception e) {
            logger.warn("{} 模块数值解析失败", moduleName);
            return 0;
        }
    }

    /**
     * 点击"看不清，换一张"链接刷新验证码
     */
    private void clickRefreshCaptchaLink() {
        String[] refreshSelectors = {
            "a:has-text('看不清')",
            "a:has-text('换一张')",
            "a:has-text('换一换')",
            "span:has-text('看不清')",
            "span:has-text('换一张')",
            ".change-captcha",
            "#changeCaptcha",
            "a[onclick*='captcha']",
            "a[onclick*='refresh']"
        };
        for (String selector : refreshSelectors) {
            try {
                Locator loc = page.locator(selector);
                if (loc.count() > 0 && loc.first().isVisible()) {
                    loc.first().click();
                    logger.info("[步骤3] 已点击刷新验证码链接 | 选择器: {}", selector);
                    return;
                }
            } catch (Exception ignored) {}
        }
        logger.debug("[步骤3] 未找到'换一张'链接");
    }

    private void refreshCaptcha() {
        try {
            page.locator(config.getCaptchaImageSelector()).click();
        } catch (Exception e) {
            logger.debug("刷新验证码失败: {}", e.getMessage());
        }
    }

    /**
     * 检查验证码是否已通过验证
     * 同时检测是否有"失效"、"错误"等提示
     */
    private CaptchaStatus checkCaptchaStatus() {
        // 检查验证码图片是否消失（通过验证的典型特征）
        boolean imgVisible = false;
        try {
            imgVisible = page.locator("#vcodeimg").isVisible();
        } catch (Exception ignored) {}

        if (!imgVisible) {
            return CaptchaStatus.PASSED;
        }

        // 检查页面是否有"失效"、"错误"提示
        String[] errorKeywords = {"失效", "错误", "失败", "请重新申请", "不正确"};
        String pageText = "";
        try {
            pageText = page.locator("body").textContent();
        } catch (Exception ignored) {}

        for (String keyword : errorKeywords) {
            if (pageText.contains(keyword)) {
                logger.warn("[步骤3] 检测到验证码{}提示", keyword);
                return CaptchaStatus.INVALID;
            }
        }

        // 验证码图片还在，但没有错误提示 → 可能是验证失败但未刷新
        return CaptchaStatus.FAILED;
    }

    private enum CaptchaStatus {
        PASSED,     // 验证通过
        INVALID,    // 验证码失效/错误
        FAILED      // 验证失败（未知原因）
    }

    /**
     * 保存调试信息：页面截图 + HTML 源码
     */
    public String saveDebugInfo(String tag) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path dir = Paths.get("debug", time + "_" + tag);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.error("创建调试目录失败", e);
            return null;
        }

        // 保存截图
        try {
            Path imgPath = dir.resolve("screenshot.png");
            page.screenshot(new Page.ScreenshotOptions()
                .setPath(imgPath)
                .setFullPage(true)
                .setType(ScreenshotType.PNG));
            logger.info("调试截图已保存: {}", imgPath);
        } catch (Exception e) {
            logger.error("保存调试截图失败", e);
        }

        // 保存页面源码
        try {
            String html = page.content();
            Path htmlPath = dir.resolve("page_source.html");
            Files.writeString(htmlPath, html, StandardCharsets.UTF_8);
            logger.info("调试源码已保存: {}", htmlPath);
        } catch (Exception e) {
            logger.error("保存页面源码失败", e);
        }

        // 保存当前 URL
        try {
            Path urlPath = dir.resolve("url.txt");
            Files.writeString(urlPath, page.url(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.error("保存 URL 失败", e);
        }

        String absPath = dir.toAbsolutePath().toString();
        logger.warn("===== 调试信息已保存到: {} =====", absPath);
        return absPath;
    }

    private String saveScreenshot() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern(config.getDateFolderFormat()));
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern(config.getScreenshotFileFormat()));
        Path dir = Paths.get(config.getScreenshotBaseDir(), date, config.getCategoryFolderName());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            logger.error("创建目录失败", e);
        }
        Path file = dir.resolve(time + ".png");
        page.screenshot(new Page.ScreenshotOptions()
            .setPath(file)
            .setFullPage(true));
        return file.toAbsolutePath().toString();
    }

    /**
     * 检测系统已安装的 Chrome 浏览器路径
     * macOS: /Applications/Google Chrome.app/Contents/MacOS/Google Chrome
     * Linux: /usr/bin/google-chrome, /usr/bin/chromium-browser
     * Windows: C:\Program Files\Google\Chrome\Application\chrome.exe
     */
    private String findSystemChrome() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("mac")) {
            String macPath = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
            if (java.nio.file.Files.exists(java.nio.file.Paths.get(macPath))) {
                return macPath;
            }
        }
        return null;
    }

    /**
     * 关闭浏览器，释放资源
     */
    public void close() {
        if (context != null) context.close();
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        logger.info("Playwright 资源已释放");
    }

    /**
     * 测试入口
     */
    public static void main(String[] args) {
        WebExplorerConfig config = new WebExplorerConfig();
        config.setHeadless(false);  // 显示浏览器窗口

        WebExplorer explorer = new WebExplorer(config);
        try {
            boolean result = explorer.queryCompanyAbnormal("汕头澄海实丰绿色科技有限公司");
            System.out.println("查询结果: " + (result ? "存在异常记录" : "无异常记录"));

            // 暂停让用户查看结果，按回车后关闭浏览器
            System.out.println("\n按回车键关闭浏览器...");
            new java.util.Scanner(System.in).nextLine();

        } finally {
            explorer.close();
        }
    }
}
