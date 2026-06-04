package com.atobkchkn.tools.captcha;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Scanner;

/**
 * 手动输入验证码策略
 * 将验证码图片保存到临时文件，通过控制台/Scanner 读取用户输入
 * 最可靠的验证码识别方式，适合调试或验证码复杂场景
 */
public class ManualCaptchaResolver implements CaptchaResolver {

    private static final Logger logger = LoggerFactory.getLogger(ManualCaptchaResolver.class);

    private final Scanner scanner;

    public ManualCaptchaResolver() {
        this.scanner = new Scanner(System.in);
    }

    @Override
    public String resolve(String captchaImageBase64) {
        if (captchaImageBase64 == null || captchaImageBase64.isEmpty()) {
            logger.warn("验证码图片数据为空");
            return null;
        }
        try {
            // 解码并保存验证码图片到临时文件
            byte[] imageBytes = Base64.getDecoder().decode(captchaImageBase64);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(imageBytes));

            Path tempDir = Files.createTempDirectory("captcha_");
            Path captchaPath = tempDir.resolve("captcha.png");
            ImageIO.write(image, "png", captchaPath.toFile());

            logger.info("验证码已保存到: {}", captchaPath);
            System.out.println("\n========================================");
            System.out.println("【验证码识别】请查看以下文件中的验证码图片：");
            System.out.println("   " + captchaPath);
            System.out.println("----------------------------------------");
            System.out.print("请输入验证码内容: ");

            String input = scanner.nextLine().trim();
            System.out.println("========================================\n");

            logger.info("用户输入验证码: '{}'", input);
            return input.isEmpty() ? null : input;

        } catch (Exception e) {
            logger.error("手动输入验证码处理失败", e);
            return null;
        }
    }
}
