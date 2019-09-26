package com.lumux.exception;

import java.io.IOException;

public class ErrorCodeException extends IOException {
    private final int code;

    public ErrorCodeException(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
