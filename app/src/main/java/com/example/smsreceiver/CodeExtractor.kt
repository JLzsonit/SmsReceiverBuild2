package com.example.smsreceiver

/**
 * 从短信内容中提取验证码。
 * 策略：
 *  1. 优先匹配带关键词的（验证码/校验码/code/otp/pin）后面的 4~8 位数字字母组合；
 *  2. 兜底取第一条独立的 4~8 位数字串（常见验证码都是这个形态）。
 */
object CodeExtractor {

    private val keywordRegex = Regex(
        pattern = "(?:验证码|校验码|动态码|认证码|code|otp|pin)[^0-9A-Za-z]{0,8}([0-9A-Za-z]{4,8})",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private val digitsRegex = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")

    /** 返回识别出的验证码，识别不到返回 null */
    fun extract(msg: String): String? {
        keywordRegex.find(msg)?.let {
            return it.groupValues[1]
        }
        return digitsRegex.find(msg)?.groupValues?.get(1)
    }
}
