package com.example.notification.sender;

/** A provider rejected or failed a delivery; triggers the normal retry/DLQ flow. */
public class ProviderException extends RuntimeException {
    private final int status;

    public ProviderException(String message, int status) {
        super(message);
        this.status = status;
    }

    /** HTTP status, or -1 when not applicable. */
    public int getStatus() { return status; }

    /** 5xx, 429 and transport errors are worth retrying; other 4xx are permanent. */
    public boolean isRetryable() { return status < 0 || status == 429 || status >= 500; }
}
