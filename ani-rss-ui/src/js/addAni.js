import {ElMessage, ElMessageBox} from "element-plus";
import * as http from '@/js/http.js'

/**
 * 后端在订阅重复时抛出的提示语，用来把"重复"与其他失败区分开。
 */
export const DUPLICATE_MESSAGES = ['此订阅已存在', '订阅标题重复']

/**
 * 添加订阅，重复时由用户决定是否覆盖。
 *
 * 首次请求**不带** override：是否覆盖永远是这一次点击的决定，
 * 而不是一个会被忘掉的全局开关（旧版 {@code config.replace}）。
 * 只有用户点了"覆盖"才携带 {@code override=true} 重试。
 *
 * @param ani 订阅
 * @returns {Promise<boolean>} 是否已成功添加（或被用户确认覆盖）
 */
export const addAniWithDuplicateConfirm = async ani => {
    try {
        await http.addAni(ani)
        return true
    } catch (error) {
        if (!DUPLICATE_MESSAGES.includes(error?.message)) {
            ElMessage.error(error?.message || '添加订阅失败')
            return false
        }
        try {
            await ElMessageBox.confirm(
                `订阅“${ani?.title || ''}”已存在，是否覆盖原订阅？`,
                '订阅重复',
                {confirmButtonText: '覆盖', cancelButtonText: '取消', type: 'warning'}
            )
        } catch {
            // 用户取消：保留原订阅，不弹错误
            return false
        }
        try {
            await http.addAni(ani, true)
            return true
        } catch (error) {
            ElMessage.error(error?.message || '覆盖订阅失败')
            return false
        }
    }
}
