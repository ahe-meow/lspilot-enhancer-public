package dev.operit.lspilot.enhancer;

import java.util.concurrent.atomic.AtomicLong;

/** Pure legal-state model for one chat-scoped compression task. */
final class CompressionStateMachine {
    enum State {
        IDLE,
        WAITING_SAFE_BOUNDARY,
        SUMMARIZING,
        RETRYING,
        VALIDATING,
        AWAITING_USER_ACTION
    }

    enum Event {
        OVER_LIMIT_UNPAIRED,
        OVER_LIMIT_SAFE,
        SAFE_BOUNDARY,
        SUMMARY_RESPONSE,
        SUMMARY_VALID,
        FIRST_FAILURE,
        FAILURE_EXHAUSTED,
        RETRY_REQUESTED,
        KEEP_2,
        KEEP_1,
        CANCEL,
        INVALIDATE,
        TIMEOUT
    }

    enum Action {
        RETRY,
        KEEP_2,
        KEEP_1,
        CANCEL
    }

    static final class Task {
        final String chatId;
        final long taskId;
        final String boundaryFingerprint;
        final String providerSignature;
        final String model;
        final int keepRecent;

        Task(String chatId, long taskId, String boundaryFingerprint,
                String providerSignature, String model, int keepRecent) {
            this.chatId = chatId;
            this.taskId = taskId;
            this.boundaryFingerprint = boundaryFingerprint;
            this.providerSignature = providerSignature;
            this.model = model;
            this.keepRecent = keepRecent;
        }
    }

    private static final AtomicLong NEXT_TASK_ID = new AtomicLong();

    private CompressionStateMachine() {
    }

    static Task newTask(String chatId, String boundaryFingerprint, String providerSignature,
            String model, int keepRecent) {
        return new Task(chatId, NEXT_TASK_ID.incrementAndGet(), boundaryFingerprint,
                providerSignature, model, keepRecent);
    }

    static State transition(State state, Event event) {
        if (state == null || event == null) return State.IDLE;
        switch (state) {
            case IDLE:
                if (event == Event.OVER_LIMIT_UNPAIRED) return State.WAITING_SAFE_BOUNDARY;
                if (event == Event.OVER_LIMIT_SAFE) return State.SUMMARIZING;
                return state;
            case WAITING_SAFE_BOUNDARY:
                if (event == Event.SAFE_BOUNDARY) return State.SUMMARIZING;
                if (event == Event.CANCEL || event == Event.INVALIDATE || event == Event.TIMEOUT) {
                    return State.IDLE;
                }
                return state;
            case SUMMARIZING:
                if (event == Event.SUMMARY_RESPONSE) return State.VALIDATING;
                if (event == Event.FIRST_FAILURE || event == Event.TIMEOUT) return State.RETRYING;
                if (event == Event.CANCEL || event == Event.INVALIDATE) return State.IDLE;
                return state;
            case RETRYING:
                if (event == Event.SUMMARY_RESPONSE || event == Event.RETRY_REQUESTED) {
                    return event == Event.SUMMARY_RESPONSE
                            ? State.VALIDATING : State.SUMMARIZING;
                }
                if (event == Event.FAILURE_EXHAUSTED) return State.AWAITING_USER_ACTION;
                if (event == Event.CANCEL || event == Event.INVALIDATE || event == Event.TIMEOUT) {
                    return State.IDLE;
                }
                return state;
            case VALIDATING:
                if (event == Event.SUMMARY_VALID) return State.IDLE;
                if (event == Event.FIRST_FAILURE) return State.RETRYING;
                if (event == Event.FAILURE_EXHAUSTED) return State.AWAITING_USER_ACTION;
                if (event == Event.CANCEL || event == Event.INVALIDATE || event == Event.TIMEOUT) {
                    return State.IDLE;
                }
                return state;
            case AWAITING_USER_ACTION:
                if (event == Event.RETRY_REQUESTED || event == Event.KEEP_2
                        || event == Event.KEEP_1) return State.SUMMARIZING;
                if (event == Event.CANCEL || event == Event.INVALIDATE || event == Event.TIMEOUT) {
                    return State.IDLE;
                }
                return state;
            default:
                return state;
        }
    }

    static boolean isCurrent(Task task, long activeTaskId, String chatId) {
        return task != null && task.taskId == activeTaskId
                && task.chatId != null && task.chatId.equals(chatId);
    }
}