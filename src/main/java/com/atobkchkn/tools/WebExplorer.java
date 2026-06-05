package com.atobkchkn.tools;

import com.atobkchkn.tools.captcha.CaptchaResolver;
import com.atobkchkn.tools.captcha.OnlineOcrCaptchaResolver;
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
     * 默认构造：使用默认配置 + 在线 OCR 自动识别
     */
    public WebExplorer() {
        this(new WebExplorerConfig(), new OnlineOcrCaptchaResolver());
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
        this(config, new OnlineOcrCaptchaResolver());
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
                "--start-maximized",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--disable-web-security",
                "--disable-blink-features=AutomationControlled",
                "--disable-features=IsolateOrigins,site-per-process",
                "--disable-site-isolation-trials",
                "--lang=zh-CN",
                "--disable-infobars",
                "--disable-extensions",
                "--disable-notifications",
                "--disable-popup-blocking",
                "--disable-save-password-bubble",
                "--disable-single-click-autofill",
                "--disable-translate",
                "--disable-logging",
                "--no-default-browser-check",
                "--no-first-run",
                // 新增反检测参数
                "--disable-background-networking",
                "--disable-background-timer-throttling",
                "--disable-backgrounding-occluded-windows",
                "--disable-breakpad",
                "--disable-component-update",
                "--disable-default-apps",
                "--disable-features=TranslateUI",
                "--disable-hang-monitor",
                "--disable-ipc-flooding-protection",
                "--disable-renderer-backgrounding",
                "--force-color-profile=srgb",
                "--metrics-recording-only",
                "--password-store=basic",
                "--use-mock-keychain"
            ));

        if (chromePath != null) {
            launchOptions.setExecutablePath(java.nio.file.Paths.get(chromePath));
            logger.info("使用系统 Chrome: {}", chromePath);
        }

        this.browser = playwright.chromium().launch(launchOptions);

        // 创建浏览器上下文，不设置 viewport，让 --start-maximized 生效
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
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
            + "  delete navigator.__proto__.webdriver;\n"
            + "  \n"
            + "  // 伪装插件\n"
            + "  Object.defineProperty(navigator, 'plugins', {\n"
            + "    get: () => [\n"
            + "      { name: 'Chrome PDF Plugin', filename: 'internal-pdf-viewer', description: 'Portable Document Format', version: 'undefined', length: 1, item: () => null, namedItem: () => null },\n"
            + "      { name: 'Chrome PDF Viewer', filename: 'mhjfbmdgcfjbbpaeojofohoefgiehjai', description: 'Portable Document Format viewer', version: 'undefined', length: 1, item: () => null, namedItem: () => null },\n"
            + "      { name: 'Native Client', filename: 'internal-nacl-plugin', description: 'Native Client module', version: 'undefined', length: 2, item: () => null, namedItem: () => null }\n"
            + "    ]\n"
            + "  });\n"
            + "  Object.defineProperty(navigator, 'mimeTypes', {\n"
            + "    get: () => [\n"
            + "      { type: 'application/pdf', suffixes: 'pdf', description: 'Portable Document Format', enabledPlugin: navigator.plugins[0] },\n"
            + "      { type: 'application/x-google-chrome-pdf', suffixes: 'pdf', description: 'Portable Document Format', enabledPlugin: navigator.plugins[1] }\n"
            + "    ]\n"
            + "  });\n"
            + "  \n"
            + "  // 伪装 chrome 对象\n"
            + "  window.chrome = {\n"
            + "    runtime: {\n"
            + "      OnInstalledReason: { CHROME_UPDATE: 'chrome_update', INSTALL: 'install', SHARED_MODULE_UPDATE: 'shared_module_update', UPDATE: 'update' },\n"
            + "      OnRestartRequiredReason: { APP_UPDATE: 'app_update', OS_UPDATE: 'os_update', PERIODIC: 'periodic' },\n"
            + "      PlatformArch: { ARM: 'arm', ARM64: 'arm64', MIPS: 'mips', MIPS64: 'mips64', X86_32: 'x86-32', X86_64: 'x86-64' },\n"
            + "      PlatformNaclArch: { ARM: 'arm', MIPS: 'mips', MIPS64: 'mips64', X86_32: 'x86-32', X86_64: 'x86-64' },\n"
            + "      PlatformOs: { ANDROID: 'android', CROS: 'cros', LINUX: 'linux', MAC: 'mac', OPENBSD: 'openbsd', WIN: 'win' },\n"
            + "      RequestUpdateCheckStatus: { NO_UPDATE: 'no_update', THROTTLED: 'throttled', UPDATE_AVAILABLE: 'update_available' }\n"
            + "    },\n"
            + "    app: { isInstalled: false }\n"
            + "  };\n"
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
            + "  // 伪装 canvas fingerprint\n"
            + "  const originalToDataURL = HTMLCanvasElement.prototype.toDataURL;\n"
            + "  const originalGetImageData = CanvasRenderingContext2D.prototype.getImageData;\n"
            + "  HTMLCanvasElement.prototype.toDataURL = function(type) {\n"
            + "    if (this.width === 0 || this.height === 0) return originalToDataURL.call(this, type);\n"
            + "    // 添加微小噪点避免被检测\n"
            + "    const ctx = this.getContext('2d');\n"
            + "    const imageData = ctx.getImageData(0, 0, this.width, this.height);\n"
            + "    for (let i = 0; i < imageData.data.length; i += 4) {\n"
            + "      imageData.data[i] = (imageData.data[i] + 1) % 256;\n"
            + "    }\n"
            + "    ctx.putImageData(imageData, 0, 0);\n"
            + "    return originalToDataURL.call(this, type);\n"
            + "  };\n"
            + "  \n"
            + "  // 伪装 WebGL\n"
            + "  const getParameter = WebGLRenderingContext.prototype.getParameter;\n"
            + "  WebGLRenderingContext.prototype.getParameter = function(parameter) {\n"
            + "    if (parameter === 37445) {\n"
            + "      return 'Intel Inc.';\n"
            + "    }\n"
            + "    if (parameter === 37446) {\n"
            + "      return 'Intel Iris OpenGL Engine';\n"
            + "    }\n"
            + "    return getParameter(parameter);\n"
            + "  };\n"
            + "  \n"
            + "  // 伪装屏幕尺寸\n"
            + "  Object.defineProperty(window.screen, 'width', { get: () => window.innerWidth });\n"
            + "  Object.defineProperty(window.screen, 'height', { get: () => window.innerHeight });\n"
            + "  Object.defineProperty(window.screen, 'availWidth', { get: () => window.innerWidth });\n"
            + "  Object.defineProperty(window.screen, 'availHeight', { get: () => window.innerHeight });\n"
            + "  \n"
            + "  // 伪装 deviceMemory\n"
            + "  Object.defineProperty(navigator, 'deviceMemory', { get: () => 8 });\n"
            + "  Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => 8 });\n"
            + "");

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
        // 随机延迟，模拟真人操作
        randomDelay(500, 1500);

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

        // 模拟鼠标移动到输入框
        searchInput.hover();
        randomDelay(200, 500);

        // 模拟逐字输入（更像真人）
        searchInput.click();
        randomDelay(100, 300);
        searchInput.fill(companyName);
        logger.info("[步骤2] 已输入公司名称: {}", companyName);

        randomDelay(300, 800);

        // 多选择器尝试查找搜索按钮（CSS + 文本内容）
        Locator searchBtn = findSearchButton();

        // 模拟鼠标移动到按钮
        searchBtn.hover();
        randomDelay(200, 500);

        // 点击搜索按钮
        searchBtn.click();
        logger.info("[步骤2] 已点击搜索按钮");

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

                // 获取验证码图片
                logger.info("[步骤3] 正在获取验证码图片...");
                byte[] captchaBytes = downloadCaptchaImage(captcha.image);
                if (captchaBytes == null || captchaBytes.length == 0) {
                    logger.error("[步骤3] 所有方式获取验证码图片均失败，跳过本次重试");
                    page.waitForTimeout(1000);
                    continue;
                }

                String captchaBase64 = java.util.Base64.getEncoder().encodeToString(captchaBytes);
                logger.info("[步骤3] 验证码图片已获取，base64 长度: {} 字符", captchaBase64.length());

                // 保存验证码图片到 debug 目录供人工核对
                saveCaptchaDebugImage(captchaBytes, attempt);

                // OCR 识别
                logger.info("[步骤3] 正在进行 OCR 识别...");
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

                // ========== 关键防护：确保输入框和确认按钮有效 ==========
                if (captcha.input == null) {
                    logger.error("[步骤3] ❌ captcha.input 为 null，无法回填验证码！保存调试信息...");
                    saveDebugInfo("captcha_input_null_" + attempt);
                    boolean refreshed = clickRefreshCaptchaLink();
                    if (!refreshed) {
                        logger.warn("[步骤3] 刷新验证码失败，直接重试 ({}/{})", attempt, config.getMaxCaptchaRetry());
                    }
                    page.waitForTimeout(2000);
                    continue;
                }
                if (captcha.confirm == null) {
                    logger.error("[步骤3] ❌ captcha.confirm 为 null，无法提交验证码！保存调试信息...");
                    saveDebugInfo("captcha_confirm_null_" + attempt);
                    boolean refreshed = clickRefreshCaptchaLink();
                    if (!refreshed) {
                        logger.warn("[步骤3] 刷新验证码失败，直接重试 ({}/{})", attempt, config.getMaxCaptchaRetry());
                    }
                    page.waitForTimeout(2000);
                    continue;
                }

                // 确保输入框和按钮在 DOM 中
                try {
                    if (captcha.input.count() == 0) {
                        logger.error("[步骤3] ❌ 验证码输入框不在 DOM 中，保存调试信息...");
                        saveDebugInfo("captcha_input_missing_" + attempt);
                        clickRefreshCaptchaLink();
                        page.waitForTimeout(2000);
                        continue;
                    }
                    if (captcha.confirm.count() == 0) {
                        logger.error("[步骤3] ❌ 验证码确认按钮不在 DOM 中，保存调试信息...");
                        saveDebugInfo("captcha_confirm_missing_" + attempt);
                        clickRefreshCaptchaLink();
                        page.waitForTimeout(2000);
                        continue;
                    }
                } catch (Exception checkEx) {
                    logger.warn("[步骤3] 检查元素存在性时出错: {}", checkEx.getMessage());
                }

                // ========== 回填验证码 ==========
                logger.info("[步骤3] 正在回填验证码 '{}' 到输入框...", code);
                captcha.input.fill(code);
                logger.info("[步骤3] ✅ 验证码 '{}' 已回填到输入框", code);

                // 短暂等待确保 fill 完成
                page.waitForTimeout(500);

                // 回填后截图验证
                saveDebugInfo("after_fill_" + attempt);

                // ========== 点击确认按钮 ==========
                logger.info("[步骤3] 正在点击确认按钮...");
                captcha.confirm.click();
                page.waitForTimeout(config.getActionDelayMillis() + 500);
                logger.info("[步骤3] ✅ 已点击确认按钮，等待页面响应...");

                // ========== 检查验证结果 ==========
                CaptchaStatus status = checkCaptchaStatus();
                logger.info("[步骤3] 验证码状态检查结果: {}", status);

                if (status == CaptchaStatus.PASSED) {
                    logger.info("[步骤3] ✅ 验证码验证成功，等待搜索结果加载...");
                    page.waitForTimeout(5000); // 等待5秒让搜索结果加载
                    return;
                }

                // 验证码失效或错误，必须刷新后重试
                if (status == CaptchaStatus.INVALID) {
                    logger.warn("[步骤3] ❌ 验证码已失效/错误（OCR识别结果: '{}'），必须刷新后重试 ({}/{})",
                        code, attempt, config.getMaxCaptchaRetry());
                    boolean refreshed = clickRefreshCaptchaLink();
                    if (!refreshed) {
                        logger.error("[步骤3] ⚠️ 点击'换一张'失败！尝试 JS 强制刷新...");
                        refreshCaptchaByJs();
                    }
                    page.waitForTimeout(2000); // 等待2秒让新验证码加载
                    continue;
                }

                // 验证失败（未知原因），刷新验证码后重试
                logger.warn("[步骤3] ❌ 验证码验证失败（OCR识别结果: '{}'），刷新后重试 ({}/{})",
                    code, attempt, config.getMaxCaptchaRetry());
                boolean refreshed = clickRefreshCaptchaLink();
                if (!refreshed) {
                    logger.error("[步骤3] ⚠️ 刷新失败，尝试 JS 强制刷新...");
                    refreshCaptchaByJs();
                }
                page.waitForTimeout(2000);
                continue;

            } catch (PlaywrightException e) {
                if (e.getMessage().contains("timeout") || e.getMessage().contains("closed")) {
                    logger.info("[步骤3] 未检测到验证码，跳过");
                    return;
                }
                logger.error("[步骤3] Playwright 异常: {}", e.getMessage(), e);
                saveDebugInfo("captcha_playwright_error_" + attempt);
            } catch (Exception e) {
                logger.error("[步骤3] 发生异常: {}", e.getMessage(), e);
                saveDebugInfo("captcha_error_" + attempt);
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
     * 必须确保返回的 CaptchaElements 中 input 和 confirm 都不为 null，否则返回 null
     */
    private CaptchaElements findCaptchaElements() {
        // 策略1：用 JavaScript 直接通过 id 获取（最可靠）
        try {
            Object result = page.evaluate("() => document.getElementById('vcodeimg') !== null");
            if (Boolean.TRUE.equals(result)) {
                logger.info("[步骤3] JS 检测到 id=vcodeimg 的验证码图片");

                Locator captchaImg = page.locator("#vcodeimg");

                // 查找输入框
                Locator captchaInput = findCaptchaInputNearImage(captchaImg);
                if (captchaInput == null) {
                    logger.error("[步骤3] ❌ 策略1未找到验证码输入框，尝试策略3...");
                    // 降级到策略3
                    return findCaptchaElementsFallback();
                }

                // 查找确认按钮
                Locator confirmBtn = findCaptchaConfirmNearImage(captchaImg);
                if (confirmBtn == null) {
                    logger.error("[步骤3] ❌ 策略1未找到验证码确认按钮，尝试策略3...");
                    return findCaptchaElementsFallback();
                }

                logger.info("[步骤3] ✅ 通过策略1找到完整的验证码元素");
                return new CaptchaElements(captchaImg, captchaInput, confirmBtn, true);
            }
        } catch (Exception e) {
            logger.debug("JS 查找 vcodeimg 失败: {}", e.getMessage());
        }

        return findCaptchaElementsFallback();
    }

    /**
     * 查找验证码元素的降级策略（策略3）
     */
    private CaptchaElements findCaptchaElementsFallback() {
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
        if (captchaImg == null) {
            logger.info("[步骤3] 策略3未找到任何验证码图片");
            return null;
        }

        Locator captchaInput = findCaptchaInputNearImage(captchaImg);
        Locator confirmBtn = findCaptchaConfirmNearImage(captchaImg);

        if (captchaInput == null || confirmBtn == null) {
            logger.error("[步骤3] ❌ 策略3也未找到完整的验证码元素 (input={}, confirm={})",
                captchaInput != null, confirmBtn != null);
            return null;
        }

        logger.info("[步骤3] ✅ 通过策略3找到完整的验证码元素");
        return new CaptchaElements(captchaImg, captchaInput, confirmBtn, false);
    }

    /**
     * 通过 JavaScript 在验证码图片的 DOM 父链中查找输入框
     */
    private Locator findCaptchaInputNearImage(Locator captchaImg) {
        // 先尝试常见选择器
        String[] inputSelectors = {
            "#vcode",
            "input[name='vcode']",
            "input[placeholder*='验证码']",
            "input[placeholder*='校验']",
            "input[type='text']"
        };
        for (String sel : inputSelectors) {
            try {
                Locator loc = page.locator(sel);
                int count = loc.count();
                if (count > 0) {
                    logger.info("[步骤3] 找到候选输入框 | 选择器: {} | 数量: {}", sel, count);
                    if (count > 1) {
                        // 有多个，返回第一个可见的
                        List<Locator> all = loc.all();
                        for (Locator l : all) {
                            try { if (l.isVisible()) return l; } catch (Exception ignored) {}
                        }
                        return all.get(0);
                    }
                    return loc.first();
                }
            } catch (Exception ignored) {}
        }

        // 用 JS 在验证码图片附近查找
        try {
            Object inputInfo = page.evaluate(
                "() => {\n"
                + "  var img = document.getElementById('vcodeimg');\n"
                + "  if (!img) return null;\n"
                + "  var parent = img.parentElement;\n"
                + "  for (var i = 0; i < 5 && parent; i++) {\n"
                + "    var inputs = parent.querySelectorAll('input[type=text], input:not([type])');\n"
                + "    if (inputs.length > 0) {\n"
                + "      return { found: true, count: inputs.length, firstId: inputs[0].id || '', firstName: inputs[0].name || '' };\n"
                + "    }\n"
                + "    parent = parent.parentElement;\n"
                + "  }\n"
                + "  return null;\n"
                + "}");
            if (inputInfo != null) {
                logger.info("[步骤3] JS 在验证码附近找到输入框: {}", inputInfo);
                // 重新用选择器获取
                for (String sel : inputSelectors) {
                    try {
                        Locator loc = page.locator(sel);
                        if (loc.count() > 0) return loc.first();
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            logger.debug("JS 查找附近输入框失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 通过 JavaScript 在验证码图片的 DOM 父链中查找确认按钮
     */
    private Locator findCaptchaConfirmNearImage(Locator captchaImg) {
        // 先尝试常见选择器
        String[] confirmSelectors = {
            "a.confirm",
            ".vcodepop .buttongroup a.confirm",
            "button:has-text('验证')",
            "button:has-text('确定')",
            "button:has-text('确认')",
            "button:has-text('提交')",
            "a:has-text('验证')",
            "a:has-text('确定')",
            ".btn-confirm",
            "#confirm",
            "button",
            "a"
        };
        for (String sel : confirmSelectors) {
            try {
                Locator loc = page.locator(sel);
                int count = loc.count();
                if (count > 0) {
                    logger.info("[步骤3] 找到候选确认按钮 | 选择器: {} | 数量: {}", sel, count);
                    if (count > 1) {
                        List<Locator> all = loc.all();
                        for (Locator l : all) {
                            try { if (l.isVisible()) return l; } catch (Exception ignored) {}
                        }
                        return all.get(0);
                    }
                    return loc.first();
                }
            } catch (Exception ignored) {}
        }

        // 用 JS 在验证码图片附近查找
        try {
            Object btnInfo = page.evaluate(
                "() => {\n"
                + "  var img = document.getElementById('vcodeimg');\n"
                + "  if (!img) return null;\n"
                + "  var parent = img.parentElement;\n"
                + "  for (var i = 0; i < 5 && parent; i++) {\n"
                + "    var btns = parent.querySelectorAll('button, a, input[type=submit]');\n"
                + "    if (btns.length > 0) {\n"
                + "      return { found: true, count: btns.length, firstText: btns[0].textContent || btns[0].value || '' };\n"
                + "    }\n"
                + "    parent = parent.parentElement;\n"
                + "  }\n"
                + "  return null;\n"
                + "}");
            if (btnInfo != null) {
                logger.info("[步骤3] JS 在验证码附近找到按钮: {}", btnInfo);
                for (String sel : confirmSelectors) {
                    try {
                        Locator loc = page.locator(sel);
                        if (loc.count() > 0) return loc.first();
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            logger.debug("JS 查找附近按钮失败: {}", e.getMessage());
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

    /** 步骤4：点击搜索结果中的公司链接 */
    private boolean step4ClickCompanyLink(String companyName) {
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.waitForTimeout(2000); // 额外等待结果渲染

        // 优先处理 companylists (ul > li 结构)
        try {
            Locator companyLists = page.locator(".companylists");
            if (companyLists.count() > 0) {
                logger.info("[步骤4] 找到 .companylists 容器");
                List<Locator> items = companyLists.locator("li").all();
                logger.info("[步骤4] .companylists 下有 {} 个 li", items.size());

                for (int i = 0; i < items.size(); i++) {
                    Locator item = items.get(i);
                    String text = item.textContent().trim();
                    logger.info("[步骤4] li #{}: '{}'", i, text.substring(0, Math.min(text.length(), 50)));

                    if (text.contains(companyName)) {
                        logger.info("[步骤4] ✅ 在 .companylists 中精确匹配 li #{}: '{}'", i, text);
                        // 优先点击 li 内部的 a 标签
                        Locator link = item.locator("a");
                        if (link.count() > 0) {
                            clickAndSwitchToDetailPage(link.first());
                        } else {
                            clickAndSwitchToDetailPage(item);
                        }
                        return true;
                    }
                }

                // 模糊匹配
                for (int i = 0; i < items.size(); i++) {
                    Locator item = items.get(i);
                    String text = item.textContent().trim();
                    String fuzzyName = companyName.length() > 5 ? companyName.substring(0, 5) : companyName;
                    if (text.contains(fuzzyName)) {
                        logger.info("[步骤4] ✅ 在 .companylists 中模糊匹配 li #{}: '{}'", i, text);
                        Locator link = item.locator("a");
                        if (link.count() > 0) {
                            clickAndSwitchToDetailPage(link.first());
                        } else {
                            clickAndSwitchToDetailPage(item);
                        }
                        return true;
                    }
                }

                // 兜底：点击第一个 li
                if (!items.isEmpty()) {
                    logger.info("[步骤4] ⚠️ 未匹配，点击 .companylists 第一个 li");
                    Locator link = items.get(0).locator("a");
                    if (link.count() > 0) {
                        clickAndSwitchToDetailPage(link.first());
                    } else {
                        clickAndSwitchToDetailPage(items.get(0));
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("处理 .companylists 失败: {}", e.getMessage());
        }

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
     * 点击链接并切换到详情页面（支持新窗口/标签页，也支持当前页面导航）
     */
    private void clickAndSwitchToDetailPage(Locator link) {
        String beforeUrl = page.url();
        logger.info("[步骤4] 当前页面URL: {}", beforeUrl);

        // 先检查链接是否有 target="_blank" 属性
        boolean isNewWindow = false;
        try {
            Object targetAttr = link.evaluate("element => element.getAttribute('target')");
            isNewWindow = "_blank".equals(targetAttr);
            logger.info("[步骤4] 链接 target 属性: {}", targetAttr);
        } catch (Exception e) {
            logger.debug("[步骤4] 获取 target 属性失败: {}", e.getMessage());
        }

        if (isNewWindow) {
            // 新窗口打开
            try {
                Page newPage = context.waitForPage(() -> link.click());
                if (newPage != null && !newPage.equals(page)) {
                    logger.info("[步骤4] 详情页在新窗口打开，切换到新页面");
                    page = newPage;
                }
            } catch (Exception e) {
                logger.warn("[步骤4] 等待新页面超时，尝试直接点击: {}", e.getMessage());
                link.click();
            }
        } else {
            // 当前页面导航
            link.click();
        }

        // 等待页面加载（无论是否新窗口）
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
     * 下载验证码原图（通过 img.src 属性获取 URL 下载）
     * 支持多种方式：直接下载 URL、解析 data URL、JS 绘制到 canvas、元素截图
     *
     * 关键处理：如果 vcodeimg.src 为空（初始状态），先触发验证码加载
     */
    private byte[] downloadCaptchaImage(Locator captchaImg) {
        // 先检查 src，如果为空则触发加载
        String src = getCaptchaSrcWithRetry(captchaImg);

        if (src != null && !src.isEmpty()) {
            // 如果是 base64 编码的图片
            if (src.startsWith("data:image")) {
                try {
                    String base64Data = src.substring(src.indexOf(",") + 1);
                    return java.util.Base64.getDecoder().decode(base64Data);
                } catch (Exception e) {
                    logger.warn("[步骤3] 解析 data URL 失败: {}", e.getMessage());
                }
            }

            // 如果是相对路径，拼接完整 URL
            if (src.startsWith("/")) {
                String baseUrl = page.url();
                src = baseUrl.substring(0, baseUrl.indexOf("/", 8)) + src;
            }

            // 使用 Java 下载图片
            try {
                java.net.URL url = new java.net.URL(src);
                try (java.io.InputStream in = url.openStream()) {
                    byte[] bytes = in.readAllBytes();
                    logger.info("[步骤3] 通过 URL 下载验证码图片成功，大小: {} 字节", bytes.length);
                    return bytes;
                }
            } catch (Exception e) {
                logger.warn("[步骤3] 通过 URL 下载验证码图片失败: {}", e.getMessage());
            }
        }

        // 方式2：使用 JavaScript 将图片绘制到 canvas 后导出 base64
        try {
            String base64FromCanvas = (String) page.evaluate(
                "() => {\n"
                + "  var img = document.getElementById('vcodeimg');\n"
                + "  if (!img || !img.complete || img.naturalWidth === 0) return null;\n"
                + "  var canvas = document.createElement('canvas');\n"
                + "  canvas.width = img.naturalWidth;\n"
                + "  canvas.height = img.naturalHeight;\n"
                + "  var ctx = canvas.getContext('2d');\n"
                + "  ctx.drawImage(img, 0, 0);\n"
                + "  return canvas.toDataURL('image/png');\n"
                + "}");
            if (base64FromCanvas != null && base64FromCanvas.startsWith("data:image")) {
                String base64Data = base64FromCanvas.substring(base64FromCanvas.indexOf(",") + 1);
                byte[] bytes = java.util.Base64.getDecoder().decode(base64Data);
                logger.info("[步骤3] 通过 Canvas 导出验证码图片成功，大小: {} 字节", bytes.length);
                return bytes;
            }
        } catch (Exception e) {
            logger.warn("[步骤3] Canvas 导出验证码图片失败: {}", e.getMessage());
        }

        // 方式3：直接元素截图（fallback）
        try {
            byte[] bytes = captchaImg.screenshot();
            logger.info("[步骤3] 元素截图获取验证码成功，大小: {} 字节", bytes.length);
            return bytes;
        } catch (Exception e) {
            logger.error("[步骤3] 元素截图也失败: {}", e.getMessage());
        }

        return null;
    }

    /**
     * 获取验证码图片的 src，如果为空则触发加载并等待
     * 同时检查图片是否已加载完成（complete && naturalWidth > 0）
     */
    private String getCaptchaSrcWithRetry(Locator captchaImg) {
        for (int i = 0; i < 5; i++) {
            String src = null;
            boolean imgLoaded = false;
            try {
                src = (String) captchaImg.evaluate("element => element.src");
                // 检查图片是否已加载完成
                Object loaded = captchaImg.evaluate(
                    "element => element.complete && element.naturalWidth > 0");
                imgLoaded = Boolean.TRUE.equals(loaded);
            } catch (Exception e) {
                logger.warn("[步骤3] 获取 img.src 失败: {}", e.getMessage());
            }

            logger.info("[步骤3] 验证码 img.src 尝试 {}/5 = {} | 图片加载完成={}", i + 1,
                (src != null && !src.isEmpty()) ? src.substring(0, Math.min(src.length(), 80)) : "(空)",
                imgLoaded);

            if (src != null && !src.isEmpty() && imgLoaded) {
                return src;
            }

            // src 为空或图片未加载，尝试触发验证码加载
            logger.info("[步骤3] 验证码 src 为空或图片未加载，尝试触发加载...");
            triggerCaptchaLoad();
            page.waitForTimeout(2000); // 等待图片加载
        }
        return null;
    }

    /**
     * 触发验证码图片加载（vcodeimg.src 为空时的处理）
     * 通过 JS 调用网站的 updateVocdeFun() 加载验证码图片
     */
    private void triggerCaptchaLoad() {
        try {
            Object result = page.evaluate(
                "() => {\n"
                + "  try {\n"
                + "    // 方法1：调用网站自带的 updateVocdeFun() 刷新验证码\n"
                + "    if (typeof updateVocdeFun === 'function') {\n"
                + "      updateVocdeFun();\n"
                + "      return 'updateVocdeFun() called';\n"
                + "    }\n"
                + "    // 方法2：点击验证码图片触发加载\n"
                + "    var img = document.getElementById('vcodeimg');\n"
                + "    if (img) {\n"
                + "      img.click();\n"
                + "      return 'img clicked';\n"
                + "    }\n"
                + "    // 方法3：显示模态框（如果隐藏）\n"
                + "    var pop = document.querySelector('.vcodepop');\n"
                + "    if (pop) {\n"
                + "      pop.style.display = 'block';\n"
                + "      var mask = document.querySelector('.mask');\n"
                + "      if (mask) mask.style.display = 'block';\n"
                + "      // 显示后再点击验证码图片触发加载\n"
                + "      var img2 = document.getElementById('vcodeimg');\n"
                + "      if (img2) img2.click();\n"
                + "      return 'popup shown and img clicked';\n"
                + "    }\n"
                + "    return 'no action';\n"
                + "  } catch (e) {\n"
                + "    return 'error: ' + e.message;\n"
                + "  }\n"
                + "}");
            logger.info("[步骤3] 触发验证码加载结果: {}", result);
        } catch (Exception e) {
            logger.warn("[步骤3] 触发验证码加载失败: {}", e.getMessage());
        }
    }

    /**
     * 点击"看不清，换一张"链接刷新验证码
     * @return 是否成功点击刷新链接
     */
    private boolean clickRefreshCaptchaLink() {
        String[] refreshSelectors = {
            "a:has-text('看不清')",
            "a:has-text('换一张')",
            "a:has-text('换一换')",
            "span:has-text('看不清')",
            "span:has-text('换一张')",
            "span:has-text('换一换')",
            ".change-captcha",
            "#changeCaptcha",
            "a[onclick*='captcha']",
            "a[onclick*='refresh']",
            "a[onclick*='change']",
            ".refresh-captcha",
            "#refreshCaptcha"
        };
        for (String selector : refreshSelectors) {
            try {
                Locator loc = page.locator(selector);
                int count = loc.count();
                if (count > 0) {
                    boolean visible = loc.first().isVisible();
                    logger.info("[步骤3] 尝试刷新选择器 '{}' | count={} visible={}", selector, count, visible);
                    if (visible) {
                        loc.first().click();
                        logger.info("[步骤3] ✅ 已点击刷新验证码链接 | 选择器: {}", selector);
                        return true;
                    }
                }
            } catch (Exception e) {
                logger.debug("[步骤3] 选择器 '{}' 刷新失败: {}", selector, e.getMessage());
            }
        }

        // 尝试 JS 直接刷新
        logger.warn("[步骤3] ⚠️ 未找到'换一张'链接，尝试 JS 强制刷新...");
        return refreshCaptchaByJs();
    }

    /**
     * 通过 JavaScript 强制刷新验证码
     * 尝试：1. 触发验证码图片点击 2. 修改 src 加时间戳 3. 查找所有含刷新关键字的元素点击
     * @return 是否成功刷新
     */
    private boolean refreshCaptchaByJs() {
        try {
            Object result = page.evaluate(
                "() => {\n"
                + "  try {\n"
                + "    // 方法1：调用网站自带的 updateVocdeFun()\n"
                + "    if (typeof updateVocdeFun === 'function') {\n"
                + "      updateVocdeFun();\n"
                + "      return 'updateVocdeFun called';\n"
                + "    }\n"
                + "    // 方法2：点击验证码图片触发刷新\n"
                + "    var img = document.getElementById('vcodeimg');\n"
                + "    if (img) {\n"
                + "      img.click();\n"
                + "      return 'img clicked';\n"
                + "    }\n"
                + "    // 方法3：遍历所有元素找刷新关键字\n"
                + "    var all = document.querySelectorAll('a, span, button, div');\n"
                + "    for (var i = 0; i < all.length; i++) {\n"
                + "      var text = (all[i].textContent || all[i].value || '').trim();\n"
                + "      if (text.indexOf('看不清') >= 0 || text.indexOf('换一张') >= 0 || text.indexOf('换一换') >= 0 || text.indexOf('刷新') >= 0) {\n"
                + "        all[i].click();\n"
                + "        return 'found_and_clicked: ' + text;\n"
                + "      }\n"
                + "    }\n"
                + "    return 'not_found';\n"
                + "  } catch (e) {\n"
                + "    return 'error: ' + e.message;\n"
                + "  }\n"
                + "}");
            String resultStr = String.valueOf(result);
            logger.info("[步骤3] JS 刷新验证码结果: {}", resultStr);
            return resultStr != null && !resultStr.contains("not_found") && !resultStr.contains("error");
        } catch (Exception e) {
            logger.error("[步骤3] JS 刷新验证码失败: {}", e.getMessage());
            return false;
        }
    }

    private void refreshCaptcha() {
        boolean ok = clickRefreshCaptchaLink();
        if (!ok) {
            refreshCaptchaByJs();
        }
    }

    /**
     * 检查验证码是否已通过验证
     * 同时检测是否有"失效"、"错误"等提示
     */
    private CaptchaStatus checkCaptchaStatus() {
        // 等待一小段时间让页面响应
        page.waitForTimeout(1500);

        // 检查1：验证码图片是否消失（通过验证的典型特征）
        boolean imgExists = false;
        try {
            imgExists = page.locator("#vcodeimg").count() > 0;
        } catch (Exception e) {
            logger.debug("[步骤3] 检查 vcodeimg 是否存在时出错: {}", e.getMessage());
        }

        boolean imgVisible = false;
        if (imgExists) {
            try {
                imgVisible = page.locator("#vcodeimg").isVisible();
            } catch (Exception e) {
                logger.debug("[步骤3] 检查 vcodeimg 可见性时出错: {}", e.getMessage());
            }
        }

        logger.info("[步骤3] 验证码图片存在={}, 可见={}", imgExists, imgVisible);

        if (!imgExists) {
            logger.info("[步骤3] 验证码图片已消失（从DOM中移除），判断为验证通过");
            return CaptchaStatus.PASSED;
        }

        // 检查2：验证码输入框是否消失（验证通过后模态框关闭）
        boolean inputExists = false;
        try {
            inputExists = page.locator("#vcode").count() > 0 || page.locator("input[placeholder*='验证码']").count() > 0;
        } catch (Exception ignored) {}
        if (!inputExists && !imgVisible) {
            logger.info("[步骤3] 验证码输入框已消失，判断为验证通过");
            return CaptchaStatus.PASSED;
        }

        // 检查3：检查可见的错误提示元素（不是整个 body 文本，避免误判隐藏的 "验证码错误"）
        boolean errorTipVisible = false;
        try {
            Locator errorTip = page.locator(".errortip");
            if (errorTip.count() > 0) {
                errorTipVisible = errorTip.isVisible();
                if (errorTipVisible) {
                    String tipText = errorTip.textContent();
                    logger.warn("[步骤3] 检测到可见的错误提示: '{}'", tipText);
                }
            }
        } catch (Exception e) {
            logger.debug("[步骤3] 检查错误提示时出错: {}", e.getMessage());
        }

        // 检查4：通过 JS 检查模态框是否已关闭（验证成功的最直接标志）
        boolean popupHidden = false;
        try {
            Object popupDisplay = page.evaluate(
                "() => {\n"
                + "  var pop = document.querySelector('.vcodepop');\n"
                + "  if (!pop) return 'not_found';\n"
                + "  return pop.style.display;\n"
                + "}");
            popupHidden = "none".equals(popupDisplay);
            logger.info("[步骤3] 模态框状态: display={}", popupDisplay);
        } catch (Exception e) {
            logger.debug("[步骤3] 检查模态框状态失败: {}", e.getMessage());
        }

        if (popupHidden) {
            logger.info("[步骤3] 验证码模态框已关闭，判断为验证通过");
            return CaptchaStatus.PASSED;
        }

        // 如果有可见的错误提示且验证码图片还在，说明验证失败
        if (errorTipVisible && imgVisible) {
            logger.warn("[步骤3] 错误提示可见且验证码仍在，判断为验证码错误");
            return CaptchaStatus.INVALID;
        }

        // 检查5：当前 URL 是否变化，是否有搜索结果
        String currentUrl = page.url();
        if (!currentUrl.contains("captcha") && !currentUrl.contains("verify") && hasSearchResults()) {
            logger.info("[步骤3] 页面已有搜索结果，判断为验证通过");
            return CaptchaStatus.PASSED;
        }

        // 验证码图片仍然可见，没有明确通过或错误迹象 -> 可能是正在处理中
        if (imgVisible) {
            logger.info("[步骤3] 验证码图片仍然可见，等待后重试");
            return CaptchaStatus.FAILED;
        }

        // 图片存在但不可见（可能被隐藏），可能是过渡状态
        logger.info("[步骤3] 验证码图片存在但不可见，可能是隐藏/过渡状态");
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

    /**
     * 保存验证码调试图片，方便人工核对 OCR 识别结果
     */
    private void saveCaptchaDebugImage(byte[] captchaBytes, int attempt) {
        try {
            Path dir = Paths.get("debug", "captcha");
            Files.createDirectories(dir);
            Path file = dir.resolve("captcha_attempt_" + attempt + "_" + System.currentTimeMillis() + ".png");
            Files.write(file, captchaBytes);
            logger.info("[步骤3] 验证码调试图片已保存: {}", file.toAbsolutePath());
        } catch (Exception e) {
            logger.debug("[步骤3] 保存验证码调试图片失败: {}", e.getMessage());
        }
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
     * 随机延迟，模拟真人操作间隔
     */
    private void randomDelay(int minMillis, int maxMillis) {
        int delay = minMillis + (int) (Math.random() * (maxMillis - minMillis));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
