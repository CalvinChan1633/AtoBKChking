package com.atobkchkn.tools.captcha;

import net.sourceforge.tess4j.Tesseract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
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
        this.tesseract.setLanguage("eng");
        this.tesseract.setOcrEngineMode(1);
        // PSM 7: 将图像视为单行文本
        this.tesseract.setPageSegMode(7);
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
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));

            if (source == null) {
                logger.warn("无法解码验证码图片");
                return null;
            }

            logger.info("原始验证码尺寸: {}x{}", source.getWidth(), source.getHeight());

            // 预处理流程
            BufferedImage scaled = scale(source, 4);
            BufferedImage gray = toGray(scaled);
            BufferedImage denoised = medianFilter(gray);
            BufferedImage binary = adaptiveThreshold(denoised);
            BufferedImage cleaned = removeNoise(binary);

            // 保存调试图片
            saveDebugImage(cleaned, "processed");
            saveDebugImage(source, "original");

            String result = tesseract.doOCR(cleaned);
            String code = result.replaceAll("[^A-Z0-9]", "").trim();
            logger.info("OCR 识别结果: '{}'", code);
            return code.isEmpty() ? null : code;

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
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    /** 灰度化 */
    private BufferedImage toGray(BufferedImage source) {
        BufferedImage gray = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics g = gray.getGraphics();
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return gray;
    }

    /** 中值滤波去噪 */
    private BufferedImage medianFilter(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int[] neighbors = new int[9];
                int idx = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            neighbors[idx++] = source.getRGB(nx, ny) & 0xFF;
                        }
                    }
                }
                java.util.Arrays.sort(neighbors, 0, idx);
                int median = neighbors[idx / 2];
                int rgb = (median << 16) | (median << 8) | median;
                result.setRGB(x, y, rgb);
            }
        }
        return result;
    }

    /** 自适应阈值二值化 */
    private BufferedImage adaptiveThreshold(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        int blockSize = 15;
        int c = 10;

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int sum = 0, count = 0;
                for (int dx = -blockSize / 2; dx <= blockSize / 2; dx++) {
                    for (int dy = -blockSize / 2; dy <= blockSize / 2; dy++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            sum += source.getRGB(nx, ny) & 0xFF;
                            count++;
                        }
                    }
                }
                int threshold = sum / count - c;
                int gray = source.getRGB(x, y) & 0xFF;
                int val = gray > threshold ? 255 : 0;
                int rgb = (val << 16) | (val << 8) | val;
                result.setRGB(x, y, rgb);
            }
        }
        return result;
    }

    /** 去除孤立点 */
    private BufferedImage removeNoise(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);

        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int isBlack = ((source.getRGB(x, y) >> 16) & 0xFF) < 128 ? 1 : 0;
                int blackNeighbors = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h && (dx != 0 || dy != 0)) {
                            if (((source.getRGB(nx, ny) >> 16) & 0xFF) < 128) {
                                blackNeighbors++;
                            }
                        }
                    }
                }
                if (isBlack == 1 && blackNeighbors < 2) {
                    result.setRGB(x, y, 0xFFFFFFFF);
                } else if (isBlack == 0 && blackNeighbors > 6) {
                    result.setRGB(x, y, 0xFF000000);
                } else {
                    result.setRGB(x, y, isBlack == 1 ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
        }
        return result;
    }

    private void saveDebugImage(BufferedImage image, String name) {
        try {
            File dir = new File("debug/captcha");
            dir.mkdirs();
            File file = new File(dir, name + "_" + System.currentTimeMillis() + ".png");
            ImageIO.write(image, "png", file);
        } catch (Exception ignored) {}
    }
}
