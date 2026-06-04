package com.atobkchkn.tools.captcha;

import net.sourceforge.tess4j.Tesseract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;

/**
 * 基于 Tesseract OCR 的验证码自动识别策略
 * 针对 4 位大写字母+数字验证码优化
 */
public class OcrCaptchaResolver implements CaptchaResolver {

    private static final Logger logger = LoggerFactory.getLogger(OcrCaptchaResolver.class);

    private final Tesseract tesseract;

    public OcrCaptchaResolver() {
        this.tesseract = new Tesseract();
        // 只用英文，不用中文（验证码是大写字母+数字）
        this.tesseract.setLanguage("eng");
        // LSTM 神经网络引擎
        this.tesseract.setOcrEngineMode(1);
        // 单字块模式（适合验证码）
        this.tesseract.setPageSegMode(8);
        // 只识别大写字母和数字
        this.tesseract.setTessVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
    }

    @Override
    public String resolve(String captchaImageBase64) {
        if (captchaImageBase64 == null || captchaImageBase64.isEmpty()) {
            logger.warn("验证码图片数据为空");
            return null;
        }
        try {
            byte[] imageBytes = Base64.getDecoder().decode(captchaImageBase64);
            BufferedImage captchaImage = ImageIO.read(new ByteArrayInputStream(imageBytes));

            if (captchaImage == null) {
                logger.warn("无法解码验证码图片");
                return null;
            }

            // 放大图片提高识别率
            BufferedImage scaled = scale(captchaImage, 3);
            // 灰度化
            BufferedImage gray = toGray(scaled);
            // 二值化
            BufferedImage binary = toBinary(gray, 160);
            // 去噪
            BufferedImage denoised = removeNoise(binary);

            String result = tesseract.doOCR(denoised);
            String cleaned = result.replaceAll("[^A-Z0-9]", "").trim();
            logger.info("OCR 识别结果: '{}'", cleaned);
            return cleaned.isEmpty() ? null : cleaned;

        } catch (Exception e) {
            logger.error("OCR 验证码识别失败", e);
            return null;
        }
    }

    /** 放大图片 */
    private BufferedImage scale(BufferedImage source, int factor) {
        int w = source.getWidth() * factor;
        int h = source.getHeight() * factor;
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    /** 灰度化 */
    private BufferedImage toGray(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics g = gray.getGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return gray;
    }

    /** 二值化 */
    private BufferedImage toBinary(BufferedImage source, int threshold) {
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage binary = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int rgb = source.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int gray = (r + g + b) / 3;
                int val = gray > threshold ? 255 : 0;
                int newRgb = (val << 16) | (val << 8) | val;
                binary.setRGB(x, y, newRgb);
            }
        }
        return binary;
    }

    /** 简单去噪：去除孤立点 */
    private BufferedImage removeNoise(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage cleaned = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int rgb = source.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                // 统计周围黑色像素数
                int blackNeighbors = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && (dx != 0 || dy != 0)) {
                            int nr = (source.getRGB(nx, ny) >> 16) & 0xFF;
                            if (nr < 128) blackNeighbors++;
                        }
                    }
                }
                // 如果是白色点且周围黑色很少，保持白色；如果是黑色点且周围白色很少，保持黑色
                if (r < 128 && blackNeighbors >= 1) {
                    cleaned.setRGB(x, y, 0xFF000000); // 黑
                } else {
                    cleaned.setRGB(x, y, 0xFFFFFFFF); // 白
                }
            }
        }
        return cleaned;
    }
}
