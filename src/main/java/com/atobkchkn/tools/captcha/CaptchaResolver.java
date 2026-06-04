package com.atobkchkn.tools.captcha;

/**
 * 验证码解析策略接口
 * 支持多种验证码识别方式：OCR、手动输入、第三方API等
 */
public interface CaptchaResolver {

    /**
     * 解析验证码图片，返回识别结果
     *
     * @param captchaImageBase64 验证码图片的 Base64 编码数据
     * @return 识别出的验证码文本，识别失败返回 null
     */
    String resolve(String captchaImageBase64);

    /**
     * 获取解析策略名称
     *
     * @return 策略名称
     */
    default String getName() {
        return this.getClass().getSimpleName();
    }
}
