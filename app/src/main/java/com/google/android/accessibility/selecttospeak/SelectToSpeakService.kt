package com.google.android.accessibility.selecttospeak

import com.eatmans.wxjev.capture.ChatCaptureService

/**
 * 采集服务, 注册在系统风格类名下——微信 8.0.52+ 只对普通命名服务混淆节点树,
 * 该伪装类名可读到完整聊天（本机小米 15 / 微信 8.0.78 已实测, 见 _reports/evidence）。
 * 全部逻辑在 [ChatCaptureService]; 此类只有类名不同。
 *
 * 绝不重命名本类或它的 Manifest 注册——伪装正是绕过微信混淆的关键。
 */
class SelectToSpeakService : ChatCaptureService()
