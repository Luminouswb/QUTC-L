package com.qutc.launcher.core.memory;

import com.qutc.launcher.utils.OsUtils;

/**
 * 智能内存分配（总纲 §三.5 的推荐表，阶段 9 会补完整设置页）。
 *
 * <p>规则：
 * <ul>
 *   <li>纯净端 2–4 GB；轻度模组（≤30）4–6 GB；中度+光影 6–10 GB；大型整合包 10–16 GB；</li>
 *   <li>任何情况下不超过系统物理内存的 <b>60%</b>（超过会弹警告，阶段 9 实现弹窗）；</li>
 *   <li>推荐值按 512 MB 对齐，并留出下限 2048 MB。</li>
 * </ul>
 */
public final class MemoryAdvisor {

    /**
     * @param modCount     mods 目录里的 jar 数
     * @param hasShaders   shaderpacks 里是否有内容
     * @param physicalMb   物理内存（MB）
     */
    public record Recommendation(int recommendedMb, int minimumMb, int maximumMb, int physicalMb, String reason) {

        public int cap() {
            return Math.max(2048, (int) (physicalMb * 0.6));
        }

        /** 当前值是否超过 60% 物理内存。 */
        public boolean isOverCap(int currentMb) {
            return currentMb > cap();
        }
    }

    private MemoryAdvisor() {
    }

    public static Recommendation recommend(int modCount, boolean hasShaders) {
        int physical = (int) Math.min(Integer.MAX_VALUE, OsUtils.totalPhysicalMemory() / 1024 / 1024);
        if (physical <= 0) {
            physical = 8192;
        }
        int minimum;
        int maximum;
        String reason;
        if (modCount <= 0 && !hasShaders) {
            minimum = 2048;
            maximum = 4096;
            reason = "纯净端";
        } else if (modCount <= 30) {
            minimum = 4096;
            maximum = 6144;
            reason = "轻度模组（" + modCount + " 个 mod）";
        } else if (modCount <= 80) {
            minimum = 6144;
            maximum = 10240;
            reason = "中度模组" + (hasShaders ? " + 光影" : "") + "（" + modCount + " 个 mod）";
        } else {
            minimum = 10240;
            maximum = 16384;
            reason = "大型整合包（" + modCount + " 个 mod）";
        }
        if (hasShaders && modCount <= 30) {
            minimum = Math.max(minimum, 6144);
            maximum = Math.max(maximum, 8192);
            reason = reason + " + 光影";
        }
        int cap = Math.max(2048, (int) (physical * 0.6));
        int recommended = Math.max(minimum, Math.min(maximum, (int) (physical * 0.35)));
        recommended = Math.min(recommended, cap);
        recommended = Math.max(2048, (recommended / 512) * 512);
        return new Recommendation(recommended,
                Math.min(minimum, cap), Math.min(maximum, cap), physical, reason);
    }
}
