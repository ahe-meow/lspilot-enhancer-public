package com.lspilot.enhancer;

final class AutoRetryPolicy {
    private static final long[] DELAYS_MS = {
            5_000L, 10_000L, 30_000L, 120_000L, 300_000L
    };

    private AutoRetryPolicy() {
    }

    static int maxRetries() {
        return DELAYS_MS.length;
    }

    static long delayForRetry(int retryNumber) {
        if (retryNumber < 1 || retryNumber > DELAYS_MS.length) {
            throw new IllegalArgumentException("retryNumber=" + retryNumber);
        }
        return DELAYS_MS[retryNumber - 1];
    }

    static String formatDelay(long delayMs) {
        if (delayMs < 60_000L) return (delayMs / 1000L) + " 秒";
        return (delayMs / 60_000L) + " 分钟";
    }

    public static void main(String[] args) {
        assert maxRetries() == 5;
        assert delayForRetry(1) == 5_000L;
        assert delayForRetry(2) == 10_000L;
        assert delayForRetry(3) == 30_000L;
        assert delayForRetry(4) == 120_000L;
        assert delayForRetry(5) == 300_000L;
        assert "5 秒".equals(formatDelay(delayForRetry(1)));
        assert "2 分钟".equals(formatDelay(delayForRetry(4)));
        assert "5 分钟".equals(formatDelay(delayForRetry(5)));
    }
}
