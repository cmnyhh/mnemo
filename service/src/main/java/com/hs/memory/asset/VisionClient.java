package com.hs.memory.asset;

/**
 * 看图模型。存在的唯一理由是让图片可被文字检索：
 * 一张没被描述过的截图，除了文件名之外没有任何可召回的词。
 *
 * <p>没配置时返回 Noop，上传仍然成功（只是没有自动图注）——
 * 多模态的价值在于「图能进记忆」，不该因为模型没配好就整个功能不可用。
 */
public interface VisionClient {

    boolean available();

    /** @throws VisionException 模型不可用、超时、返回不可解析 */
    Caption describe(byte[] image, String mimeType) throws VisionException;

    /**
     * @param caption 一句话描述，用「用户会怎么问」的口吻写，直接进检索文本
     * @param ocr     图内文字，按阅读顺序
     */
    record Caption(String caption, String ocr, String model, long latencyMs) {
        public String toNarrative(String fallback) {
            StringBuilder sb = new StringBuilder();
            if (caption != null && !caption.isBlank()) {
                sb.append(caption.trim());
            }
            if (ocr != null && !ocr.isBlank()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("图中文字：").append(ocr.trim());
            }
            return sb.length() == 0 ? fallback : sb.toString();
        }
    }
}
