export function audioTranscriptionError(error: unknown): string {
  const name = error instanceof Error ? error.name : "";
  const message = error instanceof Error ? error.message : String(error ?? "");
  if (/requires appId\/apiKey\/apiSecret/.test(message)) {
    return "语音识别服务尚未配置，暂时无法转写。请先使用文字回答。";
  }
  if (name === "NotAllowedError" || name === "SecurityError") {
    return "麦克风权限被拒绝。请在浏览器地址栏的网站权限中允许使用麦克风，再重试。";
  }
  if (name === "NotFoundError") return "未找到麦克风，请连接麦克风后重试。";
  if (name === "NotReadableError")
    return "无法读取麦克风，请检查设备是否被其他应用占用。";
  if (/AST business failure/.test(message)) {
    return "语音识别服务不可用，请检查讯飞语音服务的权限及额度。你可以继续文字回答。";
  }
  return "语音转写连接失败或已中断，请重试；已识别的文字会保留。";
}
