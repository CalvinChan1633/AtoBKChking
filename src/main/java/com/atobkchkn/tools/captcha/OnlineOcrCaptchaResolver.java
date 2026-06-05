package com.atobkchkn.tools.captcha;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 基于 OCR.space 在线 API 的验证码识别策略
 * 免费版每天 25,000 次请求，识别率远高于本地 Tesseract
 */
public class OnlineOcrCaptchaResolver implements CaptchaResolver {

    private static final Logger logger = LoggerFactory.getLogger(OnlineOcrCaptchaResolver.class);

    private final HttpClient httpClient;
    private final Gson gson;

    /** OCR.space API Key（免费版用 "helloworld"） */
    private String apiKey = "helloworld";

    public OnlineOcrCaptchaResolver() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.gson = new Gson();
    }

    public OnlineOcrCaptchaResolver(String apiKey) {
        this();
        this.apiKey = apiKey;
    }

    @Override
    public String resolve(String captchaImageBase64) {
        if (captchaImageBase64 == null || captchaImageBase64.isEmpty()) {
            logger.warn("验证码图片数据为空");
            return null;
        }

        try {
            // 构造 base64 data URL
            String dataUrl = "data:image/png;base64," + captchaImageBase64;

            // 构造表单数据
            String formData = "apikey=" + apiKey
                + "&base64Image=" + java.net.URLEncoder.encode(dataUrl, StandardCharsets.UTF_8)
                + "&language=eng"
                + "&isOverlayRequired=false"
                + "&OCREngine=2"; // Engine 2 更适合验证码

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.ocr.space/parse/image"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build();

            logger.info("[OCR] 正在调用在线识别 API...");
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.error("[OCR] API 返回错误状态码: {}", response.statusCode());
                return null;
            }

            // 解析响应
            JsonObject json = gson.fromJson(response.body(), JsonObject.class);
            boolean isErrored = json.has("IsErroredOnProcessing") && json.get("IsErroredOnProcessing").getAsBoolean();

            if (isErrored) {
                String error = json.has("ErrorMessage") ? json.get("ErrorMessage").getAsString() : "未知错误";
                logger.error("[OCR] API 处理错误: {}", error);
                return null;
            }

            JsonArray results = json.getAsJsonArray("ParsedResults");
            if (results == null || results.isEmpty()) {
                logger.warn("[OCR] API 未返回识别结果");
                return null;
            }

            String text = results.get(0).getAsJsonObject().get("ParsedText").getAsString();
            String cleaned = text.replaceAll("[^A-Z0-9]", "").trim();
            logger.info("[OCR] 在线识别结果: '{}'", cleaned);
            return cleaned.isEmpty() ? null : cleaned;

        } catch (Exception e) {
            logger.error("[OCR] 在线识别失败: {}", e.getMessage());
            return null;
        }
    }
}
