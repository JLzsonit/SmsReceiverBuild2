package com.example.smsreceiver

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.text.InputType
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import java.util.Locale

/**
 * 系统级自动填充服务：当任意 App 的"验证码输入框"获得焦点时，
 * 从本地保存的转发短信中提取最新验证码，弹出候选气泡供用户一键填入。
 *
 * 注意：出于系统安全设计，Android 不允许在用户无感知的情况下静默填充，
 * 必须由用户点一下候选气泡（这与 Gboard/密码管理器的体验一致）。
 *
 * 用户需要先在系统「设置 → 自动填充服务」里选择本 App 才会生效。
 */
class SmsAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onSuccess(null)
            return
        }

        // 从本地持久化消息中取最新验证码（服务可能被系统单独拉起，不能依赖内存状态）
        val code = latestCode() ?: run {
            callback.onSuccess(null)
            return
        }

        // 遍历视图树，找到第一个验证码输入框
        val root = structure.getWindowNodeAt(0)?.rootViewNode ?: run {
            callback.onSuccess(null)
            return
        }
        val otpNode = findOtpField(root, cancellationSignal) ?: run {
            callback.onSuccess(null)
            return
        }
        val autofillId = otpNode.autofillId ?: run {
            callback.onSuccess(null)
            return
        }

        val presentation = RemoteViews(packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(R.id.autofill_code_text, "填入验证码 $code")
        }

        val dataset = Dataset.Builder(presentation)
            .setValue(autofillId, AutofillValue.forText(code))
            .build()

        val response = FillResponse.Builder()
            .addDataset(dataset)
            .build()

        callback.onSuccess(response)
    }

    /** 从本地消息历史里取最新一条可识别的验证码 */
    private fun latestCode(): String? {
        return try {
            SmsStore.load(applicationContext).firstNotNullOfOrNull { sms ->
                CodeExtractor.extract(sms.msg)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 判断一个输入框是否是验证码输入框。
     * 综合 autofillHints、inputType、hint、id 等特征，避免误填金额、手机号等其他数字框。
     */
    private fun isOtpField(node: android.app.assist.AssistStructure.ViewNode): Boolean {
        val hints = node.autofillHints?.joinToString(",") ?: ""
        val idEntry = node.idEntry ?: ""
        val hintText = node.hint?.toString() ?: ""
        val all = "$hints,$idEntry,$hintText".lowercase(Locale.getDefault())

        // 明确的 OTP / 验证码语义（各语言关键词 + Android 标准提示）
        val semanticHit = all.contains("otp") ||
            all.contains("2fa") ||
            all.contains("验证码") ||
            all.contains("校验码") ||
            all.contains("verification") ||
            all.contains("verify") ||
            all.contains("sms code") ||
            (all.contains("sms") && all.contains("code"))

        if (semanticHit) return true

        // 数字密码输入框（常见于 6 位验证码框）
        val inputType = node.inputType
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val isNumeric = inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE
        return isNumeric
    }

    private fun findOtpField(
        node: android.app.assist.AssistStructure.ViewNode,
        signal: CancellationSignal
    ): android.app.assist.AssistStructure.ViewNode? {
        if (signal.isCanceled) return null
        if (isOtpField(node) && node.autofillId != null) return node
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) ?: continue
            val found = findOtpField(child, signal)
            if (found != null) return found
        }
        return null
    }

    override fun onSaveRequest(
        request: android.service.autofill.SaveRequest,
        callback: android.service.autofill.SaveCallback
    ) {
        // 我们不保存凭据，忽略
    }
}
